package dev.nucleusframework.nja.plugin.codegen

import dev.nucleusframework.nja.plugin.ir.KneType

enum class Side { CLIENT, SERVER }

/**
 * Generates the code writing and reading values of each IR type, for one side of the bridge. Value types, enums and
 * collections are encoded the same way on both sides; objects travel as handles (with their exact type when the JVM
 * sends them) and lambdas as ids: the sender's lambda, which the receiving side calls back.
 *
 * [encode] returns a statement writing [value] to the writer [w]; [decode] an expression reading from [r]. [d] keeps
 * the names of nested lambdas apart.
 */
class Codec(private val model: ApiModel, private val side: Side) {

    private fun type(t: KneType) = if (side == Side.CLIENT) model.clientType(t) else t.serverName

    fun encode(t: KneType, value: String, w: String, d: Int = 0): String = when (t) {
        KneType.INT -> "$w.writeInt($value)"
        KneType.LONG -> "$w.writeLong($value)"
        KneType.DOUBLE -> "$w.writeDouble($value)"
        KneType.FLOAT -> "$w.writeFloat($value)"
        KneType.BOOLEAN -> "$w.writeBoolean($value)"
        KneType.BYTE -> "$w.writeByte($value)"
        KneType.SHORT -> "$w.writeShort($value)"
        KneType.STRING -> "$w.writeString($value)"
        KneType.BYTE_ARRAY -> "$w.writeBytes($value)"
        KneType.UNIT -> "Unit"
        is KneType.ENUM -> "$w.writeInt($value.ordinal)"
        is KneType.DATA_CLASS -> if (side == Side.CLIENT) "nja_write_${t.simpleName}($w, $value, njaS)" else "nja_write_${t.simpleName}($w, $value)"
        is KneType.OBJECT, is KneType.INTERFACE -> encodeObject(value, w, nullable = false)
        is KneType.LIST -> "writeList($w, $value) { w$d, e$d -> ${encode(t.elementType, "e$d", "w$d", d + 1)} }"
        is KneType.SET -> "writeList($w, $value) { w$d, e$d -> ${encode(t.elementType, "e$d", "w$d", d + 1)} }"
        is KneType.MAP -> "writeMap($w, $value, { w$d, k$d -> ${encode(t.keyType, "k$d", "w$d", d + 1)} }, " +
            "{ w$d, v$d -> ${encode(t.valueType, "v$d", "w$d", d + 1)} })"
        is KneType.FUNCTION -> encodeFunction(t, value, w, d, nullable = false)
        is KneType.NULLABLE -> encodeNullable(t.inner, value, w, d)
        is KneType.FLOW -> error("A Flow is only supported as a return type")
    }

    private fun encodeNullable(inner: KneType, value: String, w: String, d: Int): String = when (inner) {
        KneType.STRING, KneType.BYTE_ARRAY, is KneType.LIST, is KneType.SET, is KneType.MAP -> encode(inner, value, w, d)
        is KneType.ENUM -> "$w.writeInt($value?.ordinal ?: -1)"
        is KneType.OBJECT, is KneType.INTERFACE -> encodeObject(value, w, nullable = true)
        is KneType.FUNCTION -> encodeFunction(inner, value, w, d, nullable = true)
        else -> "writeNullable($w, $value) { w$d, e$d -> ${encode(inner, "e$d", "w$d", d + 1)} }"
    }

    private fun encodeObject(value: String, w: String, nullable: Boolean): String = when (side) {
        Side.SERVER -> "nja_writeObject($w, $value)"
        // Leased by the call (njaS) until it ends
        Side.CLIENT -> if (nullable) "$w.writeLong(($value)?.let { njaS.lease(it.njaRef) } ?: 0L)" else "$w.writeLong(njaS.lease($value.njaRef))"
    }

    private fun encodeFunction(t: KneType.FUNCTION, value: String, w: String, d: Int, nullable: Boolean): String = when (side) {
        // The JVM hands out one of its lambdas: the app calls it back through its handle
        Side.SERVER -> if (nullable) "$w.writeLong(if ($value == null) 0L else Handles.export($value))" else "$w.writeLong(Handles.export($value))"
        // The app passes one of its lambdas: a callback of this call, by id
        Side.CLIENT -> {
            val f = "f$d"
            val args = t.paramTypes.indices.joinToString(", ") { "a${d}_$it" }
            val reads = t.paramTypes.mapIndexed { i, p -> "val a${d}_$i = ${decode(p, "r$d", d + 1)}" }
            val result = if (t.returnType == KneType.UNIT) "$f($args)" else "val res$d = $f($args); ${encode(t.returnType, "res$d", "w$d", d + 1)}"
            val adapter = "njaS.register { r$d, w$d -> ${(reads + result).joinToString("; ")} }"
            if (nullable) "$w.writeLong(($value)?.let { $f -> $adapter } ?: 0L)" else "$w.writeLong(($value).let { $f -> $adapter })"
        }
    }

    fun decode(t: KneType, r: String, d: Int = 0): String = when (t) {
        KneType.INT -> "$r.readInt()"
        KneType.LONG -> "$r.readLong()"
        KneType.DOUBLE -> "$r.readDouble()"
        KneType.FLOAT -> "$r.readFloat()"
        KneType.BOOLEAN -> "$r.readBoolean()"
        KneType.BYTE -> "$r.readByte()"
        KneType.SHORT -> "$r.readShort()"
        KneType.STRING -> "$r.readString()!!"
        KneType.BYTE_ARRAY -> "$r.readBytes()!!"
        KneType.UNIT -> "Unit"
        is KneType.ENUM -> "${type(t)}.entries[$r.readInt()]"
        is KneType.DATA_CLASS -> "nja_read_${t.simpleName}($r)"
        is KneType.OBJECT, is KneType.INTERFACE -> decodeObject(t, r, nullable = false)
        is KneType.LIST -> "readList($r) { r$d -> ${decode(t.elementType, "r$d", d + 1)} }!!"
        is KneType.SET -> "readSet($r) { r$d -> ${decode(t.elementType, "r$d", d + 1)} }!!"
        is KneType.MAP -> "readMap($r, { r$d -> ${decode(t.keyType, "r$d", d + 1)} }, { r$d -> ${decode(t.valueType, "r$d", d + 1)} })!!"
        is KneType.FUNCTION -> decodeFunction(t, r, d, nullable = false)
        is KneType.NULLABLE -> decodeNullable(t.inner, r, d)
        is KneType.FLOW -> error("A Flow is only supported as a return type")
    }

    private fun decodeNullable(inner: KneType, r: String, d: Int): String = when (inner) {
        KneType.STRING -> "$r.readString()"
        KneType.BYTE_ARRAY -> "$r.readBytes()"
        is KneType.LIST -> "readList($r) { r$d -> ${decode(inner.elementType, "r$d", d + 1)} }"
        is KneType.SET -> "readSet($r) { r$d -> ${decode(inner.elementType, "r$d", d + 1)} }"
        is KneType.MAP -> "readMap($r, { r$d -> ${decode(inner.keyType, "r$d", d + 1)} }, { r$d -> ${decode(inner.valueType, "r$d", d + 1)} })"
        is KneType.ENUM -> "$r.readInt().let { if (it < 0) null else ${type(inner)}.entries[it] }"
        is KneType.OBJECT, is KneType.INTERFACE -> decodeObject(inner, r, nullable = true)
        is KneType.FUNCTION -> decodeFunction(inner, r, d, nullable = true)
        else -> "readNullable($r) { r$d -> ${decode(inner, "r$d", d + 1)} }"
    }

    private fun decodeObject(t: KneType, r: String, nullable: Boolean): String = when (side) {
        Side.SERVER -> if (nullable) "$r.readLong().let { if (it == 0L) null else Handles.get<${type(t)}>(it) }" else "Handles.get<${type(t)}>($r.readLong())"
        Side.CLIENT -> if (nullable) "(nja_readObjectOrNull($r) as ${type(t)}?)" else "(nja_readObject($r) as ${type(t)})"
    }

    private fun decodeFunction(t: KneType.FUNCTION, r: String, d: Int, nullable: Boolean): String = when (side) {
        // One of the app's lambdas: calling it calls the app back, during this call only
        Side.SERVER -> {
            val params = t.paramTypes.mapIndexed { i, p -> "a${d}_$i: ${p.serverName}" }.joinToString(", ")
            val writes = t.paramTypes.mapIndexed { i, p -> encode(p, "a${d}_$i", "w$d", d + 1) }.joinToString("; ").ifEmpty { "Unit" }
            val read = if (t.returnType == KneType.UNIT) "Unit" else decode(t.returnType, "r$d", d + 1)
            val lambda = "({ $params -> njaCtx.invokeCallback(id$d, { w$d -> $writes }) { r$d -> $read } })"
            if (nullable) "$r.readLong().let { id$d -> if (id$d == 0L) null else $lambda }"
            else "$r.readLong().let { id$d -> $lambda }"
        }
        // One of the JVM's lambdas, by handle
        Side.CLIENT -> {
            val key = ApiModel.signatureKey(t)
            if (nullable) "$r.readLong().let { if (it == 0L) null else NjaFn_$key(JvmRef(it)) }" else "NjaFn_$key(JvmRef($r.readLong()))"
        }
    }
}
