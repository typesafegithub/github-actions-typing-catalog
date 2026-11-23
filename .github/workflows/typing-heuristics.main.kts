#!/usr/bin/env kotlin
@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.kotest:kotest-framework-engine-jvm:6.2.7")
@file:DependsOn("io.kotest:kotest-assertions-core-jvm:6.2.7")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")

import io.kotest.core.spec.SpecRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

// Mirrored by TypeInference in update-typings.main.kts at the script import boundary.
data class HeuristicType(
    val type: String,
    val reason: String?,
    val separator: String? = null,
    val listItemType: String? = null,
    val allowedValues: List<String>? = null,
    val description: String? = null,
) {
    // Only attributes written to action-types.yml are included. `reason` explains the inference,
    // and `description` is used in PR bodies - neither is a typing attribute.
    fun toTypingMap(): Map<String, Any> = buildMap {
        put("type", type)
        separator?.let { put("separator", it) }
        listItemType?.let { put("list-item", mapOf("type" to it)) }
        allowedValues?.let { put("allowed-values", it) }
    }
}

fun List<HeuristicType>.toTypingMaps(): List<Map<String, Any>> = map { it.toTypingMap() }

object TypingHeuristics {
    private val listWordRegex = Regex("""\blist\b""", RegexOption.IGNORE_CASE)
    private val multilineRegex = Regex("""\bmultiline\b""", RegexOption.IGNORE_CASE)
    private val commaSeparatedRegex = Regex("""\bcomma[\s-]+separated\b""", RegexOption.IGNORE_CASE)
    private val commaDelimitedValuesRegex = Regex(
        """\b(?:with commas?|separated by commas?|comma[\s-]+delimited|csv|delimited by commas?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val listCueRegex = Regex(
        """\b(?:multiline string|additional installer flags|flags passed|paths within|exclude from cache|wildcard pattern|one or several|you can also specify|line[\s-]+separated)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val optionsRegex = Regex("""\boptions\s*:\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val enumListCueRegex = Regex(
        """\b(?:supported values|supported options|available options|options|possible values|valid values|one of)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val enumValueTokenRegex = Regex("""^[\w][\w.+/+-]*$""")
    private val enumValueDefaultSuffixRegex = Regex(
        """\s*\((?:default[^)]*)\)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val enumInlineSentenceRegex = Regex("""(?<=[\w`'"])\.""")
    private val enumValuePrefixRegex = Regex("""^(?:are|is|:)\s*""", RegexOption.IGNORE_CASE)
    private val quotedValuesCueRegex = Regex(
        """\b(can be|valid values|values are|possible values|one of|supported|set to|supports)\b[^.;\n]{0,200}""",
        RegexOption.IGNORE_CASE,
    )
    private val singleQuotedValueCueRegex = Regex(
        """\bcan be set to\b[^.;\n]{0,80}""",
        RegexOption.IGNORE_CASE,
    )
    private val specifyOrDescriptionRegex = Regex("""\bspecify\s+(.+?)\.""", RegexOption.IGNORE_CASE)
    private val orSeparatorRegex = Regex("""\s+or\s+""", RegexOption.IGNORE_CASE)
    private val quotedValueRegex = Regex("""'([^']+)'|"([^"]+)"|`([^`]+)`""")
    private val integerDescriptionRegex = Regex(
        """\b(number of times|retry|hours|milliseconds|bytes|integer|retention|retain|threads?|memory|mb|timeout|minutes|count|amount|reported|major version|minor version|patch version|limits the number|ci)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val integerNameRegex = Regex("""(?:seconds|number|issue-number)$""", RegexOption.IGNORE_CASE)
    private val stringIntegerDefaultRegex = Regex("""[+-]?\d+""")
    private val stringFloatDefaultRegex = Regex("""[+-]?(?:\d+\.\d*|\.\d+)""")
    private val booleanDescriptionPrefixRegex = Regex("""^\s*(whether|when)\b""", RegexOption.IGNORE_CASE)
    private val booleanDescriptionWordRegex = Regex(
        """\b(boolean|shorthand for|to false|disable|prevent)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val booleanDescriptionPhraseRegex = Regex(
        """^\s*(?:remove|enforce|enforces)\b|\ballow\b|\bfail\b|\bwhen set ["']?true\b""",
        RegexOption.IGNORE_CASE,
    )
    private val booleanDescriptionWordingRegex = Regex(
        """\bwhen set to ["']?true\b|\benabled when\b|\bvalue is ["']?true\b|\bskip session tagging\b|\brole assumption\b|\bsupport for large files\b|\bbypassed\b|^\s*exempt all\b[^\n]*\bmilestones\b""",
        RegexOption.IGNORE_CASE,
    )
    private val stringDescriptionRegex = Regex("""\barguments\b|\bvalid values\b""", RegexOption.IGNORE_CASE)
    private val booleanNameExcludeRegex = Regex(
        """^(?:check[_-]?name|checkout[_-]?path)$""",
        RegexOption.IGNORE_CASE,
    )
    private val ifDescriptionExcludeRegex = Regex(
        """^if\s+(?:provided|you|specified|present)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val booleanNamePrefixes = listOf(
        "skip", "check", "allow", "ignore", "show", "enable", "disable", "include", "dry",
        "fail-on", "force", "lookup", "ascending", "delete", "only", "debug-only", "always",
        "keep", "clean", "reset",
    )

    fun inferType(definition: Any?, inputName: String? = null): List<HeuristicType> {
        val map = definition as? Map<*, *> ?: return listOf(HeuristicType(type = "string", reason = null))
        val description = (map["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val declaredType = (map["type"] as? String)?.lowercase()

        val inference = when (declaredType) {
            "boolean", "bool" -> HeuristicType(
                type = "boolean",
                reason = "the action manifest declares `type: $declaredType`",
            )
            "integer", "int", "long" -> HeuristicType(
                type = "integer",
                reason = "the action manifest declares `type: $declaredType`",
            )
            "float", "number", "double" -> HeuristicType(
                type = "float",
                reason = "the action manifest declares `type: $declaredType`",
            )
            "string" -> HeuristicType(
                type = "string",
                reason = "the action manifest declares `type: $declaredType`",
            )
            else -> singleQuotedValueEnumHeuristic(map)
                ?: specifyOrEnumHeuristic(map)
                ?: unquotedEnumHeuristic(map)
                ?: enumHeuristic(map)
                ?: quotedValuesEnumHeuristic(map)
                ?: listHeuristic(map) ?: run {
                val heuristicReason = booleanHeuristicReason(inputName, map)
                if (heuristicReason != null) {
                    HeuristicType(type = "boolean", reason = heuristicReason)
                } else {
                    integerDescriptionHeuristic(map)?.let { reason ->
                        HeuristicType(type = "integer", reason = reason)
                    } ?: integerNameHeuristic(inputName)?.let { reason ->
                        HeuristicType(type = "integer", reason = reason)
                    } ?: when (val defaultValue = map["default"]) {
                        is Boolean -> HeuristicType(
                            type = "boolean",
                            reason = defaultValueReason(defaultValue),
                        )
                        is Int, is Long -> HeuristicType(
                            type = "integer",
                            reason = defaultValueReason(defaultValue),
                        )
                        is Double, is Float -> HeuristicType(
                            type = "float",
                            reason = defaultValueReason(defaultValue),
                        )
                        is String -> stringDefaultHeuristic(defaultValue, description, inputName)
                        else -> HeuristicType(type = "string", reason = null)
                    }
                }
            }
        }
        return listOf(inference.copy(description = description))
    }

    private fun enumHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        val allowedValues = optionsRegex.find(description)?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return HeuristicType(
            type = "enum",
            reason = "the input description lists options",
            allowedValues = allowedValues,
        )
    }

    private fun quotedValuesEnumHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        val snippet = quotedValuesCueRegex.find(description)?.value ?: return null
        val allowedValues = quotedValueRegex.findAll(snippet)
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() }.trim() }
            .toList()
            .takeIf { it.size in 2..12 }
            ?: return null

        val loweredValues = allowedValues.map { it.lowercase() }.toSet()
        if (loweredValues == setOf("true", "false") ||
            loweredValues == setOf("true", "always-true", "false")
        ) {
            return null
        }

        return HeuristicType(
            type = "enum",
            reason = "the description lists quoted allowed values",
            allowedValues = allowedValues,
        )
    }

    private fun singleQuotedValueEnumHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        val snippet = singleQuotedValueCueRegex.find(description)?.value ?: return null
        val allowedValues = quotedValueRegex.findAll(snippet)
            .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() }.trim() }
            .toList()
            .takeIf { it.size == 1 }
            ?: return null

        return HeuristicType(
            type = "enum",
            reason = "the description lists quoted allowed values",
            allowedValues = allowedValues,
        )
    }

    private fun specifyOrEnumHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        val candidates = specifyOrDescriptionRegex.find(description)?.groupValues?.get(1)
            ?.split(orSeparatorRegex)
            ?.map { it.trim().trim('\'', '"') }
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (candidates.size !in 2..10 || candidates.any { !enumValueTokenRegex.matches(it) }) {
            return null
        }

        return HeuristicType(
            type = "enum",
            reason = "the description lists allowed values",
            allowedValues = candidates,
        )
    }

    private fun unquotedEnumHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        val cue = enumListCueRegex.find(description) ?: return null
        var rest = description.substring(cue.range.last + 1).trimStart()
        if (cue.value.lowercase().endsWith("options") && rest.startsWith("are", ignoreCase = true)) {
            return null
        }
        rest = enumValuePrefixRegex.replaceFirst(rest, "")

        val bulletValues = mutableListOf<String>()
        for (rawLine in rest.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (bulletValues.isNotEmpty()) break
                continue
            }
            val isBullet = line.matches(Regex("""^[-*]\s+.*"""))
            val isQuoted = line.startsWith("'") || line.startsWith("\"") || line.startsWith("`")
            if (!isBullet && !isQuoted) {
                if (bulletValues.isNotEmpty()) break
                continue
            }
            val value = cleanEnumValue(line)
            if (!enumValueTokenRegex.matches(value) ||
                value.lowercase() in setOf("default", "example", "e.g")
            ) {
                break
            }
            bulletValues.add(value)
        }
        if (bulletValues.size >= 2) {
            return HeuristicType(
                type = "enum",
                reason = "the description lists allowed values",
                allowedValues = bulletValues.take(20),
            )
        }

        val firstLine = rest.substringBefore('\n').trim()
        val period = enumInlineSentenceRegex.find(firstLine)
        val segment = (period?.let { firstLine.substring(0, it.range.first) } ?: firstLine)
            .trimEnd(' ', '.', ';')
        if (segment.isEmpty()) return null
        val allowedValues = segment.split(',').map { cleanEnumValue(it) }
        if (allowedValues.any { !enumValueTokenRegex.matches(it) } || allowedValues.size !in 2..20) {
            return null
        }
        return HeuristicType(
            type = "enum",
            reason = "the description lists allowed values",
            allowedValues = allowedValues,
        )
    }

    private fun cleanEnumValue(text: String): String {
        var value = text.trim()
        Regex("""^[-*]\s+(.+)""").find(value)?.let { value = it.groupValues[1].trim() }
        val colonIndex = value.indexOf(':')
        if (colonIndex >= 0) value = value.substring(0, colonIndex).trim()
        value = value.trim('\'', '"', '`').trim()
        return enumValueDefaultSuffixRegex.replace(value, "").trim()
    }

    private fun listHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        if (!listWordRegex.containsMatchIn(description) &&
            !commaDelimitedValuesRegex.containsMatchIn(description) &&
            !listCueRegex.containsMatchIn(description)
        ) {
            return null
        }

        return when {
            commaSeparatedRegex.containsMatchIn(description) ||
                commaDelimitedValuesRegex.containsMatchIn(description) -> HeuristicType(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            )
            multilineRegex.containsMatchIn(description) -> HeuristicType(
                type = "list",
                reason = "the input description mentions a multiline list",
                separator = "\n",
                listItemType = "string",
            )
            else -> HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            )
        }
    }

    private fun booleanHeuristicReason(inputName: String?, definition: Map<*, *>): String? {
        val description = definition["description"] as? String
        val trimmedDescription = description?.trimStart()
        if (trimmedDescription != null &&
            !stringDescriptionRegex.containsMatchIn(trimmedDescription) &&
            (booleanDescriptionPrefixRegex.containsMatchIn(trimmedDescription) ||
                booleanDescriptionWordRegex.containsMatchIn(trimmedDescription) ||
                booleanDescriptionPhraseRegex.containsMatchIn(trimmedDescription) ||
                booleanDescriptionWordingRegex.containsMatchIn(trimmedDescription))
        ) {
            return "the input description uses boolean wording"
        }

        val lowerName = inputName?.lowercase() ?: return null
        if (lowerName.matches(booleanNameExcludeRegex)) return null
        val matchedPrefix = booleanNamePrefixes.firstOrNull { lowerName.startsWith(it) }
        if (matchedPrefix != null) {
            return "the input name starts with `$matchedPrefix`"
        }

        if (trimmedDescription == null) return null
        val startsWithIfWord = trimmedDescription.startsWith("if", ignoreCase = true) &&
            (trimmedDescription.length == 2 || !trimmedDescription[2].isLetterOrDigit())

        return if (startsWithIfWord && !ifDescriptionExcludeRegex.containsMatchIn(trimmedDescription)) {
            "the input description starts with `${trimmedDescription.take(2)}`"
        } else {
            null
        }
    }

    private fun integerDescriptionHeuristic(definition: Map<*, *>): String? {
        val description = definition["description"] as? String ?: return null
        return if (integerDescriptionRegex.containsMatchIn(description)) {
            "the input description specifies an integer amount"
        } else {
            null
        }
    }

    private fun integerNameHeuristic(inputName: String?): String? {
        if (inputName == null || !integerNameRegex.containsMatchIn(inputName)) return null
        return "the input name specifies an integer amount"
    }

    private fun stringDefaultHeuristic(
        defaultValue: String,
        description: String?,
        inputName: String?,
    ): HeuristicType {
        val text = defaultValue.trim()
        val safeDescription = description.orEmpty()
        val looksBoolean = text.equals("true", ignoreCase = true) || text.equals("false", ignoreCase = true)
        val booleanDefaultAllowed = !stringDescriptionRegex.containsMatchIn(safeDescription) &&
            !safeDescription.startsWith("force", ignoreCase = true)
        if (looksBoolean && booleanDefaultAllowed) {
            return HeuristicType(
                type = "boolean",
                reason = "the action manifest declares a boolean string default of `$text`",
            )
        }

        val numericDefaultAllowed = inputName?.contains("version", ignoreCase = true) != true
        if (numericDefaultAllowed && stringIntegerDefaultRegex.matches(text)) {
            return HeuristicType(
                type = "integer",
                reason = "the action manifest declares an integer string default of `$text`",
            )
        }
        if (numericDefaultAllowed && stringFloatDefaultRegex.matches(text)) {
            return HeuristicType(
                type = "float",
                reason = "the action manifest declares a float string default of `$text`",
            )
        }

        return HeuristicType(type = "string", reason = null)
    }

    private fun defaultValueReason(value: Any): String =
        "the action manifest does not declare a supported type, but its default value is `$value`"
}

// Separate flag so importing this script from update-typings.main.kts doesn't run these tests there.
if (System.getenv("RUN_TYPING_HEURISTICS_TESTS") == "true") {
    runTypingHeuristicsTests()
}

private fun runTypingHeuristicsTests() {
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
        TypingHeuristics.inferType(
            linkedMapOf("type" to "string", "description" to "If true"),
            inputName = "skip-cache",
        ) shouldBe listOf(HeuristicType(
            type = "string",
            reason = "the action manifest declares `type: string`",
            description = "If true",
        ))
    }

    withData(
        nameFn = { (declaredType, _) -> "maps declared type `$declaredType`" },
        listOf(
            "boolean" to HeuristicType(
                type = "boolean",
                reason = "the action manifest declares `type: boolean`",
            ),
            "bool" to HeuristicType(
                type = "boolean",
                reason = "the action manifest declares `type: bool`",
            ),
            "integer" to HeuristicType(
                type = "integer",
                reason = "the action manifest declares `type: integer`",
            ),
            "int" to HeuristicType(
                type = "integer",
                reason = "the action manifest declares `type: int`",
            ),
            "long" to HeuristicType(
                type = "integer",
                reason = "the action manifest declares `type: long`",
            ),
            "float" to HeuristicType(
                type = "float",
                reason = "the action manifest declares `type: float`",
            ),
            "number" to HeuristicType(
                type = "float",
                reason = "the action manifest declares `type: number`",
            ),
            "double" to HeuristicType(
                type = "float",
                reason = "the action manifest declares `type: double`",
            ),
            "string" to HeuristicType(
                type = "string",
                reason = "the action manifest declares `type: string`",
            ),
        ),
    ) { (declaredType, expected) ->
        TypingHeuristics.inferType(linkedMapOf("type" to declaredType)) shouldBe listOf(expected)
    }

    withData(
        nameFn = { (inputName, _) -> "infers boolean for input `$inputName`" },
        listOf(
            "skip-cache" to HeuristicType(
                type = "boolean",
                reason = "the input name starts with `skip`",
            ),
            "check-cache" to HeuristicType(
                type = "boolean",
                reason = "the input name starts with `check`",
            ),
            "allow-cache" to HeuristicType(
                type = "boolean",
                reason = "the input name starts with `allow`",
            ),
            "SKIP-cache" to HeuristicType(
                type = "boolean",
                reason = "the input name starts with `skip`",
            ),
        ),
    ) { (inputName, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Some arbitrary text"),
            inputName = inputName,
        ) shouldBe listOf(expected.copy(description = "Some arbitrary text"))
    }

    withData(
        nameFn = { (inputName, _) -> "infers boolean for input `$inputName`" },
        listOf(
            "ignore-cache" to "ignore",
            "show-progress" to "show",
            "enable-cache" to "enable",
            "disable-cache" to "disable",
            "include-paths" to "include",
            "dry-run" to "dry",
            "fail-on-warnings" to "fail-on",
            "force-push" to "force",
            "lookup-only" to "lookup",
            "ascending-order" to "ascending",
            "delete-branch" to "delete",
            "only-changed" to "only",
            "debug-only" to "debug-only",
            "always-publish" to "always",
            "keep-files" to "keep",
            "clean-workspace" to "clean",
            "reset-cache" to "reset",
        ),
    ) { (inputName, prefix) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Some arbitrary text"),
            inputName = inputName,
        ) shouldBe listOf(HeuristicType(
            type = "boolean",
            reason = "the input name starts with `$prefix`",
            description = "Some arbitrary text",
        ))
    }

    withData(
        nameFn = { description -> "infers booleans for description `$description`" },
        listOf(
            "Whether to cache dependencies.",
            "When enabled, publish the artifact.",
            "A boolean flag controlling caching.",
            "This is a shorthand for skipping tests.",
            "Set the flag to false to opt out.",
            "Disable caching for this step.",
            "Prevent the deployment from continuing.",
        ),
    ) { description ->
        listOf(null, "some-input").forEach { inputName ->
            TypingHeuristics.inferType(linkedMapOf("description" to description), inputName = inputName) shouldBe
                listOf(HeuristicType(
                    type = "boolean",
                    reason = "the input description uses boolean wording",
                    description = description,
                ))
        }
    }

    withData(
        nameFn = { (description, _) -> "infers booleans from phrase `$description`" },
        listOf(
            "Remove stale labels from issues when they are updated." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Enforce the configured Develocity URL." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Allow communication with an untrusted server." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Fail the action when the upload fails." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Reports all individual failures when set \"true\" or the first only otherwise." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Support for large files is enabled when set to \"true\"." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Caching is enabled when dependency caching is configured." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Value is \"true\" when the repository was dirty." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Skip session tagging during role assumption." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "The task uses role assumption for AWS." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Integrity checking will be bypassed." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
            "Exempt all issues with milestones from being marked as stale." to HeuristicType(
                type = "boolean",
                reason = "the input description uses boolean wording",
            ),
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe
            listOf(expected.copy(description = description))
    }

    test("does not infer booleans from descriptions that explicitly accept strings") {
        listOf(
            "Whether to push the commit. It can also be used as git push arguments.",
            "Whether to mask this value. Valid values are 'true' and 'false'.",
        ).forEach { description ->
            TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe listOf(HeuristicType(
                type = "string",
                reason = null,
                description = description,
            ))
        }
    }

    test("infers booleans from descriptions starting with the word If") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "If the token is set, use it"),
            inputName = "token",
        ) shouldBe listOf(HeuristicType(
            type = "boolean",
            reason = "the input description starts with `If`",
            description = "If the token is set, use it",
        ))

        TypingHeuristics.inferType(
            linkedMapOf("description" to "ifdef is not the word If"),
            inputName = "token",
        ) shouldBe listOf(HeuristicType(type = "string", reason = null, description = "ifdef is not the word If"))
    }

    withData(
        nameFn = { (inputName, _) -> "keeps expected typing for input `$inputName`" },
        listOf(
            "check_name" to HeuristicType(
                type = "string",
                reason = null,
            ),
            "checkout_path" to HeuristicType(
                type = "string",
                reason = null,
            ),
        ),
    ) { (inputName, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Some arbitrary text"),
            inputName = inputName,
        ) shouldBe listOf(expected.copy(description = "Some arbitrary text"))
    }

    withData(
        nameFn = { description -> "keeps expected typing for description `$description`" },
        listOf(
            "If provided, will be used to authenticate the calls to the GitHub API.",
            "If you would like to push the contents into a specific directory you can specify it here.",
            "If specified, a discussion of the specified category is created.",
            "If present, and `url` is to a GitHub release asset, we will use this token.",
        ),
    ) { description ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to description),
            inputName = "token",
        ) shouldBe listOf(HeuristicType(
            type = "string",
            reason = null,
            description = description,
        ))
    }

    withData(
        nameFn = { (description, _) -> "infers a list from description `$description`" },
        listOf(
            "Multiline list of Maven mirrors in the format `mirror-id:mirror-url:mirror-of`." to HeuristicType(
                type = "list",
                reason = "the input description mentions a multiline list",
                separator = "\n",
                listItemType = "string",
            ),
            "MULTILINE LIST of Maven mirrors." to HeuristicType(
                type = "list",
                reason = "the input description mentions a multiline list",
                separator = "\n",
                listItemType = "string",
            ),
            "Comma-separated list of labels to apply." to HeuristicType(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
            "Comma separated list of labels to apply." to HeuristicType(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
            "Exempt all issues with milestones with commas e.g. milestone1,milestone2." to HeuristicType(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
            "A comma delimited set of labels to apply." to HeuristicType(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
            "An ordered multiline string listing cache keys." to HeuristicType(
                type = "list",
                reason = "the input description mentions a multiline list",
                separator = "\n",
                listItemType = "string",
            ),
            "A file, directory or wildcard pattern that describes what to upload." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "Additional installer flags passed to the installer script." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "Paths within Gradle User Home to cache." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "Paths within Gradle User Home to exclude from cache." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "One or several sharable configurations for the build." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "You can also specify version ranges for the extra plugins." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "Entries are line-separated." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to description),
            inputName = "some-input",
        ) shouldBe listOf(expected.copy(description = description))
    }

    withData(
        nameFn = { (description, _) -> "keeps no separator alternatives for `$description`" },
        listOf(
            "Comma or newline-separated list of labels." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "A list of Maven mirrors." to HeuristicType(
                type = "list",
                reason = "the input description mentions a list",
                separator = "\n",
                listItemType = "string",
            ),
            "Comma-separated list of labels to apply." to HeuristicType(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe
            listOf(expected.copy(description = description))
    }

    withData(
        nameFn = { (inputName, description, _) ->
            "keeps current typing for `${inputName ?: "outputs"}` in `$description`"
        },
        listOf<Triple<String?, String, HeuristicType>>(
            Triple(
                "platforms",
                "Target platforms to build.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "platforms",
                "List of target platforms for build.",
                HeuristicType(
                    type = "list",
                    reason = "the input description mentions a list",
                    separator = "\n",
                    listItemType = "string",
                ),
            ),
            Triple(
                null,
                "Comma separated GitHub user names.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                null,
                "The name of the container or containers. If more than one container, add the names comma separated.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "xunit_files",
                "Deprecated, use \"files\" option instead.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "days-before-pr-stale",
                "The number of days old a pull request can be before marking it stale. Set to -1 to never mark it stale.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "artifact-id",
                "A unique identifier for the artifact.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "artifact-ids",
                "IDs of the artifacts to download.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                null,
                "List of all closed issues and pull requests.",
                HeuristicType(
                    type = "list",
                    reason = "the input description mentions a list",
                    separator = "\n",
                    listItemType = "string",
                ),
            ),
            Triple(
                "if-no-files-found",
                "The desired behavior if no files are found. Available Options:\n  warn: Warn\n  error: Fail",
                HeuristicType(type = "boolean", reason = "the input description uses boolean wording"),
            ),
            Triple(
                "close-issue-reason",
                "The reason to use when closing an issue.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                null,
                "Exempt all pull requests with assignees from being marked as stale.",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "exempt-all-pr-assignees",
                "Some arbitrary text",
                HeuristicType(type = "string", reason = null),
            ),
            Triple(
                "cache-cleanup",
                "By default ('on-success'), cleanup is performed. This behaviour can be disabled ('never'), " +
                    "or configured to always run ('always'). Valid values are 'never', 'on-success' and 'always'.",
                HeuristicType(
                    type = "enum",
                    reason = "the description lists quoted allowed values",
                    allowedValues = listOf("never", "always"),
                ),
            ),
            Triple(
                "secondary-rate-limit-wait-seconds",
                "Sets the number of seconds to wait before retrying secondary rate limit errors.",
                HeuristicType(type = "integer", reason = "the input name specifies an integer amount"),
            ),
            Triple(
                "failed-wrapper",
                "The path of the Gradle Wrapper(s) JAR that failed validation. Multiple paths are separated by a | character.",
                HeuristicType(type = "string", reason = null),
            ),
        ),
    ) { (inputName, description, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to description),
            inputName = inputName,
        ) shouldBe listOf(expected.copy(description = description))
    }

    test("assumes newline separators for a list without a detected separator") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "A list of Maven mirrors."),
            inputName = "mvn-mirrors",
        ) shouldBe listOf(HeuristicType(
            type = "list",
            reason = "the input description mentions a list",
            separator = "\n",
            listItemType = "string",
            description = "A list of Maven mirrors.",
        ))
    }

    test("does not treat words containing `list` as a list") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "A holistic approach to Maven mirrors."),
            inputName = "mvn-mirrors",
        ) shouldBe listOf(HeuristicType(type = "string", reason = null, description = "A holistic approach to Maven mirrors."))
    }

    test("infers types from default values") {
        TypingHeuristics.inferType(linkedMapOf("default" to false)) shouldBe listOf(HeuristicType(
            type = "boolean",
            reason = "the action manifest does not declare a supported type, but its default value is `false`",
        ))
        TypingHeuristics.inferType(linkedMapOf("default" to 42)) shouldBe listOf(HeuristicType(
            type = "integer",
            reason = "the action manifest does not declare a supported type, but its default value is `42`",
        ))
        TypingHeuristics.inferType(linkedMapOf("default" to 1.5)) shouldBe listOf(HeuristicType(
            type = "float",
            reason = "the action manifest does not declare a supported type, but its default value is `1.5`",
        ))
    }

    withData(
        nameFn = { (description, defaultValue) ->
            "infers boolean from boolean string default `$defaultValue` in `$description`"
        },
        listOf(
            "Enable debug logging." to "true",
            "Run the post step to save the cache even if another step before fails" to "false",
        ),
    ) { (description, defaultValue) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to description, "default" to defaultValue),
        ) shouldBe listOf(HeuristicType(
            type = "boolean",
            reason = "the action manifest declares a boolean string default of `$defaultValue`",
            description = description,
        ))
    }

    withData(
        nameFn = { (description, defaultValue, expected) ->
            "infers ${expected.type} from numeric string default `$defaultValue` in `$description`"
        },
        listOf(
            Triple(
                "The configured value for the build.",
                "10",
                HeuristicType(
                    type = "integer",
                    reason = "the action manifest declares an integer string default of `10`",
                ),
            ),
            Triple(
                "The configured ratio for the build.",
                "1.5",
                HeuristicType(
                    type = "float",
                    reason = "the action manifest declares a float string default of `1.5`",
                ),
            ),
        ),
    ) { (description, defaultValue, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to description, "default" to defaultValue),
        ) shouldBe listOf(expected.copy(description = description))
    }

    withData(
        nameFn = { description -> "infers integer from numeric wording in `$description`" },
        listOf(
            "The number of times to retry a request.",
            "Retry the request on failure.",
            "Cache for 12 hours.",
            "Wait for 100 milliseconds.",
            "Maximum upload size in bytes.",
            "The number is stored as an integer.",
        ),
    ) { description ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe listOf(HeuristicType(
            type = "integer",
            reason = "the input description specifies an integer amount",
            description = description,
        ))
    }

    withData(
        nameFn = { (description, _) -> "infers integer from amount wording in `$description`" },
        listOf(
            "Specifies the number of days to retain any artifacts generated by the action." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "The amount of memory in MB that can be used by the extractor." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "The number of threads that can be used by CodeQL." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "Count of passed tests." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "A global timeout in seconds for the action." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "The build was reported as successful." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "The major version of the action." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "Limits the number of concurrent jobs." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
            "Run in CI." to HeuristicType(
                type = "integer",
                reason = "the input description specifies an integer amount",
            ),
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe
            listOf(expected.copy(description = description))
    }

    withData(
        nameFn = { (inputName, _) -> "keeps expected typing for input `$inputName`" },
        listOf(
            "wait-seconds" to HeuristicType(
                type = "integer",
                reason = "the input name specifies an integer amount",
            ),
            "pr-number" to HeuristicType(
                type = "integer",
                reason = "the input name specifies an integer amount",
            ),
            "issue-number" to HeuristicType(
                type = "integer",
                reason = "the input name specifies an integer amount",
            ),
        ),
    ) { (inputName, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Some arbitrary text"),
            inputName = inputName,
        ) shouldBe listOf(expected.copy(description = "Some arbitrary text"))
    }

    test("does not infer scalar types from misleading string defaults") {
        val stringArgumentDescription =
            "Whether to push the commit. It can also be used as git push arguments."
        TypingHeuristics.inferType(
            linkedMapOf("description" to stringArgumentDescription, "default" to "true"),
            inputName = "push",
        ) shouldBe listOf(HeuristicType(type = "string", reason = null, description = stringArgumentDescription))

        val forceDescription = "Force adding package suffix to Go benchmark names."
        TypingHeuristics.inferType(
            linkedMapOf("description" to forceDescription, "default" to "false"),
            inputName = "go-force-package-suffix",
        ) shouldBe listOf(HeuristicType(type = "string", reason = null, description = forceDescription))

        val versionDescription = "The Julia version to download (if necessary) and use."
        TypingHeuristics.inferType(
            linkedMapOf("description" to versionDescription, "default" to "1"),
            inputName = "version",
        ) shouldBe listOf(HeuristicType(type = "string", reason = null, description = versionDescription))
    }

    test("falls back to string without a reason") {
        TypingHeuristics.inferType(null) shouldBe listOf(HeuristicType(type = "string", reason = null))
        TypingHeuristics.inferType(linkedMapOf<String, Any?>()) shouldBe listOf(HeuristicType(type = "string", reason = null))
        TypingHeuristics.inferType(linkedMapOf("description" to "Some description")) shouldBe
            listOf(HeuristicType(type = "string", reason = null, description = "Some description"))
    }

    test("infers an enum from options listed in the description") {
        val description = "Choose the token type for ECR Private registries. " +
            "Options: [regional-token, global-token]"
        val inference = TypingHeuristics.inferType(
            linkedMapOf("description" to description, "default" to "regional-token"),
            inputName = "ecr-private-token-type",
        ).single()

        inference.type shouldBe "enum"
        inference.reason shouldBe "the input description lists options"
        inference.allowedValues shouldBe listOf("regional-token", "global-token")
    }

    withData(
        nameFn = { (description, _) -> "infers enum from quoted choices in `$description`" },
        listOf(
            "Can be set to 'read' or 'write'." to listOf("read", "write"),
            "It can be \"maven\", \"gradle\" or \"sbt\"." to listOf("maven", "gradle", "sbt"),
            "Valid values are `never`, `on-success`, or `always`." to
                listOf("never", "on-success", "always"),
            "The deployment target supports 'production' and 'staging'." to
                listOf("production", "staging"),
            "The log level can be 'debug', 'info', or 'warn'." to listOf("debug", "info", "warn"),
        ),
    ) { (description, allowedValues) ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe listOf(HeuristicType(
            type = "enum",
            reason = "the description lists quoted allowed values",
            allowedValues = allowedValues,
            description = description,
        ))
    }

    withData(
        nameFn = { (description, _) -> "infers enum from unquoted choices in `$description`" },
        listOf(
            "Optional architecture for the .NET install. Supported values: x64, x86, arm64." to
                HeuristicType(
                    type = "enum",
                    reason = "the description lists allowed values",
                    allowedValues = listOf("x64", "x86", "arm64"),
                ),
            "Supported options:\n  - all\n  - failed\n  - none" to
                HeuristicType(
                    type = "enum",
                    reason = "the description lists allowed values",
                    allowedValues = listOf("all", "failed", "none"),
                ),
            "The behavior on digest mismatch. Options: ignore, info, warn, error." to
                HeuristicType(
                    type = "enum",
                    reason = "the description lists allowed values",
                    allowedValues = listOf("ignore", "info", "warn", "error"),
                ),
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe
            listOf(expected.copy(description = description))
    }

    withData(
        nameFn = { (description, _) -> "keeps expected typing for description `$description`" },
        listOf(
            "Can be set to 'read'." to HeuristicType(
                type = "enum",
                reason = "the description lists quoted allowed values",
                allowedValues = listOf("read"),
            ),
            "Specify success or failure or cancelled or custom." to HeuristicType(
                type = "enum",
                reason = "the description lists allowed values",
                allowedValues = listOf("success", "failure", "cancelled", "custom"),
            ),
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe
            listOf(expected.copy(description = description))
    }

    withData(
        nameFn = { description -> "keeps string typing for boolean-looking values in `$description`" },
        listOf(
            "Valid values are 'true' and 'false'.",
            "Valid values are `true`, `always-true`, and `false`.",
            "Choose which CLI command to run. Options are \"upload-coverage\", \"empty-upload\", " +
                "\"pr-base-picking\", \"send-notifications\".",
        ),
    ) { description ->
        TypingHeuristics.inferType(linkedMapOf("description" to description)) shouldBe listOf(HeuristicType(
            type = "string",
            reason = null,
            description = description,
        ))
    }

    test("enum options take precedence over name heuristics and default values") {
        val inference = TypingHeuristics.inferType(
            linkedMapOf(
                "description" to "Options: [regional-token, global-token]",
                "default" to true,
            ),
            inputName = "skip-token-type",
        ).single()

        inference.type shouldBe "enum"
        inference.reason shouldBe "the input description lists options"
    }

    test("does not infer an enum from an options word without a list of options") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Choose the token type from the available options."),
            inputName = "token-type",
        ) shouldBe listOf(HeuristicType(
            type = "string",
            reason = null,
            description = "Choose the token type from the available options.",
        ))
    }

    test("maps a list inference to YAML typing attributes") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Comma-separated list of labels to apply."),
            inputName = "labels",
        ).toTypingMaps() shouldBe listOf(mapOf(
            "type" to "list",
            "separator" to ",",
            "list-item" to mapOf("type" to "string"),
        ))
    }

    test("maps an enum inference to YAML typing attributes") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Options: [regional-token, global-token]"),
            inputName = "token-type",
        ).toTypingMaps() shouldBe listOf(mapOf(
            "type" to "enum",
            "allowed-values" to listOf("regional-token", "global-token"),
        ))
    }

    test("maps each typing variant to YAML typing attributes") {
        val primary = HeuristicType(
            type = "list",
            reason = "the input description mentions a list",
            separator = "\n",
            listItemType = "string",
        )
        val alternative = primary.copy(
            reason = "the input description allows comma or newline separators",
            separator = ",",
        )

        listOf(primary, alternative).map { it.toTypingMap() } shouldBe listOf(
            mapOf(
                "type" to "list",
                "separator" to "\n",
                "list-item" to mapOf("type" to "string"),
            ),
            mapOf(
                "type" to "list",
                "separator" to ",",
                "list-item" to mapOf("type" to "string"),
            ),
        )
    }
})
