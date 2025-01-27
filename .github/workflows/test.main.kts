#!/usr/bin/env kotlin
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.2.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp:3.1.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v4")
@file:OptIn(ExperimentalKotlinLogicStep::class)
@file:Suppress("UNCHECKED_CAST")

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.PullRequest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.dsl.workflow
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.util.Collections.emptySet
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

workflow(
    name = "Test",
    on = listOf(
        Push(branches = listOf("main")),
        PullRequest(),
    ),
    sourceFile = __FILE__,
) {
    job(
        id = "build_kotlin_scripts",
        name = "Build Kotlin scripts",
        runsOn = UbuntuLatest,
    ) {
        uses(action = Checkout())
        run(
            command = """
            find -name *.main.kts -print0 | while read -d ${'$'}'\0' file
            do
                echo "Compiling ${'$'}file..."
                kotlinc -Werror -Xallow-any-scripts-in-source-roots -Xuse-fir-lt=false "${'$'}file"
            done
            """.trimIndent()
        )
    }

    job(
        id = "check_inputs_and_outputs",
        name = "Check inputs and outputs against action manifests",
        runsOn = UbuntuLatest,
    ) {
        uses(action = Checkout())
        run(name = "Check for all actions") {
            checkInputAndOutputNames()
        }
    }

    job(
        id = "workflows_consistency_check",
        name = "Run consistency check on all GitHub workflows",
        runsOn = UbuntuLatest,
    ) {
        uses(action = Checkout())
        run(command = "cd .github/workflows")
        run(
            name = "Regenerate all workflow YAMLs",
            command = """
            find -name "*.main.kts" -print0 | while read -d ${'$'}'\0' file
            do
                if [ -x "${'$'}file" ]; then
                    echo "Regenerating ${'$'}file..."
                    (${'$'}file)
                fi
            done
            """.trimIndent(),
        )
        run(
            name = "Check if some file is different after regeneration",
            command = "git diff --exit-code .",
        )
    }
}

private data class ActionCoords(
    val owner: String,
    val name: String,
    val version: String,
    val path: String? = null,
    val pathToTypings: String,
)

private fun checkInputAndOutputNames() {
    val actions = Files.walk(Path("typings"))
        .filter { it.name in setOf("action-types.yml", "action-types.yaml") }
        .map {
            val (_, owner, name, version, pathAndYaml) = it.invariantSeparatorsPathString.split("/", limit = 5)
            val path = if ("/" in pathAndYaml) pathAndYaml.substringBeforeLast("/") else null
            ActionCoords(
                owner = owner,
                name = name,
                version = version,
                path = path,
                pathToTypings = it.invariantSeparatorsPathString,
            )
        }

    var shouldFail = false

    for (action in actions) {
        println("➡\uFE0F For ${action.owner}/${listOfNotNull(action.name, action.path).joinToString("/")}@${action.version}:")
        val typings = loadTypings(path = action.pathToTypings)
        val typingsInputs = if ("inputs" in typings) (typings["inputs"] as Map<String, Any>).keys else emptySet()
        val typingsOutputs = if ("outputs" in typings) (typings["outputs"] as Map<String, Any>).keys else emptySet()
        val manifest = fetchManifest(action)

        if (manifest == null) {
            println("\uD83D\uDD34 No manifest found!")
            shouldFail = true
            continue
        }

        val manifestInputs = if ("inputs" in manifest) (manifest["inputs"] as Map<String, Any>).keys else emptySet()
        val manifestOutputs = if ("outputs" in manifest) (manifest["outputs"] as Map<String, Any>).keys else emptySet()

        if (typingsInputs != manifestInputs || typingsOutputs != manifestOutputs) {
            println("\uD83D\uDD34 Something is wrong with the typings!")
            println("Typings inputs: $typingsInputs")
            println("Manifest inputs: $manifestInputs")
            println("Extra inputs in typings: ${typingsInputs - manifestInputs}")
            println("Extra inputs in manifest: ${manifestInputs - typingsInputs}")
            println("Typings outputs: $typingsOutputs")
            println("Manifest outputs: $manifestOutputs")
            println("Extra outputs in typings: ${typingsOutputs - manifestOutputs}")
            println("Extra outputs in manifest: ${manifestOutputs - typingsOutputs}")
            shouldFail = true
        }
        println("\uD83D\uDFE2 OK!")
    }

    require(shouldFail == false) {
        "This is the end of processing, and something doesn't match - see the logs!"
    }
}

private fun loadTypings(path: String): Map<String, Any> =
    Load().loadOne(File(path).readText()) as Map<String, Any>

private fun fetchManifest(action: ActionCoords): Map<String, Any>? {
    val list = listOf(action.actionYmlUrl, action.actionYamlUrl)

    return list
        .firstNotNullOfOrNull { url ->
            try {
                URI(url).toURL().readText()
            } catch (e: IOException) {
                null
            }
        }?.let { Load().loadOne(string = it) } as Map<String, Any>?
}

private val ActionCoords.actionYmlUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$version$subName/action.yml"

private val ActionCoords.actionYamlUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$version$subName/action.yaml"

/**
 * For most actions, it's empty.
 * For actions that aren't executed from the root of the repo, it returns the path relative to the repo root where the
 * action lives, starting with a slash.
 */
private val ActionCoords.subName: String get() = path?.let { "/$path" } ?: ""
