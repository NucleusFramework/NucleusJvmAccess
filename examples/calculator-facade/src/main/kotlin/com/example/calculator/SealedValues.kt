package com.example.calculator

/** A sealed value hierarchy: figures travel by value, nested in one another. */
sealed interface Figure {
    data class Circle(val radius: Double) : Figure

    data class Rect(val width: Double, val height: Double) : Figure

    data class Group(val name: String, val children: List<Figure>, val anchor: Figure? = null) : Figure

    data object Empty : Figure
}

/** A data class holding a sealed value: the recursion goes through it too. */
data class Layer(val shape: Figure, val tags: Set<String>)

/** A sealed class of values. */
sealed class Op {
    data class Add(val a: Long, val b: Long) : Op()

    data class Sum(val values: LongArray) : Op()

    data object Noop : Op()
}

class Geometry {
    fun area(shape: Figure): Double = when (shape) {
        is Figure.Circle -> Math.PI * shape.radius * shape.radius
        is Figure.Rect -> shape.width * shape.height
        is Figure.Group -> shape.children.sumOf { area(it) }
        Figure.Empty -> 0.0
    }

    fun scale(shape: Figure, factor: Double): Figure = when (shape) {
        is Figure.Circle -> Figure.Circle(shape.radius * factor)
        is Figure.Rect -> Figure.Rect(shape.width * factor, shape.height * factor)
        is Figure.Group -> shape.copy(children = shape.children.map { scale(it, factor) }, anchor = shape.anchor?.let { scale(it, factor) })
        Figure.Empty -> Figure.Empty
    }

    fun flatten(shape: Figure): List<Figure> = if (shape is Figure.Group) shape.children.flatMap { flatten(it) } else listOf(shape)

    fun nested(depth: Int): Figure = if (depth == 0) Figure.Empty else Figure.Group("level $depth", listOf(nested(depth - 1), Figure.Circle(depth.toDouble())))

    fun depth(shape: Figure): Int = if (shape is Figure.Group) 1 + (shape.children.maxOfOrNull { depth(it) } ?: 0) else 0

    fun layerArea(layer: Layer): Double = area(layer.shape) * layer.tags.size

    fun maybe(present: Boolean): Figure? = if (present) Figure.Rect(2.0, 3.0) else null

    fun eval(op: Op): Long = when (op) {
        is Op.Add -> op.a + op.b
        is Op.Sum -> op.values.sum()
        Op.Noop -> 0
    }

    fun viaApp(shape: Figure, transform: (Figure) -> Figure): Figure = transform(shape)

    /** Default arguments: the app may leave out the trailing ones, the JVM fills them in. */
    fun describe(shape: Figure, decimals: Int = 1, unit: String = "u²"): String = String.format(java.util.Locale.ROOT, "%.${decimals}f %s", area(shape), unit)
}
