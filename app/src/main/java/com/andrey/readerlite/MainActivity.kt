package com.andrey.readerlite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    // Та же модель ленты, что и на экране (одна на всё окно приложения).
    private val feedVm: FeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Если открыли через «Поделиться» из браузера, берём ссылку из текста.
        val sharedUrl = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { text -> URL_REGEX.find(text)?.value }

        setContent {
            ReaderApp(initialUrl = sharedUrl, feedVm = feedVm)
        }
    }

    override fun onStop() {
        super.onStop()
        // Ушли из приложения — сохраняем ленту с картинками, чтобы после перезапуска
        // она открылась сразу и на том же месте.
        feedVm.persist()
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}
