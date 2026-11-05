#!/usr/bin/env kotlin
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:5.0.0")

@file:Repository("https://bindings.krzeminski.it")
@file:DependsOn("actions:checkout:v7")
@file:OptIn(ExperimentalKotlinLogicStep::class)
@file:Suppress("UNCHECKED_CAST")

import io.github.typesafegithub.workflows.actions.actions.Checkout
import io.github.typesafegithub.workflows.annotations.ExperimentalKotlinLogicStep
import io.github.typesafegithub.workflows.domain.Mode
import io.github.typesafegithub.workflows.domain.Permission
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.triggers.Cron
import io.github.typesafegithub.workflows.domain.triggers.Schedule
import io.github.typesafegithub.workflows.domain.triggers.WorkflowDispatch
import io.github.typesafegithub.workflows.dsl.expressions.expr
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.CheckoutActionVersionSource
import io.github.typesafegithub.workflows.yaml.DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG
import it.krzeminski.snakeyaml.engine.kmp.api.Dump
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.api.copy
import it.krzeminski.snakeyaml.engine.kmp.common.FlowStyle
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val typingDifferenceRegex = Regex(
    """TypingDifference\(action=ActionCoords\(owner=([^,]+), name=([^,]+), version=([^,]+), path=(null|[^,]+), pathToTypings=([^)]+)\)"""
)

private val missingVersionRegex = Regex("""-\s*([^/\s:]+)/([^:\s]+):\s*\[([^\]]*)\]""")

private val typingsHeader = "# See https://github.com/typesafegithub/github-actions-typing\n"

workflow(
    name = "Update typings",
    on = listOf(
        Schedule(triggers = listOf(Cron(hour = "7", minute = "0"))),
        WorkflowDispatch(),
    ),
    consistencyCheckJobConfig = DEFAULT_CONSISTENCY_CHECK_JOB_CONFIG.copy(
        checkoutActionVersion = CheckoutActionVersionSource.InferFromClasspath(),
    ),
    sourceFile = __FILE__,
) {
    job(
        id = "update_typings",
        runsOn = UbuntuLatest,
        permissions = mapOf(
            Permission.Actions to Mode.Read,
            Permission.Contents to Mode.Write,
            Permission.PullRequests to Mode.Write,
        ),
        env = mapOf(
            "GH_TOKEN" to expr("secrets.GITHUB_TOKEN"),
            "GITHUB_TOKEN" to expr("secrets.GITHUB_TOKEN"),
        ),
    ) {
        uses(action = Checkout())
        run(name = "Create a PR for every outdated action version") {
            updateTypingsFromLatestFailedTestRun()
        }
    }
}

private fun updateTypingsFromLatestFailedTestRun() {
    val latestRun = runCommand(
        "gh", "run", "list",
        "--workflow", ".github/workflows/test.yaml",
        "--limit", "1",
        "--status", "completed",
        "--json", "databaseId,conclusion",
        "--jq", ".[0] | [(.databaseId | tostring), .conclusion] | @tsv",
    ).trim()

    if (latestRun.isEmpty()) {
        println("No completed Test workflow run found.")
        return
    }

    val (runId, conclusion) = latestRun.split('\t', limit = 2)
    println("Latest Test workflow run: $runId (conclusion: $conclusion)")

    if (conclusion != "failure") {
        println("The latest Test workflow run did not fail. Nothing to do.")
        return
    }

    val jobs = runCommand(
        "gh", "run", "view", runId,
        "--json", "jobs",
        "--jq", ".jobs[] | [(.databaseId | tostring), .name] | @tsv",
    ).lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        if (parts.size == 2) parts else null
    }

    val validateJobId = jobs.firstOrNull { it[1] == "Validate typings" }?.get(0)
        ?: jobs.firstOrNull { it[1].contains("typings", ignoreCase = true) }?.get(0)

    if (validateJobId == null) {
        println("Could not find the 'Validate typings' job in run $runId.")
        return
    }

    val logs = runCommand("gh", "run", "view", runId, "--log", "--job", validateJobId)

    val differences = parseTypingDifferences(logs)
    val missingVersions = parseMissingVersions(logs)

    val fileFixes = buildList {
        differences.forEach { add(it) }
        missingVersions.forEach { addAll(newVersionFixes(it)) }
    }

    if (fileFixes.isEmpty()) {
        println("No automatable typing differences or missing action versions were found.")
        return
    }

    val defaultBranch = runCommand(
        "gh", "repo", "view", "--json", "defaultBranchRef", "--jq", ".defaultBranchRef.name",
    ).trim()
    val baseSha = try {
        runCommand("git", "fetch", "--depth=1", "origin", defaultBranch)
        runCommand("git", "rev-parse", "origin/$defaultBranch").trim()
    } catch (e: Exception) {
        println("Could not fetch '$defaultBranch', falling back to HEAD: ${e.message}")
        runCommand("git", "rev-parse", "HEAD").trim()
    }

    fileFixes
        .groupBy { it.action }
        .forEach { (action, fixes) ->
            createPullRequestIfNeeded(action, fixes, defaultBranch, baseSha)
        }
}

private fun parseTypingDifferences(logs: String): List<FileFix> =
    typingDifferenceRegex.findAll(logs).map { match ->
        val owner = match.groupValues[1]
        val name = match.groupValues[2]
        val version = match.groupValues[3]
        val path = match.groupValues[4].takeUnless { it == "null" }
        val typingsPath = match.groupValues[5]

        FileFix(
            action = ActionKey(owner = owner, name = name, version = version),
            actionPath = path,
            typingsPath = typingsPath,
            copyFromTypingsPath = null,
            copiedFromVersion = null,
        )
    }.distinct().toList()

private fun parseMissingVersions(logs: String): List<ActionKey> {
    val missingSection = logs.substringAfter("Missing major versions found:", missingDelimiterValue = "")
    return missingVersionRegex.findAll(missingSection).map { match ->
        val owner = match.groupValues[1]
        val name = match.groupValues[2]
        match.groupValues[3]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { version -> ActionKey(owner = owner, name = name, version = version) }
    }.flatten().distinct().toList()
}

private fun newVersionFixes(action: ActionKey): List<FileFix> {
    val actionDir = File("typings/${action.owner}/${action.name}")
    val newVersionNumber = action.version.removePrefix("v").toIntOrNull()
        ?: run {
            println("Skipping ${action.owner}/${action.name}@${action.version}: not a numeric major version.")
            return emptyList()
        }

    val previousVersion = actionDir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir -> dir.name.removePrefix("v").toIntOrNull()?.let { it to dir } }
        ?.filter { (number, _) -> number < newVersionNumber }
        ?.maxByOrNull { (number, _) -> number }
        ?.second
        ?: run {
            println("Skipping ${action.owner}/${action.name}@${action.version}: no previous version to copy from.")
            return emptyList()
        }

    val previousFiles = previousVersion.walkTopDown()
        .filter { it.isFile && it.name == "action-types.yml" }
        .toList()

    if (previousFiles.isEmpty()) {
        return listOf(
            FileFix(
                action = action,
                actionPath = null,
                typingsPath = "typings/${action.owner}/${action.name}/${action.version}/action-types.yml",
                copyFromTypingsPath = null,
                copiedFromVersion = previousVersion.name,
            )
        )
    }

    return previousFiles.map { previousFile ->
        val relativeDir = if (previousFile.parentFile == previousVersion) {
            ""
        } else {
            previousFile.parentFile.relativeTo(previousVersion).invariantSeparatorsPath
        }
        val actionPath = relativeDir.ifBlank { null }
        val targetSuffix = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        FileFix(
            action = action,
            actionPath = actionPath,
            typingsPath = "typings/${action.owner}/${action.name}/${action.version}/${targetSuffix}action-types.yml",
            copyFromTypingsPath = previousFile.invariantSeparatorsPath,
            copiedFromVersion = previousVersion.name,
        )
    }
}

private fun createPullRequestIfNeeded(
    action: ActionKey,
    fixes: List<FileFix>,
    defaultBranch: String,
    baseSha: String,
) {
    if (pullRequestExists(action)) {
        println("An open PR already exists for ${action.owner}/${action.name}@${action.version}. Skipping.")
        return
    }

    runCommand("git", "checkout", "-B", action.branch, baseSha)

    val changes = fixes.mapNotNull { applyFileFix(it) }
    if (changes.isEmpty()) {
        println("No changes to apply for ${action.owner}/${action.name}@${action.version}.")
        return
    }

    if (runCommand("git", "status", "--porcelain").isBlank()) {
        println("Typings for ${action.owner}/${action.name}@${action.version} are already up to date.")
        return
    }

    runCommand("git", "add", "-A", "typings")
    runCommand(
        "git",
        "-c", "user.name=github-actions[bot]",
        "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "commit", "-m", action.title,
    )
    runCommand("git", "push", "--force", "origin", "HEAD:refs/heads/${action.branch}")

    val body = buildPullRequestBody(action, changes)
    val prUrl = runCommand(
        "gh", "pr", "create",
        "--base", defaultBranch,
        "--head", action.branch,
        "--title", action.title,
        "--body", body,
    ).trim()

    println("Created PR '${action.title}'${if (prUrl.isEmpty()) "" else ": $prUrl"}.")
}

private fun pullRequestExists(action: ActionKey): Boolean {
    val byBranch = runCommand(
        "gh", "pr", "list",
        "--state", "open",
        "--head", action.branch,
        "--json", "number",
        "--jq", ".[].number",
    ).trim()
    if (byBranch.isNotEmpty()) {
        return true
    }

    return runCommand(
        "gh", "pr", "list",
        "--state", "open",
        "--search", "${action.title} in:title",
        "--json", "number,title",
        "--jq", ".[] | [(.number | tostring), .title] | @tsv",
        "--limit", "100",
    ).lines().any { line ->
        line.split('\t', limit = 2).getOrNull(1) == action.title
    }
}

private fun applyFileFix(fix: FileFix): AppliedChange? {
    val manifest = fetchManifest(fix.action, fix.actionPath)
    if (manifest == null) {
        println("Could not fetch the manifest for ${fix.action.owner}/${fix.action.name}@${fix.action.version}${fix.actionPath?.let { "/$it" } ?: ""}.")
        return null
    }

    val targetFile = File(fix.typingsPath)
    val baseFile = File(fix.copyFromTypingsPath ?: fix.typingsPath)
    val typings = loadYaml(baseFile)
    val typingsInputs = typings.section("inputs")
    val typingsOutputs = typings.section("outputs")

    val manifestInputs = manifest.names("inputs")
    val manifestOutputs = manifest.names("outputs")

    val addedInputs = manifestInputs.filterNot { it in typingsInputs }.associateWith { inputName ->
        inferType(manifest.definition("inputs", inputName))
    }
    val removedInputs = typingsInputs.keys.filterNot { it in manifestInputs }.toSet()
    val addedOutputs = manifestOutputs.filterNot { it in typingsOutputs }.associateWith { outputName ->
        inferType(manifest.definition("outputs", outputName))
    }
    val removedOutputs = typingsOutputs.keys.filterNot { it in manifestOutputs }.toSet()

    val isNewVersion = fix.copiedFromVersion != null
    if (!isNewVersion &&
        addedInputs.isEmpty() &&
        removedInputs.isEmpty() &&
        addedOutputs.isEmpty() &&
        removedOutputs.isEmpty()
    ) {
        return null
    }

    removedInputs.forEach(typingsInputs::remove)
    removedOutputs.forEach(typingsOutputs::remove)
    addedInputs.forEach { (name, type) -> typingsInputs[name] = property(type) }
    addedOutputs.forEach { (name, type) -> typingsOutputs[name] = property(type) }

    if (typingsInputs.isEmpty() && typings["inputs"] != null) {
        typings.remove("inputs")
    }
    if (typingsOutputs.isEmpty() && typings["outputs"] != null) {
        typings.remove("outputs")
    }

    targetFile.parentFile.mkdirs()
    targetFile.writeText(typingsHeader + dumpYaml(typings))

    return AppliedChange(
        typingsPath = fix.typingsPath,
        copiedFromVersion = fix.copiedFromVersion,
        addedInputs = addedInputs,
        removedInputs = removedInputs,
        addedOutputs = addedOutputs,
        removedOutputs = removedOutputs,
    )
}

private fun buildPullRequestBody(action: ActionKey, changes: List<AppliedChange>): String = buildString {
    appendLine("## ${action.owner}/${action.name}@${action.version}")
    appendLine()
    appendLine("Generated automatically from the latest failed Test workflow run.")
    appendLine()
    changes.forEach { change ->
        appendLine("### `${change.typingsPath}`")
        appendLine()
        if (change.copiedFromVersion != null) {
            appendLine("Copied from `${change.copiedFromVersion}` and reconciled against the action manifest.")
            appendLine()
        }
        change.addedInputs.forEach { (name, type) ->
            appendLine("- `$name` (input): added with type `$type`.")
        }
        change.removedInputs.forEach { name ->
            appendLine("- `$name` (input): removed because it is no longer declared in the action manifest.")
        }
        change.addedOutputs.forEach { (name, type) ->
            appendLine("- `$name` (output): added with type `$type`.")
        }
        change.removedOutputs.forEach { name ->
            appendLine("- `$name` (output): removed because it is no longer declared in the action manifest.")
        }
        if (change.addedInputs.isEmpty() &&
            change.removedInputs.isEmpty() &&
            change.addedOutputs.isEmpty() &&
            change.removedOutputs.isEmpty()
        ) {
            appendLine("- No input/output changes compared to the previous major version.")
        }
        appendLine()
    }
}

private fun fetchManifest(action: ActionKey, actionPath: String?): Map<String, Any?>? {
    val pathSuffix = actionPath?.let { "/$it" } ?: ""
    val encodedPath = actionPath
        ?.split('/')
        ?.joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
        ?.let { "/$it" }
        ?: ""

    return listOf("action.yml", "action.yaml").firstNotNullOfOrNull { fileName ->
        val url = "https://raw.githubusercontent.com/${action.owner}/${action.name}/${action.version}$encodedPath/$fileName"
        try {
            loadYaml(URI(url).toURL().readText())
        } catch (_: Exception) {
            null
        }
    }.also {
        if (it == null) {
            println("Tried ${action.owner}/${action.name}@${action.version}$pathSuffix.")
        }
    }
}

private fun loadYaml(file: File): MutableMap<String, Any?> =
    if (file.exists()) loadYaml(file.readText()) else linkedMapOf()

private fun loadYaml(text: String): MutableMap<String, Any?> =
    (Load().loadOne(text) as? MutableMap<String, Any?>) ?: linkedMapOf()

private fun MutableMap<String, Any?>.section(name: String): MutableMap<String, Any?> {
    val existing = this[name]
    return when (existing) {
        null -> linkedMapOf<String, Any?>().also { this[name] = it }
        is MutableMap<*, *> -> existing as MutableMap<String, Any?>
        else -> error("Expected '$name' to be a map, got ${existing::class.qualifiedName}.")
    }
}

private fun Map<String, Any?>.names(section: String): List<String> =
    (this[section] as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList()

private fun Map<String, Any?>.definition(section: String, name: String): Any? =
    (this[section] as? Map<*, *>)?.get(name)

private fun inferType(definition: Any?): String {
    val map = definition as? Map<*, *> ?: return "string"

    return when ((map["type"] as? String)?.lowercase()) {
        "boolean", "bool" -> "boolean"
        "integer", "int", "long" -> "integer"
        "float", "number", "double" -> "float"
        "string" -> "string"
        else -> when (map["default"]) {
            is Boolean -> "boolean"
            is Int, is Long -> "integer"
            is Double, is Float -> "float"
            else -> "string"
        }
    }
}

private fun property(type: String): Map<String, Any> =
    linkedMapOf("type" to type)

private fun dumpYaml(value: Any?): String {
    val settings = DumpSettings().copy {
        defaultFlowStyle = FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 0
        width = 120
    }
    return Dump(settings).dumpToString(value)
}

private fun runCommand(vararg command: String): String {
    val processBuilder = ProcessBuilder(*command)
        .redirectErrorStream(true)
    processBuilder.environment()["GIT_TERMINAL_PROMPT"] = "0"

    val process = processBuilder.start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    check(exitCode == 0) {
        "Command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output"
    }
    return output
}

private data class ActionKey(
    val owner: String,
    val name: String,
    val version: String,
) {
    val branch: String get() = "update-typings/$owner/$name/$version"
    val title: String get() = "Update typings for $owner/$name@$version"
}

private data class FileFix(
    val action: ActionKey,
    val actionPath: String?,
    val typingsPath: String,
    val copyFromTypingsPath: String?,
    val copiedFromVersion: String?,
)

private data class AppliedChange(
    val typingsPath: String,
    val copiedFromVersion: String?,
    val addedInputs: Map<String, String>,
    val removedInputs: Set<String>,
    val addedOutputs: Map<String, String>,
    val removedOutputs: Set<String>,
)
