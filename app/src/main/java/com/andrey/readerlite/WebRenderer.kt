package com.andrey.readerlite

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Скрытый браузер для страниц, которые без JavaScript пустые (например, Forbes).
 * Открывает страницу, ждёт, пока скрипты дорисуют текст, и возвращает готовый HTML.
 * Пользователь эту страницу не видит: на экран попадает только извлечённая статья.
 */
object WebRenderer {

    /** Сколько символов текста сейчас в абзацах страницы. */
    private const val TEXT_LENGTH_JS =
        "(function(){var t=0;document.querySelectorAll('p').forEach(function(p){t+=(p.innerText||'').length});" +
            "return Math.max(t,(document.body&&document.body.innerText||'').length/3);})()"

    private const val HTML_JS = "document.documentElement.outerHTML"

    suspend fun render(context: Context, url: String): String? = withContext(Dispatchers.Main) {
        val webView = createWebView(context)
        try {
            // Ждём первой загрузки, но не дольше 20 секунд.
            var failed = false
            withTimeoutOrNull(20_000) { loadAndWait(webView, url) { failed = true } }
            // Страница не открылась (нет сети, сайт недоступен и т. п.) — у WebView
            // на экране своя страница ошибки, выдавать её за статью нельзя.
            if (failed) return@withContext null

            // Страница могла ещё догружать текст скриптами или пройти проверку на бота
            // и перейти дальше. Ждём, пока длина текста перестанет расти (до ~15 секунд).
            var last = -1
            for (i in 0 until 20) {
                val length = evaluate(webView, TEXT_LENGTH_JS).toDoubleOrNull()?.toInt() ?: 0
                if (length > 800 && length == last) break
                last = length
                delay(750)
            }

            if (failed) return@withContext null
            decodeJsString(evaluate(webView, HTML_JS))
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Картинки скрытому браузеру не нужны: адреса картинок всё равно останутся в HTML.
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        layout(0, 0, 1080, 2400)
    }

    private suspend fun loadAndWait(webView: WebView, url: String, onMainFrameError: () -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    // Ошибки картинок и счётчиков не важны, важна только сама страница.
                    if (request.isForMainFrame) {
                        onMainFrameError()
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            webView.loadUrl(url)
        }

    private suspend fun evaluate(webView: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
        }

    /** evaluateJavascript возвращает строку в виде JSON-литерала, раскодируем её. */
    private fun decodeJsString(raw: String): String? = try {
        JSONArray("[$raw]").optString(0).takeIf { it.isNotBlank() && it != "null" }
    } catch (e: Exception) {
        null
    }
}
