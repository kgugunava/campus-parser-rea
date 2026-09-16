/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.rea.group

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDate
import org.apache.logging.log4j.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.campus.parser.sdk.base.ScheduleCollector
import ru.campus.parser.sdk.model.Entity
import ru.campus.parser.sdk.model.ExplicitDatePredicate
import ru.campus.parser.sdk.model.Schedule
import ru.campus.parser.sdk.model.TimeTableInterval
import ru.campus.parser.sdk.model.WeekScheduleItem
import ru.campus.parsers.rea.ResettableCookieJar
import java.util.concurrent.ConcurrentHashMap

/**
 * Коллектор расписания РЭУ им. Плеханова.
 *
 * Сайт rasp.rea.ru отдаёт расписание не JSON-ом, а HTML-фрагментом (`GET /Schedule/ScheduleCard`) —
 * шесть таблиц (Пн..Сб) на одну неделю одной группы. Собираем 4 недели вперёд от текущей, для
 * каждой пары строим `WeekScheduleItem` с `ExplicitDatePredicate` на конкретную дату (сайт отдаёт
 * занятия по точным датам, а не по чётности недели — как и в примере SPBSTU).
 *
 * Важный нюанс площадки: сама таблица `ScheduleCard` не содержит имени преподавателя — оно есть
 * только в отдельном ответе `GET /Schedule/GetDetailsById?id=<data-elementid>`. Поэтому на каждую
 * пару может понадобиться дополнительный запрос; результаты кэшируются по `elementId` на время
 * жизни коллектора, т.к. одна и та же потоковая лекция встречается в расписании многих групп.
 *
 * Номер недели сайт считает сам, порядковым счётчиком от начала учебного года. Явного API для
 * "текущего номера недели" нет, но есть удобный приём (подтверждён вживую через браузер): запрос
 * с `weekNum=-1` сам резолвит текущую неделю на сервере и возвращает её реальный номер в скрытом
 * поле `<input id="weekNum">` ответа — не нужно ни вычислять его самим, ни хардкодить дату начала
 * учебного года.
 *
 * ## Надёжность на масштабе — путь к текущему дизайну
 *
 * rasp.rea.ru нестабилен под нагрузкой, причём не самым очевидным образом: падает даже первый
 * простой запрос без всякой конкурентности, а деградация усиливается с ростом числа групп в
 * прогоне (3 → 8 → 30), даже при одном и том же мгновенном параллелизме. Ниже — по шагам, что было
 * опробовано вживую на реальном сайте и почему текущая конфигурация выглядит именно так.
 *
 * **Что не сработало (или сработало не так, как ожидалось):**
 * - Общий (на все группы) "штрафной таймер" с плановой паузой каждые N групп + адаптивной паузой
 *   по проценту неудач в скользящем окне, включая эскалацию длительности при повторных триггерах —
 *   выглядело логично (не долбить и так плохо себя чувствующий сайт), но контрольные прогоны на
 *   30 группах показали: даже полные 5 минут тишины НЕ снижали процент неудач после выхода из
 *   паузы. Раз пауза не коррелирует с реальным восстановлением, это чистые накладные расходы
 *   (минуты простоя без всякой пользы взамен) — механизм убран целиком.
 * - `Connection: close` на каждый запрос и джиттер при выходе из паузы (чтобы конкурентные "слоты"
 *   не просыпались одновременно) — тоже не дали измеримого сдвига поверх уже описанных ниже фиксов.
 * - Более детальный разбор конкретного эпизода деградации показал: подряд идущие запросы к РАЗНЫМ
 *   ресурсам (разные `elementId`, разные недели) один за другим проваливались в течение ~100 секунд
 *   — это не самоусиление от нашей же архитектуры (не параллелизм, не переиспользование соединений,
 *   не сессия — проверено при `permits=1` и свежей сессии, и всё равно проваливалось), а похоже на
 *   настоящий внешний эпизод неотзывчивости сайта, который клиентской настройкой не убрать в принципе.
 *
 * **Что действительно помогло:**
 *
 * 1. [groupSemaphore] с `permits = 1` — строго одна группа обрабатывается в любой момент времени.
 *    Первая версия оборачивала permit'ом только отдельные HTTP-вызовы — из-за этого все 10 групп,
 *    запущенных SDK-шным `parallelProcessing`, перехватывали permit друг у друга по кругу, и ни одна
 *    не успевала полностью завершиться. `permits=1` — не просто "самый безопасный" вариант по
 *    мгновенной конкурентности, а ЕЩЁ И необходимое условие для пункта 2 (общий cookie jar на весь
 *    httpClient нельзя безопасно сбрасывать из нескольких параллельно обрабатываемых групп).
 * 2. [establishFreshSession] — сбрасывает общий [cookieJar] и получает новую сессионную куку перед
 *    КАЖДОЙ группой, так что с точки зрения сайта каждая группа выглядит независимым визитом, а не
 *    продолжением одной долгой сессии, копящей историю активности. Живые прогоны показали: первый
 *    сбой начинает происходить заметно позже (~2:20 вместо стабильных ~40-45 секунд на всех прежних
 *    попытках) — то есть часть деградации реально была завязана на накопленное состояние сессии.
 * 3. Разный бюджет терпения и таймаутов в зависимости от цены неудачи — первый запрос группы
 *    (`неделя -1`) критичен (без него теряется вся группа), поэтому у него свой, более щедрый таймаут
 *    ([criticalSocketTimeoutMillis]/[criticalRequestTimeoutMillis]) и больше попыток
 *    ([maxRetriesCritical]). Всё остальное (недели 2-4, детали занятия) деградирует по месту: у него
 *    короче таймаут ([fastFailSocketTimeoutMillis]/[fastFailRequestTimeoutMillis]) и меньше попыток
 *    ([maxRetriesOptional]) — не имеет смысла ждать полную цену встроенного в `HttpClient` retry за
 *    то, что мы и так готовы пропустить. [detailsCache] делает эту "плату" одноразовой на весь прогон
 *    (одна и та же лекция встречается в расписаниях многих групп).
 * 4. [withRetry] — экспоненциальный бэкофф (1с, 2с, 4с...) поверх уже встроенного в `HttpClient`
 *    retry, плюс небольшая пауза [requestSpacingMillis] между запросами внутри группы — дешёвая,
 *    полезная защита от коротких транзиентных сбоев, в отличие от снятого общего "штрафного таймера".
 *
 * Итог: сайт всё ещё может уходить в неотзывчивость на десятки секунд (внешний фактор, не наш) —
 * но текущий дизайн не тратит на это дополнительные минуты простоя сверху, не ломается насмерть на
 * масштабе и не покидает группу из-за единичного сбоя там, где есть шанс восстановиться.
 */
class ReaGroupScheduleCollector(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val cookieJar: ResettableCookieJar,
) : ScheduleCollector {
    private val baseUrl: String = "https://rasp.rea.ru"
    private val weeksAhead: Int = 4
    private val requestSpacingMillis: Long = 400

    // Разный бюджет терпения в зависимости от цены неудачи — см. KDoc класса, пункт 3.
    private val maxRetriesCritical: Int = 3
    private val maxRetriesOptional: Int = 1
    private val maxBackoffMillis: Long = 16_000
    private val fastFailSocketTimeoutMillis: Long = 6_000
    private val fastFailRequestTimeoutMillis: Long = 12_000
    private val criticalSocketTimeoutMillis: Long = 10_000
    private val criticalRequestTimeoutMillis: Long = 20_000

    // permits=1 — необходимое условие для establishFreshSession (общий cookieJar), см. KDoc класса.
    private val groupSemaphore = Semaphore(permits = 1)
    private val detailsCache = ConcurrentHashMap<String, LessonDetails>()

    override suspend fun collectSchedule(
        entity: Entity,
        intervals: List<TimeTableInterval>,
    ): ScheduleCollector.Result = groupSemaphore.withPermit {
        collectScheduleInternal(entity)
    }

    private suspend fun collectScheduleInternal(entity: Entity): ScheduleCollector.Result {
        val key: String = requireNotNull(entity.code) { "У сущности ${entity.name} не задан code (ключ группы)" }

        establishFreshSession(entity.name)

        // Первый запрос критичен — без него не узнать currentWeek, поэтому его провал роняет всю
        // группу целиком (после исчерпания withRetry) — иначе не с чем работать дальше.
        val firstWeekHtml: String = withRetry("${entity.name}: неделя -1", maxRetriesCritical) {
            fetchScheduleCard(key, weekNum = -1, critical = true)
        }
        val currentWeek: Int = resolveWeekNum(firstWeekHtml) ?: 1

        val weekScheduleItems = mutableListOf<WeekScheduleItem>()
        weekScheduleItems += parseWeek(firstWeekHtml)
        logger.info("{}: неделя {} — собрано занятий: {}", entity.name, currentWeek, weekScheduleItems.size)

        // Остальные недели — не критичны: если конкретная неделя не отдалась даже после повторов,
        // пропускаем именно её (группа просто останется без этих дат), а не теряем всю группу.
        for (weekNum in (currentWeek + 1) until (currentWeek + weeksAhead)) {
            val items: List<WeekScheduleItem> = try {
                parseWeek(
                    withRetry("${entity.name}: неделя $weekNum", maxRetriesOptional) {
                        fetchScheduleCard(key, weekNum, critical = false)
                    }
                )
            } catch (exc: Exception) {
                logger.warn("{}: неделя {} не отдалась после {} попыток — пропускаю её", entity.name, weekNum, maxRetriesOptional, exc)
                emptyList()
            }
            weekScheduleItems += items
            logger.info("{}: неделя {} — собрано занятий: {}", entity.name, weekNum, weekScheduleItems.size)
        }

        return ScheduleCollector.Result(processedEntity = entity, weekScheduleItems = weekScheduleItems)
    }

    /**
     * Сбрасывает общий [cookieJar] и получает новую сессионную куку — см. KDoc класса, пункт 2.
     * Best-effort: если сама эта установка не удалась, группу всё равно пробуем обработать с тем,
     * что есть — сайт и так деградирует градуированно на каждом из следующих запросов.
     */
    private suspend fun establishFreshSession(groupName: String) {
        cookieJar.reset()
        try {
            withRetry("$groupName: новая сессия", maxRetriesOptional) {
                throttled {
                    httpClient.get(baseUrl) { applyTimeout(critical = false) }
                }
            }
        } catch (exc: Exception) {
            logger.warn("{}: не удалось получить новую сессию — обрабатываю без неё", groupName, exc)
        }
    }

    /**
     * Общий хелпер повторов для запросов к сайту: до [maxRetries] попыток с экспоненциальной паузой
     * (1с, 2с, 4с, 8с...; ограничена [maxBackoffMillis]) поверх уже встроенного в `HttpClient` retry.
     * [maxRetries] задаётся на вызове — терпим дольше только там, где неудача критична (см. KDoc
     * полей [maxRetriesCritical]/[maxRetriesOptional]).
     */
    private suspend fun <T> withRetry(description: String, maxRetries: Int, block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (exc: Exception) {
                lastError = exc
                val backoffMillis = (1_000L * (1L shl attempt)).coerceAtMost(maxBackoffMillis)
                logger.warn("{}: попытка {}/{} не удалась, жду {} мс", description, attempt + 1, maxRetries, backoffMillis, exc)
                delay(backoffMillis)
            }
        }
        throw lastError!!
    }

    /**
     * Небольшая пауза после запроса — сериализация уже обеспечена [groupSemaphore] на уровне всей
     * группы (см. KDoc класса), здесь только дополнительно придерживаем темп внутри одной группы.
     */
    private suspend fun <T> throttled(block: suspend () -> T): T {
        val result = block()
        delay(requestSpacingMillis)
        return result
    }

    private suspend fun fetchScheduleCard(key: String, weekNum: Int, critical: Boolean): String = throttled {
        httpClient.get("$baseUrl/Schedule/ScheduleCard") {
            header("X-Requested-With", "XMLHttpRequest")
            parameter("selection", key)
            parameter("weekNum", weekNum)
            parameter("catfilter", 0)
            applyTimeout(critical = critical)
        }.body()
    }

    /**
     * Переопределяет таймаут ОДНОГО запроса короче общего щедрого таймаута `HttpClient` из
     * [ReaParser] (20с/60с) — за него платим полную цену на каждой из повторных попыток, если не
     * переопределить. Критический запрос (`критично = true`) и некритичные (`критично = false`)
     * используют разные пороги — см. KDoc класса, пункт 3.
     *
     * Заодно ставит `Connection: close` на каждый запрос — не переиспользуем соединения вообще,
     * проще и надёжнее, чем подбирать подходящий `keepAliveDuration` в пуле [ReaParser].
     */
    private fun HttpRequestBuilder.applyTimeout(critical: Boolean) {
        header(HttpHeaders.Connection, "close")
        timeout {
            if (critical) {
                socketTimeoutMillis = criticalSocketTimeoutMillis
                requestTimeoutMillis = criticalRequestTimeoutMillis
            } else {
                socketTimeoutMillis = fastFailSocketTimeoutMillis
                requestTimeoutMillis = fastFailRequestTimeoutMillis
            }
        }
    }

    private fun resolveWeekNum(html: String): Int? {
        return Jsoup.parse(html).selectFirst("#weekNum")?.attr("value")?.toIntOrNull()
    }

    private suspend fun parseWeek(html: String): List<WeekScheduleItem> {
        val document: Document = Jsoup.parse(html)
        val dayTables: List<Element> = document.select("table.table")

        val items = mutableListOf<WeekScheduleItem>()
        for (table in dayTables) {
            val headerText: String = table.selectFirst("th.dayh h5")?.text() ?: continue
            val date: LocalDate = parseDayHeader(headerText) ?: continue

            for (row in table.select("tr.slot")) {
                val taskLink: Element = row.selectFirst("a.task") ?: continue // строка "Занятия отсутствуют"
                items += parseLessonRow(row, taskLink, date)
            }
        }
        return items
    }

    private suspend fun parseLessonRow(row: Element, taskLink: Element, date: LocalDate): WeekScheduleItem {
        val timeCell: Element = row.selectFirst("td") ?: error("В строке расписания нет ячейки времени")
        val pairNumber: Int = timeCell.selectFirst("span.pcap")
            ?.text()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
            ?: 0

        val times: List<String> = timeCell.textNodes().map { it.text().trim() }.filter { it.isNotEmpty() }
        val startTime: String = times.getOrNull(0).orEmpty()
        val endTime: String = times.getOrNull(1).orEmpty()

        val texts: List<String> = taskLink.textNodes().map { it.text().trim() }.filter { it.isNotEmpty() }
        val subject: String = texts.getOrNull(0).orEmpty()
        val locationLine: String = texts.getOrNull(1).orEmpty()
        val lessonType: String? = taskLink.selectFirst("i")?.text()?.trim()

        val (building, classroom, campus) = parseLocation(locationLine)

        val elementId: String = taskLink.attr("data-elementid")
        val details: LessonDetails = if (elementId.isNotBlank()) detailsFor(elementId) else LessonDetails.EMPTY

        // SDK требует comment/type длиннее 1/2 символов, classroom/building.name длиннее 1 — на
        // живом прогоне на 30 группах реальная аудитория "1" уронила assertLength ДО того, как
        // группа успела сохраниться, и AssertionError (не Exception!) прошёл мимо catch-блока SDK
        // в parallelProcessing и уронил весь процесс целиком. Раз сайт может отдать короткие
        // значения (однозначный номер аудитории и т.п.), подчищаем их сами перед конструированием
        // Schedule.Lesson, а не полагаемся, что assertLength никогда не сработает.
        val lesson = Schedule.Lesson(
            subject = subject,
            comment = nullIfTooShort(campus, minLength = 1),
            type = nullIfTooShort(lessonType, minLength = 2),
            classroom = nullIfTooShort(classroom, minLength = 1),
            building = nullIfTooShort(building, minLength = 1)?.let { Schedule.Building(name = it, address = null, coordinate = null) },
            teachers = details.teachers,
            subgroups = details.subgroups,
            links = if (elementId.isNotBlank()) {
                listOf(Schedule.Link(title = "Ссылка на занятие", url = "$baseUrl/?id=$elementId"))
            } else {
                emptyList()
            }
        )

        return WeekScheduleItem(
            dayOfWeek = date.dayOfWeek,
            timeTableInterval = TimeTableInterval(lessonNumber = pairNumber, startTime = startTime, endTime = endTime),
            dayCondition = ExplicitDatePredicate(date),
            lesson = lesson,
        )
    }

    /**
     * SDK требует у ряда строковых полей `Schedule.Lesson`/`Schedule.Building` длину БОЛЬШЕ
     * [minLength] символов (`assertLength`, см. `parser-sdk`) и бросает `AssertionError` при
     * нарушении — а `AssertionError` не `Exception`, поэтому не ловится SDK-шным `parallelProcessing`
     * и роняет весь процесс, а не только одну группу (найдено на живом прогоне: аудитория "1").
     * Подчищаем короткие значения на null заранее, а не полагаемся, что сайт их не отдаст.
     */
    private fun nullIfTooShort(value: String?, minLength: Int): String? {
        return value?.takeIf { it.length > minLength }
    }

    /**
     * Разбирает строку места проведения вида "3 корпус - 103 , пл. Основная" на корпус/аудиторию/площадку.
     * Основано на единственном реально увиденном формате — для дистанционных/экзаменационных занятий
     * разметка может отличаться, тогда потребуется расширить этот разбор.
     */
    private fun parseLocation(raw: String): Triple<String?, String?, String?> {
        if (raw.isBlank()) return Triple(null, null, null)

        val parts: List<String> = raw.split(",")
        val buildingAndRoom: String = parts.getOrNull(0)?.trim().orEmpty()
        val campus: String? = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        val dashIndex: Int = buildingAndRoom.lastIndexOf('-')
        return if (dashIndex != -1) {
            val building: String? = buildingAndRoom.substring(0, dashIndex).trim().takeIf { it.isNotEmpty() }
            val room: String? = buildingAndRoom.substring(dashIndex + 1).trim().takeIf { it.isNotEmpty() }
            Triple(building, room, campus)
        } else {
            Triple(null, buildingAndRoom.takeIf { it.isNotEmpty() }, campus)
        }
    }

    /** "ПОНЕДЕЛЬНИК, 14.09.2026" -> LocalDate(2026, 9, 14) */
    private fun parseDayHeader(headerText: String): LocalDate? {
        val datePart: String = headerText.substringAfter(",", missingDelimiterValue = "").trim()
        val fields: List<String> = datePart.split(".")
        if (fields.size != 3) return null

        val day: Int = fields[0].trim().toIntOrNull() ?: return null
        val month: Int = fields[1].trim().toIntOrNull() ?: return null
        val year: Int = fields[2].trim().toIntOrNull() ?: return null

        return runCatching { LocalDate(year = year, monthNumber = month, dayOfMonth = day) }.getOrNull()
    }

    /**
     * ФИО/кафедра преподавателя и номер подгруппы есть только в GetDetailsById — отдельным запросом
     * на каждое занятие. Кэшируем по elementId, чтобы не дублировать запросы между группами.
     *
     * Как и `ScheduleCard`, проходит через [withRetry] прежде чем сдаться — но, в отличие от
     * недели целиком, потеря деталей ОДНОГО занятия не критична: при исчерпании попыток занятие
     * остаётся без преподавателя/подгруппы вместо падения всего collectSchedule().
     */
    private suspend fun detailsFor(elementId: String): LessonDetails {
        detailsCache[elementId]?.let { return it }

        val details: LessonDetails = try {
            val html: String = withRetry("детали занятия $elementId", maxRetriesOptional) {
                throttled {
                    httpClient.get("$baseUrl/Schedule/GetDetailsById") {
                        header("X-Requested-With", "XMLHttpRequest")
                        parameter("id", elementId)
                        applyTimeout(critical = false)
                    }.body()
                }
            }
            parseLessonDetails(html)
        } catch (exc: Exception) {
            logger.warn("Не удалось получить детали занятия {} после {} попыток — оставляю без преподавателя", elementId, maxRetriesOptional, exc)
            LessonDetails.EMPTY
        }

        detailsCache[elementId] = details
        return details
    }

    private fun parseLessonDetails(html: String): LessonDetails {
        val document: Document = Jsoup.parse(html)

        // Ссылки на преподавателя и на группу выглядят одинаково (<a href="?q=..."><i>...</i> Имя</a>),
        // различаются только иконкой: "school" у преподавателя, "group" у самой группы.
        val teachers: List<Schedule.Entity> = document.select(".element-info-body a[href^=\"?q=\"]")
            .filter { it.selectFirst("i.material-icons")?.text()?.trim() == "school" }
            .mapNotNull { link ->
                val name: String = link.ownText().trim()
                if (name.isEmpty()) return@mapNotNull null
                Schedule.Entity(name = name, code = link.attr("href").removePrefix("?q="))
            }

        val subgroup: String = document.selectFirst(".element-info-body")
            ?.attr("data-subgroup")
            ?.trim()
            .orEmpty()

        return LessonDetails(
            teachers = teachers,
            subgroups = if (subgroup.isNotEmpty()) listOf(subgroup) else emptyList()
        )
    }

    private data class LessonDetails(
        val teachers: List<Schedule.Entity>,
        val subgroups: List<String>,
    ) {
        companion object {
            val EMPTY = LessonDetails(teachers = emptyList(), subgroups = emptyList())
        }
    }
}
