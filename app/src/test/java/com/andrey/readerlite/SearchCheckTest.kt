package com.andrey.readerlite

import org.jsoup.Jsoup
import org.junit.Test

/** Проверка поиска новостей по слову: Google Новости и Bing Новости. */
class SearchCheckTest {

    @Test
    fun search() {
        val q = "автомобиль"
        val urls = listOf(
            "google" to GoogleNews.searchUrl(q),
            "bing" to "https://www.bing.com/news/search?q=" +
                java.net.URLEncoder.encode(q, "UTF-8") + "&format=rss&setlang=ru&cc=ru",
        )
        urls.forEach { (name, url) ->
            val t = System.currentTimeMillis()
            val line = try {
                val items = FeedLoader.load(url)
                "FEED src $name OK ${System.currentTimeMillis() - t}ms items=${items.size} img=${items.count { it.imageUrl != null }} " +
                    "first=${items.firstOrNull()?.let { it.source + " | " + it.title + " | " + it.link.take(120) }}"
            } catch (e: Exception) {
                "FEED src $name FAIL ${System.currentTimeMillis() - t}ms ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
            println(line)
        }
        // Как выглядит элемент Bing целиком (для разбора картинок и ссылки).
        try {
            val body = Jsoup.connect(urls[1].second).ignoreContentType(true).timeout(15000).execute().body()
            val i = body.indexOf("<item>")
            println("FEED src bing raw: " + body.substring(maxOf(i, 0), minOf(body.length, maxOf(i, 0) + 900)).replace("<", "‹").replace(Regex("\\s+"), " "))
        } catch (e: Exception) {
            println("FEED src bing raw FAIL ${e.message}")
        }
    }
}
