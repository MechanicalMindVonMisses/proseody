package dev.aliberke.sesli.importers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.aliberke.sesli.text.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class ImportException(message: String) : Exception(message)

data class ImportedText(
    val title: String,
    val text: String,
    val source: String,
    val sourceUri: String? = null,
)

object Importers {
    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

    suspend fun fromUri(context: Context, uri: Uri, mimeHint: String? = null): ImportedText = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val title = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = (context.contentResolver.getType(uri) ?: mimeHint ?: "").lowercase()
        when {
            mime == "application/pdf" || ext == "pdf" -> PdfImporter.import(context, uri, title)
            mime == "application/epub+zip" || ext == "epub" -> EpubImporter.import(context, uri, title)
            mime == DOCX || ext == "docx" -> DocxImporter.import(context, uri, title)
            mime == "application/msword" || ext == "doc" ->
                throw ImportException("Old .doc files aren't supported. Save it as .docx or PDF and try again.")
            mime.startsWith("image/") || ext in imageExtensions -> OcrImporter.fromImages(context, listOf(uri), title)
            mime == "text/html" || ext == "html" || ext == "htm" -> {
                val doc = Jsoup.parse(readText(context, uri))
                ImportedText(doc.title().ifBlank { title }, HtmlText.extract(doc.body()), "html", uri.toString())
            }
            mime == "text/markdown" || ext == "md" || ext == "markdown" ->
                ImportedText(title, Markdown.strip(readText(context, uri)), "markdown", uri.toString())
            else -> ImportedText(title, readText(context, uri), "text", uri.toString())
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
            // Some providers reject queries; fall back to the path.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    }

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ImportException("Couldn't open the file.")

    /** Decodes as UTF-8, falling back to Windows-1254 (Turkish) for legacy text files. */
    fun readText(context: Context, uri: Uri): String = decode(readBytes(context, uri))

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val nonText = bytes.take(4096).count { it.toInt() == 0 }
        if (nonText > 8) throw ImportException("This file type isn't supported.")
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1254"))
        }
        return decoded.removePrefix("﻿")
    }

    fun plainTextImport(title: String, text: String, sourceUri: String? = null) =
        ImportedText(title, TextCleaner.normalize(text), "text", sourceUri)
}

object Markdown {
    private val fence = Regex("^\\s*(```|~~~).*$")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val bullet = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
    private val quote = Regex("^\\s*>\\s?")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val emphasis = Regex("(\\*{1,3}|_{1,3}|`+|~~)")
    private val rule = Regex("^\\s*([-*_]\\s*){3,}$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{3,}.*$")

    fun strip(md: String): String = md.lines()
        .filterNot { fence.matches(it) || rule.matches(it) || tableSep.matches(it) }
        .joinToString("\n") { line ->
            line.replace(heading, "")
                .replace(bullet, "")
                .replace(quote, "")
                .replace(image, "$1")
                .replace(link, "$1")
                .replace(emphasis, "")
                .replace('|', ' ')
        }
}
