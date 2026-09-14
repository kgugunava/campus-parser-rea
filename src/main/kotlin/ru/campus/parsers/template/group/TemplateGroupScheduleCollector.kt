/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.template.group

import io.ktor.client.HttpClient
import org.apache.logging.log4j.Logger
import ru.campus.parser.sdk.DateProvider
import ru.campus.parser.sdk.base.ScheduleCollector
import ru.campus.parser.sdk.model.Entity
import ru.campus.parser.sdk.model.TimeTableInterval

class TemplateGroupScheduleCollector(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val dateProvider: DateProvider,
) : ScheduleCollector {

    override suspend fun collectSchedule(
        entity: Entity,
        intervals: List<TimeTableInterval>,
    ): ScheduleCollector.Result {
        TODO()
    }
}
