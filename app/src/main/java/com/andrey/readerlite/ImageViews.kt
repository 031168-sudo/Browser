package com.andrey.readerlite

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Картинка в статье: на всю ширину. Нажатие — открыть на весь экран,
 * долгое нажатие — меню как в браузере.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleImage(url: String, alt: String) {
    var fullScreen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Box {
        AsyncImage(
            model = url,
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { fullScreen = true },
                    onLongClick = { menu = true },
                ),
        )
        ImageMenu(url = url, expanded = menu, onDismiss = { menu = false })
    }

    if (fullScreen) {
        FullScreenImage(url = url, alt = alt, onClose = { fullScreen = false })
    }
}

/** Меню картинки: сохранить, поделиться, скопировать адрес, открыть в браузере. */
@Composable
private fun ImageMenu(url: String, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Сохранить картинку") },
            onClick = { onDismiss(); ShareActions.saveImage(context, url) },
        )
        DropdownMenuItem(
            text = { Text("Поделиться картинкой") },
            onClick = { onDismiss(); scope.launch { ShareActions.shareImage(context, url) } },
        )
        DropdownMenuItem(
            text = { Text("Копировать адрес картинки") },
            onClick = { onDismiss(); ShareActions.copyText(context, "Адрес картинки", url) },
        )
        DropdownMenuItem(
            text = { Text("Открыть в браузере") },
            onClick = { onDismiss(); ShareActions.openInBrowser(context, url) },
        )
    }
}

/**
 * Картинка на весь экран на чёрном фоне. Масштаб двумя пальцами,
 * двойное нажатие — увеличить или вернуть, долгое нажатие — то же меню.
 */
@Composable
private fun FullScreenImage(url: String, alt: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var menu by remember { mutableStateOf(false) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AsyncImage(
                model = url,
                contentDescription = alt.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onLongPress = { menu = true },
                        )
                    }
                    .transformable(transform)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
            }
            Box(Modifier.align(Alignment.Center)) {
                ImageMenu(url = url, expanded = menu, onDismiss = { menu = false })
            }
        }
    }
}
