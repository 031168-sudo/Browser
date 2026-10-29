package com.andrey.readerlite

/**
 * Разбор текста на «термины» — слова и устойчивые сочетания, по которым
 * можно узнать тему статьи. Работает целиком на телефоне.
 *
 * Чтобы находить тему, а не случайные слова:
 *  - формы одного слова склеиваются (автомобиль / автомобили / автомобилей);
 *  - сочетания «прилагательное + существительное» считаются одним термином
 *    («искусственный интеллект», «квантовый компьютер»);
 *  - синонимы сводятся к одному («ИИ», «AI», «искусственный интеллект», «нейросеть»);
 *  - слова с заглавной буквы в середине предложения отмечаются как имена:
 *    фамилии меняются от статьи к статье, тему они почти не определяют.
 */
object TextAnalyzer {

    /** Статистика термина в одной статье. */
    data class TermStat(
        val key: String,
        val label: String,
        val count: Double,
        /** Какая доля упоминаний — с заглавной буквы в середине предложения (похоже на имя). */
        val nameShare: Double,
    )

    private val STOP_WORDS = setOf(
        "который", "которая", "которое", "которые", "которых", "которым", "которой", "котором",
        "может", "могут", "будет", "будут", "после", "также", "более", "менее", "чтобы", "этого",
        "этой", "этот", "этом", "этих", "этим", "году", "года", "годы", "время", "заявил", "заявила",
        "заявили", "сообщил", "сообщила", "сообщили", "сообщает", "рассказал", "рассказала",
        "рассказали", "назвал", "назвала", "назвали", "стало", "стал", "стала", "стали", "новый",
        "новая", "новое", "новые", "нового", "новых", "своих", "свой", "своей", "свою", "своим",
        "почему", "когда", "через", "между", "против", "около", "ради", "всех", "всего", "только",
        "даже", "очень", "можно", "нужно", "самый", "самые", "первый", "первые", "теперь", "сегодня",
        "вчера", "завтра", "россии", "россия", "россиян", "россияне", "рублей", "рубля", "человек",
        "людей", "раскрыли", "раскрыл", "объяснил", "объяснили", "эксперт", "эксперты", "новости",
        "фото", "видео", "главное", "день", "дней", "неделе", "месяц", "мнение", "тысяч",
        "процентов", "почти", "пока", "какие", "какой", "каких", "такой", "такие", "будто",
        "весь", "хотят", "хочет", "стоит", "станет", "начал", "начали", "решил", "решили",
        "получил", "получили", "показал", "показали", "появился", "появились", "появилась",
        "словам", "отметил", "отметила", "отметили", "добавил", "добавила", "пишет", "написал",
        "говорит", "сказал", "сказала", "считает", "уверен", "также", "однако", "кроме", "более",
        "этому", "таким", "образом", "например", "сейчас", "ранее", "позже", "всегда", "никогда",
        "много", "многие", "другие", "других", "другой", "один", "одна", "одно", "двух", "трех",
        "трёх", "больше", "меньше", "лучше", "хуже", "свои", "себя", "себе", "есть", "было", "были",
        "быть", "если", "чем", "потому", "поэтому", "затем", "тогда", "здесь", "там", "где", "куда",
        "сайт", "статья", "статье", "материал", "читайте", "подписывайтесь", "реклама",
    )

    private val STOP_ACRONYMS = setOf("рф", "риа", "тасс", "сми", "ооо", "пао", "зао", "мск", "сша", "снг")

    private val ENDING = Regex(
        "(иями|ями|ами|ого|его|ому|ему|ыми|ими|ией|ия|ий|ый|ой|ая|яя|ое|ее|ые|ие|ую|юю|ых|их|ым|им|ов|ев|ей|ам|ям|ах|ях|ом|ем|а|я|о|е|ы|и|у|ю|ь)$"
    )
    private val ADJECTIVE = Regex("(ый|ий|ой|ая|яя|ое|ее|ые|ие|ого|его|ому|ему|ых|их|ым|им|ую|юю|ыми|ими)$")
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)?")

    /** Синонимы: ключ → общий термин и его название. */
    private val ALIASES: Map<String, Pair<String, String>> = buildMap {
        val ai = "ии" to "ИИ (искусственный интеллект)"
        listOf("ии", "ai", "искусственн интеллект", "нейросет", "нейросеть").forEach { put(it, ai) }
        val drones = "бпла" to "беспилотники"
        listOf("бпла", "беспилотник", "дрон").forEach { put(it, drones) }
        val ev = "электромобил" to "электромобили"
        listOf("электромобил", "электрокар").forEach { put(it, ev) }
    }

    private val STOP_STEMS = STOP_WORDS.map { stem(it) }.toSet()

    fun stem(word: String): String {
        val w = word.lowercase().replace('ё', 'е')
        if (w.length <= 4) return w
        val cut = ENDING.find(w)?.range?.first ?: return w
        return if (cut >= 4) w.substring(0, cut) else w
    }

    private data class Tok(val raw: String, val key: String?, val acronym: Boolean, val midCap: Boolean)

    private fun tokens(sentence: String): List<Tok> =
        WORD.findAll(sentence).mapIndexed { index, m ->
            val raw = m.value
            val lower = raw.lowercase()
            val isAcronym = raw.length in 2..6 && raw.any { it.isLetter() } && raw == raw.uppercase() && raw != lower
            val key = when {
                isAcronym -> lower.takeIf { it !in STOP_ACRONYMS }
                raw.length < 4 || raw.all { it.isDigit() } -> null
                lower.replace('ё', 'е') in STOP_WORDS -> null
                else -> stem(lower).takeIf { it !in STOP_STEMS }
            }
            Tok(raw, key, isAcronym, !isAcronym && index > 0 && raw[0].isUpperCase())
        }.toList()

    private fun sentences(text: String): List<String> =
        text.split(Regex("[.!?…\\n«»\"]+")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun canonical(key: String): Pair<String, String?> =
        ALIASES[key]?.let { it.first to it.second } ?: (key to null)

    /** Термины статьи с количеством упоминаний. Заголовок весит втрое больше текста. */
    fun analyze(title: String, body: String): List<TermStat> {
        val counts = HashMap<String, Double>()
        val names = HashMap<String, Double>()
        val forms = HashMap<String, HashMap<String, Int>>()
        val fixedLabels = HashMap<String, String>()

        fun add(key: String, label: String, weight: Double, midCap: Boolean, fixed: String?) {
            counts[key] = (counts[key] ?: 0.0) + weight
            if (midCap) names[key] = (names[key] ?: 0.0) + weight
            if (fixed != null) fixedLabels[key] = fixed
            val f = forms.getOrPut(key) { HashMap() }
            f[label] = (f[label] ?: 0) + 1
        }

        fun scan(text: String, weight: Double) {
            sentences(text).forEach { sentence ->
                val toks = tokens(sentence)
                val usedInPhrase = HashSet<Int>()
                // Сочетания «прилагательное + существительное».
                for (i in 0 until toks.size - 1) {
                    val a = toks[i]
                    val b = toks[i + 1]
                    if (a.key == null || b.key == null || a.acronym || b.acronym) continue
                    if (!ADJECTIVE.containsMatchIn(a.raw.lowercase())) continue
                    val (key, alias) = canonical("${a.key} ${b.key}")
                    val phrase = "${a.raw.lowercase()} ${b.raw.lowercase()}"
                    add(key, phrase, weight, a.midCap && b.midCap, alias)
                    // Если сочетание — известный синоним («искусственный интеллект»),
                    // отдельные слова не считаем, чтобы не дробить тему.
                    if (alias != null) {
                        usedInPhrase += i
                        usedInPhrase += i + 1
                    }
                }
                toks.forEachIndexed { i, t ->
                    if (t.key == null || i in usedInPhrase) return@forEachIndexed
                    val (key, alias) = canonical(t.key)
                    val label = if (t.acronym || t.midCap) t.raw else t.raw.lowercase()
                    add(key, label, weight, t.midCap, alias)
                }
            }
        }

        scan(title, 3.0)
        scan(body, 1.0)

        return counts.map { (key, count) ->
            // Сочетание, встретившееся один раз только в тексте, — скорее случайность.
            val label = fixedLabels[key] ?: forms[key]?.maxByOrNull { it.value }?.key ?: key
            TermStat(key, label, count, (names[key] ?: 0.0) / count)
        }.filter { !(it.key.contains(' ') && it.count < 2.0) }
    }

    /** Термины заголовка — для сравнения новости с интересами. */
    fun titleKeys(title: String): Set<String> = analyze(title, "").map { it.key }.toSet() +
        // Сочетания в заголовке встречаются по одному разу, учитываем и их.
        sentences(title).flatMap { s ->
            val toks = tokens(s)
            (0 until toks.size - 1).mapNotNull { i ->
                val a = toks[i]
                val b = toks[i + 1]
                if (a.key == null || b.key == null || a.acronym || b.acronym) null
                else canonical("${a.key} ${b.key}").first
            }
        }

    /** Ключ для интереса, введённого вручную: «электромобили» → основа, «квантовый компьютер» → сочетание. */
    fun keyFor(text: String): Pair<String, String>? {
        val toks = tokens(text.trim()).filter { it.key != null }
        return when {
            toks.isEmpty() -> null
            toks.size == 1 -> canonical(toks[0].key!!).let { (k, alias) -> k to (alias ?: text.trim()) }
            else -> canonical(toks.take(2).joinToString(" ") { it.key!! }).let { (k, alias) -> k to (alias ?: text.trim()) }
        }
    }
}
