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
)

object TypingHeuristics {
    private val listWordRegex = Regex("""\blist\b""", RegexOption.IGNORE_CASE)
    private val multilineRegex = Regex("""\bmultiline\b""", RegexOption.IGNORE_CASE)
    private val commaSeparatedRegex = Regex("""\bcomma[\s-]+separated\b""", RegexOption.IGNORE_CASE)
    private val optionsRegex = Regex("""\boptions\s*:\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)

    fun inferType(definition: Any?, inputName: String? = null): HeuristicType {
        val map = definition as? Map<*, *> ?: return HeuristicType(type = "string", reason = null)
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
            else -> enumHeuristic(map) ?: listHeuristic(map) ?: run {
                val heuristicReason = inputName?.let { booleanHeuristicReason(it, map) }
                if (heuristicReason != null) {
                    HeuristicType(type = "boolean", reason = heuristicReason)
                } else {
                    when (val defaultValue = map["default"]) {
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
                        else -> HeuristicType(type = "string", reason = null)
                    }
                }
            }
        }
        return inference.copy(description = description)
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

    private fun listHeuristic(definition: Map<*, *>): HeuristicType? {
        val description = definition["description"] as? String ?: return null
        if (!listWordRegex.containsMatchIn(description)) return null

        return when {
            commaSeparatedRegex.containsMatchIn(description) -> HeuristicType(
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
}

fun runTypingHeuristicsTests() {
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
        ) shouldBe HeuristicType(
            type = "string",
            reason = "the action manifest declares `type: string`",
            description = "If true",
        )
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
        TypingHeuristics.inferType(linkedMapOf("type" to declaredType)) shouldBe expected
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
            linkedMapOf("description" to "Not a boolean description"),
            inputName = inputName,
        ) shouldBe expected.copy(description = "Not a boolean description")
    }

    test("infers booleans from descriptions starting with the word If") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "If the token is set, use it"),
            inputName = "token",
        ) shouldBe HeuristicType(
            type = "boolean",
            reason = "the input description starts with `If`",
            description = "If the token is set, use it",
        )

        TypingHeuristics.inferType(
            linkedMapOf("description" to "ifdef is not the word If"),
            inputName = "token",
        ) shouldBe HeuristicType(type = "string", reason = null, description = "ifdef is not the word If")
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
        ),
    ) { (description, expected) ->
        TypingHeuristics.inferType(
            linkedMapOf("description" to description),
            inputName = "some-input",
        ) shouldBe expected.copy(description = description)
    }

    test("assumes newline separators for a list without a detected separator") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "A list of Maven mirrors."),
            inputName = "mvn-mirrors",
        ) shouldBe HeuristicType(
            type = "list",
            reason = "the input description mentions a list",
            separator = "\n",
            listItemType = "string",
            description = "A list of Maven mirrors.",
        )
    }

    test("does not treat words containing `list` as a list") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "A holistic approach to Maven mirrors."),
            inputName = "mvn-mirrors",
        ) shouldBe HeuristicType(type = "string", reason = null, description = "A holistic approach to Maven mirrors.")
    }

    test("infers types from default values") {
        TypingHeuristics.inferType(linkedMapOf("default" to false)) shouldBe HeuristicType(
            type = "boolean",
            reason = "the action manifest does not declare a supported type, but its default value is `false`",
        )
        TypingHeuristics.inferType(linkedMapOf("default" to 42)) shouldBe HeuristicType(
            type = "integer",
            reason = "the action manifest does not declare a supported type, but its default value is `42`",
        )
        TypingHeuristics.inferType(linkedMapOf("default" to 1.5)) shouldBe HeuristicType(
            type = "float",
            reason = "the action manifest does not declare a supported type, but its default value is `1.5`",
        )
    }

    test("falls back to string without a reason") {
        TypingHeuristics.inferType(null) shouldBe HeuristicType(type = "string", reason = null)
        TypingHeuristics.inferType(linkedMapOf<String, Any?>()) shouldBe HeuristicType(type = "string", reason = null)
        TypingHeuristics.inferType(linkedMapOf("description" to "Some description")) shouldBe
            HeuristicType(type = "string", reason = null, description = "Some description")
    }

    test("infers an enum from options listed in the description") {
        val description = "Choose the token type for ECR Private registries. " +
            "Options: [regional-token, global-token]"
        val inference = TypingHeuristics.inferType(
            linkedMapOf("description" to description, "default" to "regional-token"),
            inputName = "ecr-private-token-type",
        )

        inference.type shouldBe "enum"
        inference.reason shouldBe "the input description lists options"
        inference.allowedValues shouldBe listOf("regional-token", "global-token")
    }

    test("enum options take precedence over name heuristics and default values") {
        val inference = TypingHeuristics.inferType(
            linkedMapOf(
                "description" to "Options: [regional-token, global-token]",
                "default" to true,
            ),
            inputName = "skip-token-type",
        )

        inference.type shouldBe "enum"
        inference.reason shouldBe "the input description lists options"
    }

    test("does not infer an enum from an options word without a list of options") {
        TypingHeuristics.inferType(
            linkedMapOf("description" to "Choose the token type from the available options."),
            inputName = "token-type",
        ) shouldBe HeuristicType(
            type = "string",
            reason = null,
            description = "Choose the token type from the available options.",
        )
    }
})
