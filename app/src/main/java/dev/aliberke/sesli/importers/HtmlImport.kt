package dev.aliberke.sesli.importers

import android.content.Context
import dev.aliberke.sesli.text.TextCleaner
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Turns an HTML subtree into paragraphs: block elements become line breaks, inline text flows. */
object HtmlText {
    fun extract(root: Element?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> sb.append(node.text())
                    node is Element && node.normalName() == "br" -> sb.append('\n')
                    node is Element && node.isBlock -> sb.append("\n\n")
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock) sb.append("\n\n")
            }
        }, root)
        return TextCleaner.normalize(sb.toString())
    }
}

/** A small Readability-style extractor: strip chrome, score containers by paragraph text, keep the best. */
object ArticleExtractor {
    private val junk = Regex(
        "comment|share|social|related|recommend|promo|subscribe|newsletter|cookie|consent|advert|sponsor|" +
            "sidebar|breadcrumb|popup|modal|banner|signup|paywall|outbrain|taboola",
        RegexOption.IGNORE_CASE,
    )
    private val alwaysJunk = Regex("comment|related|recommend|outbrain|taboola", RegexOption.IGNORE_CASE)
    private val citation = Regex("\\[(\\d+|[a-z]|citation needed|note \\d+|clarification needed|when\\?|who\\?)]", RegexOption.IGNORE_CASE)

    private fun paragraphText(el: Element) = el.getElementsByTag("p").sumOf { p -> p.text().length.takeIf { it >= 25 } ?: 0 }

    fun title(doc: Document): String = listOf(
        doc.selectFirst("meta[property=og:title]")?.attr("content"),
        doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
        doc.selectFirst("h1")?.text(),
        doc.title(),
    ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    fun extract(doc: Document): String {
        val title = title(doc)
        doc.select(
            "script,style,noscript,template,iframe,svg,canvas,form,button,input,select,textarea," +
                "nav,header,footer,aside,figure,picture,video,audio,[aria-hidden=true],[hidden]," +
                // Wikipedia/MediaWiki furniture: citation marks, infoboxes, navboxes, reference lists.
                "sup.reference,.mw-editsection,.infobox,.navbox,.metadata,.hatnote,.thumb,.toc,#toc," +
                ".reflist,ol.references,.mw-references-wrap,.noprint,.sidebar,.shortdescription"
        ).remove()
        doc.allElements
            .filter { el ->
                el.normalName() !in setOf("html", "body", "article", "main") &&
                    (el.id() + " " + el.className()).let { attrs ->
                        alwaysJunk.containsMatchIn(attrs) || (junk.containsMatchIn(attrs) && el.getElementsByTag("p").size < 3)
                    }
            }
            .forEach { it.remove() }

        val scores = HashMap<Element, Int>()
        for (p in doc.getElementsByTag("p")) {
            val len = p.text().length
            if (len < 25) continue
            p.parent()?.let { scores[it] = (scores[it] ?: 0) + len }
            p.parent()?.parent()?.let { scores[it] = (scores[it] ?: 0) + len / 2 }
        }
        val best = scores.maxByOrNull { it.value }
        val article = doc.selectFirst("article")
        val articleScore = article?.getElementsByTag("p")?.sumOf { it.text().length } ?: 0
        var root = when {
            article != null && (best == null || articleScore >= best.value / 2) -> article
            best != null -> best.key
            else -> doc.body()
        }
        // Articles split into <section>s (often inside wrapper divs) score per section: move up to any
        // ancestor that holds substantially more prose than the current pick.
        var rootText = paragraphText(root)
        var ancestor = root.parent()
        while (ancestor != null && ancestor.normalName() != "html") {
            val text = paragraphText(ancestor)
            if (text > rootText * 5 / 4) {
                root = ancestor
                rootText = text
            }
            ancestor = ancestor.parent()
        }
        val body = HtmlText.extract(root).replace(citation, "")
        return if (title.isNotEmpty() && !body.startsWith(title)) "$title\n\n$body" else body
    }
}

object WebImporter {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    fun import(context: Context, rawUrl: String): ImportedText {
        val url = rawUrl.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val response = try {
            Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(25_000)
                .followRedirects(true)
                .ignoreContentType(true)
                .maxBodySize(60 * 1024 * 1024)
                .execute()
        } catch (e: Exception) {
            throw ImportException("Couldn't load the page: ${e.message ?: e.javaClass.simpleName}")
        }
        val contentType = response.contentType().orEmpty().lowercase()
        val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return when {
            contentType.contains("pdf") || name.endsWith(".pdf", ignoreCase = true) ->
                PdfImporter.fromBytes(context, response.bodyAsBytes(), name.removeSuffix(".pdf"), url).copy(source = "web")
            contentType.startsWith("text/plain") ->
                ImportedText(name, Importers.decode(response.bodyAsBytes()), "web", url)
            else -> {
                val doc = response.parse()
                val text = ArticleExtractor.extract(doc)
                ImportedText(ArticleExtractor.title(doc).ifBlank { response.url().host }, text, "web", url)
            }
        }
    }
}
