package dev.nucleusframework.nja.server

/**
 * The binary format both sides of the bridge speak (little-endian). Generated dispatchers write and read values
 * with these primitives, in the order of the declaration they encode.
 */
class WireWriter(capacity: Int = 256) {
    @PublishedApi internal var buffer = ByteArray(capacity)
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
    }

    fun writeByte(value: Byte) {
        ensure(1)
        buffer[size++] = value
    }

    fun writeBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    fun writeShort(value: Short) {
        ensure(2)
        val v = value.toInt()
        buffer[size] = v.toByte()
        buffer[size + 1] = (v shr 8).toByte()
        size += 2
    }

    fun writeInt(value: Int) {
        ensure(4)
        val b = buffer
        val s = size
        b[s] = value.toByte()
        b[s + 1] = (value shr 8).toByte()
        b[s + 2] = (value shr 16).toByte()
        b[s + 3] = (value shr 24).toByte()
        size = s + 4
    }

    fun writeLong(value: Long) {
        ensure(8)
        val b = buffer
        val s = size
        for (i in 0 until 8) b[s + i] = (value shr (8 * i)).toByte()
        size = s + 8
    }

    fun writeFloat(value: Float) = writeInt(value.toRawBits())

    fun writeDouble(value: Double) = writeLong(value.toRawBits())

    /** UTF-8 bytes prefixed by their count, -1 for null. */
    fun writeString(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        // ASCII strings (the common case) are encoded without an intermediate array
        val length = value.length
        var ascii = true
        for (i in 0 until length) if (value[i].code >= 0x80) {
            ascii = false
            break
        }
        if (ascii) {
            writeInt(length)
            ensure(length)
            val b = buffer
            val s = size
            for (i in 0 until length) b[s + i] = value[i].code.toByte()
            size = s + length
        } else {
            writeBytes(value.encodeToByteArray())
        }
    }

    fun writeBytes(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeInt(value.size)
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun writeRaw(value: ByteArray, offset: Int = 0, length: Int = value.size - offset) {
        ensure(length)
        value.copyInto(buffer, size, offset, offset + length)
        size += length
    }
}

class WireReader(private val buffer: ByteArray, private var position: Int = 0, private val limit: Int = buffer.size) {
    val remaining: Int get() = limit - position

    fun readByte(): Byte {
        if (position >= limit) throw IllegalStateException("NJA: read past the end of the message")
        return buffer[position++]
    }

    fun readBoolean(): Boolean = readByte().toInt() != 0

    fun readShort(): Short {
        check(2)
        val v = (buffer[position].toInt() and 0xff) or (buffer[position + 1].toInt() shl 8)
        position += 2
        return v.toShort()
    }

    fun readInt(): Int {
        check(4)
        val b = buffer
        val p = position
        position = p + 4
        return (b[p].toInt() and 0xff) or
            ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or
            (b[p + 3].toInt() shl 24)
    }

    fun readLong(): Long {
        check(8)
        val b = buffer
        val p = position
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[p + i].toLong() and 0xff)
        position = p + 8
        return v
    }

    fun readFloat(): Float = Float.fromBits(readInt())

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readString(): String? {
        val length = readInt()
        if (length < 0) return null
        check(length)
        val value = buffer.decodeToString(position, position + length)
        position += length
        return value
    }

    fun readBytes(): ByteArray? {
        val length = readInt()
        if (length < 0) return null
        check(length)
        val value = buffer.copyOfRange(position, position + length)
        position += length
        return value
    }

    private fun check(count: Int) {
        if (count < 0 || position + count > limit) throw IllegalStateException("NJA: read past the end of the message")
    }
}

// Composite values, as generated code writes them: collections and maps are their size (-1 for null) then their
// elements, other nullable values a presence flag then the value.

inline fun <T> writeList(w: WireWriter, value: Collection<T>?, element: (WireWriter, T) -> Unit) {
    if (value == null) {
        w.writeInt(-1)
        return
    }
    w.writeInt(value.size)
    for (e in value) element(w, e)
}

inline fun <T> readList(r: WireReader, element: (WireReader) -> T): List<T>? {
    val size = r.readInt()
    if (size < 0) return null
    val list = ArrayList<T>(size)
    repeat(size) { list.add(element(r)) }
    return list
}

inline fun <T> readSet(r: WireReader, element: (WireReader) -> T): Set<T>? {
    val size = r.readInt()
    if (size < 0) return null
    val set = LinkedHashSet<T>(size * 2)
    repeat(size) { set.add(element(r)) }
    return set
}

inline fun <K, V> writeMap(w: WireWriter, value: Map<K, V>?, key: (WireWriter, K) -> Unit, item: (WireWriter, V) -> Unit) {
    if (value == null) {
        w.writeInt(-1)
        return
    }
    w.writeInt(value.size)
    for ((k, v) in value) {
        key(w, k)
        item(w, v)
    }
}

inline fun <K, V> readMap(r: WireReader, key: (WireReader) -> K, item: (WireReader) -> V): Map<K, V>? {
    val size = r.readInt()
    if (size < 0) return null
    val map = LinkedHashMap<K, V>(size * 2)
    repeat(size) {
        val k = key(r)
        map[k] = item(r)
    }
    return map
}

inline fun <T : Any> writeNullable(w: WireWriter, value: T?, write: (WireWriter, T) -> Unit) {
    if (value == null) {
        w.writeBoolean(false)
    } else {
        w.writeBoolean(true)
        write(w, value)
    }
}

inline fun <T : Any> readNullable(r: WireReader, read: (WireReader) -> T): T? = if (r.readBoolean()) read(r) else null
