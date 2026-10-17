package dev.nucleusframework.nja.plugin.ir

import java.io.Serializable

data class ApiModule(
    val libName: String,
    val packages: Set<String>,
    val classes: List<ApiClass>,
    val dataClasses: List<ApiDataClass>,
    val enums: List<ApiEnum>,
    val functions: List<ApiFunction>,
    val interfaces: List<ApiInterface> = emptyList(),
    val sealedValues: List<ApiSealedValue> = emptyList(),
) : Serializable

/**
 * A sealed value hierarchy: a sealed interface or class whose cases, nested in it, are all data classes or objects.
 * It travels by value (its case, then the case's fields), copied verbatim to the app like a data class.
 */
data class ApiSealedValue(
    val simpleName: String,
    val fqName: String,
    val cases: List<ApiSealedCase>,
    val source: ApiSourceDecl? = null,
) : Serializable

/** A case of an [ApiSealedValue]: [name] nested in it, an object or a data class with [fields]. */
data class ApiSealedCase(
    val name: String,
    val fqName: String,
    val isObject: Boolean,
    val fields: List<ApiParam>,
) : Serializable

data class ApiDataClass(
    val simpleName: String,
    val fqName: String,
    val fields: List<ApiParam>,
    val isCommon: Boolean = false,
    val source: ApiSourceDecl? = null,
) : Serializable

/**
 * Verbatim source of a value-type declaration (enum / data class), copied as-is to the JVM side
 * so that the JVM variant matches the native one (body members, defaults, KDoc, annotations).
 */
data class ApiSourceDecl(
    val text: String,
    val imports: List<String> = emptyList(),
) : Serializable

data class ApiInterface(
    val simpleName: String,
    val fqName: String,
    val methods: List<ApiFunction>,
    val properties: List<ApiProperty>,
    val superInterfaces: List<String> = emptyList(),
    val isCommon: Boolean = false,
) : Serializable

data class ApiClass(
    val simpleName: String,
    val fqName: String,
    val constructor: ApiConstructor,
    val methods: List<ApiFunction>,
    val properties: List<ApiProperty>,
    val companionMethods: List<ApiFunction> = emptyList(),
    val companionProperties: List<ApiProperty> = emptyList(),
    val isOpen: Boolean = false,
    val isAbstract: Boolean = false,
    val isSealed: Boolean = false,
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val sealedSubclasses: List<String> = emptyList(),
    val isCommon: Boolean = false,
    /** Whether the app may construct it: a public constructor whose parameters all cross the bridge. */
    val isConstructible: Boolean = true,
) : Serializable

data class ApiEnumEntry(
    val name: String,
    val constructorArgs: List<String> = emptyList(),
) : Serializable

data class ApiEnum(
    val simpleName: String,
    val fqName: String,
    val entries: List<ApiEnumEntry>,
    val constructorParams: List<ApiParam> = emptyList(),
    val source: ApiSourceDecl? = null,
) : Serializable

data class ApiConstructor(
    val params: List<ApiParam>,
) : Serializable

data class ApiFunction(
    val name: String,
    val params: List<ApiParam>,
    val returnType: ApiType,
    val isSuspend: Boolean = false,
    val isExtension: Boolean = false,
    val receiverType: ApiType? = null,
    val isOverride: Boolean = false,
) : Serializable

data class ApiProperty(
    val name: String,
    val type: ApiType,
    val mutable: Boolean,
    val isOverride: Boolean = false,
) : Serializable

data class ApiParam(
    val name: String,
    val type: ApiType,
    val hasDefault: Boolean = false,
) : Serializable

sealed class ApiType : Serializable {
    data object INT : ApiType()
    data object LONG : ApiType()
    data object DOUBLE : ApiType()
    data object FLOAT : ApiType()
    data object BOOLEAN : ApiType()
    data object BYTE : ApiType()
    data object SHORT : ApiType()
    data object STRING : ApiType()
    data object UNIT : ApiType()
    data class OBJECT(val fqName: String, val simpleName: String) : ApiType()
    data class INTERFACE(val fqName: String, val simpleName: String) : ApiType()
    data class ENUM(val fqName: String, val simpleName: String) : ApiType()
    data class NULLABLE(val inner: ApiType) : ApiType()
    data class FUNCTION(val paramTypes: List<ApiType>, val returnType: ApiType) : ApiType()
    data class DATA_CLASS(val fqName: String, val simpleName: String, val fields: List<ApiParam>) : ApiType()
    data class SEALED_VALUE(val fqName: String, val simpleName: String) : ApiType()
    data object BYTE_ARRAY : ApiType()
    data object INT_ARRAY : ApiType()
    data object LONG_ARRAY : ApiType()
    data object FLOAT_ARRAY : ApiType()
    data object DOUBLE_ARRAY : ApiType()
    data class LIST(val elementType: ApiType) : ApiType()
    data class SET(val elementType: ApiType) : ApiType()
    data class MAP(val keyType: ApiType, val valueType: ApiType) : ApiType()
    data class FLOW(val elementType: ApiType) : ApiType()

    companion object {
        /** The arrays, encoded as a count and their elements: nullable like strings, with no wrapper. */
        val ARRAYS: Set<ApiType> get() = setOf(BYTE_ARRAY, INT_ARRAY, LONG_ARRAY, FLOAT_ARRAY, DOUBLE_ARRAY)
    }

    /** Preserves singleton identity when deserialized from the incremental cache. */
    protected fun readResolve(): Any = when (this) {
        INT -> INT
        LONG -> LONG
        DOUBLE -> DOUBLE
        FLOAT -> FLOAT
        BOOLEAN -> BOOLEAN
        BYTE -> BYTE
        SHORT -> SHORT
        STRING -> STRING
        UNIT -> UNIT
        BYTE_ARRAY -> BYTE_ARRAY
        INT_ARRAY -> INT_ARRAY
        LONG_ARRAY -> LONG_ARRAY
        FLOAT_ARRAY -> FLOAT_ARRAY
        DOUBLE_ARRAY -> DOUBLE_ARRAY
        else -> this
    }

    /** The type as the app sees it: declarations by simple name (proxies and value types share one package). */
    val clientName: String
        get() = when (this) {
            INT -> "Int"
            LONG -> "Long"
            DOUBLE -> "Double"
            FLOAT -> "Float"
            BOOLEAN -> "Boolean"
            BYTE -> "Byte"
            SHORT -> "Short"
            STRING -> "String"
            UNIT -> "Unit"
            is OBJECT -> simpleName
            is INTERFACE -> simpleName
            is ENUM -> simpleName
            is NULLABLE -> if (inner is FUNCTION) "(${inner.clientName})?" else "${inner.clientName}?"
            is FUNCTION -> "(${paramTypes.joinToString(", ") { it.clientName }}) -> ${returnType.clientName}"
            is DATA_CLASS -> simpleName
            is SEALED_VALUE -> simpleName
            BYTE_ARRAY -> "ByteArray"
            INT_ARRAY -> "IntArray"
            LONG_ARRAY -> "LongArray"
            FLOAT_ARRAY -> "FloatArray"
            DOUBLE_ARRAY -> "DoubleArray"
            is LIST -> "List<${elementType.clientName}>"
            is SET -> "Set<${elementType.clientName}>"
            is MAP -> "Map<${keyType.clientName}, ${valueType.clientName}>"
            is FLOW -> "kotlinx.coroutines.flow.Flow<${elementType.clientName}>"
        }

    /** The type as the façade declares it: declarations by fully qualified name. */
    val serverName: String
        get() = when (this) {
            is OBJECT -> fqName
            is INTERFACE -> fqName
            is ENUM -> fqName
            is DATA_CLASS -> fqName
            is SEALED_VALUE -> fqName
            is NULLABLE -> if (inner is FUNCTION) "(${inner.serverName})?" else "${inner.serverName}?"
            is FUNCTION -> "(${paramTypes.joinToString(", ") { it.serverName }}) -> ${returnType.serverName}"
            is LIST -> "List<${elementType.serverName}>"
            is SET -> "Set<${elementType.serverName}>"
            is MAP -> "Map<${keyType.serverName}, ${valueType.serverName}>"
            is FLOW -> "kotlinx.coroutines.flow.Flow<${elementType.serverName}>"
            else -> clientName
        }
}
