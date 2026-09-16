/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.rea

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import okhttp3.ConnectionPool
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.campus.parser.sdk.DateProvider
import ru.campus.parser.sdk.api.ParserApi
import ru.campus.parser.sdk.api.createDefaultHttpClient
import ru.campus.parser.sdk.base.BaseParser
import ru.campus.parser.sdk.base.EntitiesCollector
import ru.campus.parser.sdk.base.ScheduleCollector
import ru.campus.parser.sdk.model.Credentials
import ru.campus.parser.sdk.model.Entity
import ru.campus.parser.sdk.model.ParserResult
import ru.campus.parser.sdk.model.ProcessedEntity
import ru.campus.parser.sdk.model.SavedEntity
import ru.campus.parser.sdk.model.SavedSchedule
import ru.campus.parser.sdk.model.Schedule
import ru.campus.parser.sdk.model.TimeTableInterval
import ru.campus.parser.sdk.model.WeekScheduleItem
import ru.campus.parser.sdk.utils.createDefaultDateProvider
import ru.campus.parser.sdk.utils.getParserApiUrl
import ru.campus.parser.sdk.utils.groupLessonsInIntervals
import ru.campus.parser.sdk.utils.weekName
import ru.campus.parser.sdk.utils.weekNumber
import ru.campus.parsers.rea.group.ReaGroupEntitiesCollector
import ru.campus.parsers.rea.group.ReaGroupScheduleCollector
import java.util.concurrent.TimeUnit

/**
 * Донастройка OkHttp: короткий `keepAliveDuration` (20 сек вместо дефолтных 5 минут — см. KDoc
 * [httpClient] ниже) и общий [cookieJar], который [ru.campus.parsers.rea.group.ReaGroupScheduleCollector]
 * может сбрасывать перед каждой группой (см. его KDoc, раздел про независимую сессию на группу).
 * Вынесено в отдельную top-level функцию с явным типом результата: инлайн-лямбда прямо в
 * default-значении параметра конструктора не резолвила `engine { config { ... } }` (вывод типа
 * `HttpClientConfig<OkHttpConfig>` не срабатывал в этом контексте) — так резолвится корректно.
 */
private fun httpClientConfig(cookieJar: ResettableCookieJar): HttpClientConfig<OkHttpConfig>.() -> Unit = {
    engine {
        config {
            connectionPool(ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 20, TimeUnit.SECONDS))
            cookieJar(cookieJar)
        }
    }
}

class ReaParser @JvmOverloads constructor(
    credentials: Credentials,
    parserApiBaseUrl: String = getParserApiUrl(),
    override val logger: Logger = LogManager.getLogger(ReaParser::class.java),
    // Общий cookie jar со сбросом — нужен ReaGroupScheduleCollector, чтобы начинать сессию заново
    // перед каждой группой (см. его KDoc). Должен быть ОДНИМ И ТЕМ ЖЕ объектом что в httpClient
    // (ниже), что и в коллекторе — поэтому отдельный параметр, а не спрятан внутри httpClient.
    private val cookieJar: ResettableCookieJar = ResettableCookieJar(),
    // rasp.rea.ru реально нестабилен (проверено на живых прогонах) — дефолтные 10/30 сек
    // (createDefaultHttpClient) местами рубят живой, просто медленный ответ. Даём сайту больше
    // времени; агрессивность компенсируется собственными ретраями/сериализацией в
    // ReaGroupScheduleCollector и ReaGroupEntitiesCollector, а не только этим таймаутом.
    //
    // connectionPool с коротким keepAliveDuration — отдельная находка от долгого прогона на
    // 30 группах: OkHttp по умолчанию держит соединения в пуле 5 минут, но сайт (или прокси перед
    // ним), похоже, закрывает простаивающие соединения заметно раньше. При долгих паузах между
    // запросами (наши же передышки от перегрузки) клиент пытался переиспользовать уже мёртвое
    // соединение и зависал до таймаута — подтверждено вживую: ручной curl на тот же URL в момент
    // зависания отвечал за доли секунды, значит дело не в сайте, а в протухшем соединении из пула.
    // Короткий keepAliveDuration заставляет клиент обновлять соединения раньше, чем это делает сервер.
    httpClient: HttpClient = createDefaultHttpClient(
        logger = logger,
        socketTimeoutMillis = 20_000,
        requestTimeoutMillis = 60_000,
        configure = httpClientConfig(cookieJar),
    ),
    parserApi: ParserApi = ParserApi(
        httpClient = httpClient,
        userName = credentials.username,
        password = credentials.password,
        baseUrl = parserApiBaseUrl
    ),
    private val dateProvider: DateProvider = createDefaultDateProvider(),
    // ВРЕМЕННО: ограничение количества групп для тестового прогона — убрать перед сдачей задания.
    private val groupsLimit: Int? = null,
) : BaseParser(parserApi) {
    override val isWithoutSchedule: Boolean = false

    private val groupsCollector: EntitiesCollector = ReaGroupEntitiesCollector(
        httpClient = httpClient,
        logger = logger
    )
    private val groupsScheduleCollector: ScheduleCollector = ReaGroupScheduleCollector(
        httpClient = httpClient,
        logger = logger,
        cookieJar = cookieJar,
    )

    override suspend fun parseInternal(): ParserResult {
        return coroutineScope {
            val groupsPromise = async { groupsCollector.collectEntities() }

            val allGroups: List<Entity> = groupsPromise.await()
            val groups: List<Entity> = if (groupsLimit != null) {
                allGroups.take(groupsLimit)
            } else {
                allGroups
            }

            val currentDate: LocalDate = dateProvider.getCurrentDateTime().date

            val successfulGroupsPromise = async {
                parallelProcessing(groups, description = "Groups processing") { group ->
                    processEntity(
                        scheduleCollector = groupsScheduleCollector,
                        entity = group,
                        currentDate = currentDate,
                        intervals = emptyList()
                    )
                }
            }

            val successfulGroups: List<ProcessedEntity> = successfulGroupsPromise.await()
            val savedSchedules: Sequence<SavedSchedule> = successfulGroups.asSequence().map { it.savedSchedule }

            ParserResult(
                entitiesCount = groups.size,
                entitiesWithLesson = successfulGroups.count { it.savedSchedule.schedule.isNotEmpty() },
                errorsCount = groups.size - successfulGroups.size,
                scheduleAddedCount = savedSchedules.sumOf { it.addedCount },
                scheduleUpdatedCount = savedSchedules.sumOf { it.updatedCount },
                savedEntities = savedSchedules.map { it.savedEntity }.toList(),
                savedSchedules = savedSchedules.toList()
            )
        }
    }

    private suspend fun processEntity(
        scheduleCollector: ScheduleCollector,
        entity: Entity,
        currentDate: LocalDate,
        intervals: List<TimeTableInterval>,
    ): ProcessedEntity {
        val scheduleResult: ScheduleCollector.Result = scheduleCollector.collectSchedule(entity, intervals)

        val savedEntity: SavedEntity = saveEntity(scheduleResult.processedEntity)

        val processedWeekItems: List<WeekScheduleItem> = scheduleResult.weekScheduleItems

        val schedules: List<Schedule> = generateSchedules(
            currentDate = currentDate,
            weekName = { it.weekNumber.weekName },
            weekScheduleItems = processedWeekItems,
            postprocessIntervals = { it.groupLessonsInIntervals() }
        )
        val savedSchedule: SavedSchedule = saveSchedule(savedEntity, schedules)

        return ProcessedEntity(savedEntity, savedSchedule)
    }
}
