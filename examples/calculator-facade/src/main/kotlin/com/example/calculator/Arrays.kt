package com.example.calculator

/** A vector and its ids, primitive arrays inside a data class. */
data class Embedding(val ids: LongArray, val values: FloatArray, val label: String)

/** Primitive arrays crossing the bridge: as parameters, results, nullables, in data classes and in callbacks. */
class ArrayOps {
    fun sumInts(values: IntArray): Long = values.fold(0L) { sum, v -> sum + v }

    fun reverseLongs(values: LongArray): LongArray = values.reversedArray()

    fun scale(values: FloatArray, factor: Float): FloatArray = FloatArray(values.size) { values[it] * factor }

    fun squares(count: Int): DoubleArray = DoubleArray(count) { (it * it).toDouble() }

    fun sizeOf(values: LongArray?): Int = values?.size ?: -1

    fun maybe(present: Boolean): IntArray? = if (present) intArrayOf(1, 2, 3) else null

    fun normalize(embedding: Embedding): Embedding {
        val norm = kotlin.math.sqrt(embedding.values.fold(0.0) { s, v -> s + v * v }).toFloat()
        return embedding.copy(values = FloatArray(embedding.values.size) { embedding.values[it] / norm }, label = embedding.label + "/n")
    }

    fun mapThroughApp(values: DoubleArray, transform: (DoubleArray) -> DoubleArray): DoubleArray = transform(values)

    fun bits(): FloatArray = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, Float.MIN_VALUE, Float.MAX_VALUE)
}
