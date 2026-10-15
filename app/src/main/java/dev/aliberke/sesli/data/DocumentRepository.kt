package dev.aliberke.sesli.data

import android.content.Context
import dev.aliberke.sesli.importers.ImportException
import dev.aliberke.sesli.importers.ImportedText
import dev.aliberke.sesli.text.LanguageDetector
import dev.aliberke.sesli.text.TextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DocumentRepository(private val context: Context, private val dao: DocumentDao) {
    val documents = dao.observeAll()

    private val dir = File(context.filesDir, "docs").apply { mkdirs() }

    private fun file(id: Long) = File(dir, "$id.txt")

    suspend fun add(imported: ImportedText): Long = withContext(Dispatchers.IO) {
        val text = TextCleaner.normalize(imported.text)
        if (text.none { it.isLetterOrDigit() }) throw ImportException("No readable text found.")
        val title = imported.title.trim().ifBlank { text.lineSequence().first().take(80) }
        val id = dao.insert(
            Document(
                title = title,
                source = imported.source,
                sourceUri = imported.sourceUri,
                language = LanguageDetector.detect(context, text),
                length = text.length,
            )
        )
        file(id).writeText(text)
        id
    }

    suspend fun get(id: Long): Document? = dao.get(id)

    suspend fun text(id: Long): String = withContext(Dispatchers.IO) {
        file(id).takeIf { it.exists() }?.readText().orEmpty()
    }

    suspend fun delete(document: Document) = withContext(Dispatchers.IO) {
        dao.delete(document)
        file(document.id).delete()
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title)

    suspend fun updateProgress(id: Long, position: Int) = dao.updateProgress(id, position)

    suspend fun markOpened(id: Long) = dao.markOpened(id, System.currentTimeMillis())
}
