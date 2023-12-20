#!/usr/bin/env kotlin
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:2.7.1")

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.common.ScalarStyle
import java.io.File

removeMetadataFiles()
generateMetadataFiles()

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

fun File.isActionRootDir() = path.split("/").filter { it.isNotBlank() }.size == 3

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
