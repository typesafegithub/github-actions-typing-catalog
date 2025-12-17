#!/usr/bin/env kotlin
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.7.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:4.0.1")
@file:DependsOn("org.eclipse.jgit:org.eclipse.jgit:7.5.0.202512021534-r")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v6")
@file:DependsOn("actions:setup-java:v5")

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.actions.actions.SetupJava
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.RunnerType
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.yaml.CheckoutActionVersionSource
import io.github.typesafegithub.workflows.yaml.DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG
import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.common.ScalarStyle
import java.io.File
import org.eclipse.jgit.api.Git

workflow(
    name = "Update metadata",
    consistencyCheckJobConfig = DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG.copy(
        checkoutActionVersion = CheckoutActionVersionSource.InferFromClasspath(),
    ),
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
    val dumpSettings = DumpSettings(
        defaultScalarStyle = ScalarStyle.DOUBLE_QUOTED,
    )
    val dump = Dump(dumpSettings)
    val yamlAsString = dump.dumpToString(structureToDump)
    actionRootDir.resolve("metadata.yml").writeText(yamlAsString)
}

fun File.isActionRootDir() = path.split("/").filter { it.isNotBlank() }.size == 3
