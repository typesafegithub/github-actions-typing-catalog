#!/usr/bin/env kotlin
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:2.7.1")
@file:DependsOn("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")

import org.eclipse.jgit.api.Git
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.common.ScalarStyle
import java.io.File

removeMetadataFiles()
generateMetadataFiles()
commitChanges()

fun removeMetadataFiles() {
    File("typings").walk()
        .filter { it.name == "metadata.yml" }
        .forEach { it.delete() }
}

fun generateMetadataFiles() {
    File("typings").walk()
        .filter { it.isActionRootDir() }
        .forEach { actionRootDir ->
            val versionsWithTypings = actionRootDir.listFiles()
                .filter { it.isDirectory }
                .map { it.name }
                .sortedBy { it.removePrefix("v") }
            writeToMetadataFile(actionRootDir, versionsWithTypings)
        }
}

fun commitChanges() {
    Git.open(File(".")).apply {
        add().addFilepattern("typings/").call()
        commit().setMessage("Update metadata").call()
    }
}

fun writeToMetadataFile(actionRootDir: File, versionsWithTypings: List<String>) {
    val structureToDump = mapOf(
        "versionsWithTypings" to versionsWithTypings
    )
    val dumpSettings = DumpSettings.builder()
        .setDefaultScalarStyle(ScalarStyle.DOUBLE_QUOTED)
        .build()
    val dump = Dump(dumpSettings)
    val yamlAsString = dump.dumpToString(structureToDump)
    actionRootDir.resolve("metadata.yml").writeText(yamlAsString)
}

fun File.isActionRootDir() = path.split("/").filter { it.isNotBlank() }.size == 3
