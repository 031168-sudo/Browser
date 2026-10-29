package com.andrey.readerlite

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.pow

/** Термин в профиле интересов. */
data class TermWeight(
    val label: String,
    /** Вес на момент [updated]. Текущий вес с учётом затухания — [InterestEngine.weightOf]. */
    val weight: Double,
    /** Добавлен вручную: не затухает. */
    val manual: Boolean,
    val updated: Long,
)

/** Сколько весит каждое действие. Меняется в окне «Мои интересы». */
data class Coefficients(
    /** Прочитал новость из ленты. */
    val read: Double = 1.0,
    /** Пришёл в читалку из другого приложения и прочитал — осознанный выбор. */
    val external: Double = 1.5,
    /** Добавил в избранное. */
    val save: Double = 2.0,
    /** Нажал «Интересно». */
    val like: Double = 3.0,
)

/** Как статья попала в читалку — от этого зависит вес прочтения. */
enum class ReadKind { FEED, EXTERNAL, REREAD }

/**
 * Профиль интересов. Один на всё приложение: в него пишут лента, читалка и избранное.
 *
 * Каждое действие (прочитал, сохранил, «Интересно») раздаёт свой вес главным
 * терминам статьи. Главными считаются те, что часто встречаются в самой статье
 * и при этом встречаются и в других новостях — то есть это тема, а не разовая фамилия.
 * Старые интересы постепенно затухают (вес вдвое меньше через [HALF_LIFE_DAYS] дней),
 * если их не подкреплять. Добавленные вручную не затухают.
 */
class InterestEngine private constructor(private val file: File) {

    var terms: Map<String, TermWeight> by mutableStateOf(emptyMap())
        private set
    var sources: Map<String, Double> by mutableStateOf(emptyMap())
        private set
    var categories: Map<String, Double> by mutableStateOf(emptyMap())
        private set
    var liked: Set<String> by mutableStateOf(emptySet())
        private set
    var coef: Coefficients by mutableStateOf(Coefficients())
        private set

    /** В скольких заголовках встречался термин — чтобы отличать темы от разовых слов. */
    private val df = HashMap<String, Int>()
    private var docs = 0
    private val seenTitles = HashSet<Int>()

    /** Что внесли «Интересно» и «Избранное» — чтобы отмена убирала ровно это. */
    private val events = HashMap<String, List<Delta>>()
    private val readAt = HashMap<String, Long>()

    private val writer = Executors.newSingleThreadExecutor()

    private data class Delta(val key: String, val label: String, val value: Double)

    // ---------- действия ----------

    /** Прочитал статью. Одна и та же статья учитывается не чаще раза в сутки. */
    fun learnRead(url: String, title: String, body: String, source: String, category: String, kind: ReadKind, progress: Double) {
        val now = System.currentTimeMillis()
        if ((readAt[url] ?: 0) > now - DAY) return
        val base = when (kind) {
            ReadKind.FEED -> coef.read
            ReadKind.EXTERNAL -> coef.external
            ReadKind.REREAD -> coef.read * 0.5
        }
        // Дочитал до конца — полный вес, бросил в начале — меньше.
        val amount = base * (0.4 + 0.6 * min(1.0, progress / 0.8))
        if (amount <= 0) return
        readAt[url] = now
        if (readAt.size > 2000) readAt.entries.sortedBy { it.value }.take(500).forEach { readAt.remove(it.key) }
        apply(contributions(title, body, source, category, amount), +1.0)
        save()
    }

    /** «Интересно»: повторное нажатие отменяет. Возвращает названия главных терминов. */
    fun toggleLike(key: String, title: String, body: String, source: String, category: String): List<String> {
        val eventKey = "like|$key"
        if (key in liked) {
            events.remove(eventKey)?.let { apply(it, -1.0) }
            liked = liked - key
            save()
            return emptyList()
        }
        val d = contributions(title, body, source, category, coef.like)
        apply(d, +1.0)
        events[eventKey] = d
        liked = liked + key
        save()
        return d.filter { !it.key.startsWith(SRC) && !it.key.startsWith(CAT) }.map { it.label }
    }

    fun learnSave(url: String, title: String, body: String, source: String, category: String) {
        val eventKey = "save|$url"
        if (eventKey in events) return
        val d = contributions(title, body, source, category, coef.save)
        apply(d, +1.0)
        events[eventKey] = d
        save()
    }

    fun revertSave(url: String) {
        events.remove("save|$url")?.let {
            apply(it, -1.0)
            save()
        }
    }

    // ---------- оценка новостей ----------

    /** Насколько новость похожа на интересы. 0 — совсем не похожа. */
    fun score(title: String, source: String, category: String): Double {
        val now = System.currentTimeMillis()
        val words = TextAnalyzer.titleKeys(title).sumOf { k -> terms[k]?.let { weightOf(it, now) } ?: 0.0 }
        val cat = if (category.isNotEmpty()) categories[category] ?: 0.0 else 0.0
        if (words <= 0.0 && cat <= 0.0) return 0.0
        return words + 0.5 * cat + 0.3 * (sources[source] ?: 0.0)
    }

    /** Самые весомые интересы — по ним «Для тебя» ищет новости. */
    fun topSearchLabels(n: Int): List<String> {
        val now = System.currentTimeMillis()
        return terms.values.sortedByDescending { weightOf(it, now) }.take(n)
            .map { it.label.substringBefore(" (") }
    }

    /** Запоминает заголовки ленты: так видно, какие слова встречаются в новостях часто. */
    fun observeTitles(titles: List<String>) {
        var changed = false
        titles.forEach { title ->
            val h = title.lowercase().hashCode()
            if (!seenTitles.add(h)) return@forEach
            docs++
            TextAnalyzer.titleKeys(title).forEach { df[it] = (df[it] ?: 0) + 1 }
            changed = true
        }
        if (seenTitles.size > 20_000) {
            seenTitles.clear()
            docs /= 2
            df.keys.toList().forEach { k -> df[k] = df[k]!! / 2; if (df[k] == 0) df.remove(k) }
        }
        if (changed) save()
    }

    // ---------- правка вручную ----------

    fun weightOf(t: TermWeight, now: Long = System.currentTimeMillis()): Double =
        if (t.manual) t.weight else t.weight * 0.5.pow((now - t.updated).toDouble() / (HALF_LIFE_DAYS * DAY))

    fun setTermWeight(key: String, weight: Double) {
        val t = terms[key] ?: return
        terms = if (weight <= 0.05) terms - key
        else terms + (key to t.copy(weight = weight, updated = System.currentTimeMillis()))
        save()
    }

    fun removeTerm(key: String) {
        terms = terms - key
        save()
    }

    /** Свой интерес: не затухает. */
    fun addManual(text: String): Boolean {
        val (key, label) = TextAnalyzer.keyFor(text) ?: return false
        val old = terms[key]
        terms = terms + (key to TermWeight(old?.label ?: label, maxOf(5.0, old?.let { weightOf(it) } ?: 0.0), true, System.currentTimeMillis()))
        save()
        return true
    }

    fun setSourceWeight(name: String, weight: Double) {
        sources = if (weight <= 0.05) sources - name else sources + (name to weight)
        save()
    }

    fun setCategoryWeight(name: String, weight: Double) {
        categories = if (weight <= 0.05) categories - name else categories + (name to weight)
        save()
    }

    fun setCoef(c: Coefficients) {
        coef = c
        save()
    }

    fun reset() {
        terms = emptyMap()
        sources = emptyMap()
        categories = emptyMap()
        liked = emptySet()
        events.clear()
        readAt.clear()
        save()
    }

    // ---------- внутреннее ----------

    /**
     * Раздаёт [amount] главным терминам статьи. Главность = упоминания в статье ×
     * «полезность» термина: термин, который встречается и в других новостях, — тема;
     * слово с заглавной буквы, которого больше нигде нет, — скорее фамилия.
     */
    private fun contributions(title: String, body: String, source: String, category: String, amount: Double): List<Delta> {
        val stats = TextAnalyzer.analyze(title, body)
        val ranked = stats.map { it to it.count * usefulness(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(TOP_TERMS)
        val max = ranked.firstOrNull()?.second ?: return emptyList()
        val result = ranked.map { (s, r) -> Delta(s.key, s.label, amount * r / max) }
            .filter { it.value >= amount * 0.15 }
            .toMutableList()
        if (source.isNotBlank()) result += Delta(SRC + source, source, amount * 0.3)
        if (category.isNotBlank()) result += Delta(CAT + category, category, amount * 0.5)
        return result
    }

    private fun usefulness(s: TextAnalyzer.TermStat): Double {
        val nameFactor = if (s.nameShare > 0.6) 0.35 else 1.0
        // Пока заголовков мало, частоту по новостям оценить нельзя.
        if (docs < 80) return nameFactor
        val d = df[s.key] ?: 0
        var base = min(1.0, 0.3 + d / 4.0)
        if (d.toDouble() / docs > 0.08) base *= 0.3 // слишком общее слово
        return base * nameFactor
    }

    private fun apply(deltas: List<Delta>, sign: Double) {
        val now = System.currentTimeMillis()
        val t = terms.toMutableMap()
        val s = sources.toMutableMap()
        val c = categories.toMutableMap()
        deltas.forEach { d ->
            when {
                d.key.startsWith(SRC) -> bump(s, d.key.removePrefix(SRC), sign * d.value)
                d.key.startsWith(CAT) -> bump(c, d.key.removePrefix(CAT), sign * d.value)
                else -> {
                    val old = t[d.key]
                    val w = (old?.let { weightOf(it, now) } ?: 0.0) + sign * d.value
                    if (w <= 0.05 && old?.manual != true) t.remove(d.key)
                    else t[d.key] = TermWeight(old?.label ?: d.label, maxOf(w, 0.0), old?.manual ?: false, now)
                }
            }
        }
        terms = t
        sources = s
        categories = c
    }

    private fun bump(map: MutableMap<String, Double>, key: String, delta: Double) {
        val w = (map[key] ?: 0.0) + delta
        if (w <= 0.05) map.remove(key) else map[key] = w
    }

    private fun save() {
        val json = toJson().toString()
        writer.execute {
            try {
                file.writeText(json)
            } catch (e: Exception) {
                // не сохранилось — профиль останется в памяти до следующей записи
            }
        }
    }

    private fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("terms", JSONObject().also { j ->
            terms.forEach { (k, t) ->
                j.put(k, JSONObject().put("l", t.label).put("w", t.weight).put("m", t.manual).put("u", t.updated))
            }
        })
        o.put("sources", JSONObject(sources))
        o.put("cats", JSONObject(categories))
        o.put("liked", JSONArray(liked.toList()))
        o.put("coef", JSONObject().put("read", coef.read).put("external", coef.external)
            .put("save", coef.save).put("like", coef.like))
        o.put("df", JSONObject(df as Map<*, *>))
        o.put("docs", docs)
        o.put("seen", JSONArray(seenTitles.toList()))
        o.put("events", JSONObject().also { j ->
            events.forEach { (k, list) ->
                j.put(k, JSONArray().also { a ->
                    list.forEach { d -> a.put(JSONObject().put("k", d.key).put("l", d.label).put("v", d.value)) }
                })
            }
        })
        o.put("read", JSONObject(readAt as Map<*, *>))
        return o
    }

    private fun load(context: Context) {
        val o = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            migrateOld(context)
            return
        }
        o.optJSONObject("terms")?.let { j ->
            terms = j.keys().asSequence().associateWith { k ->
                val t = j.getJSONObject(k)
                TermWeight(t.getString("l"), t.getDouble("w"), t.optBoolean("m"), t.optLong("u"))
            }
        }
        sources = o.optJSONObject("sources").toDoubleMap()
        categories = o.optJSONObject("cats").toDoubleMap()
        liked = o.optJSONArray("liked")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        o.optJSONObject("coef")?.let { c ->
            coef = Coefficients(c.optDouble("read", 1.0), c.optDouble("external", 1.5),
                c.optDouble("save", 2.0), c.optDouble("like", 3.0))
        }
        o.optJSONObject("df")?.let { j -> j.keys().forEach { k -> df[k] = j.getInt(k) } }
        docs = o.optInt("docs")
        o.optJSONArray("seen")?.let { a -> (0 until a.length()).forEach { seenTitles += a.getInt(it) } }
        o.optJSONObject("events")?.let { j ->
            j.keys().forEach { k ->
                val a = j.getJSONArray(k)
                events[k] = (0 until a.length()).map { i ->
                    val d = a.getJSONObject(i)
                    Delta(d.getString("k"), d.getString("l"), d.getDouble("v"))
                }
            }
        }
        o.optJSONObject("read")?.let { j -> j.keys().forEach { k -> readAt[k] = j.getLong(k) } }
    }

    /** Переносим интересы из прошлой версии (слова из «Интересно»). */
    private fun migrateOld(context: Context) {
        val prefs = context.getSharedPreferences("feed_settings", Context.MODE_PRIVATE)
        try {
            val old = JSONObject(prefs.getString("interests", "{}") ?: "{}")
            val now = System.currentTimeMillis()
            terms = old.keys().asSequence().associateWith { k ->
                val v = old.getJSONObject(k)
                TermWeight(v.getString("w"), v.getDouble("v"), false, now)
            }
            sources = JSONObject(prefs.getString("source_weights", "{}") ?: "{}").toDoubleMap()
            liked = prefs.getStringSet("liked", null)?.toSet() ?: emptySet()
            if (terms.isNotEmpty()) save()
        } catch (e: Exception) {
            // нечего переносить
        }
    }

    private fun JSONObject?.toDoubleMap(): Map<String, Double> =
        this?.let { j -> j.keys().asSequence().associateWith { j.getDouble(it) } } ?: emptyMap()

    companion object {
        const val HALF_LIFE_DAYS = 30.0
        private const val DAY = 24L * 60 * 60 * 1000
        private const val TOP_TERMS = 8
        private const val SRC = "src:"
        private const val CAT = "cat:"

        @Volatile
        private var instance: InterestEngine? = null

        fun get(context: Context): InterestEngine = instance ?: synchronized(this) {
            instance ?: InterestEngine(File(context.applicationContext.filesDir, "interests.json")).also {
                it.load(context.applicationContext)
                instance = it
            }
        }
    }
}
