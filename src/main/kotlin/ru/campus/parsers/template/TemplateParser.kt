/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.template

import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
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
import ru.campus.parsers.template.group.TemplateGroupEntitiesCollector
import ru.campus.parsers.template.group.TemplateGroupScheduleCollector

class TemplateParser @JvmOverloads constructor(
    credentials: Credentials,
    parserApiBaseUrl: String = getParserApiUrl(),
    override val logger: Logger = LogManager.getLogger(TemplateParser::class.java),
    httpClient: HttpClient = createDefaultHttpClient(logger),
    parserApi: ParserApi = ParserApi(
        httpClient = httpClient,
        userName = credentials.username,
        password = credentials.password,
        baseUrl = parserApiBaseUrl
    ),
    private val dateProvider: DateProvider = createDefaultDateProvider(),
) : BaseParser(parserApi) {
    override val isWithoutSchedule: Boolean = false

    private val groupsCollector: EntitiesCollector = TemplateGroupEntitiesCollector(
        httpClient = httpClient,
        logger = logger
    )
    private val groupsScheduleCollector: ScheduleCollector = TemplateGroupScheduleCollector(
        httpClient = httpClient,
        logger = logger,
        dateProvider = dateProvider
    )

    override suspend fun parseInternal(): ParserResult {
        return coroutineScope {
            val groupsPromise = async { groupsCollector.collectEntities() }

            val groups: List<Entity> = groupsPromise.await()

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
