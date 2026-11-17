import io.kotest.core.spec.SpecRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.CollectingTestEngineListener
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

val listWordRegex = Regex("""\blist\b""", RegexOption.IGNORE_CASE)
val multilineRegex = Regex("""\bmultiline\b""", RegexOption.IGNORE_CASE)
val commaSeparatedRegex = Regex("""\bcomma[\s-]+separated\b""", RegexOption.IGNORE_CASE)

fun inferType(definition: Any?, inputName: String? = null): TypeInference {
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
            val listInference = listHeuristic(map)
            if (listInference != null) {
                listInference
            } else {
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
}

fun listHeuristic(definition: Map<*, *>): TypeInference? {
    val description = definition["description"] as? String ?: return null
    if (!listWordRegex.containsMatchIn(description)) return null

    return when {
        commaSeparatedRegex.containsMatchIn(description) -> TypeInference(
            type = "list",
            reason = "the input description mentions a comma-separated list",
            separator = ",",
            listItemType = "string",
        )
        multilineRegex.containsMatchIn(description) -> TypeInference(
            type = "list",
            reason = "the input description mentions a multiline list",
            separator = "\n",
            listItemType = "string",
        )
        else -> TypeInference(
            type = "list",
            reason = "the input description mentions a list",
            separator = "\n",
            listItemType = "string",
        )
    }
}

fun booleanHeuristicReason(inputName: String, definition: Map<*, *>): String? {
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

fun defaultValueReason(value: Any): String =
    "the action manifest does not declare a supported type, but its default value is `$value`"

data class TypeInference(
    val type: String,
    val reason: String?,
    val separator: String? = null,
    val listItemType: String? = null,
)

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

    withData(
        nameFn = { (description, _) -> "infers a list from description `$description`" },
        listOf(
            "Multiline list of Maven mirrors in the format `mirror-id:mirror-url:mirror-of`." to TypeInference(
                type = "list",
                reason = "the input description mentions a multiline list",
                separator = "\n",
                listItemType = "string",
            ),
            "MULTILINE LIST of Maven mirrors." to TypeInference(
                type = "list",
                reason = "the input description mentions a multiline list",
                separator = "\n",
                listItemType = "string",
            ),
            "Comma-separated list of labels to apply." to TypeInference(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
            "Comma separated list of labels to apply." to TypeInference(
                type = "list",
                reason = "the input description mentions a comma-separated list",
                separator = ",",
                listItemType = "string",
            ),
        ),
    ) { (description, expected) ->
        inferType(
            linkedMapOf("description" to description),
            inputName = "some-input",
        ) shouldBe expected
    }

    test("assumes newline separators for a list without a detected separator") {
        inferType(
            linkedMapOf("description" to "A list of Maven mirrors."),
            inputName = "mvn-mirrors",
        ) shouldBe TypeInference(
            type = "list",
            reason = "the input description mentions a list",
            separator = "\n",
            listItemType = "string",
        )
    }

    test("does not treat words containing `list` as a list") {
        inferType(
            linkedMapOf("description" to "A holistic approach to Maven mirrors."),
            inputName = "mvn-mirrors",
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
