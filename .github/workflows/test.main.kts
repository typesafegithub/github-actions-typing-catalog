#!/usr/bin/env kotlin
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.2.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp:3.1.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v4")
@file:OptIn(ExperimentalKotlinLogicStep::class)

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.PullRequest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.dsl.workflow
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path
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
    Files.walk(Path("typings"))
        .filter { it.name in setOf("action-types.yml", "action-types.yaml") }
        .map {
            val (_, owner, name, version, pathAndYaml) = it.toString().split("/", limit = 5)
            val path = if ("/" in pathAndYaml) pathAndYaml.substringBeforeLast("/") else null
            ActionCoords(
                owner = owner,
                name = name,
                version = version,
                path = path,
                pathToTypings = it.toString(),
            )
        }
        .forEach {
            println("➡\uFE0F For ${it.owner}/${listOfNotNull(it.name, it.path).joinToString("/")}@${it.version}:")
            val typings = Load().loadOne(File(it.pathToTypings).readText())
            println("Typings: $typings")
        }
}
