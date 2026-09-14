package ru.campus.parsers.template

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import ru.campus.parser.sdk.model.Credentials
import ru.campus.parser.sdk.model.ParserResult
import ru.campus.parsers.tests.sdk.dump.createDumpMockHttpClient
import ru.campus.parsers.tests.sdk.dump.createDumpMockParserApi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DumpParserTest {
    @Ignore
    @Test
    fun success() = runTest {
        val collector = TemplateParser(
            credentials = Credentials("", ""),
            httpClient = createDumpMockHttpClient(),
            parserApi = createDumpMockParserApi(),
            dateProvider = { LocalDateTime.parse("2023-02-06T00:00:00") }
        )
        val data: ParserResult = collector.parse()

        assertEquals(
            expected = data.entitiesCount,
            actual = data.savedEntities.size
        )
    }
}
