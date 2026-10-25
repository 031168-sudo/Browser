package com.andrey.readerlite

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Действия со ссылками и картинками: поделиться, сохранить, скопировать, открыть. */
object ShareActions {

    /**
     * Системное меню «Поделиться» для ссылки. Браузеры добавлены в начало списка,
     * чтобы статью можно было сразу открыть в Chrome, Яндексе и т. п.
     */
    fun shareLink(context: Context, url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        val chooser = Intent.createChooser(send, "Поделиться ссылкой").apply {
            putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                arrayOf<Parcelable>(Intent(Intent.ACTION_VIEW, Uri.parse(url))),
            )
        }
        context.startActivity(chooser)
    }

    fun openInBrowser(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(context, "Не нашёл приложение, чтобы открыть ссылку")
        }
    }

    fun copyText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(context, "Адрес скопирован")
    }

    /** Сохраняет картинку в «Картинки/Читалка» через системный загрузчик, она появится в Галерее. */
    fun saveImage(context: Context, url: String) {
        try {
            val name = fileNameFor(url)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "Читалка/$name")
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toast(context, "Сохраняю в Галерею…")
        } catch (e: Exception) {
            toast(context, "Не удалось сохранить картинку")
        }
    }

    /** Скачивает картинку во временный файл и отдаёт её как файл в системное «Поделиться». */
    suspend fun shareImage(context: Context, url: String) {
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, fileNameFor(url))
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                target
            } catch (e: Exception) {
                null
            }
        }
        if (file == null) {
            toast(context, "Не удалось загрузить картинку")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Поделиться картинкой"))
    }

    private fun fileNameFor(url: String): String {
        val last = Uri.parse(url).lastPathSegment.orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(60)
        val hasExt = Regex("\\.(jpe?g|png|webp|gif|avif)$", RegexOption.IGNORE_CASE).containsMatchIn(last)
        val base = last.ifBlank { "image_${System.currentTimeMillis()}" }
        return if (hasExt) base else "$base.jpg"
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
