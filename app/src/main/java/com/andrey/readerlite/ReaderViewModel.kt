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

data class ReaderPrefs(
    val darkTheme: Boolean = true,
    val fontSize: Int = 18,
    val serif: Boolean = false,
)

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
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
        state = UiState.Loading
        viewModelScope.launch {
            state = try {
                val article = withContext(Dispatchers.IO) { ArticleExtractor.fetch(url) }
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

    fun removeSaved(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.byUrl(url) ?: return@launch
            dao.upsert(existing.copy(saved = false))
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { dao.clearHistory() }
    }

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        val next = transform(prefs)
        prefsStore.edit()
            .putBoolean(KEY_DARK, next.darkTheme)
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
        darkTheme = prefsStore.getBoolean(KEY_DARK, true),
        fontSize = prefsStore.getInt(KEY_FONT, 18),
        serif = prefsStore.getBoolean(KEY_SERIF, false),
    )

    override fun onCleared() {
        db.close()
        super.onCleared()
    }

    companion object {
        private const val KEY_DARK = "dark"
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
