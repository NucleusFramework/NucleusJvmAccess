package dev.nucleusframework.nja.plugin.analysis

import dev.nucleusframework.nja.plugin.ir.ApiParam
import dev.nucleusframework.nja.plugin.ir.ApiSealedCase
import dev.nucleusframework.nja.plugin.ir.ApiType
import java.io.Serializable

/**
 * PSI-free view of a type reference. Lets type resolution run on cached per-file data,
 * so unchanged source files never need to be re-parsed.
 */
sealed interface TypeSyntax : Serializable {
    data class User(val name: String, val args: List<TypeSyntax?>) : TypeSyntax
    data class Function(val params: List<TypeSyntax?>, val returnType: TypeSyntax?) : TypeSyntax
    data class Nullable(val inner: TypeSyntax?) : TypeSyntax
    data object Unsupported : TypeSyntax
}

enum class DeclKind { ENUM, DATA_CLASS, INTERFACE, CLASS, SEALED_VALUE }

/** A primary-constructor parameter of a data class, kept unresolved until the global symbol table exists. */
data class DataParamSyntax(val name: String?, val isProperty: Boolean, val type: TypeSyntax?) : Serializable

/** A case of a sealed value hierarchy: a nested data class ([params]) or object ([isObject]). */
data class SealedCaseSyntax(val name: String, val isObject: Boolean, val params: List<DataParamSyntax>?) : Serializable

/**
 * A type declaration discovered by the prescan phase. [dataParams] is null when there is no primary constructor;
 * [cases] are those of a sealed value hierarchy.
 */
data class PrescanDecl(
    val name: String,
    val fqName: String,
    val kind: DeclKind,
    val dataParams: List<DataParamSyntax>? = null,
    val cases: List<SealedCaseSyntax>? = null,
) : Serializable

/** Resolved data class: FQ name + resolved fields. */
typealias ResolvedDataClass = Pair<String, List<ApiParam>>

/** Resolved sealed value hierarchy: FQ name + its cases. */
typealias ResolvedSealedValue = Pair<String, List<ApiSealedCase>>

/**
 * Everything the symbol table knows about a simple name. Per-file parse results record the
 * [SymbolInfo] of every name they looked up; a cached result stays valid as long as those
 * snapshots still match the current table.
 */
data class SymbolInfo(
    val enumFq: String?,
    val dataClass: ResolvedDataClass?,
    val interfaceFq: String?,
    val classFq: String?,
    val isCommonDataClass: Boolean,
    val sealedValue: ResolvedSealedValue? = null,
) : Serializable

/** Name-based lookups used to resolve [TypeSyntax] into [ApiType]. */
interface TypeScope {
    fun enumFq(name: String): String?
    fun dataClass(name: String): ResolvedDataClass?
    fun interfaceFq(name: String): String?
    fun classFq(name: String): String?
    fun sealedValue(name: String): ResolvedSealedValue? = null
}

/** Global symbol table built from the prescan results of all source files, in merge order. */
class SymbolTable private constructor(
    private val enums: Map<String, String>,
    val dataClasses: Map<String, ResolvedDataClass>,
    private val interfaces: Map<String, String>,
    private val classes: Map<String, String>,
    val commonDataClassNames: Set<String>,
    val sealedValues: Map<String, ResolvedSealedValue> = emptyMap(),
) {

    fun info(name: String): SymbolInfo? {
        val info = SymbolInfo(
            enumFq = enums[name],
            dataClass = dataClasses[name],
            interfaceFq = interfaces[name],
            classFq = classes[name],
            isCommonDataClass = name in commonDataClassNames,
            sealedValue = sealedValues[name],
        )
        return info.takeUnless { it == UNKNOWN }
    }

    companion object {
        private val UNKNOWN = SymbolInfo(null, null, null, null, false)

        /** Builds the table from `(prescan, isCommon)` pairs, one per source file, in merge order. */
        fun build(files: List<Pair<List<PrescanDecl>, Boolean>>): SymbolTable {
            val enums = LinkedHashMap<String, String>()
            val interfaces = LinkedHashMap<String, String>()
            val classes = LinkedHashMap<String, String>()
            val rawDataClasses = LinkedHashMap<String, PrescanDecl>()
            val rawSealed = LinkedHashMap<String, PrescanDecl>()
            val commonDataClassNames = LinkedHashSet<String>()

            for ((decls, isCommon) in files) {
                val dataClassesBefore = rawDataClasses.keys.toSet()
                for (decl in decls) {
                    when (decl.kind) {
                        DeclKind.ENUM -> enums[decl.name] = decl.fqName
                        DeclKind.DATA_CLASS -> rawDataClasses[decl.name] = decl
                        DeclKind.INTERFACE -> interfaces[decl.name] = decl.fqName
                        DeclKind.CLASS -> classes[decl.name] = decl.fqName
                        DeclKind.SEALED_VALUE -> rawSealed[decl.name] = decl
                    }
                }
                if (isCommon) commonDataClassNames += rawDataClasses.keys - dataClassesBefore
            }

            // Value types (data classes, sealed value hierarchies) may nest one another, recursively: resolve them all
            // assuming every candidate is one (types reference them by name), drop those with an unresolvable field,
            // and again until none drops. Those dropped are exported as regular classes
            val valueData = rawDataClasses.keys.toMutableSet()
            val valueSealed = rawSealed.keys.toMutableSet()
            val dataClasses = LinkedHashMap<String, ResolvedDataClass>()
            val sealedValues = LinkedHashMap<String, ResolvedSealedValue>()
            while (true) {
                val scope = object : TypeScope {
                    override fun enumFq(name: String) = enums[name]
                    override fun dataClass(name: String) = if (name in valueData) rawDataClasses.getValue(name).fqName to emptyList<ApiParam>() else null
                    override fun sealedValue(name: String) = if (name in valueSealed) rawSealed.getValue(name).fqName to emptyList<ApiSealedCase>() else null
                    override fun interfaceFq(name: String): String? = null
                    override fun classFq(name: String) = classes[name]
                }
                dataClasses.clear()
                sealedValues.clear()
                for (name in valueData) {
                    val decl = rawDataClasses.getValue(name)
                    resolveDataClassFields(decl.dataParams, scope)?.let { dataClasses[name] = decl.fqName to it }
                }
                for (name in valueSealed) {
                    val decl = rawSealed.getValue(name)
                    resolveCases(decl, scope)?.let { sealedValues[name] = decl.fqName to it }
                }
                val dropped = valueData.retainAll(dataClasses.keys) or valueSealed.retainAll(sealedValues.keys)
                if (!dropped) break
            }
            for ((name, decl) in rawDataClasses) {
                if (name !in dataClasses) classes[name] = decl.fqName
            }
            for ((name, decl) in rawSealed) {
                if (name !in sealedValues) interfaces[name] = decl.fqName
            }

            return SymbolTable(enums, dataClasses, interfaces, classes, commonDataClassNames, sealedValues)
        }

        private fun resolveCases(decl: PrescanDecl, scope: TypeScope): List<ApiSealedCase>? =
            decl.cases?.map { case ->
                val fields = if (case.isObject) emptyList() else resolveDataClassFields(case.params, scope) ?: return null
                ApiSealedCase(case.name, "${decl.fqName}.${case.name}", case.isObject, fields)
            }?.ifEmpty { null }

        private fun resolveDataClassFields(params: List<DataParamSyntax>?, scope: TypeScope): List<ApiParam>? {
            params ?: return null
            val fields = mutableListOf<ApiParam>()
            for (param in params) {
                if (!param.isProperty) continue
                val name = param.name ?: return null
                val type = TypeResolver.resolve(param.type, scope) ?: return null
                fields.add(ApiParam(name, type))
            }
            return fields.ifEmpty { null }
        }
    }
}

/** [TypeScope] over a [SymbolTable] that records every name it is asked about. */
class RecordingScope(private val table: SymbolTable) : TypeScope {

    private val recorded = LinkedHashMap<String, SymbolInfo?>()

    /** Snapshot of every looked-up name and what it resolved to. */
    val lookups: Map<String, SymbolInfo?> get() = recorded

    fun info(name: String): SymbolInfo? =
        if (name in recorded) recorded[name] else table.info(name).also { recorded[name] = it }

    override fun enumFq(name: String) = info(name)?.enumFq
    override fun dataClass(name: String) = info(name)?.dataClass
    override fun interfaceFq(name: String) = info(name)?.interfaceFq
    override fun classFq(name: String) = info(name)?.classFq
    override fun sealedValue(name: String) = info(name)?.sealedValue
    fun isCommonDataClass(name: String) = info(name)?.isCommonDataClass == true
}

object TypeResolver {

    private val CALLBACK_PRIMITIVES = setOf(
        ApiType.INT, ApiType.LONG, ApiType.DOUBLE, ApiType.FLOAT,
        ApiType.BOOLEAN, ApiType.BYTE, ApiType.SHORT, ApiType.STRING,
    )

    fun resolve(type: TypeSyntax?, scope: TypeScope): ApiType? {
        if (type is TypeSyntax.Nullable) {
            val inner = resolveElement(type.inner, scope) ?: return null
            return if (inner == ApiType.UNIT || inner is ApiType.NULLABLE) inner else ApiType.NULLABLE(inner)
        }
        return resolveElement(type, scope)
    }

    private fun resolveElement(type: TypeSyntax?, scope: TypeScope): ApiType? = when (type) {
        is TypeSyntax.Function -> resolveFunction(type, scope)
        is TypeSyntax.User -> resolveUser(type, scope)
        else -> null
    }

    private fun resolveFunction(type: TypeSyntax.Function, scope: TypeScope): ApiType? {
        val paramTypes = type.params.mapNotNull { resolve(it, scope) }
        val returnType = resolve(type.returnType, scope) ?: ApiType.UNIT
        fun ok(t: ApiType) = t in CALLBACK_PRIMITIVES || t in ApiType.ARRAYS || t is ApiType.DATA_CLASS || t is ApiType.SEALED_VALUE ||
            t is ApiType.ENUM || t is ApiType.OBJECT || t is ApiType.INTERFACE ||
            t is ApiType.LIST || t is ApiType.SET || t is ApiType.MAP
        if (paramTypes.any { !ok(it) } || !(ok(returnType) || returnType == ApiType.UNIT)) return null
        return ApiType.FUNCTION(paramTypes, returnType)
    }

    private fun resolveUser(type: TypeSyntax.User, scope: TypeScope): ApiType? {
        val name = type.name
        val typeArgs = type.args.mapNotNull { resolve(it, scope) }
        return when (name) {
            "Int" -> ApiType.INT; "Long" -> ApiType.LONG; "Double" -> ApiType.DOUBLE; "Float" -> ApiType.FLOAT
            "Boolean" -> ApiType.BOOLEAN; "Byte" -> ApiType.BYTE; "Short" -> ApiType.SHORT
            "String" -> ApiType.STRING; "ByteArray" -> ApiType.BYTE_ARRAY; "Unit" -> ApiType.UNIT
            "IntArray" -> ApiType.INT_ARRAY; "LongArray" -> ApiType.LONG_ARRAY
            "FloatArray" -> ApiType.FLOAT_ARRAY; "DoubleArray" -> ApiType.DOUBLE_ARRAY
            "List", "MutableList" -> if (typeArgs.size == 1) ApiType.LIST(typeArgs[0]) else null
            "Set", "MutableSet" -> if (typeArgs.size == 1) ApiType.SET(typeArgs[0]) else null
            "Map", "MutableMap" -> if (typeArgs.size == 2) ApiType.MAP(typeArgs[0], typeArgs[1]) else null
            "Flow" -> if (typeArgs.size == 1) ApiType.FLOW(typeArgs[0]) else null
            else -> scope.enumFq(name)?.let { ApiType.ENUM(it, name) }
                ?: scope.dataClass(name)?.let { ApiType.DATA_CLASS(it.first, name, it.second) }
                ?: scope.sealedValue(name)?.let { ApiType.SEALED_VALUE(it.first, name) }
                ?: scope.interfaceFq(name)?.let { ApiType.INTERFACE(it, name) }
                ?: scope.classFq(name)?.let { ApiType.OBJECT(it, name) }
        }
    }
}
