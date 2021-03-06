package com.github.technoir42.plugin.aarpublish

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.api.SourceKind
import com.android.utils.appendCapitalized
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.jvm.tasks.Jar
import javax.inject.Inject

@Suppress("UnstableApiUsage")
class AarPublishPlugin @Inject constructor(
    private val softwareComponentFactory: SoftwareComponentFactory
) : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("com.android.library") {
            configurePlugin(project)
        }
    }

    private fun configurePlugin(project: Project) {
        val aarPublishingExtension = project.extensions.create("aarPublishing", AarPublishingExtension::class.java)
        val libraryExtension = project.extensions.findByType(LibraryExtension::class.java)!!

        val defaultComponent = softwareComponentFactory.adhoc("android")
        project.components.add(defaultComponent)

        libraryExtension.libraryVariants.all { variant ->
            val archives = createArchivesConfigurationForVariant(project, variant, libraryExtension, aarPublishingExtension)

            val component = softwareComponentFactory.adhoc("android".appendCapitalized(variant.name))
            component.setupFromConfigurations(archives, variant.compileConfiguration, variant.runtimeConfiguration)
            project.components.add(component)

            if (variant.name == libraryExtension.defaultPublishConfig) {
                defaultComponent.setupFromConfigurations(archives, variant.compileConfiguration, variant.runtimeConfiguration)
            }
        }
    }

    private fun createArchivesConfigurationForVariant(project: Project, variant: LibraryVariant, libraryExtension: LibraryExtension, aarPublishingExtension: AarPublishingExtension): Configuration {
        val archives = project.configurations.create("${variant.name}Archives")
        project.artifacts.add(archives.name, variant.packageLibraryProvider)

        if (aarPublishingExtension.publishJavadoc) {
            val javadoc = project.tasks.register("javadoc${variant.name.capitalize()}", Javadoc::class.java) { task ->
                task.dependsOn(variant.javaCompileProvider)
                variant.getSourceFolders(SourceKind.JAVA).forEach { task.source += it }
                task.classpath += project.files(libraryExtension.bootClasspath)
                task.classpath += variant.javaCompileProvider.get().classpath
            }

            val javadocJar = project.tasks.register("package${variant.name.capitalize()}Javadoc", Jar::class.java) { task ->
                task.dependsOn(javadoc)
                task.from(javadoc.get().destinationDir)
                task.archiveClassifier.set("javadoc")
            }

            project.artifacts.add(archives.name, javadocJar)
        }

        if (aarPublishingExtension.publishSources) {
            val sourcesJar = project.tasks.register("package${variant.name.capitalize()}Sources", Jar::class.java) { task ->
                task.from(variant.getSourceFolders(SourceKind.JAVA))

                val kotlinSources = variant.sourceSets.asSequence()
                    .filterIsInstance<AndroidSourceSet>()
                    .flatMap { it.java.sourceDirectoryTrees.asSequence() }
                    .map { it.setIncludes(setOf("**/*.kt")) }
                    .toList()

                task.from(kotlinSources)

                task.archiveClassifier.set("sources")
            }

            project.artifacts.add(archives.name, sourcesJar)
        }
        return archives
    }

    private fun AdhocComponentWithVariants.setupFromConfigurations(archivesConfiguration: Configuration, compileConfiguration: Configuration, runtimeConfiguration: Configuration) {
        addVariantsFromConfiguration(archivesConfiguration) {}
        addVariantsFromConfiguration(compileConfiguration) { it.mapToMavenScope("compile") }
        addVariantsFromConfiguration(runtimeConfiguration) { it.mapToMavenScope("runtime") }
    }
}
