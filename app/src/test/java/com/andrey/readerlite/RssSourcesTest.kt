package com.andrey.readerlite

import org.junit.Test

/** Проверка списка RSS-адресов изданий: сколько новостей отдаёт лента и есть ли картинки. */
class RssSourcesTest {

    private val feeds = listOf(
        "Лента.ру" to "https://lenta.ru/rss",
        "РИА Новости" to "https://ria.ru/export/rss2/archive/index.xml",
        "ТАСС" to "https://tass.ru/rss/v2.xml",
        "Интерфакс" to "https://www.interfax.ru/rss.asp",
        "РБК" to "https://rssexport.rbc.ru/rbcnews/news/30/full.rss",
        "Коммерсантъ" to "https://www.kommersant.ru/RSS/news.xml",
        "Ведомости" to "https://www.vedomosti.ru/rss/news",
        "Известия" to "https://iz.ru/xml/rss/all.xml",
        "Газета.ру" to "https://www.gazeta.ru/export/rss/lenta.xml",
        "Российская газета" to "https://rg.ru/xml/index.xml",
        "RT" to "https://russian.rt.com/rss",
        "Forbes" to "https://www.forbes.ru/newrss.xml",
        "Фонтанка" to "https://www.fontanka.ru/fontanka.rss",
        "Москва 24" to "https://www.m24.ru/rss.xml",
        "iXBT" to "https://www.ixbt.com/export/news.rss",
        "3DNews" to "https://3dnews.ru/news/rss/",
        "Хабр статьи" to "https://habr.com/ru/rss/articles/?fl=ru",
        "Хабр новости" to "https://habr.com/ru/rss/news/?fl=ru",
        "CNews" to "https://www.cnews.ru/inc/rss/news.xml",
        "Ferra" to "https://www.ferra.ru/exports/rss.xml",
        "Хайтек Mail" to "https://hi-tech.mail.ru/rss/all/",
        "N+1" to "https://nplus1.ru/rss",
        "Naked Science" to "https://naked-science.ru/allrss",
        "Autonews" to "https://www.autonews.ru/rss/",
        "Drom новости" to "https://news.drom.ru/export/xml/news.rss",
        "За рулём" to "https://www.zr.ru/rss/",
        "Чемпионат" to "https://www.championat.com/rss/news/",
        "Спорт-Экспресс" to "https://www.sport-express.ru/services/materials/news/se/",
    )

    @Test
    fun checkAll() {
        feeds.forEach { (name, url) ->
            val line = try {
                val items = FeedLoader.load(url)
                val withImages = items.count { it.imageUrl != null }
                "FEED src OK | $name | $url | новостей ${items.size} | с картинкой $withImages"
            } catch (e: Exception) {
                "FEED src FAIL | $name | $url | ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            }
            println(line)
        }
    }
}
