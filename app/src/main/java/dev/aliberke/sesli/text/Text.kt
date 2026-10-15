package dev.aliberke.sesli.text

import android.content.Context
import android.icu.text.BreakIterator
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Half-open character range [start, end) into a document. */
data class Span(val start: Int, val end: Int) {
    val length get() = end - start

    fun intersect(other: Span): Span? {
        val s = max(start, other.start)
        val e = min(end, other.end)
        return if (e > s) Span(s, e) else null
    }
}

object Segmenter {
    /** Longest utterance handed to the TTS engine; longer sentences are cut at a comma or space. */
    private const val MAX_UTTERANCE = 400

    /** Non-blank lines; each becomes one paragraph in the reader. */
    fun paragraphs(text: String): List<Span> {
        val out = ArrayList<Span>()
        var p = 0
        while (p <= text.length) {
            val nl = text.indexOf('\n', p).let { if (it < 0) text.length else it }
            var s = p
            var e = nl
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) out.add(Span(s, e))
            p = nl + 1
        }
        return out
    }

    /** Sentence-sized utterances, never crossing a line break. */
    fun sentences(text: String, locale: Locale): List<Span> {
        val out = ArrayList<Span>()
        val iterator = BreakIterator.getSentenceInstance(locale)
        for (para in paragraphs(text)) {
            iterator.setText(text.substring(para.start, para.end))
            var s = iterator.first()
            var e = iterator.next()
            while (e != BreakIterator.DONE) {
                addSentence(text, para.start + s, para.start + e, out)
                s = e
                e = iterator.next()
            }
        }
        return out
    }

    private fun addSentence(text: String, start: Int, end: Int, out: MutableList<Span>) {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e <= s || (s until e).none { text[it].isLetterOrDigit() }) return
        while (e - s > MAX_UTTERANCE) {
            val limit = s + MAX_UTTERANCE
            val floor = s + MAX_UTTERANCE / 2
            var cut = (limit downTo floor).firstOrNull { text[it - 1] in ",;:" && text[it].isWhitespace() }
                ?: (limit downTo floor).firstOrNull { text[it].isWhitespace() }
                ?: limit
            out.add(Span(s, cut))
            while (cut < e && text[cut].isWhitespace()) cut++
            s = cut
        }
        if (e > s) out.add(Span(s, e))
    }

    /** Index of the last span starting at or before [pos]; 0 when [pos] precedes all spans, -1 when empty. */
    fun indexAt(spans: List<Span>, pos: Int): Int {
        if (spans.isEmpty()) return -1
        var lo = 0
        var hi = spans.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].start <= pos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

object TextCleaner {
    private val horizontalSpace = Regex("[ \\t\\u000B\\u000C\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val zeroWidth = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]")

    /** Collapses spaces, trims every line, and keeps at most one blank line between paragraphs. */
    fun normalize(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace(zeroWidth, "").split('\n')
        val sb = StringBuilder(raw.length)
        var pendingBlank = false
        for (line in lines) {
            val l = line.replace(horizontalSpace, " ").trim()
            if (l.isEmpty()) {
                pendingBlank = true
                continue
            }
            if (sb.isNotEmpty()) sb.append(if (pendingBlank) "\n\n" else "\n")
            sb.append(l)
            pendingBlank = false
        }
        return sb.toString()
    }

    /** Joins hard-wrapped lines (PDF, OCR) into flowing paragraphs, undoing end-of-line hyphenation. */
    fun reflow(raw: String): String {
        val dehyphenated = raw.replace("\r\n", "\n").replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")
        val paragraphs = dehyphenated.split(Regex("\\n[ \\t]*\\n"))
            .map { it.replace('\n', ' ').replace(horizontalSpace, " ").trim() }
            .filter { it.isNotEmpty() }
        // Paragraph detection sometimes breaks mid-sentence; glue a lowercase continuation back on.
        val merged = ArrayList<String>()
        for (p in paragraphs) {
            val last = merged.lastOrNull()
            if (last != null && last.last().let { it.isLetterOrDigit() || it == ',' } && p.first().isLowerCase()) {
                merged[merged.size - 1] = "$last $p"
            } else {
                merged.add(p)
            }
        }
        return merged.joinToString("\n\n")
    }
}

object LanguageDetector {
    private const val TURKISH_ONLY = "ğĞışŞİ"

    fun detect(context: Context, text: String): String? {
        val sample = text.take(2000)
        try {
            val manager = context.getSystemService(TextClassificationManager::class.java)
            val result = manager?.textClassifier?.detectLanguage(TextLanguage.Request.Builder(sample).build())
            if (result != null && result.localeHypothesisCount > 0) {
                val best = result.getLocale(0)
                if (result.getConfidenceScore(best) >= 0.5f) return best.toLanguageTag()
            }
        } catch (_: Exception) {
            // Fall through to the heuristic below.
        }
        val turkishChars = sample.count { it in TURKISH_ONLY }
        return if (turkishChars * 200 > sample.length) "tr" else null
    }
}
