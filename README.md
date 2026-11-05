# Свой DSL

Создайте DSL для проверки HTTP-ответов и перепишите через него шесть функций `check01`–`check06` из `lesson09.legacy`. Новые проверки должны возвращать список расхождений с ожиданиями.

Полное условие, требования к тестам и необязательное усложнение — в [docs/lesson09.md](docs/lesson09.md).

## Что уже есть в проекте

- `app/src/main/kotlin/lesson09/HttpResponse.kt` — модель ответа.
- `app/src/main/kotlin/lesson09/legacy/Checks.kt` — шесть исходных проверок.
- `app/src/main/kotlin/lesson09/support/json/Json.kt` — готовый парсер JSON. Писать свой парсер не нужно.
- `app/src/test/kotlin/lesson09/LegacySmokeTest.kt` — начальные тесты исходных проверок.
- `app/src/test/kotlin/lesson09/support/json/JsonParserTest.kt` — тесты готового парсера.

Реализацию DSL добавьте в пакет `lesson09.dsl`, свои тесты — в `app/src/test/kotlin/lesson09`. Предоставленные исходники и тесты оставьте без изменений.

## Запуск

Установите JDK 25 и укажите его в `JAVA_HOME`. В IntelliJ IDEA выберите этот же JDK для Gradle JVM. Gradle устанавливать отдельно не нужно: проект использует Wrapper. При первом запуске нужен доступ к интернету для загрузки Gradle и зависимостей.

В проекте используются Kotlin 2.4.10, Gradle 9.5.1 и JUnit 6.1.3. Запускайте команды из корня репозитория:

| Действие | Bash | PowerShell |
|---|---|---|
| Сборка и тесты | `bash ./gradlew build` | `.\gradlew.bat build` |
| Тесты | `bash ./gradlew :app:test` | `.\gradlew.bat :app:test` |

Начальные тесты должны проходить до начала работы. По мере реализации DSL добавляйте собственные тесты из условия задания.

Если продолжаете работу в своём проекте курса, перенесите каталоги `app/src/main/kotlin/lesson09`, `app/src/test/kotlin/lesson09` и файл `docs/lesson09.md`. В модуле должны быть подключены Kotlin/JVM, JUnit Jupiter и `kotlin-test-junit5`, а задача тестирования должна использовать `useJUnitPlatform()`; настройки можно взять из `app/build.gradle.kts` и `gradle/libs.versions.toml`.

## Сдача

Передайте преподавателю ссылку на свой репозиторий. Добавьте в README пример использования DSL, пример расхождений и объяснение решения.

## Реализованное решение

Выполнена обязательная часть задания: DSL, новые `check01`–`check06` в
`lesson09.dsl`, сравнение с исходными функциями и тесты пограничных случаев.
Необязательные спецификации, диапазоны статусов и отдельная операция сравнения
с JSON null не добавлялись.

### Пример использования

```kotlin
import lesson09.HttpResponse
import lesson09.dsl.validate

val response = HttpResponse(
    status = 500,
    headers = mapOf("Content-Type" to listOf("text/plain")),
    body = "{}",
)
val result = validate(response) {
    status { equalTo(200) }
    header("Content-Type") { startsWith("application/json") }
    body {
        json {
            field("id") { present() }
        }
    }
}
println(result.isValid) // false
println(result.describe())
```

Результат `describe()`:

```text
status: expected 200, actual 500
headers["Content-Type"]: expected prefix "application/json", actual ["text/plain"]
body.json["id"]: expected present, actual <missing>
```

`ValidationResult` содержит список `mismatches`. Каждая запись `Mismatch` отдельно
хранит `path`, `expected`, `actual`. Успех означает пустой список. Ошибки данных
не бросают исключений и не останавливают независимые проверки. Порядок записей
совпадает с порядком выполнения операций, включая вложенные блоки и повторы.

### Получатели и операции

| Получатель | Операции |
| --- | --- |
| `ResponseScope` | `status`, `header`, `body` |
| `StatusScope` | `equalTo(Int)` |
| `HeaderScope` | `equalTo(String)`, `startsWith`, `satisfies` |
| `BodyScope` | `absentOrEmpty`, `nonEmpty`, `json` |
| `JsonScope` | `field` |
| `FieldScope` | `present`, `equalTo(String / Long / Boolean)` |

Все шесть получателей отмечены `@ResponseValidationDsl`, объявленной через
`@DslMarker`. Внутри `body` неявный вызов `status` внешнего получателя запрещён
компилятором. Явный вызов `this@validate.status { equalTo(200) }` разрешён;
он выполняется именно в месте записи.

Блок настройки имеет тип `ResponseScope.() -> Unit`, вложенные блоки —
`StatusScope.() -> Unit`, `HeaderScope.() -> Unit`, `BodyScope.() -> Unit`,
`JsonScope.() -> Unit`, `FieldScope.() -> Unit`. Все выполняются сразу при
соответствующем вызове. JSON-блок выполняется только после успешного разбора.
Пользовательский предикат имеет тип `(String) -> Boolean`:

```kotlin
val nonBlank: (String) -> Boolean = { it.isNotBlank() }
val result = validate(response) {
    header("X-Trace") { satisfies("non-blank value", nonBlank) }
}
```

Предикат можно передать также литералом лямбды или ссылкой `::functionName`.
Достаточно первого подходящего значения; используется короткое замыкание.
При отсутствии значений предикат не вызывается. Исключения пользовательского
предиката не перехватываются, как и допускает условие. `inline`, `noinline`,
`crossinline`, рефлексия и внешние библиотеки проверок не используются.

### Правила данных и путей

- Пути: `status`, `body`, `body.json`, `headers["name"]`,
  `body.json["field"]`. Имена в скобках экранируются: кавычки, обратная косая
  черта и управляющие символы. Это отличает имя поля `a.b` от вложенного пути.
- Имена заголовков сравниваются без учёта регистра. Значения всех совпавших
  ключей объединяются в порядке обхода исходной Map, затем в порядке исходных
  списков. Для гарантированного порядка используйте `linkedMapOf`.
- Значения заголовков сравниваются с учётом регистра, без удаления пробелов
  и разделения по запятой. Каждая операция независимо ищет подходящее значение.
  Префикс использует обычный `String.startsWith`, включая `application/jsonp`.
- Отсутствующий заголовок отображается как `<missing>`, пустой список как `[]`,
  список с пустой строкой как `[""]`. Строки в диагностике заключены в кавычки.
- `absentOrEmpty` допускает `null` и `""`; `nonEmpty` допускает любую строку
  положительной длины, включая пробелы, и не запускает JSON-парсер.
- Только явный `json` вызывает предоставленный `parseJsonObject`. При `null`
  добавляется одна ошибка `<missing body>`, при неверном JSON — одна ошибка
  с причиной парсера. Поля этого блока не проверяются, следующие проверки
  продолжаются. Каждый повторный вызов `json` проверяется отдельно.
- `present` использует `containsKey`: JSON null присутствует. Равенство
  учитывает тип и значение; целое число задавайте как `42L`. Диагностика
  различает `integer 42`, `string "42"`, `boolean true`, `null`, `<missing>`.
  Сам выбор поля без операций не создаёт ошибок.

### Независимость запусков

Каждый `validate` создаёт новый список ошибок и возвращает его копию.
Получатели существуют только в текущем запуске, входной ответ и его коллекции
не изменяются. Один блок можно сохранить и применить повторно:

```kotlin
import lesson09.dsl.ResponseScope

val rules: ResponseScope.() -> Unit = {
    status { equalTo(200) }
    body { nonEmpty() }
}
val first = validate(response, rules)
val second = validate(response.copy(status = 200, body = "ok"), rules)
```

Предыдущие результаты сохраняются. Это повторное выполнение блока настройки,
а не необязательная компилируемая спецификация из усложнения задания.

### Проверка

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25) # macOS
bash ./gradlew build
```

Для других систем настройте `JAVA_HOME` на JDK 25 и используйте команды из
таблицы выше. HTML-отчёт: `app/build/reports/tests/test/index.html`.

`DslValidationTest` проверяет успешный пример каждой исходной функции и
отдельное нарушение каждого условия, сравнивает успешность старой и новой
реализаций и полные структурированные расхождения. Дополнительные проверки
покрывают тела, заголовки, типы и наличие JSON-полей, порядок и повторы ошибок,
три формы предиката, явный внешний получатель, экранирование путей, сохранность
входа и повторное применение одного блока к хорошему, плохому и хорошему ответу.
