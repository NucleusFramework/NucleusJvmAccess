package dev.nucleusframework.nja.plugin.tasks

import dev.nucleusframework.nja.plugin.analysis.PsiSourceParser
import dev.nucleusframework.nja.plugin.codegen.ApiModel
import dev.nucleusframework.nja.plugin.codegen.ClientGenerator
import dev.nucleusframework.nja.plugin.codegen.ServerGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Parses the façade's sources (Kotlin PSI) and generates both sides of the bridge from the same member table: the
 * JVM's dispatcher ([serverDir], compiled with the façade) and the app's proxies ([clientDir], the consumers'
 * common sources).
 */
@CacheableTask
abstract class GenerateNjaBridgesTask : DefaultTask() {
    @get:Inject abstract val workerExecutor: WorkerExecutor
    @get:Inject abstract val objects: ObjectFactory

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Classpath abstract val psiClasspath: ConfigurableFileCollection
    @get:Input abstract val libName: Property<String>

    /** The proxies' package; empty: the façade's first package. */
    @get:Input abstract val clientPackage: Property<String>

    @get:OutputDirectory abstract val serverDir: DirectoryProperty
    @get:OutputDirectory abstract val clientDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val files = sources.files.flatMap { root ->
            when {
                root.isFile -> listOf(root)
                root.isDirectory -> root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
                else -> emptyList()
            }
        }.filter { it.extension == "kt" }.distinct()
        val pluginJar = GenerateWork::class.java.protectionDomain?.codeSource?.location?.let { File(it.toURI()) }
        val queue = workerExecutor.classLoaderIsolation {
            classpath.from(psiClasspath)
            pluginJar?.let { classpath.from(it) }
        }
        queue.submit(GenerateWork::class.java) {
            sourceFiles.from(files)
            libName.set(this@GenerateNjaBridgesTask.libName)
            clientPackage.set(this@GenerateNjaBridgesTask.clientPackage)
            serverDir.set(this@GenerateNjaBridgesTask.serverDir)
            clientDir.set(this@GenerateNjaBridgesTask.clientDir)
        }
    }

    interface Params : WorkParameters {
        val sourceFiles: ConfigurableFileCollection
        val libName: Property<String>
        val clientPackage: Property<String>
        val serverDir: DirectoryProperty
        val clientDir: DirectoryProperty
    }

    abstract class GenerateWork : WorkAction<Params> {
        private val logger = Logging.getLogger(GenerateWork::class.java)

        override fun execute() {
            val libName = parameters.libName.get()
            val module = PsiSourceParser().parse(parameters.sourceFiles.files, libName, emptyList())
            val model = ApiModel(module)
            val clientPackage = parameters.clientPackage.get().ifEmpty { module.packages.firstOrNull().orEmpty() }
            write(parameters.serverDir.get().asFile, ServerGenerator(model, libName).generate())
            // The packages, for a JVM consumer's check: its class path holds both the façade and the proxies
            val packages = "client=$clientPackage\nfacade=${module.packages.joinToString(",")}\n"
            write(
                parameters.clientDir.get().asFile,
                ClientGenerator(model, libName, clientPackage).generate() + (PACKAGES_FILE to packages),
            )
            logger.lifecycle(
                "nja: $libName: ${module.classes.size} classes, ${module.interfaces.size} interfaces, " +
                    "${module.dataClasses.size} data classes, ${module.enums.size} enums, ${model.members.size} members",
            )
        }

        private fun write(root: File, files: Map<String, String>) {
            root.deleteRecursively()
            for ((path, content) in files) {
                val file = File(root, path)
                file.parentFile.mkdirs()
                file.writeText(content)
            }
        }
    }
}

/** The client sources' packages file: `client=<package>`, `facade=<package>,...`. */
internal const val PACKAGES_FILE = "nja-packages.properties"
