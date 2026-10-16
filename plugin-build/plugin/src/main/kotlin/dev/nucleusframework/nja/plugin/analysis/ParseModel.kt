package dev.nucleusframework.nja.plugin.analysis

import dev.nucleusframework.nja.plugin.ir.ApiClass
import dev.nucleusframework.nja.plugin.ir.ApiDataClass
import dev.nucleusframework.nja.plugin.ir.ApiEnum
import dev.nucleusframework.nja.plugin.ir.ApiFunction
import dev.nucleusframework.nja.plugin.ir.ApiInterface
import java.io.Serializable
import java.security.MessageDigest

/** IR declarations contributed by a single source file, merged in source order into the module. */
data class FileFragment(
    val packageName: String,
    val classes: List<ApiClass>,
    val dataClasses: List<ApiDataClass>,
    val enums: List<ApiEnum>,
    /** Keyed by function name, or `Receiver.name` for extension functions. */
    val functions: List<Pair<String, ApiFunction>>,
    val interfaces: List<ApiInterface>,
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
