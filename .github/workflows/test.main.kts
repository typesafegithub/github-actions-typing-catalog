#!/usr/bin/env kotlin
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:3.5.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp:3.1.1")
@file:DependsOn("io.github.optimumcode:json-schema-validator-jvm:0.5.2")
@file:DependsOn("com.github.sya-ri:kgit:1.1.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v4")
@file:OptIn(ExperimentalKotlinLogicStep::class)
@file:Suppress("UNCHECKED_CAST")

import com.github.syari.kgit.KGit
import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.Cron
import io.github.typesafegithub.workflows.domain.triggers.PullRequest
import io.github.typesafegithub.workflows.domain.triggers.Push
import io.github.typesafegithub.workflows.domain.triggers.Schedule
import io.github.typesafegithub.workflows.dsl.workflow
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.OrTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections.emptySet
import java.util.stream.Stream
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

workflow(
    name = "Test",
    on = listOf(
        Push(branches = listOf("main")),
        PullRequest(),
        Schedule(triggers = listOf(Cron(hour = "6", minute = "0"))),
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
        id = "validate_typings",
        name = "Validate typings",
        runsOn = UbuntuLatest,
    ) {
        uses(action = Checkout())
        run(
            name = "Check for actions",
        ) {
            validateTypings(github.sha, github.base_ref?.ifEmpty { null })
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

private fun validateTypings(sha: String, baseRef: String?) {
    val typingsSchema = JsonSchema.fromDefinition(
        URI.create("https://raw.githubusercontent.com/typesafegithub/github-actions-typing/" +
                "refs/heads/schema-latest/github-actions-typing.schema.json"
        ).toURL().readText())

    val notValidatedActions: List<(ActionCoords) -> Boolean> = listOf(
        // Doesn't have a major version branch/tag, and we keep the typings by the major version
        { it.owner == "damianreeves" && it.name == "write-file-action" },
    )

    println()
    val actions = listActionsToValidate(sha = sha, baseRef = baseRef)

    var shouldFail = false

    for (action in actions) {
        println()
        println("➡\uFE0F For https://github.com/${action.owner}/${action.name}/tree/${action.version}/${action.path ?: ""}")

        if (action.pathToTypings != action.pathToTypings.lowercase()) {
            println("\uD83D\uDD34 Action's owner and name should be lowercase, " +
                    "to enable the bindings server to load them in a case-insensitive manner!")
            shouldFail = true
            continue
        }

        if (notValidatedActions.any { predicate -> predicate(action) }) {
            println("Skipping...")
            continue
        }

        val typings = loadTypings(path = action.pathToTypings)

        val schemaComplianceErrors = typingsSchema.checkForSchemaComplianceErrors(typings)
        if (schemaComplianceErrors != null) {
            println("\uD83D\uDD34 Typings aren't compliant with the schema!")
            println(schemaComplianceErrors)
            shouldFail = true
            continue
        }

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
            (typingsInputs - manifestInputs).let {
                if (it.isNotEmpty()) { println("Extra inputs in typings: $it") }
            }
            (manifestInputs - typingsInputs).let {
                if (it.isNotEmpty()) { println("Extra inputs in manifest: $it") }
            }
            (typingsOutputs - manifestOutputs).let {
                if (it.isNotEmpty()) { println("Extra outputs in typings: $it") }
            }
            (manifestOutputs - typingsOutputs).let {
                if (it.isNotEmpty()) { println("Extra outputs in manifest: $it") }
            }
            shouldFail = true
            continue
        }

        println("\uD83D\uDFE2 OK!")
    }

    require(shouldFail == false) {
        "This is the end of processing, and something doesn't match - see the logs!"
    }
}

private fun listActionsToValidate(sha: String, baseRef: String?): Stream<ActionCoords> =
    baseRef.let { baseRef ->
        if (baseRef == null) {
            println("Validating all typings")
            listAllActionManifestFilesInRepo()
        } else {
            // TODO revert - temporarily
            println("Validating all typings")
            listAllActionManifestFilesInRepo()
        }.map {
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
    }

private fun listAllActionManifestFilesInRepo(): Stream<Path> {
    val actionsWithYamlExtension = Files.walk(Path("typings"))
        .filter { it.name == "action-types.yaml" }
        .toList()
    check(actionsWithYamlExtension.isEmpty()) {
        "Some files have .yaml extension, and we'd like to use only .yml here: $actionsWithYamlExtension"
    }

    return Files.walk(Path("typings")).filter { it.name == "action-types.yml" }
}

private fun listAffectedActionManifestFiles(sha: String, baseRef: String): Stream<Path> {
    val typings = try {
        KGit.open(File(".")).use { git ->
            git.fetch {
                setRefSpecs("refs/heads/$baseRef:refs/heads/$baseRef")
                setDepth(1)
            }
            git.diff {
                setShowNameAndStatusOnly(true)
                git.repository.newObjectReader().use { objectReader ->
                    setOldTree(CanonicalTreeParser().apply {
                        reset(objectReader, git.repository.resolve("refs/heads/$baseRef^{tree}"))
                    })
                    setNewTree(CanonicalTreeParser().apply {
                        reset(objectReader, git.repository.resolve("$sha^{tree}"))
                    })
                }
                setPathFilter(
                    AndTreeFilter.create(
                        PathFilter.create("typings/"),
                        OrTreeFilter.create(
                            PathSuffixFilter.create("/action-types.yml"),
                            PathSuffixFilter.create("/action-types.yaml"),
                        ),
                    )
                )
            }
                .filter { it.changeType != DELETE }
                .map { Path(it.newPath) }
                .groupBy { it.extension == "yml" }
        }
    } finally {
        KGit.shutdown()
    }

    check(typings[false].isNullOrEmpty()) {
        "Some files have .yaml extension, and we'd like to use only .yml here: ${typings[false]}"
    }

    return typings[true]?.stream() ?: Stream.of()
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

private fun JsonSchema.checkForSchemaComplianceErrors(typings: Map<String, Any>): String? {
    var errorMessage: String? = null
    this.validate(typings.toJsonElement(), object : ErrorCollector {
        override fun onError(error: ValidationError) {
            errorMessage = buildString {
                appendLine("Error message: ${error.message}")
                appendLine("Object path: ${error.objectPath}")
            }
        }
    })
    return errorMessage
}

// work-around for https://github.com/OptimumCode/json-schema-validator/issues/194 (direct support for Kotlin classes)
// or https://github.com/OptimumCode/json-schema-validator/issues/195 (direct support for snakeyaml Node)
// or https://github.com/OptimumCode/json-schema-validator/issues/190 (direct support for kaml YamlNode)
private fun Any?.toJsonElement(): JsonElement {
    return when (this) {
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> "$key" to value.toJsonElement() })
        is List<*> -> JsonArray(map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        null -> JsonNull
        else -> error("Unexpected type: ${this::class.qualifiedName}")
    }
}

private val ActionCoords.actionYmlUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$version$subName/action.yml"

private val ActionCoords.actionYamlUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$version$subName/action.yaml"

/**
 * For most actions, it's empty.
 * For actions that aren't executed from the root of the repo, it returns the path relative to the repo root where the
 * action lives, starting with a slash.
 */
private val ActionCoords.subName: String get() = path?.let { "/$path" } ?: ""
