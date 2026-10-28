package com.andrey.readerlite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val store = app.getSharedPreferences("feed_settings", Context.MODE_PRIVATE)

    /** Включённые темы Google (имена NewsTopic). */
    var topics: Set<String> by mutableStateOf(
        store.getStringSet(KEY_TOPICS, null)?.toSet() ?: DEFAULT_TOPICS
    )
        private set

    /** Свои темы: поиск в Google Новостях по словам. */
    var queries: Set<String> by mutableStateOf(store.getStringSet(KEY_QUERIES, null)?.toSet() ?: emptySet())
        private set

    /** Свои сайты по RSS. */
    var rssFeeds: Set<String> by mutableStateOf(store.getStringSet(KEY_RSS, null)?.toSet() ?: emptySet())
        private set

    /** Скрытые ресурсы, как они названы в ленте. */
    var blocked: Set<String> by mutableStateOf(store.getStringSet(KEY_BLOCKED, null)?.toSet() ?: emptySet())
        private set

    /** Выбранная вкладка-чип ленты. null — все темы вместе. */
    var selected: String? by mutableStateOf(null)
        private set

    var items: List<FeedItem> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Подобранные картинки: ссылка новости → адрес картинки. */
    val images = mutableStateMapOf<String, String>()
    private val imageRequested = ConcurrentHashMap.newKeySet<String>()
    private val realUrls = ConcurrentHashMap<String, String>()
    private val imageLimit = Semaphore(4)
    private var loadJob: Job? = null

    val sources: List<FeedSource>
        get() = NewsTopic.entries.filter { it.name in topics }.map { FeedSource.Topic(it) } +
            queries.sorted().map { FeedSource.Query(it) } +
            rssFeeds.sorted().map { FeedSource.Rss(it) }

    /** Лента без скрытых ресурсов. */
    val visibleItems: List<FeedItem>
        get() {
            val hidden = blocked.map { it.lowercase() }.toSet()
            return items.filter { it.source.lowercase() !in hidden }
        }

    init {
        refresh()
    }

    fun select(key: String?) {
        selected = key
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        val all = sources
        val list = selected?.let { key -> all.filter { it.key == key } }?.ifEmpty { null } ?: all
        if (list.isEmpty()) {
            items = emptyList()
            error = "Не выбрано ни одной темы. Включи темы в «Настройках»."
            return
        }
        loading = true
        error = null
        loadJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                list.map { src ->
                    async { runCatching { FeedLoader.load(src.url) }.getOrElse { emptyList() } }
                }.awaitAll()
            }
            val merged = results.flatten()
                .distinctBy { normalizeTitle(it.title) }
                .sortedByDescending { it.publishedAt }
                .take(MAX_ITEMS)
            merged.forEach { item -> item.imageUrl?.let { images[item.link] = it } }
            items = merged
            loading = false
            if (merged.isEmpty()) error = "Новости не загрузились. Проверь интернет и нажми «Обновить»."
        }
    }

    /** Подбирает картинку для новости, у которой её нет: адрес статьи → og:image. */
    fun ensureImage(item: FeedItem) {
        if (images.containsKey(item.link) || !imageRequested.add(item.link)) return
        viewModelScope.launch(Dispatchers.IO) {
            imageLimit.withPermit {
                val article = realUrl(item) ?: return@withPermit
                val image = runCatching { FeedLoader.ogImage(article) }.getOrNull() ?: return@withPermit
                withContext(Dispatchers.Main) { images[item.link] = image }
            }
        }
    }

    /** Настоящий адрес статьи (для Google расшифровывает переадресацию). */
    suspend fun realUrl(item: FeedItem): String? = withContext(Dispatchers.IO) {
        if (!item.fromGoogle) return@withContext item.link
        realUrls[item.link] ?: GoogleNews.decode(item.link)?.also { realUrls[item.link] = it }
    }

    fun toggleTopic(topic: NewsTopic) {
        topics = if (topic.name in topics) topics - topic.name else topics + topic.name
        save(KEY_TOPICS, topics)
        afterSourcesChanged()
    }

    fun addQuery(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        queries = queries + q
        save(KEY_QUERIES, queries)
        afterSourcesChanged()
    }

    fun removeQuery(q: String) {
        queries = queries - q
        save(KEY_QUERIES, queries)
        afterSourcesChanged()
    }

    fun addRss(raw: String) {
        val url = raw.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (hostOf(url).isEmpty()) return
        rssFeeds = rssFeeds + url
        save(KEY_RSS, rssFeeds)
        afterSourcesChanged()
    }

    fun removeRss(url: String) {
        rssFeeds = rssFeeds - url
        save(KEY_RSS, rssFeeds)
        afterSourcesChanged()
    }

    fun block(source: String) {
        blocked = blocked + source
        save(KEY_BLOCKED, blocked)
    }

    fun unblock(source: String) {
        blocked = blocked - source
        save(KEY_BLOCKED, blocked)
    }

    private fun afterSourcesChanged() {
        if (selected != null && sources.none { it.key == selected }) selected = null
        refresh()
    }

    private fun save(key: String, value: Set<String>) {
        store.edit().putStringSet(key, HashSet(value)).apply()
    }

    private fun normalizeTitle(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    companion object {
        private const val KEY_TOPICS = "topics"
        private const val KEY_QUERIES = "queries"
        private const val KEY_RSS = "rss"
        private const val KEY_BLOCKED = "blocked"
        private const val MAX_ITEMS = 150
        private val DEFAULT_TOPICS = setOf("TOP", "TECHNOLOGY", "SCIENCE")
    }
}
