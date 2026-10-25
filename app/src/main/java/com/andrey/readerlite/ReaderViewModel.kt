package com.andrey.readerlite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Тема оформления: как в системе, всегда тёмная или всегда светлая. */
enum class ThemeMode(val label: String) {
    SYSTEM("Как в системе"),
    DARK("Тёмная"),
    LIGHT("Светлая"),
}

data class ReaderPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: Int = 18,
    val serif: Boolean = false,
)

sealed interface UiState {
    data object Idle : UiState
    data class Loading(val note: String) : UiState
    data class Error(val message: String) : UiState
    data class Ready(val article: Article, val saved: Boolean) : UiState
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "reader.db").build()
    private val dao = db.articles()
    private val prefsStore = app.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    val history: StateFlow<List<ArticleEntity>> =
        dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val saved: StateFlow<List<ArticleEntity>> =
        dao.saved().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var state: UiState by mutableStateOf(UiState.Idle)
        private set

    var prefs: ReaderPrefs by mutableStateOf(loadPrefs())
        private set

    fun open(rawUrl: String) {
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            state = UiState.Error("Похоже, это не ссылка. Вставь адрес, который начинается с https://")
            return
        }
        state = UiState.Loading("Загружаю страницу…")
        viewModelScope.launch {
            state = try {
                // Быстрый путь: просто скачать HTML. Если текста нет (страница строится
                // скриптами или закрыта проверкой на бота), открываем её в скрытом браузере.
                val article = withContext(Dispatchers.IO) {
                    runCatching { ArticleExtractor.fetch(url) }.getOrNull()
                } ?: run {
                    state = UiState.Loading("Страница сложная, открываю через встроенный браузер…")
                    val html = WebRenderer.render(getApplication(), url)
                        ?: throw IllegalStateException("страница не загрузилась")
                    withContext(Dispatchers.Default) {
                        ArticleExtractor.extract(Jsoup.parse(html, url), url)
                    }
                }
                val saved = withContext(Dispatchers.IO) { store(article) }
                UiState.Ready(article, saved)
            } catch (e: Exception) {
                // Офлайн-фолбэк: если статья уже лежит в базе, показываем её.
                val cached = withContext(Dispatchers.IO) { dao.byUrl(url) }
                if (cached != null) {
                    UiState.Ready(cached.toArticle(), cached.saved)
                } else {
                    UiState.Error("Не удалось открыть страницу: ${e.message ?: "неизвестная ошибка"}")
                }
            }
        }
    }

    fun openCached(entity: ArticleEntity) {
        state = UiState.Ready(entity.toArticle(), entity.saved)
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(entity.copy(lastOpenedAt = System.currentTimeMillis()))
        }
    }

    fun toggleSaved() {
        val ready = state as? UiState.Ready ?: return
        val newSaved = !ready.saved
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existing = dao.byUrl(ready.article.url)
                if (existing != null) {
                    dao.upsert(
                        existing.copy(
                            saved = newSaved,
                            savedAt = if (newSaved) System.currentTimeMillis() else existing.savedAt,
                        )
                    )
                }
            }
            state = ready.copy(saved = newSaved)
        }
    }

    /** Сохранить или убрать статью из списка (история, избранное). */
    fun setSaved(url: String, saved: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existing = dao.byUrl(url) ?: return@withContext
                dao.upsert(
                    existing.copy(
                        saved = saved,
                        savedAt = if (saved) System.currentTimeMillis() else existing.savedAt,
                    )
                )
            }
            // Если эта же статья сейчас открыта, обновляем и её кнопку.
            val ready = state as? UiState.Ready
            if (ready != null && ready.article.url == url) state = ready.copy(saved = saved)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { dao.clearHistory() }
    }

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        val next = transform(prefs)
        prefsStore.edit()
            .putString(KEY_THEME, next.theme.name)
            .putInt(KEY_FONT, next.fontSize)
            .putBoolean(KEY_SERIF, next.serif)
            .apply()
        prefs = next
    }

    /** Сохраняет или обновляет статью в базе. Возвращает, сохранена ли она сейчас в «Сохранённые». */
    private suspend fun store(article: Article): Boolean {
        val existing = dao.byUrl(article.url)
        val saved = existing?.saved ?: false
        dao.upsert(
            ArticleEntity(
                url = article.url,
                title = article.title,
                siteName = article.siteName,
                blocksJson = BlockCodec.encode(article.blocks),
                savedAt = existing?.savedAt ?: 0L,
                lastOpenedAt = System.currentTimeMillis(),
                saved = saved,
            )
        )
        return saved
    }

    private fun loadPrefs(): ReaderPrefs = ReaderPrefs(
        theme = prefsStore.getString(KEY_THEME, null)
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            // Раньше был переключатель «Тёмная тема»: переносим его значение, если он менялся.
            ?: when {
                !prefsStore.contains(KEY_DARK) -> ThemeMode.SYSTEM
                prefsStore.getBoolean(KEY_DARK, true) -> ThemeMode.DARK
                else -> ThemeMode.LIGHT
            },
        fontSize = prefsStore.getInt(KEY_FONT, 18),
        serif = prefsStore.getBoolean(KEY_SERIF, false),
    )

    override fun onCleared() {
        db.close()
        super.onCleared()
    }

    companion object {
        private const val KEY_DARK = "dark"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font"
        private const val KEY_SERIF = "serif"

        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val host = withScheme.substringAfter("://").substringBefore("/")
            return if (host.contains(".")) withScheme else null
        }
    }
}

fun ArticleEntity.toArticle(): Article = Article(
    url = url,
    title = title,
    siteName = siteName,
    blocks = BlockCodec.decode(blocksJson),
)
