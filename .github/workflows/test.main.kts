#!/usr/bin/env kotlin
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:5.0.0")
@file:DependsOn("io.github.optimumcode:json-schema-validator-jvm:0.5.5")
@file:DependsOn("com.github.sya-ri:kgit:1.2.2")

@file:Import("typing-heuristics.main.kts")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v7")
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
import io.github.typesafegithub.workflows.dsl.expressions.expr
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.CheckoutActionVersionSource
import io.github.typesafegithub.workflows.yaml.DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG
import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.common.FlowStyle
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
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.system.exitProcess

private val mismatchBaselinePath = "typing-mismatches.yml"

private val mismatchBaselineHeader = listOf(
    "# Generated with: DUMP_TYPING_MISMATCHES=true .github/workflows/test.main.kts",
    "# Lists all differences between the typings in this catalog and the types inferred by the heuristics.",
    "# CI fails when this file is out of sync with the actual mismatches.",
).joinToString("\n")

private val mismatchComparator = compareBy<TypingMismatch> { it.action.owner }
    .thenBy { it.action.name }
    .thenBy { versionOrder(it.action.version) }
    .thenBy { it.action.version }
    .thenBy { it.action.path ?: "" }
    .thenBy { sectionOrder(it.inference.section) }
    .thenBy { it.inference.name }

if (System.getenv("DUMP_TYPING_MISMATCHES") == "true") {
    dumpTypingMismatchBaseline()
    exitProcess(0)
}

workflow(
    name = "Test",
    on = listOf(
        Push(branches = listOf("main")),
        PullRequest(),
        Schedule(triggers = listOf(Cron(hour = "6", minute = "0"))),
    ),
    consistencyCheckJobConfig = DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG.copy(
        checkoutActionVersion = CheckoutActionVersionSource.InferFromClasspath(),
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
            command = $$"""
            KOTLIN_HOME=$(dirname $(dirname $(which kotlinc)))
            KOTLIN_MAIN_KTS_JAR="${KOTLIN_HOME}/lib/kotlin-main-kts.jar"
            find -name *.main.kts -print0 | while read -d $'\0' file
            do
                echo "Compiling $file..."
                kotlinc -cp "${KOTLIN_MAIN_KTS_JAR}" -script -Xallow-any-scripts-in-source-roots "$file"
            done
            """.trimIndent()
        )
    }

    job(
        id = "run_tests",
        name = "Run tests",
        runsOn = UbuntuLatest,
    ) {
        uses(action = Checkout())
        run(
            name = "Run typing heuristics tests",
            command = "RUN_TYPING_HEURISTICS_TESTS=true .github/workflows/typing-heuristics.main.kts",
        )
        run(
            name = "Run update typings tests",
            command = "RUN_TESTS=true .github/workflows/update-typings.main.kts",
        )
    }

    job(
        id = "validate_typings",
        name = "Validate typings",
        runsOn = UbuntuLatest,
        env = mapOf(
            "GH_TOKEN" to expr { "github.token" },
        )
    ) {
        uses(action = Checkout())
        run(
            name = "Check for actions",
        ) {
            val baseRef = resolveBaseRef(github.base_ref?.ifEmpty { null }, github.ref, github.repository)
            validateTypings(github.sha, baseRef)
            validateAllMajorVersionsPresent(baseRef)
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

private data class TypingDifference(
    val action: ActionCoords,
    val extraInputsInManifest: Set<String>,
    val extraInputsInTypings: Set<String>,
    val extraOutputsInManifest: Set<String>,
    val extraOutputsInTypings: Set<String>,
)

private data class TypingInference(
    val section: String,
    val name: String,
    val actual: Map<String, Any?>,
    val inferred: Map<String, Any?>,
    val reason: String?,
) {
    val isMismatch: Boolean get() = actual != inferred
}

private data class TypingMismatch(
    val action: ActionCoords,
    val inference: TypingInference,
) {
    val key: MismatchKey = MismatchKey(action.key, inference.section, inference.name)
    val values: MismatchValues = MismatchValues(inference.actual, inference.inferred)
}

private data class MismatchKey(
    val action: String,
    val section: String,
    val name: String,
) {
    override fun toString(): String = "$action:$section:$name"
}

private data class MismatchValues(
    val actual: Map<String, Any?>,
    val inferred: Map<String, Any?>,
)

private val ActionCoords.key: String
    get() = listOfNotNull("$owner/$name@$version", path).joinToString("/")

private fun resolveBaseRef(rawBaseRef: String?, ref: String?, repository: String?): String? {
    if (rawBaseRef == null || !rawBaseRef.startsWith("graphite-base/")) return rawBaseRef

    // Graphite reports a synthetic, non-existent base branch for stacked PRs. Ask GitHub for the real one.
    val prNumber = ref?.removePrefix("refs/pull/")?.substringBefore("/merge")
    check(prNumber != null && prNumber != ref) {
        "Cannot resolve the real base branch for Graphite PR from ref $ref"
    }
    val process = ProcessBuilder(
        "gh", "api", "repos/$repository/pulls/$prNumber", "--jq", ".base.ref",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { "Failed to resolve the PR base branch: $output" }
    return output
}

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
    val actionVersions = listActionVersionsToValidate(sha = sha, baseRef = baseRef).toList()

    var shouldFail = false

    val typingDifferences = mutableListOf<TypingDifference>()
    val validatedActionKeys = mutableSetOf<String>()
    val mismatches = mutableListOf<TypingMismatch>()

    for (action in actionVersions) {
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

        val typingsInputs = typings.names("inputs")
        val typingsOutputs = typings.names("outputs")
        val manifest = fetchManifest(action)

        if (manifest == null) {
            println("\uD83D\uDD34 No manifest found!")
            shouldFail = true
            continue
        }

        val manifestInputs = manifest.names("inputs")
        val manifestOutputs = manifest.names("outputs")

        val inferences = compareTypings(typings, manifest)
        validatedActionKeys += action.key
        mismatches += inferences.filter { it.isMismatch }.map { TypingMismatch(action, it) }

        inferences.forEach { inference ->
            val warning = if (inference.isMismatch) "\u26A0\uFE0F " else ""
            val reason = inference.reason?.let { " ($it)" } ?: ""
            val label = if (inference.section == "inputs") "Input" else "Output"
            println(
                "  ${warning}$label `${inference.name}`: actual `${formatTyping(inference.actual)}`, " +
                    "inferred `${formatTyping(inference.inferred)}`$reason"
            )
        }

        if (typingsInputs != manifestInputs || typingsOutputs != manifestOutputs) {
            println("\uD83D\uDD34 Something is wrong with the typings!")

            val difference = TypingDifference(
                action = action,
                extraInputsInManifest = manifestInputs - typingsInputs,
                extraInputsInTypings = typingsInputs - manifestInputs,
                extraOutputsInManifest = manifestOutputs - typingsOutputs,
                extraOutputsInTypings = typingsOutputs - manifestOutputs
            )
            println(difference)

            typingDifferences += difference
            shouldFail = true
            continue
        }

        println("\uD83D\uDFE2 OK!")
    }

    if (!validateMismatchBaseline(validatedActionKeys, mismatches)) {
        shouldFail = true
    }

    require(shouldFail == false) {
        "This is the end of processing, and something doesn't match - see the logs!"
    }
}

private fun validateAllMajorVersionsPresent(baseRef: String?) {
    if (baseRef != null) {
        // Assuming we're in a PR - skip this check
        return
    }

    val missingMajorVersionsForAction: MutableMap<String, List<String>> = mutableMapOf()

    listAllActionMetadataFilesInRepo()
        .map {
            val (_, owner, name) = it.invariantSeparatorsPathString.split("/", limit = 4)
            Pair(owner, name)
        }.forEach { (owner, name) ->
            println("For $owner/$name...")
            val versionsInCatalog = (Path("typings") / owner / name).walk()
                .filter { it.name == "action-types.yml" }
                .map { it.invariantSeparatorsPathString.split("/")[3] }
                .toSet()
                .sortedBy { it.removePrefix("v").toInt() }
                .toList()
            println("  versions in catalog: $versionsInCatalog")
            val versionsInAction = listGithubRefs(owner = owner, name = name)
                .filter { it.startsWith("v") && it.removePrefix("v").toIntOrNull() != null }
                .sortedBy { it.removePrefix("v").toInt() }
                .toList()
            println("  versions in action: $versionsInAction")
            val maxVersionInCatalog = versionsInCatalog.maxBy { it.removePrefix("v").toInt() }
            val versionsMissingInCatalog = versionsInAction.filter {
                it.removePrefix("v").toInt() > maxVersionInCatalog.removePrefix("v").toInt()
            }
            println("  versions missing in catalog: $versionsMissingInCatalog")

            if (versionsMissingInCatalog.isNotEmpty()) {
                missingMajorVersionsForAction["$owner/$name"] = versionsMissingInCatalog
            }
        }

    if (missingMajorVersionsForAction.isEmpty()) {
        return
    }

    println("Missing major versions found:")
    missingMajorVersionsForAction.forEach { (actionCoords, versions) ->
        println("- $actionCoords: $versions")
    }

    throw RuntimeException("Missing major versions found!")
}

private fun listActionVersionsToValidate(sha: String, baseRef: String?): Stream<ActionCoords> =
    baseRef.let { baseRef ->
        if (baseRef == null) {
            println("Validating all typings")
            listAllActionManifestFilesInRepo()
        } else {
            println("Validating all typings")
            listAllActionManifestFilesInRepo()
        }.map { it.toActionCoords() }
    }

private fun Path.toActionCoords(): ActionCoords {
    val (_, owner, name, version, pathAndYaml) = invariantSeparatorsPathString.split("/", limit = 5)
    val path = if ("/" in pathAndYaml) pathAndYaml.substringBeforeLast("/") else null
    return ActionCoords(
        owner = owner,
        name = name,
        version = version,
        path = path,
        pathToTypings = invariantSeparatorsPathString,
    )
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


private fun listAllActionMetadataFilesInRepo(): Stream<Path> {
    val actionsWithYamlExtension = Files.walk(Path("typings"))
        .filter { it.name == "metadata.yaml" }
        .toList()
    check(actionsWithYamlExtension.isEmpty()) {
        "Some files have .yaml extension, and we'd like to use only .yml here: $actionsWithYamlExtension"
    }

    return Files.walk(Path("typings")).filter { it.name == "metadata.yml" }
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

private fun Map<*, *>.definition(section: String, name: String): Any? =
    (this[section] as? Map<*, *>)?.get(name)

private fun formatTyping(typing: Map<*, *>): String =
    typing.entries.joinToString(", ") { (key, value) -> "$key: ${formatTypingValue(value)}" }

private fun formatTypingValue(value: Any?): String = when (value) {
    is Map<*, *> -> "{${formatTyping(value)}}"
    is List<*> -> value.joinToString(", ", "[", "]") { formatTypingValue(it) }
    else -> value?.toString()?.replace("\n", "\\n") ?: "n/a"
}

private fun Map<*, *>.names(section: String): Set<String> =
    (this[section] as? Map<*, *>)?.keys?.map { it.toString() }?.toSet() ?: emptySet()

/**
 * Single source of truth for comparing stored typings with heuristic inferences. Both validation and
 * the baseline dump go through here, so the YAML file can't drift from what CI checks.
 */
private fun compareTypings(typings: Map<*, *>, manifest: Map<*, *>): List<TypingInference> =
    // Input names feed boolean heuristics (e.g. `skip-cache`). Outputs have names too, but
    // update-typings.main.kts currently omits passing them - that's a bug there. Mirror it for now
    // so validation agrees with what the update workflow would actually generate.
    listOf("inputs" to true, "outputs" to false).flatMap { (section, includeName) ->
        manifest.names(section).map { name ->
            val inference = TypingHeuristics.inferType(
                manifest.definition(section, name),
                if (includeName) name else null,
            )
            TypingInference(
                section = section,
                name = name,
                actual = (typings.definition(section, name) as? Map<*, *>)?.asTypingMap() ?: emptyMap(),
                inferred = inference.toTypingMap().asTypingMap(),
                reason = inference.reason,
            )
        }
    }

private fun Map<*, *>.asTypingMap(): Map<String, Any?> =
    normalizeTyping(this) as Map<String, Any?>

private fun normalizeTyping(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { (key, entryValue) -> key.toString() to normalizeTyping(entryValue) }
    is List<*> -> value.map { normalizeTyping(it) }
    else -> value
}

private fun validateMismatchBaseline(
    validatedActionKeys: Set<String>,
    mismatches: List<TypingMismatch>,
): Boolean {
    val expected = loadMismatchBaseline().filterKeys { it.action in validatedActionKeys }
    val actual = mismatches.associate { it.key to it.values }

    val unexpected = actual.keys - expected.keys
    val stale = expected.keys - actual.keys
    val changed = actual.keys.intersect(expected.keys).filter { actual[it] != expected[it] }

    if (unexpected.isEmpty() && stale.isEmpty() && changed.isEmpty()) {
        return true
    }

    println()
    println("\uD83D\uDD34 $mismatchBaselinePath is out of date!")
    if (unexpected.isNotEmpty()) {
        println("  Mismatches missing from the baseline:")
        unexpected.sortedBy { it.toString() }.forEach { println("    - $it: ${formatMismatchValues(actual[it]!!)}") }
    }
    if (stale.isNotEmpty()) {
        println("  Baseline entries that are no longer mismatches:")
        stale.sortedBy { it.toString() }.forEach { println("    - $it") }
    }
    if (changed.isNotEmpty()) {
        println("  Baseline entries with outdated typings:")
        changed.sortedBy { it.toString() }.forEach { key ->
            println("    - $key")
            println("      baseline: ${formatMismatchValues(expected[key]!!)}")
            println("      actual:   ${formatMismatchValues(actual[key]!!)}")
        }
    }
    println("  Regenerate with: DUMP_TYPING_MISMATCHES=true .github/workflows/test.main.kts")
    return false
}

private fun formatMismatchValues(values: MismatchValues): String =
    "actual: {${formatTyping(values.actual)}}, inferred: {${formatTyping(values.inferred)}}"

private fun loadMismatchBaseline(): Map<MismatchKey, MismatchValues> {
    val file = File(mismatchBaselinePath)
    if (!file.exists()) return emptyMap()
    val root = Load().loadOne(file.readText()) as? Map<*, *> ?: return emptyMap()
    val actions = root["actions"] as? Map<*, *> ?: return emptyMap()
    return buildMap {
        actions.forEach { (actionKey, actionNode) ->
            (actionNode as? Map<*, *>)?.forEach { (section, sectionNode) ->
                (sectionNode as? Map<*, *>)?.forEach { (name, values) ->
                    val valueMap = values as? Map<*, *> ?: return@forEach
                    put(
                        MismatchKey(actionKey.toString(), section.toString(), name.toString()),
                        MismatchValues(
                            actual = (valueMap["actual"] as? Map<*, *>)?.asTypingMap() ?: emptyMap(),
                            inferred = (valueMap["inferred"] as? Map<*, *>)?.asTypingMap() ?: emptyMap(),
                        ),
                    )
                }
            }
        }
    }
}

private fun dumpTypingMismatchBaseline() {
    val mismatches = listAllActionManifestFilesInRepo()
        .map { it.toActionCoords() }
        .toList()
        .flatMap { action ->
            val manifest = fetchManifest(action)
            if (manifest == null) {
                println("\u26A0\uFE0F No manifest found for ${action.key}, skipping")
                emptyList()
            } else {
                compareTypings(loadTypings(action.pathToTypings), manifest)
                    .filter { it.isMismatch }
                    .map { TypingMismatch(action, it) }
            }
        }
        .sortedWith(mismatchComparator)

    File(mismatchBaselinePath).writeText(renderMismatchBaseline(mismatches))
    println("Wrote ${mismatches.size} mismatches to $mismatchBaselinePath")
}

private fun renderMismatchBaseline(mismatches: List<TypingMismatch>): String {
    val actions = linkedMapOf<String, Any>()
    mismatches.forEach { mismatch ->
        val actionNode = actions.getOrPut(mismatch.action.key) { linkedMapOf<String, Any>() } as MutableMap<String, Any>
        val sectionNode = actionNode.getOrPut(mismatch.inference.section) { linkedMapOf<String, Any>() } as MutableMap<String, Any>
        sectionNode[mismatch.inference.name] = linkedMapOf(
            "actual" to mismatch.inference.actual,
            "inferred" to mismatch.inference.inferred,
        )
    }
    val settings = DumpSettings(defaultFlowStyle = FlowStyle.BLOCK, indent = 2, width = 120)
    return mismatchBaselineHeader + "\n" + Dump(settings).dumpToString(mapOf("actions" to actions))
}

private fun versionOrder(version: String): Int = version.removePrefix("v").toIntOrNull() ?: Int.MAX_VALUE

private fun sectionOrder(section: String): Int = if (section == "inputs") 0 else 1

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

private fun listGithubRefs(owner: String, name: String): List<String> {
    fun ghApi(endpoint: String): List<String> {
        val process = ProcessBuilder("gh", "api", "--paginate", endpoint, "--jq", ".[].name")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println("Warning: gh api call failed (exit $exitCode): ${process.errorStream.bufferedReader().readText()}")
            return emptyList()
        }
        return output.lines().filter { it.isNotEmpty() }
    }
    return ghApi("repos/$owner/$name/branches") +
        ghApi("repos/$owner/$name/tags")
}

private val ActionCoords.actionYmlUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$version$subName/action.yml"

private val ActionCoords.actionYamlUrl: String get() = "https://raw.githubusercontent.com/$owner/$name/$version$subName/action.yaml"

/**
 * For most actions, it's empty.
 * For actions that aren't executed from the root of the repo, it returns the path relative to the repo root where the
 * action lives, starting with a slash.
 */
private val ActionCoords.subName: String get() = path?.let { "/$path" } ?: ""
