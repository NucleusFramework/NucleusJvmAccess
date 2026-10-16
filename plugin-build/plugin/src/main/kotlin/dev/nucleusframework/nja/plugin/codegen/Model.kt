package dev.nucleusframework.nja.plugin.codegen

import dev.nucleusframework.nja.plugin.ir.KneClass
import dev.nucleusframework.nja.plugin.ir.KneFunction
import dev.nucleusframework.nja.plugin.ir.KneInterface
import dev.nucleusframework.nja.plugin.ir.KneModule
import dev.nucleusframework.nja.plugin.ir.KneParam
import dev.nucleusframework.nja.plugin.ir.KneProperty
import dev.nucleusframework.nja.plugin.ir.KneType

/** Who a member belongs to: what the call passes before the arguments, and how the JVM side reaches it. */
sealed class Owner {
    /** An instance member of a class or interface: the receiver's handle comes first. */
    data class Instance(val fqName: String, val clientName: String) : Owner()

    /** A companion member: no receiver. */
    data class Companion(val classFq: String) : Owner()

    /** A top-level function (or an extension one: its receiver comes first, as a value). */
    data object TopLevel : Owner()
}

/** One entry point of the façade, identified by its index in the module. Both sides are generated from this table. */
sealed class Member {
    abstract val id: Int

    data class Constructor(override val id: Int, val cls: KneClass, val params: List<KneParam>) : Member()
    data class Function(override val id: Int, val owner: Owner, val fn: KneFunction) : Member()
    data class Getter(override val id: Int, val owner: Owner, val property: KneProperty) : Member()
    data class Setter(override val id: Int, val owner: Owner, val property: KneProperty) : Member()

    /** Invokes a JVM lambda the app holds (one the façade returned). */
    data class Invoke(override val id: Int, val type: KneType.FUNCTION) : Member()
}

/** The module's members, the exported types' ids and their names on the app's side. */
class ApiModel(val module: KneModule) {
    val members = ArrayList<Member>()

    /** Client (flattened) name of every exported class and interface, by FQ name. */
    val clientNames = HashMap<String, String>()
    val classesByFq = module.classes.associateBy { it.fqName }
    val interfacesByFq = module.interfaces.associateBy { it.fqName }

    /** Ids of the types a JVM object may be wrapped as, most specific first (see the server's typeOf). */
    val typeIds = LinkedHashMap<String, Int>()

    /** Function types the JVM hands out (returned lambdas), by signature key. */
    val invokers = LinkedHashMap<String, Member.Invoke>()

    init {
        module.classes.forEach { clientNames[it.fqName] = it.simpleName }
        module.interfaces.forEach { clientNames[it.fqName] = it.simpleName }

        fun add(member: (Int) -> Member) {
            members += member(members.size)
        }
        for (cls in module.classes) {
            val owner = Owner.Instance(cls.fqName, cls.simpleName)
            if (!cls.isAbstract && !cls.isSealed) {
                val params = cls.constructor.params
                val trailingDefaults = params.reversed().takeWhile { it.hasDefault }.size
                for (drop in 0..trailingDefaults) add { Member.Constructor(it, cls, params.dropLast(drop)) }
            }
            cls.methods.forEach { fn -> add { Member.Function(it, owner, fn) } }
            cls.properties.forEach { p ->
                add { Member.Getter(it, owner, p) }
                if (p.mutable) add { Member.Setter(it, owner, p) }
            }
            val companion = Owner.Companion(cls.fqName)
            cls.companionMethods.forEach { fn -> add { Member.Function(it, companion, fn) } }
            cls.companionProperties.forEach { p ->
                add { Member.Getter(it, companion, p) }
                if (p.mutable) add { Member.Setter(it, companion, p) }
            }
        }
        for (iface in module.interfaces) {
            val owner = Owner.Instance(iface.fqName, iface.simpleName)
            iface.methods.forEach { fn -> add { Member.Function(it, owner, fn) } }
            iface.properties.forEach { p ->
                add { Member.Getter(it, owner, p) }
                if (p.mutable) add { Member.Setter(it, owner, p) }
            }
        }
        module.functions.forEach { fn -> add { Member.Function(it, Owner.TopLevel, fn) } }

        // Lambdas the JVM returns (directly, or inside collections and nullable values)
        fun scanReturned(type: KneType) {
            when (type) {
                is KneType.FUNCTION -> {
                    val key = signatureKey(type)
                    if (key !in invokers) add { Member.Invoke(it, type).also { m -> invokers[key] = m } }
                }
                is KneType.NULLABLE -> scanReturned(type.inner)
                is KneType.LIST -> scanReturned(type.elementType)
                is KneType.SET -> scanReturned(type.elementType)
                is KneType.MAP -> {
                    scanReturned(type.keyType)
                    scanReturned(type.valueType)
                }
                is KneType.FLOW -> scanReturned(type.elementType)
                else -> Unit
            }
        }
        (module.classes.flatMap { it.methods + it.companionMethods } + module.interfaces.flatMap { it.methods } + module.functions)
            .forEach { scanReturned(it.returnType) }
        (module.classes.flatMap { it.properties + it.companionProperties } + module.interfaces.flatMap { it.properties })
            .forEach { scanReturned(it.type) }

        // Most derived first, so that the first `is` matching an object is its most specific exported type
        fun classDepth(cls: KneClass): Int = cls.superClass?.let { classesByFq[it] }?.let { classDepth(it) + 1 } ?: 0
        fun interfaceDepth(iface: KneInterface): Int =
            (iface.superInterfaces.mapNotNull { interfacesByFq[it] }.maxOfOrNull { interfaceDepth(it) + 1 }) ?: 0
        module.classes.sortedByDescending { classDepth(it) }.forEach { typeIds[it.fqName] = typeIds.size }
        module.interfaces.sortedByDescending { interfaceDepth(it) }.forEach { typeIds[it.fqName] = typeIds.size }
    }

    fun clientName(fq: String): String = clientNames[fq] ?: fq.substringAfterLast('.')

    /** The type as the app's generated code names it. */
    fun clientType(type: KneType): String = when (type) {
        is KneType.OBJECT -> clientName(type.fqName)
        is KneType.INTERFACE -> clientName(type.fqName)
        is KneType.NULLABLE -> if (type.inner is KneType.FUNCTION) "(${clientType(type.inner)})?" else "${clientType(type.inner)}?"
        is KneType.FUNCTION -> "(${type.paramTypes.joinToString(", ") { clientType(it) }}) -> ${clientType(type.returnType)}"
        is KneType.LIST -> "List<${clientType(type.elementType)}>"
        is KneType.SET -> "Set<${clientType(type.elementType)}>"
        is KneType.MAP -> "Map<${clientType(type.keyType)}, ${clientType(type.valueType)}>"
        is KneType.FLOW -> "kotlinx.coroutines.flow.Flow<${clientType(type.elementType)}>"
        else -> type.clientName
    }

    /** All the interfaces [fq] (a class or an interface) implements, transitively. */
    fun allInterfaces(fq: String): Set<String> {
        val result = LinkedHashSet<String>()
        fun visit(name: String) {
            classesByFq[name]?.let { cls ->
                cls.interfaces.forEach { if (result.add(it)) visit(it) }
                cls.superClass?.let { visit(it) }
            }
            interfacesByFq[name]?.superInterfaces?.forEach { if (result.add(it)) visit(it) }
        }
        visit(fq)
        return result
    }

    /** The superclass chain of [cls] within the module, nearest first. */
    fun superClasses(cls: KneClass): List<KneClass> {
        val result = ArrayList<KneClass>()
        var current = cls.superClass?.let { classesByFq[it] }
        while (current != null) {
            result += current
            current = current.superClass?.let { classesByFq[it] }
        }
        return result
    }

    companion object {
        fun signatureKey(type: KneType.FUNCTION): String =
            (type.paramTypes + type.returnType).joinToString("_") { it.serverName }
                .replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}

private val HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
)

/** A declaration name, back-quoted when it is a Kotlin keyword. */
fun esc(name: String): String = if (name in HARD_KEYWORDS) "`$name`" else name
