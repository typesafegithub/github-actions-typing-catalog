#!/usr/bin/env kotlin
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.0.1")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:3.0.3")
@file:DependsOn("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v4")
@file:DependsOn("actions:setup-java:v4")

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.actions.SetupJava
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.RunnerType
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.domain.triggers.Push
import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.common.ScalarStyle
import java.io.File
import org.eclipse.jgit.api.Git

workflow(
    name = "Update metadata",
    sourceFile = __FILE__,
    on = listOf(Push(branches = listOf("main"))),
) {
    job(
        id = "generate",
        runsOn = RunnerType.UbuntuLatest,
    ) {
        uses(action = Checkout())
        uses(action = SetupJava(
            distribution = SetupJava.Distribution.Zulu,
            javaVersion = "17",
        ))
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
            commitChanges(github.sha)
        }
        run(
            name = "Push new commit",
            command = "git push",
        )
    }
}

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

fun commitChanges(sha: String) {
    Git.open(File(".")).apply {
        add().addFilepattern("typings/").call()

        if (status().call().hasUncommittedChanges()) {
            commit().setMessage("Update metadata for commit $sha").call()
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
