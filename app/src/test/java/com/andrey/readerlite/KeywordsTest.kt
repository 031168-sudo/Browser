package com.andrey.readerlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordsTest {

    @Test
    fun formsOfOneWordMatch() {
        val a = TextAnalyzer.titleKeys("Новый автомобиль Lada Azimut показали в Москве")
        val b = TextAnalyzer.titleKeys("Продажи автомобилей Lada выросли")
        println("FEED kw: $a | $b")
        assertTrue(a.intersect(b).contains(TextAnalyzer.stem("автомобиль")))
    }

    /** Статья «физик говорит об ИИ»: главной темой должен стать ИИ, а не физик и его фамилия. */
    @Test
    fun topicBeatsPersonName() {
        val title = "Физик Михаил Гурвич: мы все уже вбежали в мышеловку ИИ"
        val body = """
            Известный физик Михаил Гурвич считает, что искусственный интеллект меняет науку.
            По словам Гурвича, ИИ уже пишет статьи и решает задачи.
            Нейросети обучаются на огромных массивах данных, и искусственный интеллект становится умнее.
            Гурвич предупреждает, что зависимость от ИИ растёт.
            Учёные спорят, сможет ли искусственный интеллект заменить исследователей.
            Компании вкладывают миллиарды в разработку ИИ.
        """.trimIndent()
        val stats = TextAnalyzer.analyze(title, body).sortedByDescending { it.count }
        stats.take(6).forEach { println("FEED kw stat: ${it.label} count=${it.count} name=${"%.2f".format(it.nameShare)}") }
        assertEquals("ии", stats.first().key)
        val gurvich = stats.first { it.key == TextAnalyzer.stem("Гурвич") }
        assertTrue("фамилия должна определяться как имя", gurvich.nameShare > 0.5)
    }
}
