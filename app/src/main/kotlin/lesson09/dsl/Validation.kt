package lesson09.dsl

import lesson09.HttpResponse
import lesson09.support.json.JsonParseResult
import lesson09.support.json.parseJsonObject

@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class ResponseValidationDsl

data class Mismatch(val path: String, val expected: String, val actual: String) {
    override fun toString(): String = "$path: expected $expected, actual $actual"
}

data class ValidationResult(val mismatches: List<Mismatch>) {
    val isValid: Boolean get() = mismatches.isEmpty()

    fun describe(): String = mismatches.joinToString("\n")
}

fun validate(response: HttpResponse, block: ResponseScope.() -> Unit): ValidationResult {
    val mismatches = mutableListOf<Mismatch>()
    ResponseScope(response, mismatches).block()
    return ValidationResult(mismatches.toList())
}

private fun quoted(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 32) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun rendered(value: Any?): String = when (value) {
    null -> "null"
    is String -> "string ${quoted(value)}"
    is Long -> "integer $value"
    is Boolean -> "boolean $value"
    else -> value.toString()
}

@ResponseValidationDsl
class ResponseScope internal constructor(
    private val response: HttpResponse,
    private val mismatches: MutableList<Mismatch>,
) {
    fun status(block: StatusScope.() -> Unit) = StatusScope(response.status, mismatches).block()

    fun header(name: String, block: HeaderScope.() -> Unit) {
        val entries = response.headers.entries.filter { it.key.equals(name, ignoreCase = true) }
        val values = if (entries.isEmpty()) null else entries.flatMap { it.value }
        HeaderScope("headers[${quoted(name)}]", values, mismatches).block()
    }

    fun body(block: BodyScope.() -> Unit) = BodyScope(response.body, mismatches).block()
}

@ResponseValidationDsl
class StatusScope internal constructor(
    private val value: Int,
    private val mismatches: MutableList<Mismatch>,
) {
    fun equalTo(expected: Int) {
        if (value != expected) {
            mismatches.add(Mismatch("status", "$expected", "$value"))
        }
    }
}

@ResponseValidationDsl
class HeaderScope internal constructor(
    private val path: String,
    private val values: List<String>?,
    private val mismatches: MutableList<Mismatch>,
) {
    fun equalTo(expected: String) = satisfies("value ${quoted(expected)}") { it == expected }

    fun startsWith(prefix: String) = satisfies("prefix ${quoted(prefix)}") { it.startsWith(prefix) }

    fun satisfies(description: String, predicate: (String) -> Boolean) {
        if (values == null || values.none(predicate)) {
            val actual = values?.joinToString(prefix = "[", postfix = "]") { quoted(it) } ?: "<missing>"
            mismatches.add(Mismatch(path, description, actual))
        }
    }
}

@ResponseValidationDsl
class BodyScope internal constructor(
    private val value: String?,
    private val mismatches: MutableList<Mismatch>,
) {
    fun absentOrEmpty() {
        if (!value.isNullOrEmpty()) {
            mismatches.add(Mismatch("body", "absent or empty", rendered(value)))
        }
    }

    fun nonEmpty() {
        if (value.isNullOrEmpty()) {
            mismatches.add(Mismatch("body", "non-empty string", rendered(value)))
        }
    }

    fun json(block: JsonScope.() -> Unit) {
        if (value == null) {
            mismatches.add(Mismatch("body.json", "JSON object", "<missing body>"))
            return
        }
        when (val parsed = parseJsonObject(value)) {
            is JsonParseResult.Success -> JsonScope(parsed.fields, mismatches).block()
            is JsonParseResult.Failure -> {
                mismatches.add(Mismatch("body.json", "JSON object", parsed.reason))
            }
        }
    }
}

@ResponseValidationDsl
class JsonScope internal constructor(
    private val fields: Map<String, Any?>,
    private val mismatches: MutableList<Mismatch>,
) {
    fun field(name: String, block: FieldScope.() -> Unit) =
        FieldScope("body.json[${quoted(name)}]", fields.containsKey(name), fields[name], mismatches).block()
}

@ResponseValidationDsl
class FieldScope internal constructor(
    private val path: String,
    private val exists: Boolean,
    private val value: Any?,
    private val mismatches: MutableList<Mismatch>,
) {
    fun present() {
        if (!exists) {
            mismatches.add(Mismatch(path, "present", "<missing>"))
        }
    }

    fun equalTo(expected: String) = equalValue(expected)
    fun equalTo(expected: Long) = equalValue(expected)
    fun equalTo(expected: Boolean) = equalValue(expected)

    private fun equalValue(expected: Any) {
        if (!exists || value != expected) {
            mismatches.add(Mismatch(path, rendered(expected), if (exists) rendered(value) else "<missing>"))
        }
    }
}
