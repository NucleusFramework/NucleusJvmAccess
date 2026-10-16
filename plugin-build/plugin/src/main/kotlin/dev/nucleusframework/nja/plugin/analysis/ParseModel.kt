package dev.nucleusframework.nja.plugin.analysis

import dev.nucleusframework.nja.plugin.ir.KneClass
import dev.nucleusframework.nja.plugin.ir.KneDataClass
import dev.nucleusframework.nja.plugin.ir.KneEnum
import dev.nucleusframework.nja.plugin.ir.KneFunction
import dev.nucleusframework.nja.plugin.ir.KneInterface
import java.io.Serializable
import java.security.MessageDigest

/** IR declarations contributed by a single source file, merged in source order into the module. */
data class FileFragment(
    val packageName: String,
    val classes: List<KneClass>,
    val dataClasses: List<KneDataClass>,
    val enums: List<KneEnum>,
    /** Keyed by function name, or `Receiver.name` for extension functions. */
    val functions: List<Pair<String, KneFunction>>,
    val interfaces: List<KneInterface>,
) : Serializable
data class CachedSourceFile(
    val contentHash: String,
    val isCommon: Boolean,
    val prescan: List<PrescanDecl>,
    val fragment: FileFragment,
    val lookups: Map<String, SymbolInfo?>,
) : Serializable

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
