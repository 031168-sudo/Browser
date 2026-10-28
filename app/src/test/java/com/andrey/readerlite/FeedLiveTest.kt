package com.andrey.readerlite

import org.junit.Assert.assertTrue
import org.junit.Test

/** Живая проверка ленты: загрузка тем Google, расшифровка ссылок, подбор картинок. */
class FeedLiveTest {

    @Test
    fun googleTopicsLoadDecodeAndFindImages() {
        val items = FeedLoader.load(GoogleNews.topicUrl(NewsTopic.TECHNOLOGY))
        println("FEED items=${items.size}")
        items.take(3).forEach { println("FEED item: ${it.source} | ${it.title} | img=${it.imageUrl}") }
        assertTrue("лента пустая", items.isNotEmpty())

        var decoded = 0
        var images = 0
        items.take(5).forEach { item ->
            val real = GoogleNews.decode(item.link)
            println("FEED decode: ${item.link.take(60)}… -> $real")
            if (real != null) {
                decoded++
                val img = runCatching { FeedLoader.ogImage(real) }.getOrNull()
                println("FEED image: $img")
                if (img != null) images++
            }
        }
        println("FEED decoded=$decoded/5 images=$images/5")
        assertTrue("не расшифровалась ни одна ссылка", decoded > 0)
    }

    @Test
    fun searchAndRssLoad() {
        val search = FeedLoader.load(GoogleNews.searchUrl("электромобили"))
        println("FEED search items=${search.size}")
        val rss = FeedLoader.load("https://lenta.ru/rss")
        println("FEED rss items=${rss.size}, with images=${rss.count { it.imageUrl != null }}")
        rss.take(2).forEach { println("FEED rss: ${it.source} | ${it.title} | img=${it.imageUrl}") }
        assertTrue(search.isNotEmpty() && rss.isNotEmpty())
    }
}
