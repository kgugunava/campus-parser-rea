/*
 * Copyright 2023 LLC Campus.
 */

package ru.campus

import ru.campus.parser.sdk.model.Credentials
import ru.campus.parser.sdk.model.ParserResult
import ru.campus.parsers.template.TemplateParser
import ru.campus.parsers.tests.sdk.dump.createDumpRequestsParserApi

suspend fun main() {
    val parser = TemplateParser(
        credentials = Credentials("", ""),
        parserApi = createDumpRequestsParserApi(dumpDirName = "app/dump")
    )
    val result: ParserResult = parser.parse()
    parser.logger.error("errors: {}", result.errorsCount)
    parser.logger.info("with lessons: {}, without: {}", result.entitiesWithLesson, result.entitiesWithoutLesson)
}
