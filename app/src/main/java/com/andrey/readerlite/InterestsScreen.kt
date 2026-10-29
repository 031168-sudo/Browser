package com.andrey.readerlite

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Окно «Мои интересы»: что приложение о тебе запомнило и сколько это весит.
 * Всё можно поправить: веса слов, рубрик и изданий, свои интересы, цену каждого действия.
 */
@Composable
fun InterestsScreen(engine: InterestEngine, onBack: () -> Unit) {
    val now = System.currentTimeMillis()
    val terms = engine.terms.entries
        .map { Triple(it.key, it.value, engine.weightOf(it.value, now)) }
        .sortedByDescending { it.third }
    val maxTerm = terms.firstOrNull()?.third ?: 1.0
    var showAll by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var newInterest by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
            Text("Мои интересы", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text(
                    "Приложение учится на том, что ты читаешь, сохраняешь и отмечаешь «Интересно». " +
                        "Из статьи берутся слова, которые проходят через весь текст и встречаются в других " +
                        "новостях, — это тема. Фамилии и разовые слова получают маленький вес. " +
                        "Интересы, которые давно не подкреплялись, постепенно затухают " +
                        "(вдвое за ${InterestEngine.HALF_LIFE_DAYS.roundToInt()} дней). Свои — не затухают.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ---- Свой интерес ----
            item {
                Section("Добавить интерес")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newInterest,
                        onValueChange = { newInterest = it },
                        singleLine = true,
                        placeholder = { Text("Например: квантовый компьютер") },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (engine.addManual(newInterest)) newInterest = ""
                    }) { Icon(Icons.Default.Add, contentDescription = "Добавить") }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---- Слова и темы ----
            item {
                Section("Слова и темы")
                if (terms.isEmpty()) {
                    Hint("Пока пусто. Почитай новости, отметь «Интересно» или добавь интерес сам.")
                }
            }
            items(if (showAll) terms else terms.take(30), key = { "t:" + it.first }) { (key, t, w) ->
                WeightRow(
                    label = t.label,
                    note = if (t.manual) "свой" else null,
                    weight = w,
                    max = maxTerm,
                    onMinus = { engine.setTermWeight(key, w - 1) },
                    onPlus = { engine.setTermWeight(key, w + 1) },
                    onRemove = { engine.removeTerm(key) },
                )
            }
            if (terms.size > 30 && !showAll) {
                item { TextButton(onClick = { showAll = true }) { Text("Показать все (${terms.size})") } }
            }

            // ---- Рубрики ----
            if (engine.categories.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Section("Рубрики")
                }
                val cats = engine.categories.entries.sortedByDescending { it.value }
                val maxCat = cats.first().value
                items(cats, key = { "c:" + it.key }) { (name, w) ->
                    WeightRow(name, null, w, maxCat,
                        onMinus = { engine.setCategoryWeight(name, w - 1) },
                        onPlus = { engine.setCategoryWeight(name, w + 1) },
                        onRemove = { engine.setCategoryWeight(name, 0.0) })
                }
            }

            // ---- Издания ----
            if (engine.sources.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Section("Издания")
                }
                val srcs = engine.sources.entries.sortedByDescending { it.value }
                val maxSrc = srcs.first().value
                items(srcs, key = { "s:" + it.key }) { (name, w) ->
                    WeightRow(name, null, w, maxSrc,
                        onMinus = { engine.setSourceWeight(name, w - 1) },
                        onPlus = { engine.setSourceWeight(name, w + 1) },
                        onRemove = { engine.setSourceWeight(name, 0.0) })
                }
            }

            // ---- Цена действий ----
            item {
                Spacer(Modifier.height(20.dp))
                Section("Сколько весит действие")
                Hint("Чем больше число, тем сильнее действие меняет интересы.")
                val c = engine.coef
                CoefSlider("Прочитал новость из ленты", c.read) { engine.updateCoef(c.copy(read = it)) }
                CoefSlider("Открыл статью из другого приложения и прочитал", c.external) { engine.updateCoef(c.copy(external = it)) }
                CoefSlider("Добавил в избранное", c.save) { engine.updateCoef(c.copy(save = it)) }
                CoefSlider("Нажал «Интересно»", c.like) { engine.updateCoef(c.copy(like = it)) }
                Hint("Прочтение засчитывается, если статья была открыта хотя бы 15 секунд; дочитанная до конца весит больше.")
            }

            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { confirmReset = true }) { Text("Сбросить все интересы") }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить интересы?") },
            text = { Text("Все слова, рубрики и издания будут забыты. Вкладка «Для тебя» начнёт учиться заново.") },
            confirmButton = {
                TextButton(onClick = { engine.reset(); confirmReset = false }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Строка с весом: название, полоска, «−», число, «+», крестик. */
@Composable
private fun WeightRow(
    label: String,
    note: String?,
    weight: Double,
    max: Double,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = onMinus) { Text("−", fontSize = 20.sp) }
            Text(format(weight), modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onPlus) { Text("+", fontSize = 20.sp) }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Clear, contentDescription = "Убрать") }
        }
        LinearProgressIndicator(
            progress = { (weight / max).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun CoefSlider(label: String, value: Double, onChange: (Double) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, modifier = Modifier.weight(1f))
            Text(format(value), fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it * 2).roundToInt() / 2.0) },
            valueRange = 0f..5f,
            steps = 9,
        )
    }
}

private fun format(w: Double): String =
    if (w >= 10) w.roundToInt().toString()
    else String.format(java.util.Locale("ru"), "%.1f", w).removeSuffix(",0")
