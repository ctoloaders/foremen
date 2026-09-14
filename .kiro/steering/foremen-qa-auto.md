---
inclusion: fileMatch
fileMatchPattern: 'foremen-qa-auto/**'
---

# Standard: Extending the foremen-qa-auto E2E suite

`foremen-qa-auto` is a **standalone Java 25 + Cucumber-JVM + Playwright-for-Java** suite that drives
the real application through its browser UI against the **live Docker stack** (frontend `:3000`,
backend `:8080`) — it is not a subproject of `foremen-backend`, so it never triggers the ~20-minute
backend integration suite. Its defining property: the client-facing report narration is sourced
**from the Gherkin itself**, so authoring a scenario (with inline narration) yields a correct Russian
client report with no separate hardcoded step table. Two test tiers exist: **smoke** (fast
build/deploy signal) and **detailed** (deep per-feature tests). For run/config/setup details
(Docker stack, `installBrowsers`, `QA_*` config keys), see the module `README.md`.

## 1. Report narration lives in the Gherkin (mandatory for every new scenario)

`FeatureNarration` parses the `.feature` source and both report renderers — the client-facing demo
`index.html` and the steering-standard `run-report.md` tables — render their narration from it.
`StepNarrator` runs **only as a fallback** when a step has no inline annotation, producing a
humanized keyword-generic line. Because the report reads from the source, **every new
feature/scenario/step MUST carry its narration inline**, written in **Russian** (the report language
per the `.kiro/steering/test-cases.md` standard).

Convention (as parsed by `FeatureNarration`):

- **Feature description** — a free-text description line directly under the `Feature:` line (before
  any `Background:`/`Scenario:`/`Rule:`/`@tag`). This becomes the feature intro in the report.

  ```gherkin
  @smoke @FOR-04
  Feature: Dictionary admin pages load and render their tables
    Загрузка страниц справочников и отрисовка их таблиц.
  ```

- **Scenario intent** (optional) — a free-text line under the `Scenario:` / `Scenario Outline:` line,
  before its first step. If omitted, the report keeps the scenario name as the intent.

  ```gherkin
  Scenario: Admin opens the users list
    Проверка, что администратор видит список пользователей.
    Given ...
  ```

- **Per-step narration (REQUIRED)** — trailing comment line(s) immediately **after** each step:

  ```gherkin
  Then the users list renders
  # что: Проверяем отрисовку списка пользователей.
  # ожидание: Таблица пользователей отображается с колонками (Имя, E-mail, Роль, Статус).
  ```

  Accepted prefixes (case-insensitive):
  - `# что:` (also `# что делаем:`) → step **description**
  - `# ожидание:` (also `# ожидаемый результат:`) → **expected** result

  The annotation binds to the step it follows, in file order. A blank line ends a step's annotation
  run.

- **Scenario Outline** — write the annotation with the `<param>` placeholder(s); the reader
  substitutes the concrete `Examples` value into the report. From `for04/dictionaries.feature`:

  ```gherkin
  Scenario Outline: The dictionary page at <route> loads and its table renders
    When I open the dictionary page "<route>"
    # что: Открываем страницу справочника <route>.
    # ожидание: Страница справочника <route> загрузилась.
    ...
    Examples:
      | route              |
      | /measurement-units |
  ```

  The report shows the concrete row value (e.g. `/measurement-units`) substituted into the `<route>`
  placeholder.

- **Rule:** if a step has no annotation, the report falls back to a humanized, keyword-generic line —
  that is a **smell**; always annotate. Keep step wording business-readable (the humanized fallback
  only drops a leading `"I "` and capitalizes, so the raw Gherkin should read cleanly on its own).

## 2. Adding a SMOKE scenario (extending an existing spec's coverage)

Smoke is the fast "is this build/deploy functional?" tier. Concise recipe (the README has the fuller
version):

1. Tag the feature `@smoke @FOR-0N` (the `@FOR-0N` is the source-spec slice tag).
2. Place the file under `src/test/resources/features/forNN/<area>.feature` — it is discovered
   automatically by the `@Suite` runner (`RunSmokeTest`); no runner change needed.
3. **Reuse first**: compose from existing steps, page objects, and `TestUserSteps` (role-based user
   provisioning) before writing anything new.
4. Keep it **shallow** — happy path plus critical negatives only. Deep flows belong in the detailed
   tier (section 3).
5. **Repeatability**: generate data with `DataGen` (never hard-code emails/codes/names) and register
   teardown via `world.registerTeardown(...)` (or rely on `TestUserSteps`, which registers its own).
6. Author the inline narration per section 1.
7. Update the coverage map in the spec OVERVIEW (`FOR-QA-AUTO-SMOKE/OVERVIEW.md`, and the parent
   `FOR-QA-AUTO/OVERVIEW.md`).
8. Verify: `./gradlew smokeTest -DQA_TAGS="@smoke and @FOR-0N"`.

The default `./gradlew test` / `build` does **NOT** run smoke — those tasks deliberately exclude and
disable the Cucumber scenarios (they need the live stack). Only `./gradlew smokeTest` runs smoke.

## 3. Adding DETAILED (deep) feature tests — new Gradle task per feature id

The detailed tier is for deep, per-spec E2E that lives in dedicated child specs
`FOR-QA-AUTO-0N-*` — one slice per completed feature.

- **Tag** scenarios `@detailed @FOR-QA-AUTO-0N`. The `@FOR-QA-AUTO-0N` tag is the **feature
  identifier** for the detailed slice.
- **Place** feature files under `src/test/resources/features/detailed/<feature-id>/...`. They are
  discovered by the same classpath-resource selection as smoke — no runner change needed.
- **Run all detailed tests**: `./gradlew featureTest` (defaults the tag filter to `@detailed`).
- **Slice one feature id**: `./gradlew featureTest -DQA_TAGS="@detailed and @FOR-QA-AUTO-05"`.
- **Per-feature thin alias task**: copy the `featureTestForQaAuto05` block in `build.gradle`, rename
  the task, and pin the tag to the new id, so each detailed feature has a named entry point visible
  in `./gradlew tasks`:

  ```groovy
  tasks.register('featureTestForQaAuto05', Test) {
      group = 'verification'
      description = 'Runs the @detailed @FOR-QA-AUTO-05 detailed feature slice.'
      useJUnitPlatform()
      testClassesDirs = sourceSets.test.output.classesDirs
      classpath = sourceSets.test.runtimeClasspath

      def tags = System.getProperty('QA_TAGS', System.getenv('QA_TAGS') ?: '@detailed and @FOR-QA-AUTO-05')
      systemProperty 'cucumber.filter.tags', tags
      systemProperty 'cucumber.execution.enabled', 'true'
      systemProperties System.getProperties().findAll { k, v -> k.toString().startsWith('QA_') || k.toString().startsWith('FOREMEN_') }

      testLogging { events 'passed', 'skipped', 'failed'; showStandardStreams = true }
  }
  ```

  The alias pins its own default tag; `-DQA_TAGS` is only an override.

- Detailed scenarios follow the **same inline-narration rule** (section 1) and the same repeatability
  rules (`DataGen` + teardown), and MUST **correspond to the source `test-cases.md`** of their spec:
  the detailed Gherkin scenarios and their `# что:` / `# ожидание:` narration should mirror the
  numbered test cases (IDs/wording) in that spec's `test-cases.md`, so the report reads as the
  documented case set. Recommend referencing the TC id in the scenario name or intent line
  (e.g. an intent line `"TC-05-03: ..."`).
- Detailed tests are **NOT** part of smoke and are not run by `smokeTest` / `test` / `build`.

## 4. Checklist for any new test case (quick reference)

- Choose the tier and tags: smoke (`@smoke @FOR-0N`) vs detailed (`@detailed @FOR-QA-AUTO-0N`).
- Place the file in the right folder: `features/forNN/` (smoke) or `features/detailed/<feature-id>/`
  (detailed).
- Reuse existing steps / page objects / `TestUserSteps` before adding anything new.
- Author inline **RU** narration: feature description + per-step `# что:` / `# ожидание:` (and an
  optional scenario intent line).
- Ensure repeatability: `DataGen` for unique data + teardown registered in `World`.
- For detailed: add or point to the `@FOR-QA-AUTO-0N` id + an alias task, and align scenarios with
  the spec's `test-cases.md` (mirror TC ids/wording).
- Verify with the right Gradle task + tag slice
  (`smokeTest -DQA_TAGS=...` or `featureTest -DQA_TAGS=...`).
- Confirm the generated `run-report.md` narration reads correctly (no keyword-generic fallback lines).
