package lesson09.dsl

import lesson09.HttpResponse

fun check01(response: HttpResponse): ValidationResult = validate(response) {
    status { equalTo(204) }
    body { absentOrEmpty() }
}

fun check02(response: HttpResponse): ValidationResult = validate(response) {
    status { equalTo(200) }
    header("Content-Type") { startsWith("application/json") }
    body { nonEmpty() }
}

fun check03(response: HttpResponse): ValidationResult = validate(response) {
    status { equalTo(200) }
    header("Accept") {
        equalTo("application/json")
        equalTo("text/html")
    }
}

fun check04(response: HttpResponse): ValidationResult = validate(response) {
    status { equalTo(200) }
    body {
        json {
            field("id") { present() }
            field("name") { equalTo("Ivanov") }
        }
    }
}

fun check05(response: HttpResponse): ValidationResult = validate(response) {
    status { equalTo(200) }
    body {
        json {
            field("id") { equalTo(42L) }
            field("active") { equalTo(true) }
        }
    }
}

fun check06(response: HttpResponse): ValidationResult = validate(response) {
    status { equalTo(422) }
    header("Content-Type") { startsWith("application/json") }
    body {
        json {
            field("error") { equalTo("invalid") }
            field("retry") { equalTo(false) }
        }
    }
}
