package com.andrey.readerlite

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.RadioButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextAlign

private enum class Tab(val label: String) {
    READ("Читать"),
    HISTORY("История"),
    SAVED("Избранное"),
    SETTINGS("Настройки"),
}

@Composable
fun ReaderApp(initialUrl: String?, vm: ReaderViewModel = viewModel()) {
    val prefs = vm.prefs
    var tab by rememberSaveable { mutableStateOf(Tab.READ) }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            tab = Tab.READ
            vm.open(initialUrl)
        }
    }

    val dark = when (prefs.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    ReaderTheme(dark = dark) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                Icon(
                                    imageVector = when (item) {
                                        Tab.READ -> Icons.Default.Search
                                        Tab.HISTORY -> Icons.Default.DateRange
                                        Tab.SAVED -> Icons.Default.Favorite
                                        Tab.SETTINGS -> Icons.Default.Settings
                                    },
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.READ -> ReadScreen(vm)
                    Tab.HISTORY -> ListScreen(
                        emptyText = "История пуста",
                        entries = vm.history.collectAsState().value,
                        onToggleSaved = { vm.setSaved(it.url, !it.saved) },
                        onOpen = { entity ->
                            vm.openCached(entity)
                            tab = Tab.READ
                        },
                        headerAction = { ClearHistoryButton(onClear = vm::clearHistory) },
                    )
                    Tab.SAVED -> ListScreen(
                        emptyText = "Пока пусто. Нажми сердечко в статье или в истории.",
                        entries = vm.saved.collectAsState().value,
                        onToggleSaved = { vm.setSaved(it.url, false) },
                        onOpen = { entity ->
                            vm.openCached(entity)
                            tab = Tab.READ
                        },
                        headerAction = null,
                    )
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun ReadScreen(vm: ReaderViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    val state = vm.state
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = {
        if (input.isNotBlank()) {
            keyboard?.hide()
            focus.clearFocus()
            vm.open(input)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Строка ввода-«пилюля»: слева лупа, справа крестик и круглая кнопка со стрелкой.
        TextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(50),
            placeholder = { Text("Вставь ссылку на статью") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                    FilledIconButton(
                        onClick = submit,
                        enabled = input.isNotBlank(),
                        modifier = Modifier.padding(end = 6.dp).size(42.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Открыть")
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            UiState.Idle -> Text(
                "Вставь ссылку или отправь страницу в приложение через «Поделиться».",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UiState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text(s.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is UiState.Ready -> ArticleView(s.article, s.saved, vm)
        }
    }
}

@Composable
private fun ArticleView(article: Article, saved: Boolean, vm: ReaderViewModel) {
    val prefs = vm.prefs
    val context = androidx.compose.ui.platform.LocalContext.current
    val family = if (prefs.serif) FontFamily.Serif else FontFamily.Default

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                article.title,
                fontSize = (prefs.fontSize + 8).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = family,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                article.siteName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::toggleSaved) {
                    Icon(
                        if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (saved) "Сохранено" else "Сохранить")
                }
                OutlinedButton(onClick = {
                    val text = buildString {
                        append(article.title).append("\n\n")
                        article.blocks.forEach { b -> appendPlain(b) }
                        append("\n").append(article.url)
                    }
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, article.title)
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Поделиться текстом",
                        )
                    )
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Текст")
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
        }

        items(article.blocks) { block ->
            BlockView(block, prefs.fontSize, family)
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
    }
}

@Composable
private fun BlockView(block: Block, baseSize: Int, family: FontFamily) {
    when (block) {
        is Block.Heading -> Text(
            block.text,
            fontSize = (baseSize + (4 - block.level) * 3).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = family,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )
        is Block.Paragraph -> Text(
            block.text,
            fontSize = baseSize.sp,
            lineHeight = (baseSize * 1.55f).sp,
            fontFamily = family,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        is Block.Quote -> Text(
            "«${block.text}»",
            fontSize = baseSize.sp,
            fontStyle = FontStyle.Italic,
            fontFamily = family,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
        )
        is Block.ListItem -> Text(
            "•  ${block.text}",
            fontSize = baseSize.sp,
            lineHeight = (baseSize * 1.5f).sp,
            fontFamily = family,
            modifier = Modifier.padding(vertical = 3.dp, horizontal = 8.dp),
        )
        is Block.Code -> Text(
            block.text,
            fontSize = (baseSize - 2).sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        is Block.Image -> Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            ArticleImage(url = block.url, alt = block.alt)
            if (block.alt.isNotBlank()) {
                Text(
                    block.alt,
                    fontSize = (baseSize - 4).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ListScreen(
    emptyText: String,
    entries: List<ArticleEntity>,
    onToggleSaved: (ArticleEntity) -> Unit,
    onOpen: (ArticleEntity) -> Unit,
    headerAction: (@Composable () -> Unit)?,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (headerAction != null) {
            headerAction()
            Spacer(Modifier.height(8.dp))
        }
        if (entries.isEmpty()) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn {
            items(entries, key = { it.url }) { entity ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entity) }
                        .padding(vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entity.title, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                            Text(
                                entity.siteName.ifBlank { entity.url },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = {
                                ShareActions.shareLink(context, entity.url, entity.title)
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Поделиться ссылкой",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onToggleSaved(entity) }) {
                                Icon(
                                    if (entity.saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (entity.saved) "Убрать из сохранённых" else "Сохранить",
                                    tint = if (entity.saved) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ClearHistoryButton(onClear: () -> Unit) {
    OutlinedButton(onClick = onClear) { Text("Очистить историю") }
}

@Composable
private fun SettingsScreen(vm: ReaderViewModel) {
    val prefs = vm.prefs
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Тема", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.updatePrefs { it.copy(theme = mode) } }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = prefs.theme == mode,
                    onClick = { vm.updatePrefs { it.copy(theme = mode) } },
                )
                Spacer(Modifier.width(4.dp))
                Text(mode.label)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Шрифт с засечками")
            Switch(
                checked = prefs.serif,
                onCheckedChange = { v -> vm.updatePrefs { it.copy(serif = v) } },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Размер текста: ${prefs.fontSize}")
        Slider(
            value = prefs.fontSize.toFloat(),
            onValueChange = { v -> vm.updatePrefs { it.copy(fontSize = v.toInt()) } },
            valueRange = 14f..28f,
            steps = 13,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "Всё хранится только на этом телефоне. Облака и синхронизации нет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReaderTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(
            background = Color(0xFF121212),
            surface = Color(0xFF121212),
            onBackground = Color(0xFFE6E6E6),
            onSurface = Color(0xFFE6E6E6),
        )
    } else {
        lightColorScheme()
    }
    // Верхняя и нижняя системные полосы под цвет темы: на светлом фоне значки
    // (часы, батарея, кнопки навигации) должны быть тёмными, иначе их не видно.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = scheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = scheme.surfaceContainer.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Текст блока для отправки через «Поделиться». */
private fun StringBuilder.appendPlain(block: Block) {
    when (block) {
        is Block.Heading -> append("\n").append(block.text).append("\n")
        is Block.Paragraph -> append(block.text).append("\n\n")
        is Block.Quote -> append("«").append(block.text).append("»\n\n")
        is Block.ListItem -> append("• ").append(block.text).append("\n")
        is Block.Code -> append(block.text).append("\n\n")
        is Block.Image -> Unit // картинки в текстовый экспорт не попадают
    }
}
