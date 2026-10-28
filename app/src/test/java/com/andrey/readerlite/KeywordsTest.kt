package com.andrey.readerlite

import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordsTest {
    @Test
    fun formsOfOneWordMatch() {
        val a = Keywords.extract("Новый автомобиль Lada Azimut показали в Москве").map { it.first }
        val b = Keywords.extract("Продажи автомобилей Lada выросли").map { it.first }
        println("FEED kw: $a | $b")
        assertEquals(true, a.contains(Keywords.stem("автомобилей")))
        assertEquals(true, b.contains(Keywords.stem("автомобиль")))
        assertEquals(true, a.contains("lada") && b.contains("lada"))
    }
}
