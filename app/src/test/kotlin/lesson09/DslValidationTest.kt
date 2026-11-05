package lesson09

import lesson09.dsl.Mismatch
import lesson09.dsl.ResponseScope
import lesson09.dsl.ValidationResult
import lesson09.dsl.check01
import lesson09.dsl.check02
import lesson09.dsl.check03
import lesson09.dsl.check04
import lesson09.dsl.check05
import lesson09.dsl.check06
import lesson09.dsl.validate
import lesson09.legacy.check01 as legacy01
import lesson09.legacy.check02 as legacy02
import lesson09.legacy.check03 as legacy03
import lesson09.legacy.check04 as legacy04
import lesson09.legacy.check05 as legacy05
import lesson09.legacy.check06 as legacy06
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DslValidationTest {
    private val jsonHeaders = mapOf("Content-Type" to listOf("application/json"))

    private fun response(
        status: Int = 200,
        body: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
    ) =
        HttpResponse(status, headers, body)

    private fun field(name: String, expected: String, actual: String) =
        Mismatch("body.json[\"$name\"]", expected, actual)

    private fun header(name: String, expected: String, actual: String) =
        Mismatch("headers[\"$name\"]", expected, actual)

    @Test
    fun legacyParityAndExactDiagnosticsForEveryCondition() {
        data class Case(
            val old: (HttpResponse) -> Boolean,
            val new: (HttpResponse) -> ValidationResult,
            val good: HttpResponse,
            val failures: List<Pair<HttpResponse, Mismatch>>,
        )
        val first = response(204)
        val second = response(body = "not JSON", headers = jsonHeaders)
        val third = response(headers = mapOf("Accept" to listOf("application/json", "text/html")))
        val fourth = response(body = """{"id":null,"name":"Ivanov","extra":true}""")
        val fifth = response(body = """{"id":42,"active":true}""")
        val sixth = response(422, """{"error":"invalid","retry":false}""", jsonHeaders)
        val cases = listOf(
            Case(::legacy01, ::check01, first, listOf(
                first.copy(status = 500) to Mismatch("status", "204", "500"),
                first.copy(body = " ") to Mismatch("body", "absent or empty", "string \" \""),
            )),
            Case(::legacy02, ::check02, second, listOf(
                second.copy(status = 500) to Mismatch("status", "200", "500"),
                second.copy(headers = emptyMap()) to header("Content-Type", "prefix \"application/json\"", "<missing>"),
                second.copy(body = "") to Mismatch("body", "non-empty string", "string \"\""),
            )),
            Case(::legacy03, ::check03, third, listOf(
                third.copy(status = 500) to Mismatch("status", "200", "500"),
                third.copy(headers = mapOf("Accept" to listOf("text/html"))) to header("Accept", "value \"application/json\"", "[\"text/html\"]"),
                third.copy(headers = mapOf("Accept" to listOf("application/json"))) to header("Accept", "value \"text/html\"", "[\"application/json\"]"),
            )),
            Case(::legacy04, ::check04, fourth, listOf(
                fourth.copy(status = 500) to Mismatch("status", "200", "500"),
                fourth.copy(body = "[]") to Mismatch("body.json", "JSON object", "Invalid token at offset 0"),
                fourth.copy(body = """{"name":"Ivanov"}""") to field("id", "present", "<missing>"),
                fourth.copy(body = """{"id":null,"name":"Other"}""") to field("name", "string \"Ivanov\"", "string \"Other\""),
            )),
            Case(::legacy05, ::check05, fifth, listOf(
                fifth.copy(status = 500) to Mismatch("status", "200", "500"),
                fifth.copy(body = null) to Mismatch("body.json", "JSON object", "<missing body>"),
                fifth.copy(body = """{"id":"42","active":true}""") to field("id", "integer 42", "string \"42\""),
                fifth.copy(body = """{"id":42,"active":false}""") to field("active", "boolean true", "boolean false"),
            )),
            Case(::legacy06, ::check06, sixth, listOf(
                sixth.copy(status = 500) to Mismatch("status", "422", "500"),
                sixth.copy(headers = emptyMap()) to header("Content-Type", "prefix \"application/json\"", "<missing>"),
                sixth.copy(body = "[]") to Mismatch("body.json", "JSON object", "Invalid token at offset 0"),
                sixth.copy(body = """{"error":null,"retry":false}""") to field("error", "string \"invalid\"", "null"),
                sixth.copy(body = """{"error":"invalid","retry":"false"}""") to field("retry", "boolean false", "string \"false\""),
            )),
        )
        cases.forEach { case ->
            assertTrue(case.old(case.good))
            assertEquals(emptyList(), case.new(case.good).mismatches)
            case.failures.forEach { (input, expected) ->
                assertEquals(case.old(input), case.new(input).isValid)
                assertEquals(listOf(expected), case.new(input).mismatches)
            }
        }
    }

    @Test
    fun bodyBoundariesAndJsonAreIndependent() {
        listOf(null, "", " ", "text").forEach { body ->
            val input = response(body = body, headers = jsonHeaders)
            assertEquals(body.isNullOrEmpty(), validate(input) { body { absentOrEmpty() } }.isValid)
            assertEquals(!body.isNullOrEmpty(), check02(input).isValid)
            assertEquals(legacy02(input), check02(input).isValid)
        }
    }

    @Test
    fun headerAbsenceEmptyListAndEmptyValueAreDistinct() {
        val block: ResponseScope.() -> Unit = { header("X") { equalTo("ok") } }
        listOf(
            emptyMap<String, List<String>>() to "<missing>",
            mapOf("X" to emptyList<String>()) to "[]",
            mapOf("X" to listOf("")) to "[\"\"]",
        ).forEach { (headers, actual) ->
            assertEquals(listOf(header("X", "value \"ok\"", actual)), validate(response(headers = headers), block).mismatches)
        }
    }

    @Test
    fun headerNamesMergeInMapOrderAndValuesKeepTheirExactContents() {
        val headers = linkedMapOf("accept" to listOf("application/json"), "ACCEPT" to listOf("text/html"), "Extra" to listOf("ignored"))
        assertTrue(check03(response(headers = headers)).isValid)
        assertEquals(listOf(header("Accept", "value \"missing\"", "[\"application/json\", \"text/html\"]")),
            validate(response(headers = headers)) { header("Accept") { equalTo("missing") } }.mismatches)
        listOf("Application/json", " application/json", "application/json ", "application/json,text/html").forEach { value ->
            assertFalse(check03(response(headers = mapOf("Accept" to listOf(value, "text/html")))).isValid)
        }
        assertTrue(check02(response(body = " ", headers = mapOf("content-type" to listOf("wrong", "application/jsonp")))).isValid)
    }

    @Test
    fun jsonPresenceTypesAndEmptySelection() {
        assertTrue(check04(response(body = """{"id":null,"name":"Ivanov"}""")).isValid)
        assertTrue(check05(response(body = """{"id":42,"active":true,"extra":"ignored"}""", headers = jsonHeaders)).isValid)
        assertTrue(validate(response(body = "{}")) { body { json { field("absent") {} } } }.isValid)
        val result = validate(response(body = "{}")) {
            body {
                json {
                    field("id") {
                        present()
                        equalTo(42L)
                        equalTo("42")
                        equalTo(true)
                    }
                }
            }
        }
        assertEquals(listOf("present", "integer 42", "string \"42\"", "boolean true").map { field("id", it, "<missing>") }, result.mismatches)
        assertEquals(listOf(field("id", "integer 42", "null")),
            validate(response(body = """{"id":null}""")) { body { json { field("id") { equalTo(42L) } } } }.mismatches)
    }

    @Test
    fun errorsAccumulateInExecutionOrderIncludingRepeatedChecksAndExplicitOuterReceiver() {
        val result = validate(response(500, "{}", mapOf("Content-Type" to listOf("text/plain")))) {
            status {
                equalTo(200)
                equalTo(200)
            }
            header("Content-Type") { startsWith("application/json") }
            body {
                json { field("id") { present() } }
                this@validate.status { equalTo(204) }
                nonEmpty()
            }
        }
        assertEquals(listOf(
            Mismatch("status", "200", "500"), Mismatch("status", "200", "500"),
            header("Content-Type", "prefix \"application/json\"", "[\"text/plain\"]"),
            field("id", "present", "<missing>"), Mismatch("status", "204", "500"),
        ), result.mismatches)
        assertEquals(result.mismatches.joinToString("\n"), result.describe())
    }

    @Test
    fun invalidJsonSkipsFieldsButContinuesIndependentChecks() {
        listOf(null to "<missing body>", "[]" to "Invalid token at offset 0", "" to "Expected an object").forEach { (source, reason) ->
            var entered = false
            val result = validate(response(500, source)) {
                status { equalTo(200) }
                body {
                    json {
                        entered = true
                        field("id") { present() }
                    }
                    json {
                        entered = true
                        field("other") { equalTo(true) }
                    }
                }
                header("X") { equalTo("yes") }
            }
            assertFalse(entered)
            assertEquals(listOf(Mismatch("status", "200", "500"), Mismatch("body.json", "JSON object", reason),
                Mismatch("body.json", "JSON object", reason),
                header("X", "value \"yes\"", "<missing>")), result.mismatches)
        }
    }

    @Test
    fun predicatesSupportAllThreeFormsAndSkipMissingValues() {
        val predicate: (String) -> Boolean = ::nonBlank
        val forms: List<ResponseScope.() -> Unit> = listOf(
            { header("X") { satisfies("non-blank value") { it.isNotBlank() } } },
            { header("X") { satisfies("non-blank value", predicate) } },
            { header("X") { satisfies("non-blank value", ::nonBlank) } },
        )
        listOf(emptyMap(), mapOf("X" to emptyList()), mapOf("X" to listOf(" ")), mapOf("X" to listOf("", "ok"))).forEach { headers ->
            val results = forms.map { validate(response(headers = headers), it) }
            assertEquals(results[0], results[1])
            assertEquals(results[0], results[2])
            assertEquals(headers["X"]?.any(::nonBlank) == true, results[0].isValid)
        }
        var calls = 0
        listOf(emptyMap(), mapOf("X" to emptyList<String>())).forEach { headers ->
            val result = validate(response(headers = headers)) {
                header("X") {
                    satisfies("anything") {
                        calls++
                        true
                    }
                }
            }
            assertFalse(result.isValid)
        }
        assertEquals(0, calls)
    }

    @Test
    fun reusedBlockHasIndependentResultsAndDoesNotMutateInput() {
        val values = mutableListOf("ok")
        val headers = linkedMapOf("X" to values)
        val good = response(body = "{}", headers = headers)
        val snapshot = good.copy(headers = headers.mapValues { it.value.toList() })
        val block: ResponseScope.() -> Unit = {
            status { equalTo(200) }
            header("X") { equalTo("ok") }
            body { nonEmpty() }
        }
        val first = validate(good, block)
        val second = validate(response(500), block)
        val savedErrors = second.mismatches.toList()
        val third = validate(good, block)
        assertTrue(first.isValid)
        assertTrue(third.isValid)
        assertEquals(listOf(Mismatch("status", "200", "500"), header("X", "value \"ok\"", "<missing>"),
            Mismatch("body", "non-empty string", "null")), savedErrors)
        assertEquals(savedErrors, second.mismatches)
        assertEquals(snapshot, good)
        values.add("later")
        assertEquals(savedErrors, second.mismatches)
    }

    @Test
    fun pathsEscapeNamesUnambiguously() {
        assertEquals(listOf(Mismatch("body.json[\"a.b\\\"c\"]", "present", "<missing>")),
            validate(response(body = "{}")) { body { json { field("a.b\"c") { present() } } } }.mismatches)
    }
}

private fun nonBlank(value: String): Boolean = value.isNotBlank()
