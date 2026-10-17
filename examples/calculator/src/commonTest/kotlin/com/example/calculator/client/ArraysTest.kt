package com.example.calculator.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArraysTest {
    private val ops = ArrayOps()

    @Test
    fun `primitive arrays go both ways`() {
        assertEquals(6L, ops.sumInts(intArrayOf(1, 2, 3)))
        assertEquals(0L, ops.sumInts(IntArray(0)))
        assertContentEquals(longArrayOf(Long.MAX_VALUE, 0, Long.MIN_VALUE), ops.reverseLongs(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)))
        assertContentEquals(floatArrayOf(2f, -4f), ops.scale(floatArrayOf(1f, -2f), 2f))
        assertContentEquals(doubleArrayOf(0.0, 1.0, 4.0, 9.0), ops.squares(4))
    }

    @Test
    fun `large arrays`() {
        val ids = LongArray(200_000) { it * 3L }
        assertContentEquals(ids.reversedArray(), ops.reverseLongs(ids))
        assertEquals(200_000, ops.squares(200_000).size)
    }

    @Test
    fun `nullable arrays`() {
        assertEquals(-1, ops.sizeOf(null))
        assertEquals(2, ops.sizeOf(longArrayOf(1, 2)))
        assertNull(ops.maybe(false))
        assertContentEquals(intArrayOf(1, 2, 3), ops.maybe(true))
    }

    @Test
    fun `arrays in a data class`() {
        val normalized = ops.normalize(Embedding(longArrayOf(7, 8), floatArrayOf(3f, 4f), "v"))
        assertContentEquals(longArrayOf(7, 8), normalized.ids)
        assertContentEquals(floatArrayOf(0.6f, 0.8f), normalized.values)
        assertEquals("v/n", normalized.label)
    }

    @Test
    fun `arrays through a callback`() {
        assertContentEquals(doubleArrayOf(2.0, 4.0), ops.mapThroughApp(doubleArrayOf(1.0, 2.0)) { a -> DoubleArray(a.size) { a[it] * 2 } })
    }

    @Test
    fun `float bits survive`() {
        val bits = ops.bits()
        assertEquals(6, bits.size)
        assert(bits[0].isNaN()) { "NaN" }
        assertEquals(Float.POSITIVE_INFINITY, bits[1])
        assertEquals(Float.NEGATIVE_INFINITY, bits[2])
        assertEquals((-0.0f).toRawBits(), bits[3].toRawBits())
        assertEquals(Float.MIN_VALUE, bits[4])
        assertEquals(Float.MAX_VALUE, bits[5])
    }
}
