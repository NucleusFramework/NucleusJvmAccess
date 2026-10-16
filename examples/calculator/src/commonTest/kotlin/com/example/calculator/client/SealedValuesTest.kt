package com.example.calculator.client

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SealedValuesTest {
    private val geometry = Geometry()

    @Test
    fun `each case goes both ways`() {
        assertEquals(PI * 4, geometry.area(Figure.Circle(2.0)), 1e-9)
        assertEquals(6.0, geometry.area(Figure.Rect(2.0, 3.0)))
        assertEquals(0.0, geometry.area(Figure.Empty))
        assertEquals(Figure.Rect(4.0, 6.0), geometry.scale(Figure.Rect(2.0, 3.0), 2.0))
        assertSame(Figure.Empty, geometry.scale(Figure.Empty, 2.0))
    }

    @Test
    fun `nested and recursive values`() {
        val group = Figure.Group("g", listOf(Figure.Rect(1.0, 1.0), Figure.Group("inner", listOf(Figure.Rect(2.0, 2.0)))), anchor = Figure.Circle(1.0))
        assertEquals(5.0, geometry.area(group))
        assertEquals(
            Figure.Group("g", listOf(Figure.Rect(2.0, 2.0), Figure.Group("inner", listOf(Figure.Rect(4.0, 4.0)))), anchor = Figure.Circle(2.0)),
            geometry.scale(group, 2.0),
        )
        assertEquals(listOf(Figure.Rect(1.0, 1.0), Figure.Rect(2.0, 2.0)), geometry.flatten(group))
        val deep = geometry.nested(200)
        assertEquals(200, geometry.depth(deep))
        assertEquals(deep, geometry.scale(geometry.scale(deep, 1.0), 1.0))
    }

    @Test
    fun `in a data class or nullable or through a callback`() {
        assertEquals(12.0, geometry.layerArea(Layer(Figure.Rect(2.0, 3.0), setOf("a", "b"))))
        assertNull(geometry.maybe(false))
        assertEquals(Figure.Rect(2.0, 3.0), geometry.maybe(true))
        assertEquals(Figure.Circle(9.0), geometry.viaApp(Figure.Circle(3.0)) { s -> Figure.Circle((s as Figure.Circle).radius * 3) })
    }

    @Test
    fun `default arguments`() {
        val rect = Figure.Rect(2.0, 3.0)
        assertEquals("6.0 u²", geometry.describe(rect))
        assertEquals("6.000 u²", geometry.describe(rect, 3))
        assertEquals("6 m²", geometry.describe(rect, 0, "m²"))
    }

    @Test
    fun `a sealed class`() {
        assertEquals(5L, geometry.eval(Op.Add(2, 3)))
        assertEquals(10L, geometry.eval(Op.Sum(longArrayOf(1, 2, 3, 4))))
        assertEquals(0L, geometry.eval(Op.Noop))
    }
}
