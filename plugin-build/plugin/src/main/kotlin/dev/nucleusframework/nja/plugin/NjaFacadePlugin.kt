package dev.nucleusframework.nja.plugin

import dev.nucleusframework.nja.plugin.tasks.GenerateNjaBridgesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

abstract class NjaFacadeExtension {
    /** The façade's name: its module in the JVM. Default: the project's name. */
    abstract val libName: Property<String>

    /** The package of the app's proxies. Default: the façade's first package. */
    abstract val clientPackage: Property<String>
}

/**
 * Applied to a Kotlin/JVM project (the façade, using the Java libraries): exports its public API to Kotlin
 * Multiplatform consumers. The JVM's dispatcher is compiled into the façade; the proxies are published to consumers.
 */
class NjaFacadePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("njaFacade", NjaFacadeExtension::class.java)
        extension.libName.convention(project.name)
        extension.clientPackage.convention("")

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
            val psiScope = project.configurations.dependencyScope("njaPsi").get()
            val psiConfiguration = project.configurations.resolvable("njaPsiClasspath") { extendsFrom(psiScope) }
            project.dependencies.add(psiScope.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:${kotlin.coreLibrariesVersion}")
            project.dependencies.add("implementation", "dev.nucleusframework.nja:nja-server:$NJA_VERSION")

            val main = kotlin.sourceSets.getByName("main")
            // The façade's own sources (not what this plugin generates into the same source set)
            val facadeSources = project.files(project.provider { main.kotlin.srcDirs.filter { !it.path.contains("generated/nja") } })
            val generate = project.tasks.register("generateNjaBridges", GenerateNjaBridgesTask::class.java) {
                sources.from(facadeSources)
                psiClasspath.from(psiConfiguration)
                libName.set(extension.libName)
                clientPackage.set(extension.clientPackage)
                serverDir.set(project.layout.buildDirectory.dir("generated/nja/server"))
                clientDir.set(project.layout.buildDirectory.dir("generated/nja/client"))
            }
            main.kotlin.srcDir(generate.flatMap { it.serverDir.dir("kotlin") })
            project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java).getByName("main")
                .resources.srcDir(generate.flatMap { it.serverDir.dir("resources") })

            // The proxies, for the consumers (see NjaPlugin)
            project.configurations.consumable(NJA_CLIENT_SOURCES) {
                attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, NJA_CLIENT_SOURCES_USAGE))
                outgoing.artifact(generate.flatMap { it.clientDir }) { builtBy(generate) }
            }
        }
    }

    companion object {
        const val NJA_CLIENT_SOURCES = "njaClientSourcesElements"
        const val NJA_CLIENT_SOURCES_USAGE = "nja-client-sources"
    }
}
