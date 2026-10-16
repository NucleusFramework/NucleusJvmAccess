package com.example.calculator

import dev.nucleusframework.nja.JvmException

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmokeTest {
    @Test
    fun endToEnd() = runBlocking<Unit> {
        Calculator(10).use { calc ->
            assertEquals(15, calc.add(5))
            assertEquals("Calculator(current=15)", calc.describe())
            assertEquals(30, calc.transform { it * 2 })
            assertEquals(Point(30, 60), calc.getPoint())
            assertEquals(listOf(30, 60, 90), calc.getScores())
            assertEquals(7, calc.delayedAdd(3, 4))
            assertEquals(listOf(1, 2, 3), calc.countUp(10).take(3).toList())
            assertEquals(11, calc.getAdder(4)(7))
            assertFailsWith<dev.nucleusframework.nja.JvmException> { calc.divide(0) }
        }
        Circle(2.0).use { c -> assertEquals("circle", c.name) }
        assertEquals("2.0", Calculator.VERSION)
    }
}
