#!/usr/bin/env kotlin
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")
@file:DependsOn("it.krzeminski:snakeyaml-engine-kmp-jvm:5.0.0")
@file:DependsOn("io.kotest:kotest-framework-engine-jvm:6.2.6")
@file:DependsOn("io.kotest:kotest-assertions-core-jvm:6.2.6")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")

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
import io.kotest.core.spec.SpecRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.matchers.shouldBe
import it.krzeminski.snakeyaml.engine.kmp.api.Load
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

private val typingDifferenceRegex = Regex(
    """TypingDifference\(action=ActionCoords\(owner=([^,]+), name=([^,]+), version=([^,]+), path=(null|[^,]+), pathToTypings=([^)]+)\)"""
)

private val missingVersionRegex = Regex("""-\s*([^/\s:]+)/([^:\s]+):\s*\[([^\]]*)\]""")

private val typingsHeader = "# See https://github.com/typesafegithub/github-actions-typing\n"

if (System.getenv("RUN_TESTS") == "true") {
    runUpdateTypingsTests()
    exitProcess(0)
}

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
    val existingPullRequest = findPullRequest(action)

    if (existingPullRequest == null) {
        runCommand("git", "checkout", "-B", action.branch, baseSha)
    } else {
        runCommand("git", "fetch", "origin", "refs/heads/${existingPullRequest.headRefName}")
        runCommand("git", "checkout", "-B", existingPullRequest.headRefName, "FETCH_HEAD")
    }

    val changes = if (existingPullRequest == null) {
        fixes.mapNotNull { applyFileFix(it) }
    } else {
        fixes.mapNotNull { applyFileFix(it, baseRevision = baseSha) }
    }
    if (changes.isEmpty()) {
        if (existingPullRequest == null) {
            println("No changes to apply for ${action.owner}/${action.name}@${action.version}.")
        } else {
            println("Could not compute the expected changes for ${action.owner}/${action.name}@${action.version}.")
        }
        return
    }

    if (runCommand("git", "status", "--porcelain").isBlank()) {
        if (existingPullRequest == null) {
            println("Typings for ${action.owner}/${action.name}@${action.version} are already up to date.")
        } else {
            println(
                "PR #${existingPullRequest.number} for ${action.owner}/${action.name}@${action.version} " +
                    "already contains the expected changes."
            )
        }
        return
    }

    runCommand("git", "add", "-A", "typings")
    runCommand(
        "git",
        "-c", "user.name=github-actions[bot]",
        "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "commit", "-m", action.title,
    )

    if (existingPullRequest != null) {
        runCommand("git", "push", "origin", "HEAD:refs/heads/${existingPullRequest.headRefName}")
        runCommand(
            "gh", "pr", "edit", existingPullRequest.number.toString(),
            "--body", buildPullRequestBody(action, changes),
        )
        println("Updated PR #${existingPullRequest.number} with a new commit.")
        return
    }

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

private fun findPullRequest(action: ActionKey): ExistingPullRequest? {
    val byBranch = runCommand(
        "gh", "pr", "list",
        "--state", "open",
        "--head", action.branch,
        "--json", "number,headRefName",
        "--jq", ".[0] | [(.number | tostring), .headRefName] | @tsv",
    ).trim()
    if (byBranch.isNotEmpty()) {
        val parts = byBranch.split('\t', limit = 2)
        if (parts.size == 2) {
            return ExistingPullRequest(number = parts[0].toInt(), headRefName = parts[1])
        }
    }

    return runCommand(
        "gh", "pr", "list",
        "--state", "open",
        "--search", "${action.title} in:title",
        "--json", "number,title,headRefName",
        "--jq", ".[] | [(.number | tostring), .title, .headRefName] | @tsv",
        "--limit", "100",
    ).lines().firstNotNullOfOrNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size == 3 && parts[1] == action.title) {
            ExistingPullRequest(number = parts[0].toInt(), headRefName = parts[2])
        } else {
            null
        }
    }
}

private fun applyFileFix(fix: FileFix, baseRevision: String? = null): AppliedChange? {
    val manifest = fetchManifest(fix.action, fix.actionPath)
    if (manifest == null) {
        println("Could not fetch the manifest for ${fix.action.owner}/${fix.action.name}@${fix.action.version}${fix.actionPath?.let { "/$it" } ?: ""}.")
        return null
    }

    val targetFile = File(fix.typingsPath)
    val basePath = fix.copyFromTypingsPath ?: fix.typingsPath
    val baseText = if (baseRevision == null) {
        val baseFile = File(basePath)
        if (baseFile.exists()) baseFile.readText() else ""
    } else {
        readFileAtRevision(baseRevision, basePath)
    }

    val manifestInputs = manifest.names("inputs")
    val manifestOutputs = manifest.names("outputs")
    val existingInputs = entryNames(baseText, "inputs")
    val existingOutputs = entryNames(baseText, "outputs")

    val addedInputs = manifestInputs.filterNot { it in existingInputs }.associateWith { inputName ->
        inferType(manifest.definition("inputs", inputName), inputName)
    }
    val removedInputs = existingInputs.filterNot { it in manifestInputs }.toSet()
    val addedOutputs = manifestOutputs.filterNot { it in existingOutputs }.associateWith { outputName ->
        inferType(manifest.definition("outputs", outputName))
    }
    val removedOutputs = existingOutputs.filterNot { it in manifestOutputs }.toSet()

    val isNewVersion = fix.copiedFromVersion != null
    if (baseRevision == null &&
        !isNewVersion &&
        addedInputs.isEmpty() &&
        removedInputs.isEmpty() &&
        addedOutputs.isEmpty() &&
        removedOutputs.isEmpty()
    ) {
        return null
    }

    var text = baseText.ifBlank { typingsHeader }
    text = addEntries(text, "inputs", addedInputs.mapValues { (_, inference) -> inference.type })
    text = addEntries(text, "outputs", addedOutputs.mapValues { (_, inference) -> inference.type })
    text = removeEntries(text, "inputs", removedInputs)
    text = removeEntries(text, "outputs", removedOutputs)

    targetFile.parentFile.mkdirs()
    targetFile.writeText(text)

    return AppliedChange(
        typingsPath = fix.typingsPath,
        copiedFromVersion = fix.copiedFromVersion,
        addedInputs = addedInputs,
        removedInputs = removedInputs,
        addedOutputs = addedOutputs,
        removedOutputs = removedOutputs,
    )
}

private fun readFileAtRevision(revision: String, path: String): String =
    try {
        runCommand("git", "show", "$revision:$path")
    } catch (_: Exception) {
        ""
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
        change.addedInputs.forEach { (name, inference) ->
            val reason = inference.reason?.let { " because $it" } ?: ""
            appendLine("- `$name` (input): added with type `${inference.type}`$reason.")
        }
        change.removedInputs.forEach { name ->
            appendLine("- `$name` (input): removed because it is no longer declared in the action manifest.")
        }
        change.addedOutputs.forEach { (name, inference) ->
            val reason = inference.reason?.let { " because $it" } ?: ""
            appendLine("- `$name` (output): added with type `${inference.type}`$reason.")
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

private fun loadYaml(text: String): MutableMap<String, Any?> =
    (Load().loadOne(text) as? MutableMap<String, Any?>) ?: linkedMapOf()

private fun Map<String, Any?>.names(section: String): List<String> =
    (this[section] as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList()

private fun Map<String, Any?>.definition(section: String, name: String): Any? =
    (this[section] as? Map<*, *>)?.get(name)

private fun inferType(definition: Any?, inputName: String? = null): TypeInference {
    val map = definition as? Map<*, *> ?: return TypeInference(type = "string", reason = null)
    val declaredType = (map["type"] as? String)?.lowercase()

    return when (declaredType) {
        "boolean", "bool" -> TypeInference(
            type = "boolean",
            reason = "the action manifest declares `type: $declaredType`",
        )
        "integer", "int", "long" -> TypeInference(
            type = "integer",
            reason = "the action manifest declares `type: $declaredType`",
        )
        "float", "number", "double" -> TypeInference(
            type = "float",
            reason = "the action manifest declares `type: $declaredType`",
        )
        "string" -> TypeInference(
            type = "string",
            reason = "the action manifest declares `type: $declaredType`",
        )
        else -> {
            val heuristicReason = inputName?.let { booleanHeuristicReason(it, map) }
            if (heuristicReason != null) {
                TypeInference(type = "boolean", reason = heuristicReason)
            } else {
                when (val defaultValue = map["default"]) {
                    is Boolean -> TypeInference(
                        type = "boolean",
                        reason = defaultValueReason(defaultValue),
                    )
                    is Int, is Long -> TypeInference(
                        type = "integer",
                        reason = defaultValueReason(defaultValue),
                    )
                    is Double, is Float -> TypeInference(
                        type = "float",
                        reason = defaultValueReason(defaultValue),
                    )
                    else -> TypeInference(type = "string", reason = null)
                }
            }
        }
    }
}

private fun booleanHeuristicReason(inputName: String, definition: Map<*, *>): String? {
    val lowerName = inputName.lowercase()
    val matchedPrefix = listOf("skip", "check", "allow").firstOrNull { lowerName.startsWith(it) }
    if (matchedPrefix != null) {
        return "the input name starts with `$matchedPrefix`"
    }

    val description = definition["description"] as? String ?: return null
    val trimmedDescription = description.trimStart()
    val startsWithIfWord = trimmedDescription.startsWith("if", ignoreCase = true) &&
        (trimmedDescription.length == 2 || !trimmedDescription[2].isLetterOrDigit())

    return if (startsWithIfWord) {
        "the input description starts with `${trimmedDescription.take(2)}`"
    } else {
        null
    }
}

private fun defaultValueReason(value: Any): String =
    "the action manifest does not declare a supported type, but its default value is `$value`"

private fun entryNames(text: String, sectionName: String): Set<String> {
    val lines = text.split("\n")
    val section = findSection(lines, sectionName) ?: return emptySet()
    return findEntries(lines, section).map { it.name }.toSet()
}

private fun removeEntries(text: String, sectionName: String, names: Set<String>): String {
    if (names.isEmpty()) return text

    val lines = text.split("\n").toMutableList()
    while (true) {
        val section = findSection(lines, sectionName) ?: break
        val entries = findEntries(lines, section)
        val index = entries.indexOfLast { it.name in names }
        if (index == -1) break

        val entry = entries[index]
        val isLast = index == entries.lastIndex
        val removeStart = if (isLast) {
            entries.getOrNull(index - 1)?.contentEndIndex ?: entry.startIndex
        } else {
            entry.startIndex
        }
        val removeEnd = if (isLast) entry.contentEndIndex else entry.endIndex
        lines.subList(removeStart, removeEnd).clear()
    }

    val updatedSection = findSection(lines, sectionName)
    if (updatedSection != null && findEntries(lines, updatedSection).isEmpty()) {
        val removeEnd = if (updatedSection.endIndex == lines.size && lines.lastOrNull()?.isEmpty() == true) {
            lines.size - 1
        } else {
            updatedSection.endIndex
        }
        lines.subList(updatedSection.headerIndex, removeEnd).clear()
    }

    return lines.joinToString("\n")
}

private fun addEntries(text: String, sectionName: String, entries: Map<String, String>): String {
    if (entries.isEmpty()) return text

    val lines = text.split("\n").toMutableList()
    val section = findSection(lines, sectionName) ?: return appendSection(text, sectionName, entries)
    val existingEntries = findEntries(lines, section)
    val insertionIndex = existingEntries.lastOrNull()?.contentEndIndex ?: (section.headerIndex + 1)
    val useBlankSeparators = hasBlankSeparators(lines, existingEntries)

    val newLines = mutableListOf<String>()
    entries.forEach { (name, type) ->
        if (newLines.isNotEmpty() && useBlankSeparators) {
            newLines.add("")
        }
        newLines.add("  $name:")
        newLines.add("    type: $type")
    }
    if (existingEntries.isNotEmpty() && useBlankSeparators) {
        newLines.add(0, "")
    }

    lines.addAll(insertionIndex, newLines)
    return lines.joinToString("\n")
}

private fun hasBlankSeparators(lines: List<String>, entries: List<YamlEntry>): Boolean =
    entries.size >= 2 && entries.zipWithNext().any { (first, second) ->
        (first.contentEndIndex until second.startIndex).any { index -> lines[index].isBlank() }
    }

private fun appendSection(text: String, sectionName: String, entries: Map<String, String>): String {
    val hadTrailingNewline = text.endsWith("\n")
    val lines = text.split("\n").toMutableList()
    while (lines.isNotEmpty() && lines.last().isEmpty()) {
        lines.removeAt(lines.lastIndex)
    }

    val hasExistingSection = lines.any { it.trimEnd() == "inputs:" || it.trimEnd() == "outputs:" }
    if (hasExistingSection && lines.isNotEmpty()) {
        lines.add("")
    }

    lines.add("$sectionName:")
    entries.forEach { (name, type) ->
        lines.add("  $name:")
        lines.add("    type: $type")
    }

    return lines.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
}

private fun findSection(lines: List<String>, sectionName: String): YamlSection? {
    val headerIndex = lines.indexOfFirst { it.trimEnd() == "$sectionName:" }
    if (headerIndex == -1) return null

    val endIndex = (headerIndex + 1 until lines.size).firstOrNull { index ->
        val line = lines[index]
        line.isNotEmpty() && !line.first().isWhitespace()
    } ?: lines.size

    return YamlSection(headerIndex = headerIndex, endIndex = endIndex)
}

private fun findEntries(lines: List<String>, section: YamlSection): List<YamlEntry> {
    val startIndices = (section.headerIndex + 1 until section.endIndex)
        .filter { isEntryKey(lines[it]) }

    return startIndices.mapIndexed { index, startIndex ->
        val endIndex = startIndices.getOrNull(index + 1) ?: section.endIndex
        var contentEndIndex = endIndex
        while (contentEndIndex > startIndex + 1 && lines[contentEndIndex - 1].isBlank()) {
            contentEndIndex--
        }
        YamlEntry(
            name = entryName(lines[startIndex]),
            startIndex = startIndex,
            contentEndIndex = contentEndIndex,
            endIndex = endIndex,
        )
    }
}

private fun isEntryKey(line: String): Boolean =
    line.startsWith("  ") &&
        line.length > 2 &&
        !line[2].isWhitespace() &&
        line[2] != '#' &&
        ':' in line.substring(2)

private fun entryName(line: String): String =
    line.trim().substringBefore(':').trim()

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

private data class TypeInference(
    val type: String,
    val reason: String?,
)

private data class AppliedChange(
    val typingsPath: String,
    val copiedFromVersion: String?,
    val addedInputs: Map<String, TypeInference>,
    val removedInputs: Set<String>,
    val addedOutputs: Map<String, TypeInference>,
    val removedOutputs: Set<String>,
)

private data class ExistingPullRequest(
    val number: Int,
    val headRefName: String,
)

private data class YamlSection(
    val headerIndex: Int,
    val endIndex: Int,
)

private data class YamlEntry(
    val name: String,
    val startIndex: Int,
    val contentEndIndex: Int,
    val endIndex: Int,
)

// -----------------------------------------------------------------------------
// TESTS
// -----------------------------------------------------------------------------

private fun runUpdateTypingsTests() {
    val collector = CollectingTestEngineListener()
    val result = runBlocking {
        TestEngineLauncher()
            .withConsoleListener()
            .withListener(collector)
            .withSpecRefs(SpecRef.Function({ TypingHeuristicsTest() }, TypingHeuristicsTest::class))
            .execute()
    }
    check(result.errors.isEmpty() && !collector.errors) {
        "Typing heuristic tests failed." +
            result.errors.joinToString(separator = "\n", prefix = "\n") { it.stackTraceToString() }
    }
}

private class TypingHeuristicsTest : FunSpec({
    test("manifest type takes precedence over name and description heuristics") {
        inferType(
            linkedMapOf("type" to "string", "description" to "If true"),
            inputName = "skip-cache",
        ) shouldBe TypeInference(
            type = "string",
            reason = "the action manifest declares `type: string`",
        )
    }

    withData(
        nameFn = { (declaredType, _) -> "maps declared type `$declaredType`" },
        listOf(
            "boolean" to TypeInference(
                type = "boolean",
                reason = "the action manifest declares `type: boolean`",
            ),
            "bool" to TypeInference(
                type = "boolean",
                reason = "the action manifest declares `type: bool`",
            ),
            "integer" to TypeInference(
                type = "integer",
                reason = "the action manifest declares `type: integer`",
            ),
            "int" to TypeInference(
                type = "integer",
                reason = "the action manifest declares `type: int`",
            ),
            "long" to TypeInference(
                type = "integer",
                reason = "the action manifest declares `type: long`",
            ),
            "float" to TypeInference(
                type = "float",
                reason = "the action manifest declares `type: float`",
            ),
            "number" to TypeInference(
                type = "float",
                reason = "the action manifest declares `type: number`",
            ),
            "double" to TypeInference(
                type = "float",
                reason = "the action manifest declares `type: double`",
            ),
            "string" to TypeInference(
                type = "string",
                reason = "the action manifest declares `type: string`",
            ),
        ),
    ) { (declaredType, expected) ->
        inferType(linkedMapOf("type" to declaredType)) shouldBe expected
    }

    withData(
        nameFn = { (inputName, _) -> "infers boolean for input `$inputName`" },
        listOf(
            "skip-cache" to TypeInference(
                type = "boolean",
                reason = "the input name starts with `skip`",
            ),
            "check-cache" to TypeInference(
                type = "boolean",
                reason = "the input name starts with `check`",
            ),
            "allow-cache" to TypeInference(
                type = "boolean",
                reason = "the input name starts with `allow`",
            ),
            "SKIP-cache" to TypeInference(
                type = "boolean",
                reason = "the input name starts with `skip`",
            ),
        ),
    ) { (inputName, expected) ->
        inferType(
            linkedMapOf("description" to "Not a boolean description"),
            inputName = inputName,
        ) shouldBe expected
    }

    test("infers booleans from descriptions starting with the word If") {
        inferType(
            linkedMapOf("description" to "If the token is set, use it"),
            inputName = "token",
        ) shouldBe TypeInference(
            type = "boolean",
            reason = "the input description starts with `If`",
        )

        inferType(
            linkedMapOf("description" to "ifdef is not the word If"),
            inputName = "token",
        ) shouldBe TypeInference(type = "string", reason = null)
    }

    test("infers types from default values") {
        inferType(linkedMapOf("default" to false)) shouldBe TypeInference(
            type = "boolean",
            reason = "the action manifest does not declare a supported type, but its default value is `false`",
        )
        inferType(linkedMapOf("default" to 42)) shouldBe TypeInference(
            type = "integer",
            reason = "the action manifest does not declare a supported type, but its default value is `42`",
        )
        inferType(linkedMapOf("default" to 1.5)) shouldBe TypeInference(
            type = "float",
            reason = "the action manifest does not declare a supported type, but its default value is `1.5`",
        )
    }

    test("falls back to string without a reason") {
        inferType(null) shouldBe TypeInference(type = "string", reason = null)
        inferType(linkedMapOf<String, Any?>()) shouldBe TypeInference(type = "string", reason = null)
        inferType(linkedMapOf("description" to "Some description")) shouldBe
            TypeInference(type = "string", reason = null)
    }
})
