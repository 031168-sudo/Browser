package com.andrey.readerlite

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/**
 * Достаёт из HTML только статью: заголовки, абзацы, цитаты, списки и картинки.
 * Скрипты, стили, формы, навигацию, баннеры, рекламу, всплывающие окна и трекеры отбрасывает.
 */
object ArticleExtractor {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Теги, которые никогда не содержат текст статьи. */
    private const val JUNK_TAGS =
        "script, style, noscript, iframe, object, embed, form, button, input, select, textarea, " +
            "svg, canvas, video, audio, nav, footer, header, aside, dialog, template, link, meta"

    /** Слова-маркеры в class/id. Сравниваются по целым токенам, чтобы «heads» не попадал под «ad». */
    private val JUNK_TOKENS = setOf(
        "banner", "banners", "advert", "advertisement", "advertising", "ad", "ads",
        "promo", "sponsor", "sponsored", "popup", "pop", "modal", "overlay", "cookie", "cookies",
        "consent", "gdpr", "subscribe", "subscription", "newsletter", "paywall", "share", "sharing",
        "social", "related", "recommend", "recommended", "comment", "comments", "sidebar",
        "widget", "breadcrumb", "breadcrumbs", "tracker", "tracking", "sticky", "toolbar",
        "outbrain", "taboola", "adfox", "teaser", "teasers",
    )

    private val ICON_TOKENS = setOf("logo", "icon", "avatar", "pixel", "spacer", "tracking", "emoji")

    /** Скачивает страницу и возвращает статью. Бросает исключение при сетевой ошибке. */
    fun fetch(url: String): Article {
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(20_000)
            .followRedirects(true)
            .get()
        return extract(doc, url)
    }

    fun extract(doc: Document, pageUrl: String): Article {
        val title = pickTitle(doc)
        val siteName = doc.select("meta[property=og:site_name]").attr("content").trim()
            .ifBlank { hostOf(pageUrl) }

        // Копия до любых удалений: нужна для запасного разбора.
        val original = doc.clone()
        // Текст из разметки schema.org (JSON-LD) — его достаём, пока скрипты не удалены.
        val ldBlocks = jsonLdBody(original)

        // Основной путь: удаляем мусор по тегам и классам, берём основной контейнер.
        doc.select(JUNK_TAGS).remove()
        removeJunkByAttributes(doc)
        var blocks = collectBlocks(findMainContainer(doc) ?: doc.body(), loose = false)

        // Запасной путь: фильтр по классам мог вырезать текст, поэтому берём всю страницу
        // без него. Заодно считаем абзацами и div-ы с длинным текстом, на случай если сайт
        // не использует тег <p>.
        if (!hasText(blocks)) {
            original.select(JUNK_TAGS).remove()
            blocks = collectBlocks(original.body(), loose = true)
        }

        // Последний вариант: текст статьи из JSON-LD.
        if (!hasText(blocks)) {
            blocks = ldBlocks
        }

        if (!hasText(blocks)) {
            throw IllegalStateException("Не нашёл текст статьи на этой странице")
        }

        return Article(url = pageUrl, title = title, siteName = siteName, blocks = blocks)
    }

    private fun hasText(blocks: List<Block>): Boolean =
        blocks.any { it is Block.Paragraph || it is Block.Quote || it is Block.ListItem }

    private fun pickTitle(doc: Document): String {
        val candidates = listOf(
            doc.select("meta[property=og:title]").attr("content"),
            doc.select("article h1").firstOrNull()?.text().orEmpty(),
            doc.select("h1").firstOrNull()?.text().orEmpty(),
            doc.title(),
        )
        return candidates.map { clean(it) }.firstOrNull { it.isNotBlank() } ?: "Без названия"
    }

    private fun removeJunkByAttributes(doc: Document) {
        // Копия списка: элементы удаляются прямо в цикле.
        doc.body().allElements.toList().forEach { el ->
            if (el === doc.body() || el.parent() == null) return@forEach
            val tokens = (el.className() + " " + el.id()).lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() }
                .toSet()
            val role = el.attr("role").lowercase()
            val isDialog = role == "dialog" || role == "alertdialog" || el.attr("aria-modal") == "true"
            val isMarkedJunk = tokens.any { it in JUNK_TOKENS }
            // Контейнер с большим количеством абзацев — почти наверняка сама статья, его не трогаем.
            val isContentContainer = el.select("p").size > 3
            if (isDialog || (isMarkedJunk && !isContentContainer)) {
                el.remove()
            }
        }
    }

    private fun findMainContainer(doc: Document): Element? {
        doc.selectFirst("article")?.takeIf { textLength(it) > 300 }?.let { return it }
        doc.selectFirst("[role=main], main")?.takeIf { textLength(it) > 300 }?.let { return it }
        return doc.select("div, section")
            .maxByOrNull { paragraphScore(it) }
            ?.takeIf { paragraphScore(it) > 300 }
    }

    private fun paragraphScore(el: Element): Int =
        el.select("p").sumOf { p -> p.text().length.takeIf { it > 40 } ?: 0 }

    private fun textLength(el: Element): Int = el.text().length

    private fun jsonLdBody(doc: Document): List<Block> {
        for (script in doc.select("script[type=application/ld+json]")) {
            val body = findArticleBody(script.data()) ?: continue
            val blocks = if (body.contains('<')) {
                collectBlocks(Jsoup.parseBodyFragment(body).body(), loose = true)
            } else {
                body.split(Regex("\\n+")).map { clean(it) }.filter { it.length > 1 }
                    .map { Block.Paragraph(it) }
            }
            if (hasText(blocks)) return blocks
        }
        return emptyList()
    }

    private fun findArticleBody(json: String): String? = try {
        findKey(JSONTokener(json.trim()).nextValue(), "articleBody")
    } catch (e: Exception) {
        null
    }

    private fun findKey(node: Any?, key: String): String? = when (node) {
        is JSONObject -> node.optString(key).takeIf { it.isNotBlank() }
            ?: node.keys().asSequence().firstNotNullOfOrNull { findKey(node.opt(it), key) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findKey(node.opt(it), key) }
        else -> null
    }

    private fun collectBlocks(root: Element, loose: Boolean): List<Block> {
        val result = mutableListOf<Block>()
        val seen = HashSet<String>()
        val selector = if (loose) {
            "h2, h3, h4, p, blockquote, li, pre, img, div"
        } else {
            "h2, h3, h4, p, blockquote, li, pre, img"
        }

        for (el in root.select(selector)) {
            when (el.tagName()) {
                "div" -> {
                    // Только собственный текст div-а, без вложенных элементов, иначе будут дубли.
                    val text = clean(el.ownText())
                    if (text.length >= 80 && seen.add(text)) result += Block.Paragraph(text)
                }
                "h2", "h3", "h4" -> {
                    val text = clean(el.text())
                    if (text.isNotEmpty() && seen.add(text)) {
                        result += Block.Heading(el.tagName()[1].digitToInt(), text)
                    }
                }
                "p" -> {
                    val text = clean(el.text())
                    if (text.length >= 2 && seen.add(text)) result += Block.Paragraph(text)
                }
                "blockquote" -> {
                    val text = clean(el.text())
                    if (text.isNotEmpty() && seen.add(text)) result += Block.Quote(text)
                }
                "li" -> {
                    val text = clean(el.text())
                    if (text.isNotEmpty() && seen.add(text)) result += Block.ListItem(text)
                }
                "pre" -> {
                    val text = el.wholeText().trimEnd()
                    if (text.isNotBlank() && seen.add(text)) result += Block.Code(text)
                }
                "img" -> {
                    if (isDecorativeImage(el)) continue
                    val url = imageUrl(el)
                    if (url.isNotEmpty() && seen.add(url)) {
                        result += Block.Image(url, clean(el.attr("alt")))
                    }
                }
            }
        }
        return result
    }

    private fun isDecorativeImage(el: Element): Boolean {
        val w = el.attr("width").toIntOrNull()
        val h = el.attr("height").toIntOrNull()
        if ((w != null && w < 120) || (h != null && h < 120)) return true
        val tokens = (el.className() + " " + el.id() + " " + el.attr("src"))
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .toSet()
        return tokens.any { it in ICON_TOKENS }
    }

    private fun imageUrl(el: Element): String {
        for (attr in listOf("src", "data-src", "data-lazy-src", "data-original")) {
            val value = el.attr(attr).trim()
            if (value.isNotEmpty() && !value.startsWith("data:")) {
                return resolve(el.baseUri(), value)
            }
        }
        val firstFromSrcset = el.attr("srcset").split(",").firstOrNull()
            ?.trim()?.split(" ")?.firstOrNull().orEmpty()
        return if (firstFromSrcset.isNotEmpty()) resolve(el.baseUri(), firstFromSrcset) else ""
    }

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(URI(ref)).toString()
    } catch (e: Exception) {
        ""
    }

    private fun hostOf(url: String): String = try {
        URI(url).host?.removePrefix("www.").orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
