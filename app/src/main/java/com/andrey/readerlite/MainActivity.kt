package com.andrey.readerlite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Если открыли через «Поделиться» из браузера, берём ссылку из текста.
        val sharedUrl = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { text -> URL_REGEX.find(text)?.value }

        setContent {
            ReaderApp(initialUrl = sharedUrl)
        }
    }

    companion object {
        private val URL_REGEX = Regex("""https?://\S+""")
    }
}
