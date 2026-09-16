/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.rea.group

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.http.content.PartData
import kotlinx.coroutines.delay
import org.apache.logging.log4j.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.campus.parser.sdk.base.EntitiesCollector
import ru.campus.parser.sdk.model.Entity

/**
 * Коллектор групп РЭУ им. Плеханова.
 *
 * На сайте rasp.rea.ru нет отдельного "списка всех групп" — есть каскадные выпадающие списки
 * Факультет → Курс → Уровень → Группа (форма `#tNavigator` на главной странице), подгружаемые
 * друг за другом через `POST /Schedule/Navigator`. Мы обходим это дерево полностью: для каждого
 * факультета — для каждого курса — для каждого уровня — забираем список групп из ответа.
 *
 * Формат запроса к Navigator подтверждён вживую через браузер (Chrome DevTools, перехват реальных
 * XHR-запросов при пошаговом выборе на сайте) — это НЕ реконструкция по JS-коду вслепую. Важные
 * находки, которые не были очевидны из статической разметки:
 *
 * 1. Тело запроса — `multipart/form-data` (обычная браузерная `FormData`), и оно включает
 *    ТОЛЬКО уже пройденные поля цепочки (плюс всегда `Cathedra=na`) — поля глубже текущего шага
 *    (например `Group`/`Teacher` на шаге выбора курса) в запросе отсутствуют вовсе, не передаются
 *    даже как "na". Отправка их с "na" (как я сперва попробовал вручную через curl) не работает —
 *    сервер в ответ отдаёт пустую форму без реальных данных.
 * 2. Финальный шаг (выбор самой Группы) НЕ уходит в Navigator — он сразу открывает
 *    `/Schedule/ScheduleCard`, поэтому обходить нужно только 3 уровня (Факультет/Курс/Уровень),
 *    а список групп разбирается прямо из HTML-ответа на 3-й шаг.
 * 3. `value` у `<option>` группы совпадает с отображаемым именем (напр. "15.24Д-Мен01/26б"), а не
 *    с отдельным "ключом" — но при реальном переходе на расписание сайт использует
 *    ЕГО ЖЕ, только в нижнем регистре (`?q=15.24д-мен01/26б`), это и есть `code`/`selection`
 *    для `ScheduleCard` (см. [ReaGroupScheduleCollector]).
 */
class ReaGroupEntitiesCollector(
    private val httpClient: HttpClient,
    private val logger: Logger,
) : EntitiesCollector {
    private val baseUrl: String = "https://rasp.rea.ru"
    private val maxRetries: Int = 5

    override suspend fun collectEntities(): List<Entity> {
        // Первый обычный GET устанавливает сессионную cookie — все AJAX-запросы ниже требуют её
        // (httpClient по умолчанию хранит cookie между запросами, см. createDefaultHttpClient).
        // Сайт нестабилен — падал даже на этом самом первом простом запросе без всякой нагрузки,
        // поэтому оборачиваем в withRetry, а не только полагаемся на retry внутри HttpClient.
        val indexHtml: String = withRetry("главная страница") { httpClient.get(baseUrl).body() }
        val document: Document = Jsoup.parse(indexHtml)

        val faculties: List<String> = document
            .select("select[name=Faculty] option[value]")
            .map { it.attr("value") }
            .filter { it != "na" }

        logger.info("Найдено факультетов: {}", faculties.size)

        // Каждый уровень дерева обёрнут в свой try/catch: неудача на одном факультете/курсе/уровне
        // не должна ронять сбор остальных — раньше (до этой правки) faculties.flatMap не ловил
        // исключения вовсе, и один окончательно не отдавшийся факультет терял ВСЕ группы вуза.
        return faculties.flatMap { faculty ->
            try {
                collectGroupsForFaculty(faculty)
            } catch (exc: Exception) {
                logger.warn("Факультет \"{}\" не удалось обработать после повторов — пропускаю его целиком", faculty, exc)
                emptyList()
            }
        }
    }

    private suspend fun collectGroupsForFaculty(faculty: String): List<Entity> {
        val coursesHtml: String = withRetry("Navigator: факультет $faculty") {
            navigator(fields = listOf("Faculty" to faculty), changedNode = "Faculty", changedValue = faculty)
        }
        val courses: List<String> = optionsOf(coursesHtml, "Course")

        return courses.flatMap { course ->
            try {
                collectGroupsForCourse(faculty, course)
            } catch (exc: Exception) {
                logger.warn("{} / курс {}: не удалось обработать после повторов — пропускаю", faculty, course, exc)
                emptyList()
            }
        }
    }

    private suspend fun collectGroupsForCourse(faculty: String, course: String): List<Entity> {
        val typesHtml: String = withRetry("Navigator: $faculty / курс $course") {
            navigator(
                fields = listOf("Faculty" to faculty, "Course" to course),
                changedNode = "Course",
                changedValue = course,
            )
        }
        val types: List<String> = optionsOf(typesHtml, "Type")

        return types.flatMap { type ->
            try {
                collectGroupsForType(faculty, course, type)
            } catch (exc: Exception) {
                logger.warn("{} / курс {} / {}: не удалось обработать после повторов — пропускаю", faculty, course, type, exc)
                emptyList()
            }
        }
    }

    private suspend fun collectGroupsForType(faculty: String, course: String, type: String): List<Entity> {
        val groupsHtml: String = withRetry("Navigator: $faculty / курс $course / $type") {
            navigator(
                fields = listOf("Faculty" to faculty, "Course" to course, "Type" to type),
                changedNode = "Type",
                changedValue = type,
            )
        }
        val groupOptions: List<Element> = Jsoup.parse(groupsHtml).select("select[name=Group] option[value]")

        val groups: List<Entity> = groupOptions.mapNotNull { option ->
            val displayName: String = option.attr("value") // value == видимое имя группы, регистр как на сайте
            if (displayName == "na" || displayName.isBlank()) return@mapNotNull null

            buildGroupEntity(displayName = displayName, faculty = faculty, course = course, degree = type)
        }
        logger.info("{} / курс {} / {}: групп найдено {}", faculty, course, type, groups.size)
        return groups
    }

    private fun buildGroupEntity(displayName: String, faculty: String, course: String, degree: String): Entity {
        // Реальная навигация на расписание использует именно нижний регистр этого же значения
        // (проверено: клик по группе на сайте уходит в ScheduleCard?selection=<lowercase>).
        val key: String = displayName.lowercase()

        return Entity(
            type = Entity.Type.Group,
            name = displayName,
            code = key,
            scheduleUrl = scheduleUrlFor(key),
            extra = Entity.Extra(
                faculty = faculty,
                course = course.filter { it.isDigit() }.toIntOrNull(),
                degree = degree,
                educationForm = guessEducationForm(key),
            )
        )
    }

    private fun scheduleUrlFor(key: String): String {
        val builder = URLBuilder()
        builder.takeFrom(baseUrl)
        builder.parameters.append("q", key)
        return builder.buildString()
    }

    /**
     * Эвристика: буква сразу после числового префикса в коде группы (напр. "15.24д-..." / "15.30в-...")
     * похожа на форму обучения — Д(невное)/В(ечернее)/З(аочное). Подтверждена на нескольких реальных
     * группах разных факультетов (везде "д" = очная), но не на всех возможных вариантах —
     * при появлении незнакомой буквы возвращает null, а не бросает исключение.
     */
    private fun guessEducationForm(key: String): String? {
        val letter: Char = Regex("""^[\d.]+([а-яё])-""").find(key)?.groupValues?.get(1)?.firstOrNull() ?: return null
        return when (letter) {
            'д' -> "Очная"
            'в' -> "Очно-заочная"
            'з' -> "Заочная"
            else -> null
        }
    }

    private fun optionsOf(html: String, selectName: String): List<String> {
        return Jsoup.parse(html).select("select[name=$selectName] option[value]")
            .map { it.attr("value") }
            .filter { it != "na" && it.isNotBlank() }
    }

    /**
     * Один шаг каскадного обновления `#tNavigator`. В отличие от первой (неверной) версии этого
     * метода, здесь отправляются ТОЛЬКО уже известные поля цепочки — см. KDoc класса выше.
     */
    private suspend fun navigator(
        fields: List<Pair<String, String>>,
        changedNode: String,
        changedValue: String,
    ): String {
        val parts: List<PartData> = formData {
            fields.forEach { (name, value) -> append(name, value) }
            append("Cathedra", "na")
            append("ChangedNode", changedNode)
            append("ChangedValue", changedValue)
        }
        return httpClient.submitFormWithBinaryData(
            url = "$baseUrl/Schedule/Navigator",
            formData = parts,
        ) {
            // Без этого заголовка сервер отвечает 302 (обычный, не-AJAX сценарий формы),
            // а не HTML-партиалом с реальными данными — подтверждено эмпирически.
            header("X-Requested-With", "XMLHttpRequest")
        }.body()
    }

    /**
     * До [maxRetries] попыток с экспоненциальной паузой (1с, 2с, 4с, 8с, 16с) поверх встроенного
     * в HttpClient retry — сайт нестабилен настолько, что падать может даже самый первый простой
     * запрос без всякой параллельной нагрузки, поэтому терпим дольше, а не сдаёмся сразу.
     */
    private suspend fun <T> withRetry(description: String, block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (exc: Exception) {
                lastError = exc
                val backoffMillis = 1_000L * (1L shl attempt)
                logger.warn("{}: попытка {}/{} не удалась, жду {} мс", description, attempt + 1, maxRetries, backoffMillis, exc)
                delay(backoffMillis)
            }
        }
        throw lastError!!
    }
}
