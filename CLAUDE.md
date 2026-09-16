# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

This is the Campus schedule parser for **РЭУ им. Плеханова** (`rea`), created from the Campus parser template.
Campus parsers are Kotlin libraries that scrape a university's public schedule site and upload the result to the
Campus backend via the `parser-sdk`. All template-specialization steps are done: package is
`ru.campus.parsers.rea`, classes are `ReaParser` / `ReaGroupEntitiesCollector` / `ReaGroupScheduleCollector`,
`rootProject.name` is `campus-parser-rea`, and the publish URL in `build.gradle.kts` points at
`campus-mobile/campus-parser-rea`.

**The scraping logic is implemented and verified against the live site** (`rasp.rea.ru`) — see
`src/main/kotlin/ru/campus/parsers/rea/group/ReaGroupEntitiesCollector.kt` and `ReaGroupScheduleCollector.kt`.
A real run against the live site found 1012 groups across all faculties. The README's
"Парсер РЭУ — проблемы и как решал" section documents the concrete issues hit while building this (missing
`X-Requested-With` header causing silent 302s, no JSON API so HTML is scraped via jsoup, teacher info requiring a
second request per lesson, site instability under rapid sequential requests, etc.) — read it before changing
either collector.

`DumpParserTest` still passes only *trivially* (0 recorded groups, since no dump fixtures have been captured yet
under `src/test/resources`) — a green `DumpParserTest` right now is not evidence the collectors work; the live-run
evidence above is. Recording real fixtures (via a dump-recording run against the live site, see "Common commands")
and wiring them into this test is the next real step, not yet done.

## Setup

- Requires JDK 11+ (CI builds with JDK 11; the `app` module's Gradle toolchain is pinned to JDK 17).
- Dependencies (`parser-sdk`, `parser-tests-sdk`) are pulled from a private GitHub Packages Maven repo
  (`campus-mobile/campus-parser-kotlin-sdk`), so a GitHub PAT with `read:packages` is required to resolve
  dependencies at all, even for local builds/tests. Add it to `gradle.properties` (not committed) as:
  ```
  github.token=<PAT with read:packages>
  ```

## Common commands

- Build + run all tests: `./gradlew build` (Windows: `gradlew.bat build`). A `BUILD SUCCESSFUL` means the project
  compiles and all tests pass — this is what CI runs on every push/PR to `main`/`develop`.
- Run a single test class: `./gradlew test --tests "ru.campus.parsers.rea.DumpParserTest"`
- Run the parser end-to-end locally: run `main()` in `app/src/main/kotlin/ru/campus/Main.kt` (e.g. via IDEA's gutter
  run arrow, or `./gradlew :app:run` if a run task is configured). This exercises the full parser but substitutes
  `createDumpRequestsParserApi`, so nothing is actually sent to the Campus server — the data that *would* be
  uploaded is instead dumped to `app/dump/`, and the full run log is written to `app/log4j2.log`.

## Architecture

Two-module Gradle project:

- **Root module** (`src/`) — the actual parser library, published as a Maven artifact
  (`me.campusapp.parsers:campus-parser-rea`) to GitHub Packages on every push to `main` (`release.yml`).
- **`app` module** — a thin runnable harness (depends on the root module) used only for manual/local end-to-end
  runs against dumped output; it is not published.

### Parser flow (`parser-sdk` extension points)

The root module implements three classes from the external `parser-sdk`:

- `ReaParser : BaseParser` — orchestrates the run inside `parseInternal()`:
  1. Uses an `EntitiesCollector` to fetch the list of entities (e.g. groups).
  2. For each entity (in parallel via `parallelProcessing`), uses a `ScheduleCollector` to fetch its schedule.
  3. Saves each entity and its generated `Schedule`s via `BaseParser.saveEntity` / `saveSchedule`.
  4. Aggregates everything into a `ParserResult` (counts, saved entities, saved schedules).
- `ReaGroupEntitiesCollector : EntitiesCollector` — walks the site's cascading `Факультет → Курс → Уровень →
  Группа` selects via `POST /Schedule/Navigator` (3 levels; the site has no JSON list of all groups) and builds
  `Entity` per group found at the leaf. Requires the `X-Requested-With: XMLHttpRequest` header on every AJAX call
  to this site, or the server 302s instead of returning data (see README).
- `ReaGroupScheduleCollector : ScheduleCollector` — for one group, fetches `GET /Schedule/ScheduleCard` (an
  HTML fragment, parsed with jsoup, not JSON) per week, resolving the *current* week number via `weekNum=-1`
  (the site echoes the real week number back in a hidden `<input id="weekNum">` — no hardcoded academic-year-start
  date needed). Teacher name/department is not in `ScheduleCard` at all — it requires one extra
  `GET /Schedule/GetDetailsById?id=<data-elementid>` call per lesson, cached by `elementId` since the same lecture
  recurs across many groups' schedules.

Both collectors receive an `HttpClient` (ktor) and `Logger` (log4j2) via constructor injection; jsoup is available
for HTML parsing. `ReaParser` still takes a `DateProvider` (used for `generateSchedules`'s "start of week"
anchor), but `ReaGroupScheduleCollector` itself does not need one — it resolves the current week from the site
directly via `weekNum=-1`, not from `DateProvider`.

### Testing

`src/test/kotlin/ru/campus/parsers/rea/DumpParserTest.kt` drives `ReaParser` end-to-end against dumped mock HTTP
responses/parser API (`createDumpMockHttpClient`, `createDumpMockParserApi` from `parser-tests-sdk`) rather than
live network calls — this is the pattern for testing a parser against real captured schedule pages. It currently
passes only because there are no recorded fixtures yet (0 groups found, 0 == 0) — this is not evidence the
collectors work; recording real fixtures from a live dump run is still an open task (see "What this repository
is").

## Workflow

Development happens on a personal branch; PRs target `develop` (CI runs `check.yml` build+test on PRs/pushes to
`main`/`develop`). After review and merge to `develop`, changes eventually reach `main`, which triggers
`release.yml` to publish the library to GitHub Packages, from where it is deployed to the Campus parser servers.

When modifying the parser's scraping logic, always verify behavior via `DumpParserTest` (dump-based, per
"Testing" above) rather than only manual runs — this is the standard way to validate a parser against different
real schedule layouts.

## Reference implementation: spbstu-parser

The `parser-sdk` repo ships a fully-implemented sample parser at
`campus-parser-kotlin-sdk/samples/spbstu-parser` (SPbPU). Its `SPBSTUParser` / `SPBSTUGroupEntitiesCollector` /
`SPBSTUGroupScheduleCollector` are structurally identical to this repo's `Rea*` classes (same base classes, same
constructor shape) but with the `TODO()`s actually filled in — it is the best available model for how to implement
`ReaGroupEntitiesCollector`/`ReaGroupScheduleCollector` and how to test them. Key conventions observed there,
worth following here:

- **DTOs for the source site's JSON** live in their own `model/` subpackage (e.g. `ru.campus.parsers.spbstu.model`),
  one `@Serializable data class` per JSON shape (`SpbstuFaculty`, `SpbstuGroup`, `SpbstuDay`, `SpbstuLesson`, …).
  Field names are kept idiomatic (`@SerialName("id") val code: Int`) rather than mirroring the raw API's naming.
  Decoding uses `kotlinx.serialization.json.Json.decodeFromString<T>(body)` — no manual JSON parsing.
- **`EntitiesCollector`** fetches an initial page/list (jsoup for HTML, ktor `HttpClient.get(...).body()` for
  JSON/API endpoints), then maps raw fields to `Entity` — mapping any enum-like raw codes (course/degree/education
  form, …) through small private `getX()` functions that `throw IllegalArgumentException` on an unrecognized value
  rather than silently defaulting, so unknown categories fail loudly instead of producing bad data.
- **`ScheduleCollector`** iterates the 4 weeks it needs to collect (`dateProvider.getCurrentDateTime().date`, then
  `+7` days per iteration), fetching/decoding one page per week and flattening into `WeekScheduleItem`s. Each
  university's site may expose the "which weeks/dates does this lesson repeat on" concept differently — spbstu's
  site gives explicit per-date lessons, so it uses `ExplicitDatePredicate(date)` as the `dayCondition` (as opposed
  to this template's default `weekNumber`/parity-based condition) — pick whichever `WeekScheduleItem.dayCondition`
  matches what the actual site returns, don't assume the template's default fits.
- A parser can deviate from the template's default `parseInternal()` when the site's data model requires it —
  e.g. spbstu's parser derives a deduplicated teacher entity list from the lessons it just parsed and saves those
  separately (for reviews), and merges same-interval lessons into one `Schedule.Interval` via a local
  `postprocess()` instead of the SDK's `groupLessonsInIntervals()`. Treat the template's `ReaParser.parseInternal()`
  as a reasonable default, not something to preserve unmodified if РЭУ's data doesn't map cleanly onto it.
- **KDoc-style block comments** at the top of each main class (`Parser`, `EntitiesCollector`, `ScheduleCollector`)
  explain the scraping approach and any non-obvious workarounds, in Russian, in prose. This is the one place in
  these parser projects where such comments are the established convention — follow it for the `Rea*` classes even
  though it's more verbose than this file's general no-comments default.

### Testing pattern (from the same sample)

- One unit test class per collector (`SPBSTUGroupEntitiesCollectorTest`, `SPBSTUGroupScheduleCollectorTest`) using
  ktor's `HttpClient(MockEngine { request -> when (request.url.toString()) { ... } })`, routing exact URLs to
  fixture files via `respondOk(readResourceFile("<uni>/..."))` and `respondBadRequest()` as the catch-all default
  (so an unexpected/extra request fails the test instead of being silently ignored).
- Fixtures live under `src/test/resources/<uni>/...` (HTML/JSON/plain-text), including expected-output snapshots
  (`entity_processed.txt`, `week_schedule_items.txt`) compared via `.toString()`/`joinToString()` equality.
- A full end-to-end `<Uni>ParserTest` mocks *both* the university site's responses *and* the Campus `parserApi`
  endpoints on the same `MockEngine`, asserting the exact outgoing request bodies with
  `readResourceFile(...).singleLineJson()` — this is stricter than this repo's current `DumpParserTest` (dump-based
  record/replay) and a good pattern to add once the collectors are implemented. Helpers used:
  `readResourceFile`, `singleLineJson`, `ThrowableLogger` — all from `ru.campus.parsers.tests.sdk` (`parser-tests-sdk`).
- The sample's `build.gradle.kts` declares explicit `implementation`/`testImplementation` deps this repo doesn't
  currently have in `gradle/libs.versions.toml` — `ktorClientMock` (for `MockEngine` in tests) and
  `coroutinesTest`/`kotlinxDateTime` if not already pulled in transitively — add them when writing collector tests
  in this style.
