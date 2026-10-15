package dev.aliberke.sesli.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dev.aliberke.sesli.Prefs
import dev.aliberke.sesli.neural.ModelManager
import dev.aliberke.sesli.neural.ModelStatus
import dev.aliberke.sesli.neural.NeuralCatalog
import dev.aliberke.sesli.neural.NeuralSpeaker
import dev.aliberke.sesli.neural.NeuralVoice
import dev.aliberke.sesli.text.Segmenter
import dev.aliberke.sesli.text.Span
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

data class PlaybackState(
    val docId: Long? = null,
    val title: String = "",
    val length: Int = 0,
    val playing: Boolean = false,
    /** Character offset of the word being spoken (or where playback will resume). */
    val position: Int = 0,
    val sentence: Span? = null,
    val word: Span? = null,
    val rate: Float = 1f,
    val language: String? = null,
    val voiceName: String? = null,
    val enginePackage: String? = null,
    val ready: Boolean = false,
    val error: String? = null,
)

data class VoiceOption(val name: String, val locale: Locale, val quality: Int, val network: Boolean)

data class EngineOption(val packageName: String, val label: String)

/**
 * Reads a document aloud through an Android TextToSpeech engine, one sentence per utterance,
 * keeping a few sentences queued so there are no gaps. Word positions come from
 * UtteranceProgressListener.onRangeStart; for engines that never report ranges the position is
 * estimated from elapsed time. All public methods must be called on the main thread.
 */
class SpeechEngine(private val context: Context, private val prefs: Prefs, val models: ModelManager) {
    companion object {
        const val CHARS_PER_SECOND = 14.5
        private const val QUEUE_AHEAD = 3
        private const val NEURAL_PIECE = 140
        private const val FIRST_PIECE = 30
        private const val GOOGLE_TTS = "com.google.android.tts"
        private val DEFAULT_COUNTRY = mapOf(
            "en" to "US", "es" to "ES", "pt" to "BR", "fr" to "FR", "de" to "DE", "tr" to "TR",
            "it" to "IT", "nl" to "NL", "ru" to "RU", "ar" to "SA", "zh" to "CN", "ja" to "JP",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private val _state = MutableStateFlow(PlaybackState(rate = prefs.rate))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val _engines = MutableStateFlow<List<EngineOption>>(emptyList())
    val engines: StateFlow<List<EngineOption>> = _engines.asStateFlow()

    /** Called with (docId, charOffset) whenever progress is worth saving. */
    var onProgress: ((Long, Int) -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Set while an on-device neural voice is selected; utterances then go to [neural] instead of [tts]. */
    private var neuralVoice: NeuralVoice? = null
    private var neural: NeuralSpeaker? = null

    private var text = ""
    private var sentences: List<Span> = emptyList()

    /** Bumped on every stop/restart; callbacks from older utterances are ignored. */
    private var generation = 0
    private var nextIndex = 0
    private var outstanding = 0
    private var pieceTarget = FIRST_PIECE
    private var pendingPlay = false
    private var consecutiveErrors = 0
    private var rangeSupported = false
    private var estimator: Job? = null
    private var lastPersist = 0L

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }, main)
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var noisyRegistered = false

    init {
        createTts(prefs.enginePackage ?: GOOGLE_TTS.takeIf { isInstalled(it) })
        adoptNewModels()
        // A freshly downloaded (or removed) neural model can change which voice the document should use.
        scope.launch {
            models.status
                .map { status -> status.filterValues { it == ModelStatus.Installed }.keys }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    adoptNewModels()
                    val before = _state.value.voiceName
                    applyVoiceForLanguage()
                    if (_state.value.playing && _state.value.voiceName != before) restartAtCurrentWord()
                }
        }
    }

    /**
     * The first time a neural model is found installed (downloaded in-app or copied over adb), make its
     * default voice the preferred one for its language; later choices in the voice sheet still win.
     */
    private fun adoptNewModels() {
        val adopted = prefs.adoptedModels
        val fresh = NeuralCatalog.models.filter { it.id !in adopted && models.isInstalled(it) }
        if (fresh.isEmpty()) return
        fresh.forEach { model -> NeuralCatalog.defaultFor(model.language)?.let { prefs.setVoiceFor(model.language, it.id) } }
        prefs.adoptedModels = adopted + fresh.map { it.id }
    }

    private fun neuralSpeaker(): NeuralSpeaker =
        neural ?: NeuralSpeaker({ models.dir(it) }, audioAttributes, listener).also {
            it.setRate(_state.value.rate)
            neural = it
        }

    private fun activateNeural(voice: NeuralVoice) {
        neuralVoice = voice
        neuralSpeaker().setVoice(voice)
        _state.update { it.copy(voiceName = voice.id) }
    }

    // ---- engine lifecycle -------------------------------------------------------------------

    private fun isInstalled(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun createTts(enginePackage: String?) {
        ttsReady = false
        rangeSupported = false
        _state.update { it.copy(ready = false, enginePackage = enginePackage) }
        val onInit = TextToSpeech.OnInitListener { status -> main.post { onTtsInit(status) } }
        tts = if (enginePackage != null) TextToSpeech(context, onInit, enginePackage) else TextToSpeech(context, onInit)
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            pendingPlay = false
            _state.update { it.copy(playing = false, error = "The text-to-speech engine failed to start.") }
            return
        }
        ttsReady = true
        t.setAudioAttributes(audioAttributes)
        t.setOnUtteranceProgressListener(listener)
        _engines.value = t.engines.map { EngineOption(it.name, it.label) }
        _state.update { it.copy(ready = true, enginePackage = it.enginePackage ?: t.defaultEngine) }
        loadVoices(attempt = 0)
    }

    /** Some engines return an empty voice list right after init, so retry a few times. */
    private fun loadVoices(attempt: Int) {
        val t = tts ?: return
        val all = try {
            t.voices
        } catch (_: Exception) {
            null
        }
        if (all.isNullOrEmpty() && attempt < 4) {
            main.postDelayed({ loadVoices(attempt + 1) }, 600)
            return
        }
        _voices.value = all.orEmpty()
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .map { VoiceOption(it.name, it.locale, it.quality, it.isNetworkConnectionRequired) }
            .sortedWith(compareBy<VoiceOption> { it.locale.getDisplayName(Locale.getDefault()) }.thenBy { it.network }.thenBy { it.name })
        applyVoiceForLanguage()
        if (pendingPlay) {
            pendingPlay = false
            play()
        }
    }

    /**
     * Picks the voice for the document's language: the user's saved choice, else an installed neural
     * voice for that language, else the best system voice.
     */
    private fun applyVoiceForLanguage() {
        val locale = Locale.forLanguageTag(_state.value.language ?: Locale.getDefault().toLanguageTag())
        val language = locale.language
        val preferred = prefs.voiceFor(language)
        val neuralChoice = (if (preferred != null) NeuralCatalog.voice(preferred) else NeuralCatalog.defaultFor(language))
            ?.takeIf { models.isInstalled(it.model) }
        if (neuralChoice != null) {
            activateNeural(neuralChoice)
            return
        }
        neuralVoice = null
        val t = tts ?: return
        if (!ttsReady) return
        val country = locale.country.ifEmpty {
            if (language == Locale.getDefault().language) Locale.getDefault().country else DEFAULT_COUNTRY[language].orEmpty()
        }
        val candidates = _voices.value
        val choice = preferred?.let { name -> candidates.firstOrNull { it.name == name } }
            ?: candidates.filter { it.locale.language == language }
                .sortedWith(
                    compareByDescending<VoiceOption> { it.locale.country.equals(country, ignoreCase = true) }
                        .thenBy { it.network }
                        .thenByDescending { it.quality }
                )
                .firstOrNull()
        val voice = choice?.let { c -> t.voices?.firstOrNull { it.name == c.name } }
        if (voice != null) {
            t.voice = voice
        } else {
            t.language = locale
        }
        _state.update { it.copy(voiceName = t.voice?.name) }
    }

    fun setEngine(pkg: String) {
        if (pkg == _state.value.enginePackage && ttsReady) return
        val wasPlaying = _state.value.playing
        stopInternal()
        releaseAudio()
        _state.update { it.copy(playing = false) }
        tts?.shutdown()
        prefs.enginePackage = pkg
        _voices.value = emptyList()
        createTts(pkg)
        pendingPlay = wasPlaying
    }

    /** Accepts a system voice name or a [NeuralVoice.id]. */
    fun setVoice(name: String) {
        val neuralChoice = NeuralCatalog.voice(name)
        if (neuralChoice != null) {
            if (!models.isInstalled(neuralChoice.model)) return
            prefs.setVoiceFor(neuralChoice.locale.language, name)
            activateNeural(neuralChoice)
        } else {
            val t = tts ?: return
            val voice = t.voices?.firstOrNull { it.name == name } ?: return
            neuralVoice = null
            neural?.stop()
            t.voice = voice
            prefs.setVoiceFor(voice.locale.language, name)
            _state.update { it.copy(voiceName = name) }
        }
        if (_state.value.playing) restartAtCurrentWord()
    }

    /** Selects a voice and, when nothing is playing, speaks the current sentence with it. */
    fun previewVoice(name: String) {
        setVoice(name)
        if (_state.value.playing) return
        val i = Segmenter.indexAt(sentences, _state.value.position)
        val sample = if (i >= 0) text.substring(sentences[i].start, sentences[i].end).take(220) else "Hello! This is how I sound."
        tts?.stop()
        neural?.stop()
        if (neuralVoice != null) {
            neuralSpeaker().speak(sample, "preview")
        } else {
            tts?.setSpeechRate(_state.value.rate)
            tts?.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
        }
    }

    fun setRate(rate: Float) {
        prefs.rate = rate
        _state.update { it.copy(rate = rate) }
        tts?.setSpeechRate(rate)
        neural?.setRate(rate)
        if (_state.value.playing) restartAtCurrentWord()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---- document & transport ---------------------------------------------------------------

    fun load(docId: Long, title: String, text: String, sentences: List<Span>, language: String?, startAt: Int) {
        if (_state.value.docId == docId && this.text == text) return
        pause()
        stopInternal()
        this.text = text
        this.sentences = sentences
        val pos = startAt.coerceIn(0, text.length)
        _state.update {
            it.copy(
                docId = docId, title = title, length = text.length, playing = false, position = pos,
                sentence = sentenceAt(pos), word = null, language = language, error = null,
            )
        }
        applyVoiceForLanguage()
    }

    fun unload() {
        pause()
        stopInternal()
        text = ""
        sentences = emptyList()
        _state.update { it.copy(docId = null, title = "", length = 0, position = 0, sentence = null, word = null) }
    }

    fun togglePlay() {
        if (_state.value.playing) pause() else play()
    }

    fun play() {
        if (_state.value.docId == null || sentences.isEmpty()) return
        if (neuralVoice == null && !ttsReady) {
            pendingPlay = true
            _state.update { it.copy(playing = true) }
            return
        }
        var pos = _state.value.position
        if (pos >= sentences.last().end) pos = 0
        startFrom(pos)
    }

    fun pause() {
        pendingPlay = false
        if (!_state.value.playing) return
        stopInternal()
        releaseAudio()
        _state.update { it.copy(playing = false) }
        persist(force = true)
    }

    fun seekTo(charPos: Int) {
        if (_state.value.docId == null) return
        val pos = wordStart(charPos)
        val wasPlaying = _state.value.playing
        stopInternal()
        val end = wordEnd(pos)
        _state.update { it.copy(position = pos, sentence = sentenceAt(pos), word = if (end > pos) Span(pos, end) else null) }
        if (wasPlaying) startFrom(pos) else persist(force = true)
    }

    fun nextSentence() {
        val i = Segmenter.indexAt(sentences, _state.value.position)
        if (i >= 0 && i + 1 < sentences.size) seekTo(sentences[i + 1].start)
    }

    /** Restarts the current sentence, or goes to the previous one when already at its start. */
    fun previousSentence() {
        val i = Segmenter.indexAt(sentences, _state.value.position)
        if (i < 0) return
        val current = sentences[i]
        val target = if (_state.value.position - current.start > 12 || i == 0) current.start else sentences[i - 1].start
        seekTo(target)
    }

    private fun restartAtCurrentWord() {
        startFrom(_state.value.word?.start ?: _state.value.position)
    }

    private fun startFrom(pos: Int) {
        val t = tts
        if (neuralVoice == null && t == null) return
        stopInternal()
        var idx = Segmenter.indexAt(sentences, pos)
        if (idx < 0) return
        if (pos >= sentences[idx].end) idx++
        if (idx >= sentences.size) {
            finish()
            return
        }
        acquireAudio()
        t?.setSpeechRate(_state.value.rate)
        neural?.setRate(_state.value.rate)
        val first = Span(max(pos, sentences[idx].start), sentences[idx].end)
        _state.update { it.copy(playing = true, position = first.start, sentence = sentences[idx]) }
        pieceTarget = FIRST_PIECE
        speak(first)
        nextIndex = idx + 1
        fillQueue()
    }

    /**
     * Neural voices must synthesize a whole piece before it plays, and synthesis runs only somewhat
     * faster than real time. So playback starts with a ~30-char piece and each following piece may be
     * ~30% longer (across sentence boundaries) up to [NEURAL_PIECE]; the time saved on each piece
     * accumulates as a buffer, so the next piece is always ready before the current one ends.
     */
    private fun neuralPieces(span: Span): List<Span> {
        val out = ArrayList<Span>()
        var start = span.start
        while (span.end - start > pieceTarget * 5 / 4) {
            val cut = breakNear(start, pieceTarget) ?: break
            out.add(Span(start, cut))
            start = cut
            while (start < span.end && text[start].isWhitespace()) start++
            pieceTarget = minOf(NEURAL_PIECE, pieceTarget * 13 / 10)
        }
        out.add(Span(start, span.end))
        pieceTarget = minOf(NEURAL_PIECE, pieceTarget * 13 / 10)
        return out
    }

    /** A cut point near start+ideal: after a comma/semicolon if one is close, else at a space. */
    private fun breakNear(start: Int, ideal: Int): Int? {
        val lo = start + ideal * 2 / 3
        val hi = minOf(text.length - 1, start + ideal * 4 / 3)
        if (lo >= hi) return null
        return (lo..hi).filter { text[it - 1] in ",;:" && text[it] == ' ' }.minByOrNull { kotlin.math.abs(it - start - ideal) }
            ?: (lo..hi).filter { text[it] == ' ' }.minByOrNull { kotlin.math.abs(it - start - ideal) }
    }

    private fun fillQueue() {
        while (outstanding < QUEUE_AHEAD && nextIndex < sentences.size) {
            speak(sentences[nextIndex])
            nextIndex++
        }
    }

    private fun speak(span: Span) {
        val pieces = if (neuralVoice != null) neuralPieces(span) else listOf(span)
        for (piece in pieces) {
            val utterance = text.substring(piece.start, piece.end)
            val id = "$generation:${piece.start}:${piece.end}"
            val ok = if (neuralVoice != null) {
                neuralSpeaker().speak(utterance, id)
            } else {
                tts?.speak(utterance, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS
            }
            if (ok) {
                outstanding++
            } else {
                main.post { onUtteranceError(TextToSpeech.ERROR) }
            }
        }
    }

    private fun stopInternal() {
        generation++
        estimator?.cancel()
        estimator = null
        outstanding = 0
        tts?.stop()
        neural?.stop()
    }

    private fun finish() {
        stopInternal()
        releaseAudio()
        _state.update { it.copy(playing = false, position = text.length, sentence = null, word = null) }
        persist(force = true)
    }

    // ---- utterance callbacks (posted to main) ------------------------------------------------

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = dispatch(utteranceId) { s, e -> onUtteranceStart(s, e) }
        override fun onDone(utteranceId: String) = dispatch(utteranceId) { _, _ -> onUtteranceDone() }
        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) =
            dispatch(utteranceId) { s, _ -> onWord(s + start, s + end) }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = dispatch(utteranceId) { _, _ -> onUtteranceError(TextToSpeech.ERROR) }
        override fun onError(utteranceId: String, errorCode: Int) = dispatch(utteranceId) { _, _ -> onUtteranceError(errorCode) }
    }

    private fun dispatch(utteranceId: String, block: (Int, Int) -> Unit) {
        val parts = utteranceId.split(':')
        if (parts.size != 3) return
        val gen = parts[0].toIntOrNull() ?: return
        val start = parts[1].toIntOrNull() ?: return
        val end = parts[2].toIntOrNull() ?: return
        main.post { if (gen == generation) block(start, end) }
    }

    private fun onUtteranceStart(start: Int, end: Int) {
        consecutiveErrors = 0
        _state.update { it.copy(position = start, sentence = sentenceAt(start) ?: Span(start, end), word = null) }
        persist(force = false)
        startEstimator(start, end)
    }

    private fun onWord(start: Int, end: Int) {
        if (start < 0 || end > text.length || end <= start) return
        rangeSupported = true
        estimator?.cancel()
        _state.update { it.copy(position = start, word = Span(start, end)) }
    }

    private fun onUtteranceDone() {
        outstanding = max(0, outstanding - 1)
        fillQueue()
        if (outstanding == 0 && nextIndex >= sentences.size) finish()
    }

    private fun onUtteranceError(code: Int) {
        consecutiveErrors++
        if (consecutiveErrors >= 2) {
            pause()
            _state.update { it.copy(error = errorMessage(code)) }
        } else {
            onUtteranceDone()
        }
    }

    private fun errorMessage(code: Int) = when (code) {
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            "This voice needs an internet connection. Pick an offline voice."
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "This voice isn't downloaded yet."
        else -> "The speech engine reported an error ($code). Try another voice."
    }

    /** For engines without onRangeStart: move the word highlight at an average speaking pace. */
    private fun startEstimator(start: Int, end: Int) {
        estimator?.cancel()
        if (rangeSupported) return
        val began = SystemClock.elapsedRealtime()
        val gen = generation
        estimator = scope.launch {
            delay(350)
            while (isActive && gen == generation && !rangeSupported) {
                val elapsed = SystemClock.elapsedRealtime() - began
                val pos = start + (elapsed / 1000.0 * CHARS_PER_SECOND * _state.value.rate).toInt()
                if (pos >= end) break
                val ws = wordStart(pos)
                val we = wordEnd(pos)
                if (we > ws) _state.update { it.copy(position = ws, word = Span(ws, we)) }
                delay(120)
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun sentenceAt(pos: Int): Span? = Segmenter.indexAt(sentences, pos).takeIf { it >= 0 }?.let { sentences[it] }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'

    private fun wordStart(pos: Int): Int {
        var p = pos.coerceIn(0, text.length)
        while (p > 0 && isWordChar(text[p - 1])) p--
        return p
    }

    private fun wordEnd(pos: Int): Int {
        var p = pos.coerceIn(0, text.length)
        while (p < text.length && isWordChar(text[p])) p++
        return p
    }

    private fun persist(force: Boolean) {
        val s = _state.value
        val id = s.docId ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersist < 4000) return
        lastPersist = now
        onProgress?.invoke(id, s.position)
    }

    private fun acquireAudio() {
        audioManager.requestAudioFocus(focusRequest)
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    private fun releaseAudio() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (noisyRegistered) {
            context.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
    }
}
