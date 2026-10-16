package com.example.bench

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.math3.linear.LUDecomposition
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.random.Random

/** A JSON document's shape, bound by Jackson through its setters (no Kotlin module: plain databind). */
class LineItem {
    var sku: String = ""
    var quantity: Int = 0
    var price: Double = 0.0
}

class Order {
    var id: Long = 0
    var customer: String = ""
    var tags: List<String> = emptyList()
    var items: List<LineItem> = emptyList()
    var paid: Boolean = false
}

/** CPU work on Java libraries; each returns a checksum (so that nothing is optimized away and runs can be compared). */
object Workloads {
    private val mapper = ObjectMapper()

    private fun orders(count: Int): List<Order> {
        val random = Random(42)
        return List(count) { i ->
            Order().apply {
                id = i.toLong()
                customer = "customer-${random.nextInt(10_000)}"
                tags = List(3) { "tag${random.nextInt(50)}" }
                items = List(5) {
                    LineItem().apply {
                        sku = "SKU-${random.nextInt(100_000)}"
                        quantity = random.nextInt(1, 10)
                        price = random.nextDouble(1.0, 500.0)
                    }
                }
                paid = random.nextBoolean()
            }
        }
    }

    /** Jackson: [count] orders serialized to JSON then parsed back. */
    fun jackson(count: Int): Long {
        val json = mapper.writeValueAsString(orders(count))
        val parsed: Array<Order> = mapper.readValue(json, Array<Order>::class.java)
        return parsed.sumOf { order -> order.items.sumOf { it.quantity.toLong() } } + json.length
    }

    /** Commons Math: forward then inverse FFT of 2^[log2] samples. */
    fun fft(log2: Int): Long {
        val random = Random(7)
        val data = DoubleArray(1 shl log2) { random.nextDouble() }
        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        val spectrum = transformer.transform(data, TransformType.FORWARD)
        val back = transformer.transform(spectrum, TransformType.INVERSE)
        return (back.sumOf { it.real } * 1_000).toLong()
    }

    /** Commons Math: LU inversion of an [n]×[n] matrix. */
    fun matrix(n: Int): Long {
        val random = Random(11)
        val m = MatrixUtils.createRealMatrix(Array(n) { i -> DoubleArray(n) { j -> random.nextDouble() + (if (i == j) n.toDouble() else 0.0) } })
        val inverse = LUDecomposition(m).solver.inverse
        return (m.multiply(inverse).trace * 1_000).toLong()
    }
}
