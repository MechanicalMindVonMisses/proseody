package dev.aliberke.sesli.neural

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Speaks utterances with an on-device sherpa-onnx model, mirroring TextToSpeech's queue semantics
 * (speak = QUEUE_ADD, stop = flush) and reporting progress through an UtteranceProgressListener.
 *
 * One thread synthesizes queued sentences ahead of time; another streams them to an AudioTrack and
 * fires onStart / onRangeStart / onDone when the playback head actually reaches each point, so the
 * word highlight follows what is heard rather than what was written to the buffer.
 */
class NeuralSpeaker(
    private val modelDir: (NeuralModel) -> File,
    private val attributes: AudioAttributes,
    private val listener: UtteranceProgressListener,
) {
    companion object {
        private const val TAG = "NeuralSpeaker"
        private const val CHUNK = 1024
        private const val MAX_MODEL_SPEED = 2f
        private const val PRELOAD = "\u0000preload"
        private val wordPattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*")
    }

    private class Job(val id: String, val text: String, val session: Int)
    private class Rendered(val id: String, val text: String, val samples: FloatArray, val sampleRate: Int, val session: Int)
    private class Marker(val frame: Long, val fire: () -> Unit)

    @Volatile private var session = 0
    @Volatile private var voice: NeuralVoice? = null
    @Volatile private var rate = 1f
    @Volatile private var synthesizing = false

    private val pending = LinkedBlockingQueue<Job>()
    private val ready = LinkedBlockingQueue<Rendered>(4)

    // Synthesis thread state.
    private var tts: OfflineTts? = null
    private var loadedKey: String? = null

    // Playback thread state.
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var appliedSpeed = 1f
    private var written = 0L
    private var drainedAt = 0L
    private var handledSession = -1
    private val markers = ArrayDeque<Marker>()
    private var current: Rendered? = null
    private var offset = 0

    init {
        thread(name = "sesli-synth", isDaemon = true) { synthLoop() }
        thread(name = "sesli-play", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            playLoop()
        }
    }

    /** Selects the voice and loads its model in the background so the first sentence starts sooner. */
    fun setVoice(v: NeuralVoice) {
        voice = v
        pending.add(Job(PRELOAD, "", session))
    }

    fun setRate(r: Float) {
        rate = r
    }

    fun speak(text: String, utteranceId: String): Boolean {
        pending.add(Job(utteranceId, text, session))
        return true
    }

    fun stop() {
        session++
        pending.clear()
        ready.clear()
    }

    // ---- synthesis ----------------------------------------------------------------------------

    private fun synthLoop() {
        while (true) {
            val job = pending.take()
            val v = voice ?: continue
            if (job.id == PRELOAD) {
                runCatching { engineFor(v) }.onFailure { Log.e(TAG, "Couldn't preload ${v.model.id}", it) }
                continue
            }
            if (job.session != session) continue
            val engine = try {
                engineFor(v)
            } catch (e: Throwable) {
                Log.e(TAG, "Couldn't load ${v.model.id}", e)
                listener.onError(job.id, TextToSpeech.ERROR_NOT_INSTALLED_YET)
                continue
            }
            val began = System.nanoTime()
            synthesizing = true
            val audio = try {
                engine.generate(job.text, v.speakerId, rate.coerceIn(0.5f, MAX_MODEL_SPEED))
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis failed", e)
                listener.onError(job.id, TextToSpeech.ERROR_SYNTHESIS)
                continue
            } finally {
                synthesizing = false
            }
            val tookMs = (System.nanoTime() - began) / 1_000_000
            val audioMs = audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
            Log.i(TAG, "Synthesized ${job.text.length} chars -> $audioMs ms audio in $tookMs ms")
            val rendered = Rendered(job.id, job.text, audio.samples, audio.sampleRate, job.session)
            while (job.session == session && !ready.offer(rendered, 50, TimeUnit.MILLISECONDS)) Unit
        }
    }

    /** British Kokoro voices need the GB lexicon, so the loaded engine is keyed by model + lexicon. */
    private fun engineFor(v: NeuralVoice): OfflineTts {
        val dir = modelDir(v.model).absolutePath
        val british = v.locale.country == "GB"
        val key = "${v.model.id}:${if (british) "gb" else "us"}"
        tts?.takeIf { loadedKey == key }?.let { return it }
        tts?.release()
        tts = null
        val modelConfig = when (v.model.kind) {
            ModelKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    // Prefer the full-precision model when present (better quality), else the int8 one.
                    model = if (File("$dir/model.onnx").exists()) "$dir/model.onnx" else "$dir/model.int8.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                    lexicon = if (british) "$dir/lexicon-gb-en.txt" else "$dir/lexicon-us-en.txt",
                    lang = if (british) "en-gb" else "en-us",
                ),
                numThreads = 4,
            )
            ModelKind.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/${v.model.id.removePrefix("vits-piper-")}.onnx",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = 2,
            )
        }
        val started = System.currentTimeMillis()
        return OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelConfig, maxNumSentences = 1)).also {
            tts = it
            loadedKey = key
            Log.i(TAG, "Loaded $key in ${System.currentTimeMillis() - started} ms, ${it.sampleRate()} Hz")
        }
    }

    // ---- playback -----------------------------------------------------------------------------

    private fun playLoop() {
        while (true) {
            try {
                playStep()
            } catch (e: Throwable) {
                Log.e(TAG, "Playback error", e)
                current = null
                Thread.sleep(50)
            }
        }
    }

    private fun playStep() {
        if (session != handledSession) {
            handledSession = session
            // A fresh track guarantees the playback head restarts at frame 0 for the new markers.
            track?.let {
                it.pause()
                it.flush()
                it.release()
            }
            track = null
            trackRate = 0
            written = 0
            drainedAt = 0L
            markers.clear()
            current = null
        }
        dispatchMarkers()
        val cur = current
        if (cur == null) {
            val next = ready.poll(15, TimeUnit.MILLISECONDS)
            if (next == null) {
                padWhileWaiting()
                trackDrain()
            } else if (next.session == session) {
                begin(next)
            }
            return
        }
        val t = track ?: return
        val n = t.write(cur.samples, offset, min(CHUNK, cur.samples.size - offset), AudioTrack.WRITE_NON_BLOCKING)
        when {
            n > 0 -> {
                offset += n
                written += n
            }
            n == 0 -> Thread.sleep(8)
            else -> current = null
        }
        if (offset >= cur.samples.size) current = null
    }

    private fun begin(r: Rendered) {
        val t = ensureTrack(r.sampleRate)
        if (drainedAt != 0L) {
            Log.i(TAG, "Gap: waited ${System.currentTimeMillis() - drainedAt} ms for synthesis")
            drainedAt = 0L
        }
        val trackSpeed = max(1f, rate / MAX_MODEL_SPEED)
        if (trackSpeed != appliedSpeed) {
            t.playbackParams = PlaybackParams().setSpeed(trackSpeed).setPitch(1f)
            appliedSpeed = trackSpeed
        }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()

        val start = written
        val total = r.samples.size.toLong()
        markers.add(Marker(start) { listener.onStart(r.id) })
        for ((range, frame) in wordTimings(r.text, total)) {
            markers.add(Marker(start + frame) { listener.onRangeStart(r.id, range.first, range.last + 1, 0) })
        }
        markers.add(Marker(start + total) { listener.onDone(r.id) })
        current = r
        offset = 0
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.takeIf { trackRate == sampleRate }?.let { return it }
        track?.release()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBytes, sampleRate / 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { t.setStartThresholdInFrames(CHUNK) }
        }
        track = t
        trackRate = sampleRate
        appliedSpeed = 1f
        written = 0
        markers.clear()
        return t
    }

    private fun headFrame(): Long = (track?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL

    private fun dispatchMarkers() {
        if (markers.isEmpty()) return
        val head = headFrame()
        while (markers.isNotEmpty() && markers.peekFirst()!!.frame <= head) markers.pollFirst()!!.fire()
    }

    /** While waiting on synthesis (or at the very end) keep the buffer moving so queued audio and markers play out. */
    private fun padWhileWaiting() {
        val t = track ?: return
        if (markers.isEmpty() || t.playState != AudioTrack.PLAYSTATE_PLAYING) return
        if (written - headFrame() > CHUNK * 2) return
        val n = t.write(FloatArray(CHUNK), 0, CHUNK, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) written += n
    }

    /** Notes when audio ran out while more speech is still expected (a gap the listener hears). */
    private fun trackDrain() {
        val t = track ?: return
        if (drainedAt == 0L && t.playState == AudioTrack.PLAYSTATE_PLAYING && headFrame() >= written && (synthesizing || pending.isNotEmpty())) {
            drainedAt = System.currentTimeMillis()
        }
    }

    /**
     * Spreads the utterance's duration over its words by length, giving extra weight to the pauses
     * that follow punctuation. Returns (char range within the utterance, frame offset).
     */
    private fun wordTimings(text: String, totalFrames: Long): List<Pair<IntRange, Long>> {
        val words = wordPattern.findAll(text).toList()
        if (words.isEmpty()) return emptyList()
        val weights = DoubleArray(words.size)
        for (i in words.indices) {
            val gapEnd = if (i + 1 < words.size) words[i + 1].range.first else text.length
            val gap = text.substring(words[i].range.last + 1, gapEnd)
            val pause = when {
                gap.any { it in ".!?…" } -> 6.0
                gap.any { it in ",;:—–()\"" } -> 3.0
                else -> 0.0
            }
            weights[i] = words[i].value.length + 1.0 + pause
        }
        val sum = weights.sum()
        val lead = totalFrames * 0.03
        val usable = totalFrames * 0.94
        var acc = 0.0
        return words.mapIndexed { i, m ->
            val frame = (lead + usable * acc / sum).toLong()
            acc += weights[i]
            m.range to frame
        }
    }
}
