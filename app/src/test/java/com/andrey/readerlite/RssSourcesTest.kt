package com.andrey.readerlite

import org.junit.Test

/** Проверка списка RSS-адресов изданий: сколько новостей отдаёт лента и есть ли картинки. */
class RssSourcesTest {

    private val feeds = listOf(
        "Газета.ру first" to "https://www.gazeta.ru/export/rss/first.xml",
        "Газета.ру lenta" to "https://www.gazeta.ru/export/rss/lenta.shtml",
        "Газета.ру news" to "https://www.gazeta.ru/export/rss/social_more.xml",
        "Фонтанка rss-feeds" to "https://www.fontanka.ru/rss-feeds/rss.xml",
        "Фонтанка 24hours" to "https://www.fontanka.ru/24hours.rss",
        "Autonews (РБК)" to "https://rssexport.rbc.ru/autonews/news/30/full.rss",
        "Autonews export" to "https://www.autonews.ru/export/rss/",
        "Drom xml" to "https://www.drom.ru/export/xml/news.rss",
        "Drom news" to "https://news.drom.ru/export/rss/",
        "За рулём rss.xml" to "https://www.zr.ru/rss.xml",
        "За рулём news" to "https://www.zr.ru/rss/news/",
        "Motor.ru" to "https://motor.ru/exports/rss",
        "Колёса.ру" to "https://www.kolesa.ru/rss",
        "Авто Mail" to "https://auto.mail.ru/rss/",
        "Forbes rss" to "https://www.forbes.ru/rss",
        "Forbes newrss" to "https://www.forbes.ru/newrss.xml",
        "Naked Science feed" to "https://naked-science.ru/feed",
        "Naked Science allrss" to "https://naked-science.ru/allrss",
    )

    @Test
    fun checkAll() {
        feeds.forEach { (name, url) ->
            val line = try {
                val items = FeedLoader.load(url)
                val withImages = items.count { it.imageUrl != null }
                ("FEED src OK | $name | $url | новостей ${items.size} | с картинкой $withImages")
                    .let { if (items.isEmpty()) it + " | начало ответа: " + head(url) else it }
            } catch (e: Exception) {
                "FEED src FAIL | $name | $url | ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            }
            println(line)
        }
    }

    private fun head(url: String): String = try {
        org.jsoup.Jsoup.connect(url).ignoreContentType(true).timeout(15000).execute().body()
            .take(200).replace(Regex("\\s+"), " ").replace("<", "‹")
    } catch (e: Exception) {
        e.message.orEmpty()
    }
}
