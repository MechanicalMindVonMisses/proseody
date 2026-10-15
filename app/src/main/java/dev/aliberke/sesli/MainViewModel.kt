package dev.aliberke.sesli

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aliberke.sesli.data.Document
import dev.aliberke.sesli.importers.ImportedText
import dev.aliberke.sesli.importers.Importers
import dev.aliberke.sesli.importers.OcrImporter
import dev.aliberke.sesli.importers.WebImporter
import dev.aliberke.sesli.text.Segmenter
import dev.aliberke.sesli.text.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

sealed interface UiEvent {
    data class Open(val docId: Long) : UiEvent
    data class Message(val text: String) : UiEvent
}

class ReaderContent(val doc: Document, val text: String, val paragraphs: List<Span>)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ProseodyApp
    private val repository = app.repository
    val engine = app.engine
    val prefs = app.prefs

    val documents = repository.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _reader = MutableStateFlow<ReaderContent?>(null)
    val reader = _reader.asStateFlow()

    private val urlPattern = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    // ---- importing --------------------------------------------------------------------------

    private fun runImport(block: suspend () -> List<ImportedText>) {
        viewModelScope.launch {
            _busy.value = true
            try {
                var lastId: Long? = null
                for (item in withContext(Dispatchers.IO) { block() }) lastId = repository.add(item)
                lastId?.let { _events.send(UiEvent.Open(it)) }
            } catch (e: Exception) {
                _events.send(UiEvent.Message(e.message ?: "Import failed."))
            } finally {
                _busy.value = false
            }
        }
    }

    fun importText(title: String, text: String) = runImport { listOf(Importers.plainTextImport(title, text)) }

    fun importUrl(url: String) = runImport { listOf(WebImporter.import(app, url)) }

    fun importImages(uris: List<Uri>, title: String?) = runImport {
        listOf(OcrImporter.fromImages(app, uris, title ?: "Scan ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}"))
    }

    /** Each picked file becomes its own document; images picked together are merged into one. */
    fun importUris(uris: List<Uri>, mimeHint: String? = null) = runImport {
        val (images, files) = uris.partition { (app.contentResolver.getType(it) ?: mimeHint ?: "").startsWith("image/") }
        buildList {
            if (images.isNotEmpty()) add(OcrImporter.fromImages(app, images, Importers.displayName(app, images.first()).substringBeforeLast('.')))
            files.forEach { add(Importers.fromUri(app, it, mimeHint)) }
        }
    }

    fun newCameraUri(): Uri {
        val dir = File(app.cacheDir, "scans").apply { mkdirs() }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", File(dir, "scan_${System.currentTimeMillis()}.jpg"))
    }

    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    importUris(listOf(stream), intent.type)
                    return
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) return
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                val url = urlPattern.find(text)?.value
                // A shared link (possibly with a short headline around it) is imported as a web page.
                if (url != null && text.length - url.length < 200) importUrl(url) else importText(subject, text)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                if (uris.isNotEmpty()) importUris(uris, intent.type)
            }
            Intent.ACTION_VIEW -> intent.data?.let { importUris(listOf(it), intent.type) }
            else -> if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_PLAYER, false)) {
                engine.state.value.docId?.let { id -> viewModelScope.launch { _events.send(UiEvent.Open(id)) } }
            }
        }
    }

    // ---- library ----------------------------------------------------------------------------

    fun delete(doc: Document) {
        if (engine.state.value.docId == doc.id) engine.unload()
        viewModelScope.launch { repository.delete(doc) }
    }

    fun rename(doc: Document, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.rename(doc.id, title.trim()) }
    }

    // ---- reader -----------------------------------------------------------------------------

    fun openReader(id: Long) {
        if (_reader.value?.doc?.id == id) return
        viewModelScope.launch {
            val doc = repository.get(id) ?: return@launch
            val text = repository.text(id)
            val locale = doc.language?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            val (sentences, paragraphs) = withContext(Dispatchers.Default) {
                Segmenter.sentences(text, locale) to Segmenter.paragraphs(text)
            }
            repository.markOpened(id)
            val start = if (doc.progress >= text.length) 0 else doc.progress
            engine.load(doc.id, doc.title, text, sentences, doc.language, start)
            _reader.value = ReaderContent(doc, text, paragraphs)
        }
    }
}
