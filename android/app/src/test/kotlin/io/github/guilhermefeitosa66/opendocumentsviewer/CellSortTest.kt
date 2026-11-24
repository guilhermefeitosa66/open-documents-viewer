package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.formats.CellSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellSortTest {
    @Test
    fun numbersInBothNotations() {
        assertEquals(1234.56, CellSort.number("1.234,56")!!, 1e-9)
        assertEquals(1234.56, CellSort.number("1,234.56")!!, 1e-9)
        assertEquals(742.37, CellSort.number("742,37")!!, 1e-9)
        assertEquals(1234.0, CellSort.number("1,234")!!, 1e-9)
        assertEquals(12.0, CellSort.number("12%")!!, 1e-9)
        assertEquals(-5.5, CellSort.number("R$ -5,50")!!, 1e-9)
        assertNull(CellSort.number("abc"))
        assertNull(CellSort.number("2026-11-24x"))
    }

    @Test
    fun sortsNumbersTextAndBlanks() {
        val rows = listOf(listOf("10"), listOf("item 10"), listOf(""), listOf("2"), listOf("item 2"), listOf("1.000,5"))
        assertEquals(listOf("2", "10", "1.000,5", "item 2", "item 10", ""), rows.sortedWith(CellSort.comparator(0, true)).map { it[0] })
        assertEquals(listOf("item 10", "item 2", "1.000,5", "10", "2", ""), rows.sortedWith(CellSort.comparator(0, false)).map { it[0] })
    }
}
