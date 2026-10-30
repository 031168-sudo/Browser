package com.andrey.readerlite

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Вкладка «Лента»: темы-чипы сверху, ниже карточки «картинка + заголовок». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    feed: FeedViewModel,
    onOpenInterests: () -> Unit,
    savedUrls: Set<String>,
    onOpen: (FeedItem) -> Unit,
    onToggleSave: (item: FeedItem, isSaved: Boolean) -> Unit,
) {
    val visible = feed.visibleItems
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = feed.selected == null,
                        onClick = { feed.select(null) },
                        label = { Text("Всё") },
                    )
                }
                if (feed.hasInterests) {
                    item {
                        FilterChip(
                            selected = feed.selected == FeedViewModel.FOR_YOU,
                            onClick = { feed.select(FeedViewModel.FOR_YOU) },
                            label = { Text("Для тебя") },
                        )
                    }
                }
                items(feed.sources, key = { it.key }) { src ->
                    FilterChip(
                        selected = feed.selected == src.key,
                        onClick = { feed.select(src.key) },
                        label = { Text(src.label) },
                    )
                }
            }
            IconButton(onClick = { feed.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }

        if (feed.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        if (feed.selected == FeedViewModel.FOR_YOU) {
            TextButton(onClick = onOpenInterests, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Подобрано по твоим интересам · настроить ›")
            }
        }

        feed.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }

        // Запоминаем место в ленте, пока листаешь (с небольшой задержкой, чтобы не писать на каждый пиксель).
        LaunchedEffect(feed.listState) {
            snapshotFlow {
                feed.listState.firstVisibleItemIndex to feed.listState.firstVisibleItemScrollOffset
            }.collectLatest { (index, offset) ->
                delay(400)
                feed.savePosition(index, offset)
            }
        }

        LazyColumn(
            state = feed.listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(visible, key = { it.link }) { item ->
                val isSaved = feed.savedLinks[item.link]?.let { it in savedUrls } == true
                FeedCard(
                    item = item,
                    image = feed.images[item.link],
                    liked = item.link in feed.liked,
                    saved = isSaved,
                    onNeedImage = { feed.ensureImage(item) },
                    onOpen = { onOpen(item) },
                    onBlock = { feed.block(item.source) },
                    onLike = { feed.toggleLike(item) },
                    onSave = { onToggleSave(item, isSaved) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedCard(
    item: FeedItem,
    image: String?,
    liked: Boolean,
    saved: Boolean,
    onNeedImage: () -> Unit,
    onOpen: () -> Unit,
    onBlock: () -> Unit,
    onLike: () -> List<String>,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(item.link) { if (image == null) onNeedImage() }

    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(vertical = 12.dp)
        ) {
            if (!image.isNullOrBlank()) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(item.source, timeAgo(item.publishedAt)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val words = onLike()
                    if (words.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            "Буду чаще показывать: " + words.take(4).joinToString(", "),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Icon(
                        if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Интересно",
                        tint = if (liked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { ShareActions.shareLink(context, item.link, item.title) }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Поделиться",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSave) {
                    Icon(
                        if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (saved) "Убрать из избранного" else "В избранное",
                        tint = if (saved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Не показывать «${item.source}»") },
                onClick = { menu = false; onBlock() },
            )
            DropdownMenuItem(
                text = { Text("Поделиться ссылкой") },
                onClick = { menu = false; ShareActions.shareLink(context, item.link, item.title) },
            )
            DropdownMenuItem(
                text = { Text("Открыть в браузере") },
                onClick = { menu = false; ShareActions.openInBrowser(context, item.link) },
            )
        }
    }
    HorizontalDivider()
}

private fun timeAgo(millis: Long): String {
    if (millis <= 0) return ""
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        minutes < 2 * 24 * 60 -> "вчера"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(millis))
    }
}

/** Раздел настроек ленты: темы, свои темы, свои сайты, скрытые ресурсы. */
@Composable
fun FeedSettingsSection(feed: FeedViewModel, onOpenInterests: () -> Unit) {
    SectionTitle("Откуда брать новости")
    listOf(true to "Bing — работает без VPN", false to "Google — нужен VPN").forEach { (bing, label) ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { feed.setEngine(bing) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(selected = feed.useBing == bing, onClick = { feed.setEngine(bing) })
            Text(label)
        }
    }
    Hint("Издания из списка ниже от этого не зависят.")

    Spacer(Modifier.height(12.dp))
    SectionTitle("Темы ленты")
    NewsTopic.entries.forEach { topic ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { feed.toggleTopic(topic) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = topic.name in feed.topics, onCheckedChange = { feed.toggleTopic(topic) })
            Text(topic.label)
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle("Свои темы")
    Hint("Новости по словам, например «электромобили» или «Подмосковье».")
    feed.queries.sorted().forEach { q -> RemovableRow(q) { feed.removeQuery(q) } }
    AddRow(placeholder = "Новая тема", onAdd = feed::addQuery)

    Spacer(Modifier.height(12.dp))
    // Интересы — в отдельном окне, чтобы не раздувать настройки.
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenInterests)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Мои интересы", style = MaterialTheme.typography.titleSmall)
            Text(
                if (feed.hasInterests) "Слов и тем: ${feed.engine.terms.size}. Посмотреть и поправить веса"
                else "Пока пусто — заполнится, когда будешь читать",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.headlineSmall)
    }
    HorizontalDivider()

    Spacer(Modifier.height(12.dp))
    SectionTitle("Издания")
    Hint("Отметь сайты — их новости появятся в ленте, у каждого своя вкладка сверху.")
    RssCatalog.groups.forEach { (group, presets) ->
        Text(
            group,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        presets.forEach { preset ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { feed.toggleRss(preset.url) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = preset.url in feed.rssFeeds, onCheckedChange = { feed.toggleRss(preset.url) })
                Text(preset.name)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SectionTitle("Свои сайты (RSS)")
    Hint("Если нужного сайта нет в списке — впиши адрес его RSS-ленты.")
    feed.rssFeeds.filterNot { RssCatalog.isPreset(it) }.sorted()
        .forEach { url -> RemovableRow(url) { feed.removeRss(url) } }
    AddRow(placeholder = "Адрес ленты", onAdd = feed::addRss)

    Spacer(Modifier.height(12.dp))
    SectionTitle("Скрытые ресурсы")
    if (feed.blocked.isEmpty()) {
        Hint("Пока нет. Чтобы скрыть ресурс, подержи палец на новости в ленте.")
    } else {
        feed.blocked.sorted().forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s, modifier = Modifier.weight(1f))
                TextButton(onClick = { feed.unblock(s) }) { Text("Вернуть") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) { Icon(Icons.Default.Clear, contentDescription = "Убрать") }
    }
}

@Composable
private fun AddRow(placeholder: String, onAdd: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            placeholder = { Text(placeholder) },
            shape = RoundedCornerShape(50),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = {
            onAdd(value)
            value = ""
        }) { Icon(Icons.Default.Add, contentDescription = "Добавить") }
    }
}
