package dev.aliberke.sesli.importers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.aliberke.sesli.text.TextCleaner
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object PdfImporter {
    private const val MAX_OCR_PAGES = 60
    private val pageNumberLine = Regex("(?m)^\\s*(page\\s*)?\\d{1,4}(\\s*(/|of)\\s*\\d{1,4})?\\s*$", RegexOption.IGNORE_CASE)

    fun import(context: Context, uri: Uri, title: String): ImportedText =
        fromBytes(context, Importers.readBytes(context, uri), title, uri.toString())

    fun fromBytes(context: Context, bytes: ByteArray, fallbackTitle: String, sourceUri: String?): ImportedText {
        val file = File.createTempFile("import", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            var title = fallbackTitle
            var pageCount = 0
            val extracted = try {
                PDDocument.load(file).use { doc ->
                    pageCount = doc.numberOfPages
                    doc.documentInformation?.title?.takeIf { it.isNotBlank() && it.length < 200 }?.let { title = it }
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        paragraphStart = "\n"
                    }
                    stripper.getText(doc)
                }
            } catch (e: Exception) {
                throw ImportException("Couldn't read this PDF: ${e.message ?: "unknown error"}")
            }
            var text = TextCleaner.reflow(extracted.replace(pageNumberLine, ""))
            // Scanned PDFs have (almost) no text layer: render the pages and run OCR instead.
            if (text.count { it.isLetter() } < 40 * minOf(pageCount, 3)) {
                text = ocrPages(file)
            }
            return ImportedText(title.ifBlank { "PDF" }, text, "pdf", sourceUri)
        } finally {
            file.delete()
        }
    }

    private fun ocrPages(file: File): String {
        val pages = ArrayList<String>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, MAX_OCR_PAGES)) {
                    renderer.openPage(i).use { page ->
                        val width = 1600
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 4000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(OcrImporter.recognize(InputImage.fromBitmap(bitmap, 0)))
                        bitmap.recycle()
                    }
                }
            }
        }
        return pages.filter { it.isNotBlank() }.joinToString("\n\n")
    }
}

object OcrImporter {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun recognize(image: InputImage): String {
        val result = Tasks.await(recognizer.process(image))
        return result.textBlocks.joinToString("\n\n") { block ->
            TextCleaner.reflow(block.lines.joinToString("\n") { it.text })
        }
    }

    fun fromImages(context: Context, uris: List<Uri>, title: String?): ImportedText {
        val text = uris.joinToString("\n\n") { uri ->
            try {
                recognize(InputImage.fromFilePath(context, uri))
            } catch (e: Exception) {
                throw ImportException("Couldn't read text from the image: ${e.message ?: "unknown error"}")
            }
        }
        if (text.isBlank()) throw ImportException("No text found in the image.")
        return ImportedText(title ?: "Scan", text, "image", uris.firstOrNull()?.toString())
    }
}

object EpubImporter {
    fun import(context: Context, uri: Uri, fallbackTitle: String): ImportedText {
        val entries = HashMap<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && ext in setOf("xhtml", "html", "htm", "xml", "opf")) entries[name] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
        } ?: throw ImportException("Couldn't open the file.")

        val container = entries["META-INF/container.xml"] ?: throw ImportException("This isn't a valid EPUB.")
        val opfPath = xml(container).getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?: throw ImportException("This isn't a valid EPUB.")
        val opf = xml(entries[opfPath] ?: throw ImportException("This EPUB is missing its package file."))
        val base = opfPath.substringBeforeLast('/', "")

        val manifest = (opf.getElementsByTag("item") + opf.getElementsByTag("opf:item"))
            .associate { it.attr("id") to it.attr("href") }
        val spine = (opf.getElementsByTag("itemref") + opf.getElementsByTag("opf:itemref")).mapNotNull { manifest[it.attr("idref")] }
        val title = (opf.getElementsByTag("dc:title").firstOrNull() ?: opf.getElementsByTag("title").firstOrNull())
            ?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle

        val chapters = spine.mapNotNull { href ->
            entries[resolve(base, href)]?.let { bytes -> HtmlText.extract(Jsoup.parse(String(bytes, Charsets.UTF_8)).body()) }
        }.filter { it.isNotBlank() }
        if (chapters.isEmpty()) throw ImportException("No readable chapters found in this EPUB.")
        return ImportedText(title, chapters.joinToString("\n\n"), "epub", uri.toString())
    }

    private fun xml(bytes: ByteArray) = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    private fun resolve(base: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayList<String>()
        for (segment in ("$base/$clean").split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }
}

object DocxImporter {
    fun import(context: Context, uri: Uri, fallbackTitle: String): ImportedText {
        var body: String? = null
        var core: String? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "word/document.xml" -> body = String(zip.readBytes(), Charsets.UTF_8)
                        "docProps/core.xml" -> core = String(zip.readBytes(), Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        } ?: throw ImportException("Couldn't open the file.")

        val doc = Jsoup.parse(body ?: throw ImportException("This isn't a valid Word document."), "", Parser.xmlParser())
        val paragraphs = doc.getElementsByTag("w:p").map { p -> paragraphText(p) }
        val title = core?.let { Jsoup.parse(it, "", Parser.xmlParser()).getElementsByTag("dc:title").firstOrNull()?.text() }
            ?.takeIf { it.isNotBlank() } ?: fallbackTitle
        return ImportedText(title, paragraphs.joinToString("\n"), "docx", uri.toString())
    }

    private fun paragraphText(p: Element): String {
        val sb = StringBuilder()
        for (el in p.getAllElements()) {
            when (el.tagName()) {
                "w:t" -> sb.append(el.wholeText())
                "w:tab" -> sb.append(' ')
                "w:br", "w:cr" -> sb.append('\n')
            }
        }
        return sb.toString()
    }
}
