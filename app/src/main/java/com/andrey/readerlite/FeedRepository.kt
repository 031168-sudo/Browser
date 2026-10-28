package com.andrey.readerlite

import org.json.JSONArray
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Одна новость в ленте. */
data class FeedItem(
    val title: String,
    /** Ссылка из ленты. У Google Новостей это переадресация через news.google.com. */
    val link: String,
    /** Название ресурса: «Forbes.ru», «iXBT.com» и т. п. По нему работает исключение. */
    val source: String,
    val publishedAt: Long,
    /** Картинка, если лента сама её дала. Для Google Новостей подбирается отдельно. */
    val imageUrl: String?,
    val fromGoogle: Boolean,
)

/** Готовые темы Google Новостей. */
enum class NewsTopic(val label: String, val section: String?) {
    TOP("Главное", null),
    NATION("Россия", "NATION"),
    WORLD("В мире", "WORLD"),
    BUSINESS("Бизнес", "BUSINESS"),
    TECHNOLOGY("Технологии", "TECHNOLOGY"),
    SCIENCE("Наука", "SCIENCE"),
    HEALTH("Здоровье", "HEALTH"),
    SPORTS("Спорт", "SPORTS"),
    ENTERTAINMENT("Развлечения", "ENTERTAINMENT"),
}

/** Откуда лента берёт новости: тема Google, своя тема (поиск в Google Новостях) или свой сайт по RSS. */
sealed interface FeedSource {
    val key: String
    val label: String
    val url: String

    data class Topic(val topic: NewsTopic) : FeedSource {
        override val key = "t:" + topic.name
        override val label = topic.label
        override val url = GoogleNews.topicUrl(topic)
    }

    data class Query(val query: String) : FeedSource {
        override val key = "q:" + query
        override val label = query
        override val url = GoogleNews.searchUrl(query)
    }

    data class Rss(val feedUrl: String) : FeedSource {
        override val key = "r:" + feedUrl
        override val label = hostOf(feedUrl).ifBlank { feedUrl }
        override val url = feedUrl
    }
}

private const val UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

fun hostOf(url: String): String = try {
    URI(url).host?.removePrefix("www.").orEmpty()
} catch (e: Exception) {
    ""
}

object GoogleNews {
    private const val LOCALE = "hl=ru&gl=RU&ceid=RU:ru"

    fun topicUrl(topic: NewsTopic): String =
        if (topic.section == null) "https://news.google.com/rss?$LOCALE"
        else "https://news.google.com/rss/headlines/section/topic/${topic.section}?$LOCALE"

    fun searchUrl(query: String): String =
        "https://news.google.com/rss/search?q=${URLEncoder.encode(query, "UTF-8")}&$LOCALE"

    fun isGoogleLink(url: String): Boolean = hostOf(url) == "news.google.com"

    /**
     * Превращает ссылку-переадресацию Google Новостей в адрес самой статьи.
     * Старые ссылки содержат адрес прямо внутри себя, для новых Google просит
     * отдельный запрос. Если не получилось, возвращает null: тогда статья откроется
     * через переадресацию во встроенном браузере.
     */
    fun decode(link: String): String? {
        val id = try {
            URI(link).path.substringAfterLast('/')
        } catch (e: Exception) {
            return null
        }
        if (id.isBlank()) return null
        oldStyle(id)?.let { return it }
        return try {
            newStyle(id)
        } catch (e: Exception) {
            null
        }
    }

    private fun oldStyle(id: String): String? = try {
        val padded = id + "=".repeat((4 - id.length % 4) % 4)
        val text = String(Base64.getUrlDecoder().decode(padded), Charsets.ISO_8859_1)
        Regex("https?://[\\x21-\\x7e]+").find(text)?.value
            ?.takeIf { it.length > 12 && !it.contains("news.google.") }
    } catch (e: Exception) {
        null
    }

    private fun newStyle(id: String): String? {
        val page = Jsoup.connect("https://news.google.com/articles/$id")
            .userAgent(UA).timeout(15_000).get()
        val div = page.selectFirst("c-wiz > div[jscontroller]") ?: page.selectFirst("[data-n-a-sg]")
            ?: return null
        val signature = div.attr("data-n-a-sg")
        val timestamp = div.attr("data-n-a-ts")
        if (signature.isEmpty() || timestamp.isEmpty()) return null

        val inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null," +
            "null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"$id\",$timestamp,\"$signature\"]"
        val request = JSONArray().put(JSONArray().put(JSONArray().put("Fbv4je").put(inner))).toString()

        val body = Jsoup.connect("https://news.google.com/_/DotsSplashUi/data/batchexecute")
            .userAgent(UA)
            .timeout(15_000)
            .ignoreContentType(true)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .requestBody("f.req=" + URLEncoder.encode(request, "UTF-8"))
            .method(Connection.Method.POST)
            .execute()
            .body()

        val fromJson = try {
            val chunk = body.split("\n\n")[1]
            JSONArray(JSONArray(chunk).getJSONArray(0).getString(2)).getString(1)
        } catch (e: Exception) {
            null
        }
        return fromJson ?: Regex("""garturlres\\",\\"(https?://[^\\"]+)""").find(body)?.groupValues?.get(1)
    }
}

object FeedLoader {

    /** Загружает RSS или Atom и возвращает новости. */
    fun load(url: String): List<FeedItem> {
        val doc = Jsoup.connect(url)
            .userAgent(UA)
            .timeout(15_000)
            .ignoreContentType(true)
            .parser(Parser.xmlParser())
            .get()
        val channelTitle = doc.selectFirst("channel > title, feed > title")?.text()?.trim().orEmpty()
        val entries = doc.select("item").ifEmpty { doc.select("entry") }

        return entries.mapNotNull { e ->
            val rawTitle = e.selectFirst("title")?.text()?.trim().orEmpty()
            val link = e.select("link")
                .firstOrNull { it.attr("rel").let { r -> r.isEmpty() || r == "alternate" } }
                ?.let { l -> l.attr("href").ifBlank { l.text() } }
                ?.trim().orEmpty()
            if (rawTitle.isEmpty() || link.isEmpty()) return@mapNotNull null

            val sourceEl = e.selectFirst("source")
            val source = sourceEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: channelTitle.ifBlank { hostOf(link) }
            // Google дописывает ресурс в конец заголовка: «Заголовок - Forbes.ru».
            val title = rawTitle.removeSuffix(" - $source").trim()

            FeedItem(
                title = title,
                link = link,
                source = source,
                publishedAt = parseDate(e.selectFirst("pubDate, published, updated, dc|date")?.text()),
                imageUrl = imageOf(e),
                fromGoogle = GoogleNews.isGoogleLink(link),
            )
        }
    }

    /** Главная картинка статьи из её разметки для соцсетей (og:image). */
    fun ogImage(articleUrl: String): String? {
        val doc = Jsoup.connect(articleUrl).userAgent(UA).timeout(12_000).followRedirects(true).get()
        val meta = doc.selectFirst(
            "meta[property=og:image], meta[name=og:image], meta[name=twitter:image], " +
                "meta[property=twitter:image], link[rel=image_src]"
        ) ?: return null
        val value = meta.absUrl("content").ifBlank { meta.absUrl("href") }
        return value.takeIf { it.startsWith("http") }
    }

    private fun imageOf(e: Element): String? {
        e.selectFirst("enclosure[type^=image]")?.attr("url")?.takeIf { it.isNotBlank() }?.let { return it }
        e.selectFirst("media|content[url], media|thumbnail[url]")?.attr("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        e.selectFirst("enclosure[url~=(?i)\\.(jpe?g|png|webp)]")?.attr("url")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val html = e.selectFirst("description, content|encoded, content, summary")?.text() ?: return null
        return Jsoup.parse(html).selectFirst("img[src]")?.attr("src")?.takeIf { it.startsWith("http") }
    }

    private fun parseDate(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        return try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                0
            }
        }
    }
}
