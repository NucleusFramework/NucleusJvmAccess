package dev.nucleusframework.nja

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The JVM (HotSpot, from jdk-mobile) in the app's process: libnja.so holds it and the bridge to ART (see
 * nja_android.c). Its runtime (java.home) and the façades' class path come with the app as assets, extracted once per
 * version into the app's files.
 */
internal object AndroidJvm {
    private const val ASSETS = "nja"
    private const val LINK_CLASS = "dev/nucleusframework/nja/server/NativeLink"

    @SuppressLint("StaticFieldLeak")
    @Volatile var context: Context? = null

    @JvmStatic external fun start(javaHome: String, classPath: String, linkClass: String, options: Array<String>): String?

    @JvmStatic external fun transact(message: ByteArray, length: Int): ByteArray

    fun start() {
        val context = context ?: error("NJA: no Android context (NjaInitializer didn't run)")
        val assets = context.assets
        val version = assets.open("$ASSETS/runtime.id").use { it.readBytes().decodeToString().trim() }
        val root = File(context.noBackupFilesDir, "nja/$version")
        if (!File(root, ".complete").exists()) {
            root.parentFile?.listFiles()?.forEach { if (it != root) it.deleteRecursively() }
            root.deleteRecursively()
            root.mkdirs()
            ZipInputStream(assets.open("$ASSETS/runtime.zip").buffered(1 shl 16)).use { zip ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = File(root, entry.name)
                    check(file.canonicalPath.startsWith(root.canonicalPath)) { "NJA: bad runtime entry ${entry.name}" }
                    if (entry.isDirectory) {
                        file.mkdirs()
                        continue
                    }
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            File(root, ".complete").createNewFile()
        }
        val classPath = File(root, "classpath").listFiles { f -> f.name.endsWith(".jar") }.orEmpty()
            .sortedBy { it.name }.joinToString(":") { it.absolutePath }
        val options = File(root, "jvm.options").takeIf { it.exists() }?.readLines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
        // java.home is per ABI (some of its generated classes depend on the architecture)
        val javaHome = Build.SUPPORTED_ABIS.map { File(root, "jdk/$it") }.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("NJA: the app has no JVM for this device's ABIs (${Build.SUPPORTED_ABIS.joinToString()})")
        System.loadLibrary("nja")
        // -Xrs: ART owns SIGQUIT and the other signals HotSpot would take (its SIGSEGV handler chains behind ART's)
        val error = start(javaHome.absolutePath, classPath, LINK_CLASS, (listOf("-Xrs") + options).toTypedArray())
        if (error != null) throw IllegalStateException("NJA: the JVM didn't start: $error")
    }
}
