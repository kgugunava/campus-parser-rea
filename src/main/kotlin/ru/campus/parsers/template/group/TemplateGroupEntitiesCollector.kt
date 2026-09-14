/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.template.group

import io.ktor.client.HttpClient
import org.apache.logging.log4j.Logger
import ru.campus.parser.sdk.base.EntitiesCollector
import ru.campus.parser.sdk.model.Entity

class TemplateGroupEntitiesCollector(
    private val httpClient: HttpClient,
    private val logger: Logger,
) : EntitiesCollector {

    override suspend fun collectEntities(): List<Entity> {
        TODO()
    }
}
