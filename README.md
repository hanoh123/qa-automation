# QANIZE Website Analyzer - Java automation

This nested Maven project is the Java 21/Selenium execution engine for QANIZE Website Analyzer. It validates a versioned run request, runs the selected browser checks, and writes machine-readable progress and summary artifacts for the web application.

## Project layout

```text
src/main/java/com/qanize/automation/
  browser/         WebDriver factory and screenshot capture
  checks/          one package per website check, plus shared page helpers
  config/          v1 configuration models, loading, and validation
  engine/          the crawler, its policy, scope, and per-page processor
  evidence/        execution evidence recording and reading
  model/           contract types: checks, findings, items, steps, taxonomies
  reporting/       Surefire reading and summary contract generation
  runtime/         run orchestration, paths, progress, and config generation

src/main/java/com/hanoh/
  Main.java        stable entry point (see below)
  support/         shared JSON utilities

src/test/java/com/qanize/automation/
  checks/          the customer browser checks beside their own unit tests
  testsupport/     JUnit/Selenium fixtures and failure screenshots
  ...              unit tests for the production engine
```

Production models and orchestration live in `src/main`; JUnit fixtures, browser checks, and test-only profile code live in `src/test`.

`com.hanoh.Main` is a thin, stable entry point that delegates to `com.qanize.automation.runtime.AutomationRunOrchestratorCli`. QANIZE launches the orchestrator class directly.

## Generic website checks

The `website-analysis` suite contains six checks:

- Smoke: opens the target and verifies basic document content and configured expectations.
- Navigation: visits the configured important pages and checks that they load.
- Links: inspects bounded, same-origin links and reports broken destinations.
- Search: enters the configured query when Search is enabled. An input selector is required; submit and result selectors are optional.
- Forms: detects forms and records their presence without submitting them.
- Login: performs the configured login flow only when Login is enabled and all selector and environment-variable references are valid.

A website assertion failure becomes a finding. Invalid input is a run-configuration error, not a finding against the website.

## Isolated Maven profiles

Surefire tags keep the three workflows separate:

| Command | Tag selected | Purpose |
| --- | --- | --- |
| `mvn test` | `unit` | Fast engine/unit tests only |
| `mvn test -Pgeneric` | `website-analysis` | The generic website checks only |

Plain `mvn test` does not run browser profiles.

## Contract v1

`run-config.json`, `progress.json`, and `summary.json` use `schemaVersion: "1.0"`. They are the boundary shared with the QANIZE web application.

The canonical run configuration has these sections:

| Section | Fields |
| --- | --- |
| `target` | `url` |
| `viewport` | `preset` (`desktop`, `tablet`, or `mobile`), `width`, `height` |
| `limits` | `maxPages`, `maxLinks` |
| `checks` | `smoke`, `navigation`, `links`, `search`, `forms`, `login` |
| `search` | `enabled`, input/submit/results selectors, and `query` |
| `login` | `enabled`, URL, environment-variable names, selectors, and optional success expectations |

`checks.search` must match `search.enabled`, and `checks.login` must match `login.enabled`. URLs must be absolute public HTTP(S) URLs and must not embed credentials.

`run-config.json` is the canonical configuration artifact. `generated-run.properties` is a derived, internal adapter used by the current JUnit/Selenium layer; it is not a second public contract.

`run-config.json` has one writer per run. When QANIZE starts the run it validates the request and writes that file before launching this engine, and the engine keeps it. The engine writes `run-config.json` itself only when the file is absent, which is what happens during a direct command-line run.

`summary.totals` reports the checks that were selected. The generic profile always starts all six check classes and the unselected ones stop on a JUnit assumption, so the raw Surefire counts are not published directly.

Progress contains `runId`, state, phase, message, current page, completed/total check counts, timestamps, elapsed time, and cancellation flags. Progress states are:

```text
QUEUED  CONFIGURING  RUNNING  FINALIZING  COMPLETED  FAILED  CANCELLED
```

Summary contains lifecycle, outcome, target URL, timestamps/duration, consistent totals, findings, errors, evidence, and artifacts. Its taxonomies are:

- Lifecycle: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`
- Outcome: `WEBSITE_FINDINGS`, `NO_FINDINGS`, `RUN_CONFIGURATION_ERROR`, `ANALYZER_INTERNAL_ERROR`, `INFRASTRUCTURE_ERROR`, `CANCELLED`
- Error category: `RUN_CONFIGURATION`, `ANALYZER_INTERNAL`, `INFRASTRUCTURE`, `CANCELLED`
- Finding severity: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`
- Artifact type: `CONFIG`, `PROGRESS`, `SUMMARY`, `MANIFEST`, `SCREENSHOT`, `REPORT`

Findings include the expected and actual behavior, why the check failed, reproduction steps, evidence references, and a recommendation. Their titles and recommendations are written per check category; JUnit class and method names stay inside the raw report and Surefire output and never appear in the product UI. Errors carry a stable code, user-safe message, suggested action, timestamp, and optional technical detail.

## Deterministic run output

Each safe `runId` maps to one confined directory:

```text
target/qanize-runs/{runId}/
  run-config.json
  generated-run.properties
  progress.json
  summary.json
  surefire-reports/
  screenshots/
```

All emitted relative artifact paths must remain inside that run directory. Files under `target/` are generated, ignored by Git, and safe to regenerate; they are not source or long-term storage.

## Prerequisites

- JDK 21, with `java` and `javac` available
- Maven, with `mvn` (or `mvn.cmd` on Windows) available
- Google Chrome installed
- Network access when Maven or Selenium Manager must resolve dependencies or a compatible driver
- Network access from Chrome to the public target site

Browser checks run headless by default. For a visible browser in a direct Maven run, append `-Dqanize.headless=false`. For the orchestrator, place `-Dqanize.headless=false` before `-cp` in the Java command.

## Commands

Run the default unit suite:

```powershell
mvn test
```

Run the generic browser suite with the bundled safe example:

```powershell
mvn test -Pgeneric "-DsiteConfig=sites/example.properties"
```

Build the production classes, then run the complete generic flow through the orchestrator:

```powershell
mvn -DskipTests package
java -cp target/classes com.qanize.automation.runtime.AutomationRunOrchestratorCli --runId=example-run --schemaVersion=1.0 --siteUrl=https://example.com/ --viewportPreset=desktop --viewportWidth=1440 --viewportHeight=900 --maxPages=5 --maxLinks=25 --smoke=true --navigation=true --links=true --search=false --forms=true --login=false
```

The orchestrator validates the request, writes the canonical config and internal properties adapter, launches `mvn test -Pgeneric` with isolated report/screenshot paths, then writes the terminal summary.

## Credentials and sensitive data

Configuration stores environment-variable names, never credential values. For example, use `login.usernameEnv=QANIZE_TEST_USERNAME` and `login.passwordEnv=QANIZE_TEST_PASSWORD`, then provide those variables to the Java process environment. Environment references must use normal variable-name syntax such as `[A-Za-z_][A-Za-z0-9_]*`.

Do not place usernames, phone numbers, passwords, tokens, cookies, or other secrets in JSON, properties files, CLI arguments, logs, screenshots, or committed source.

## Nested repository

`qa-automation` is intentionally a nested repository with its own `.git` directory. Do not remove `qa-automation/.git`, absorb it into the parent repository, or run Git commands without first confirming which repository is in scope. Generated `target/` output may be recreated; repository metadata may not.
