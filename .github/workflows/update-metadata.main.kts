#!/usr/bin/env kotlin
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:1.14.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:2.7.5")
@file:DependsOn("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")

import io.github.typesafegithub.workflows.actions.actions.CheckoutV4
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.RunnerType
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.yaml.writeToFile
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.common.ScalarStyle
import java.io.File
import org.eclipse.jgit.api.Git

workflow(
    name = "Update metadata",
    sourceFile = __FILE__.toPath(),
    on = listOf(Push(branches = listOf("main"))),
) {
    job(
        id = "generate",
        runsOn = RunnerType.UbuntuLatest,
    ) {
        uses(action = CheckoutV4())
        run(
            name = "Configure git",
            command = """
                git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
                git config user.name "github-actions[bot]"
            """.trimIndent()
        )
        @OptIn(ExperimentalKotlinLogicStep::class)
        run(name = "Run generation logic") {
            removeMetadataFiles()
            generateMetadataFiles()
            commitChanges()
        }
        run(
            name = "Push new commit",
            command = "git push",
        )
    }
}.writeToFile()

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

        if (status().call().hasUncommittedChanges()) {
            commit().setMessage("Update metadata").call()
        }
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
