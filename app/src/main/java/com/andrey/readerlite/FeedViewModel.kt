package com.andrey.readerlite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    /** Профиль интересов — общий для ленты, читалки и избранного. */
    val engine = InterestEngine.get(app)

    /** Есть ли что-то в профиле интересов (тогда показываем вкладку «Для тебя»). */
    val hasInterests: Boolean get() = engine.terms.isNotEmpty() || engine.categories.isNotEmpty()

    /** Отмеченные «Интересно» (ссылки новостей и адреса статей). */
    val liked: Set<String> get() = engine.liked

    /** Новости, сохранённые в избранное из ленты: ссылка в ленте → адрес статьи. */
    var savedLinks: Map<String, String> by mutableStateOf(loadSavedLinks())
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

    /** Прокрутка ленты живёт здесь, а не в экране: так она не сбрасывается при смене вкладки. */
    val listState: LazyListState

    init {
        // Сначала показываем ленту, сохранённую в прошлый раз, и возвращаемся
        // к той новости, на которой остановился. Потом тихо подгружаем свежие:
        // список привязан к новостям, а не к номерам строк, поэтому место не сдвинется.
        val cached = loadCache()
        items = cached
        cached.forEach { item -> item.imageUrl?.let { images[item.link] = it } }
        selected = store.getString(KEY_SELECTED, null)
        val anchor = store.getString(KEY_ANCHOR, null)
        val index = visibleItems.indexOfFirst { it.link == anchor }
        listState = if (index >= 0) {
            LazyListState(index, store.getInt(KEY_OFFSET, 0))
        } else {
            LazyListState()
        }
        refresh(toTop = false)
    }

    /** «Интересно» на карточке ленты: статья ещё не прочитана, судим по заголовку. */
    fun toggleLike(item: FeedItem): List<String> =
        engine.toggleLike(item.link, item.title, "", item.source, item.topic)

    fun score(item: FeedItem): Double = engine.score(item.title, item.source, item.topic)

    private fun topWords(n: Int): List<String> = engine.topSearchLabels(n)

    fun rememberSaved(link: String, articleUrl: String) {
        savedLinks = savedLinks + (link to articleUrl)
        store.edit().putString(KEY_SAVED_LINKS, JSONObject(savedLinks).toString()).apply()
    }

    fun select(key: String?) {
        selected = key
        store.edit().putString(KEY_SELECTED, key).apply()
        refresh()
    }

    /** Запоминает новость, которая сейчас вверху экрана, и сдвиг внутри неё. */
    fun savePosition(index: Int, offset: Int) {
        val link = visibleItems.getOrNull(index)?.link ?: return
        store.edit().putString(KEY_ANCHOR, link).putInt(KEY_OFFSET, offset).apply()
    }

    /** Сохраняет ленту вместе с найденными картинками, чтобы после перезапуска показать её сразу. */
    fun persist() {
        val snapshot = items.map { it.copy(imageUrl = images[it.link] ?: it.imageUrl) }
        viewModelScope.launch(Dispatchers.IO) { saveCache(snapshot) }
    }

    /** toTop = true: после обновления показать ленту с начала (кнопка «Обновить», смена темы). */
    fun refresh(toTop: Boolean = true) {
        loadJob?.cancel()
        val all = sources
        val forYou = selected == FOR_YOU && hasInterests
        val list = when {
            forYou -> all + topWords(3).map { FeedSource.Query(it) }
            else -> selected?.let { key -> all.filter { it.key == key } }?.ifEmpty { null } ?: all
        }
        if (list.isEmpty()) {
            items = emptyList()
            error = "Не выбрано ни одной темы. Включи темы в «Настройках»."
            return
        }
        loading = true
        error = null
        loadJob = viewModelScope.launch {
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
            val results = withContext(Dispatchers.IO) {
                list.flatMap { src -> src.urls.map { url -> src to url } }.map { (src, url) ->
                    async {
                        val loaded = try {
                            FeedLoader.load(url)
                        } catch (e: Exception) {
                            val where = when {
                                GoogleNews.isGoogleLink(url) -> "Google"
                                BingNews.isBing(url) -> "Bing"
                                else -> src.label
                            }
                            failures += "«${src.label}» ($where): ${describeError(e)}"
                            emptyList()
                        }
                        // Новости изданий из готового списка подписываем его названием,
                        // чтобы скрытие ресурса работало одинаково.
                        val presetName = (src as? FeedSource.Rss)?.let { RssCatalog.nameFor(it.feedUrl) }
                        // Запоминаем рубрику: по ней тоже учитываются интересы.
                        val topic = when (src) {
                            is FeedSource.Topic -> if (src.topic == NewsTopic.TOP) "" else src.label
                            is FeedSource.Rss -> RssCatalog.categoryFor(src.feedUrl)
                            is FeedSource.Query -> ""
                        }
                        loaded.map { it.copy(source = presetName ?: it.source, topic = topic) }
                    }
                }.awaitAll()
            }
            val unique = results.flatten().distinctBy { normalizeTitle(it.title) }
            engine.observeTitles(unique.map { it.title })
            val merged = if (forYou) {
                // «Для тебя»: только похожие на отмеченные, самые похожие сверху.
                unique.map { it to score(it) }
                    .filter { it.second > 0 }
                    .sortedWith(compareByDescending<Pair<FeedItem, Double>> { it.second }
                        .thenByDescending { it.first.publishedAt })
                    .map { it.first }
                    .take(MAX_ITEMS)
            } else {
                unique.sortedByDescending { it.publishedAt }.take(MAX_ITEMS)
            }
            merged.forEach { item -> item.imageUrl?.let { images[item.link] = it } }
            items = merged
            loading = false
            if (toTop && merged.isNotEmpty()) listState.scrollToItem(0)
            withContext(Dispatchers.IO) { saveCache(merged) }
            if (merged.isEmpty()) {
                error = if (failures.isEmpty()) {
                    "Ничего не нашлось. Попробуй другое слово или нажми «Обновить»."
                } else {
                    "Не загрузилось:\n" + failures.joinToString("\n")
                }
            }
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

    /** Включить или выключить издание из готового списка. */
    fun toggleRss(url: String) {
        rssFeeds = if (url in rssFeeds) rssFeeds - url else rssFeeds + url
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
        if (selected != null && selected != FOR_YOU && sources.none { it.key == selected }) selected = null
        refresh()
    }

    private fun save(key: String, value: Set<String>) {
        store.edit().putStringSet(key, HashSet(value)).apply()
    }

    private fun loadSavedLinks(): Map<String, String> = try {
        val o = JSONObject(store.getString(KEY_SAVED_LINKS, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun cacheFile() = File(getApplication<Application>().filesDir, "feed_cache.json")

    private fun saveCache(list: List<FeedItem>) {
        try {
            val array = JSONArray()
            list.forEach { item ->
                array.put(
                    JSONObject()
                        .put("title", item.title)
                        .put("link", item.link)
                        .put("source", item.source)
                        .put("time", item.publishedAt)
                        .put("image", item.imageUrl ?: images[item.link] ?: "")
                        .put("google", item.fromGoogle)
                        .put("topic", item.topic)
                )
            }
            cacheFile().writeText(array.toString())
        } catch (e: Exception) {
            // Не получилось сохранить — не страшно, лента просто загрузится заново.
        }
    }

    private fun loadCache(): List<FeedItem> = try {
        val array = JSONArray(cacheFile().readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            FeedItem(
                title = o.getString("title"),
                link = o.getString("link"),
                source = o.getString("source"),
                publishedAt = o.getLong("time"),
                imageUrl = o.optString("image").ifBlank { null },
                fromGoogle = o.optBoolean("google"),
                topic = o.optString("topic"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun normalizeTitle(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    companion object {
        private const val KEY_TOPICS = "topics"
        private const val KEY_QUERIES = "queries"
        private const val KEY_RSS = "rss"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_SELECTED = "selected"
        private const val KEY_SAVED_LINKS = "saved_links"
        const val FOR_YOU = "foryou"
        private const val KEY_ANCHOR = "anchor"
        private const val KEY_OFFSET = "offset"
        private const val MAX_ITEMS = 150
        private val DEFAULT_TOPICS = setOf("TOP", "TECHNOLOGY", "SCIENCE")
    }
}
