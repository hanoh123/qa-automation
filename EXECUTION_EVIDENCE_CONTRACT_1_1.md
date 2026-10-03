# Execution Evidence — Contract 1.1 (design only)

Status: **design approved for review, not implemented.**
Owner: `qa-automation` (the Java engine defines the contract; QANize consumes it).
Depends on: stable check identity (`com.qanize.automation.model.CheckId`), delivered in Foundation Slice A.

Contract `1.0` remains the shipping contract. Nothing in this document is implemented yet, and no
`1.0` reader or writer changes until the slices in section 12 are approved individually.

---

## 1. What problem 1.1 solves

Contract `1.0` can say *what a check concluded*. It cannot say *what the analyzer did*.

A user reading a `1.0` summary sees `LINKS: PASSED, 12 tested`. They cannot see which URLs were
requested, in what order, how long each took, what was expected, or what was actually observed.
When a check fails they get one sentence assembled after the fact from a Surefire message. When a
check is skipped they get a reason string, but no record of the attempt that produced it.

The gap is structural: **`1.0` derives its narrative from Surefire XML after the browser has
closed.** Surefire knows a test passed; it does not know the test clicked a button. Any attempt to
enrich the report from Surefire alone must invent the steps, which is precisely the failure mode
this project has been eliminating.

`1.1` therefore records evidence **at the moment the action happens**, in the Java check, and lets
Surefire contribute only what it legitimately knows: whether the JUnit test method passed, failed,
errored or was skipped.

---

## 2. Files and versioning

`1.1` adds no new files. It extends two existing artifacts and reuses the existing evidence stream.

| File | `1.0` | `1.1` |
|---|---|---|
| `run-config.json` | unchanged | unchanged |
| `progress.json` | unchanged | unchanged |
| `evidence.jsonl` | `TestedItem` records | adds `ExecutionStep` and `RunEvent` records, discriminated by a `record` field |
| `summary.json` | `schemaVersion: "1.0"` | `schemaVersion: "1.1"`, adds `events[]`, extends `testExecution[]`, adds `testCases[]` |

`schemaVersion` is the only version signal. It already exists in `1.0` and is already read by the
TypeScript parser, so no discovery mechanism is added.

`AutomationRunRequest.CONTRACT_VERSION` moves from `"1.0"` to `"1.1"` **in the final slice only**,
after every producer and consumer is in place. Until then the engine writes `1.0` and the new
fields do not exist.

---

## 3. A. Run lifecycle event

One entry per notable transition in the run, in the order they happened.

### Java

```java
package com.qanize.automation.model;

public record RunEvent(
    int sequence,               // 1-based, unique and gapless within a run
    Instant timestamp,
    Phase phase,
    String action,              // stable machine token, e.g. "MAVEN_STARTED"
    Status status,              // STARTED | SUCCEEDED | FAILED | SKIPPED
    long durationMs,            // 0 when the event is instantaneous
    String plainLanguageMessage,// user-facing, no jargon, no identifiers
    Map<String, String> sanitizedTechnicalData
) {
    public enum Status { STARTED, SUCCEEDED, FAILED, SKIPPED }
}
```

`Phase` is the shared enum defined in section 7.

### JSON

```json
{
  "sequence": 3,
  "timestamp": "2026-08-13T09:14:22.481Z",
  "phase": "SETUP",
  "action": "BROWSER_STARTED",
  "status": "SUCCEEDED",
  "durationMs": 1840,
  "plainLanguageMessage": "Opened a Chrome browser at 1440×900.",
  "sanitizedTechnicalData": { "browser": "chrome", "viewport": "1440x900" }
}
```

Published at `summary.events[]`. Required fields: `sequence`, `timestamp`, `phase`, `action`,
`status`, `plainLanguageMessage`. Optional: `durationMs` (defaults to 0),
`sanitizedTechnicalData` (defaults to `{}`).

---

## 4. B. Check execution

`1.1` extends the existing `TestExecution` record rather than replacing it. Every `1.0` field keeps
its name, type and meaning, so a `1.0` reader that ignores unknown keys continues to work.

### Java

```java
public record CheckExecution(
    CheckId checkId,            // replaces the free String testType
    boolean selected,
    boolean executed,
    Status status,              // PASSED | FAILED | WARNING | SKIPPED | NOT_TESTED
    Instant startedAt,          // null when the check never started
    Instant completedAt,        // null when the check never completed
    long durationMs,
    DiscoveryUnit discoveryUnit,// what the check counts: PAGE | LINK | FORM | FIELD | SEARCH | LOGIN
    int discoveredCount,
    int testedCount,
    int passed,
    int failed,
    int skipped,
    String skipReason,          // "" when not skipped
    List<TestCase> testCases,
    List<String> evidenceRefs
)
```

### JSON

```json
{
  "checkId": "LINKS",
  "selected": true,
  "executed": true,
  "status": "PASSED",
  "startedAt": "2026-08-13T09:14:24.002Z",
  "completedAt": "2026-08-13T09:14:31.115Z",
  "durationMs": 7113,
  "discoveryUnit": "LINK",
  "discoveredCount": 14,
  "testedCount": 12,
  "passed": 12,
  "failed": 0,
  "skipped": 2,
  "skipReason": "",
  "testCases": [ /* section 5 */ ],
  "evidenceRefs": ["screenshot-001"]
}
```

**Compatibility note.** `1.1` writes **both** `testType` (the `1.0` string) and `checkId` for one
release, so the existing TypeScript enum parser keeps working unchanged while the new reader is
introduced. `testType` is removed only in a later contract version, never silently.

`testedCount = passed + failed` must hold. `discoveredCount = testedCount + skipped` must hold.
These are the invariants that make a skipped check impossible to publish as a pass.

---

## 5. C. Concrete test case

One entry per real thing the check acted on — one link, one form, one login attempt.

### Java

```java
public record TestCase(
    String testCaseId,          // stable within a run, e.g. "links-007"
    CheckId checkId,
    String name,                // user-facing, e.g. "Pricing link in the main navigation"
    Status status,              // PASSED | FAILED | SKIPPED
    String sourceUrl,           // the page the item was found on
    String target,              // the URL, selector label or field the case acted on
    ExecutionMethod executionMethod, // the primary method; steps carry the full sequence
    Instant startedAt,
    Instant completedAt,
    long durationMs,
    String expected,
    String actual,
    List<ExecutionStep> steps,
    List<String> evidenceRefs,
    ErrorCategory errorCategory // null when status is PASSED
)
```

### JSON

```json
{
  "testCaseId": "links-007",
  "checkId": "LINKS",
  "name": "Pricing link in the main navigation",
  "status": "FAILED",
  "sourceUrl": "https://example.com/",
  "target": "https://example.com/pricing",
  "executionMethod": "HTTP_REQUEST",
  "startedAt": "2026-08-13T09:14:26.400Z",
  "completedAt": "2026-08-13T09:14:26.902Z",
  "durationMs": 502,
  "expected": "The destination returns a successful response.",
  "actual": "The destination returned HTTP 404.",
  "steps": [ /* section 6 */ ],
  "evidenceRefs": [],
  "errorCategory": "WEBSITE_FINDING"
}
```

`testCases[]` is nested inside its check rather than published as a flat top-level list, so a case
can never be orphaned from the check that produced it or double-counted across two checks.

---

## 6. D. Execution step

The atomic record. One per browser or network action the analyzer actually performed.

### Java

```java
public record ExecutionStep(
    int sequence,               // 1-based within its test case
    Phase phase,
    ExecutionMethod method,
    String action,              // plain language: "Opened the pricing page"
    String targetLabel,         // human label, never a raw selector
    String sanitizedLocator,    // the selector, redacted; "" when not applicable
    String expectedObservation,
    String actualObservation,
    Status status,              // PASSED | FAILED | SKIPPED
    long durationMs,
    String evidenceRef          // "" when the step produced no artifact
)
```

### JSON

```json
{
  "sequence": 2,
  "phase": "ACTION",
  "method": "NAVIGATE",
  "action": "Opened the destination in the browser.",
  "targetLabel": "Pricing",
  "sanitizedLocator": "a[href='/pricing']",
  "expectedObservation": "The page reaches a usable ready state.",
  "actualObservation": "The server returned HTTP 404.",
  "status": "FAILED",
  "durationMs": 502,
  "evidenceRef": "screenshot-003"
}
```

---

## 7. Shared enums

```java
public enum Phase { SETUP, ACTION, WAIT, DISCOVERY, ASSERTION, EVIDENCE, CLEANUP }

public enum ExecutionMethod {
    BROWSER_START, NAVIGATE, CLICK, TYPE, DOM_INSPECTION,
    HTTP_REQUEST, WAIT_FOR_CONDITION, ASSERT, SCREENSHOT, BROWSER_CLOSE
}

public enum ErrorCategory {
    WEBSITE_FINDING,        // the site behaved wrongly — the only category that is the user's problem
    CONFIGURATION_ERROR,    // the run configuration was wrong
    INFRASTRUCTURE_ERROR,   // browser, driver, Maven or network failed
    NOT_APPLICABLE,         // nothing eligible existed to test
    NOT_SELECTED            // the user did not select this check
}
```

`ErrorCategory` is the field that finally separates "your website has a defect" from "our analyzer
could not run". In `1.0` both arrive as a failed check and the distinction is inferred from prose.

---

## 8. Backward and forward compatibility

**1. Reading `1.0`.** The reader branches on `schemaVersion` once, at the top level, and never
per-field. A `1.0` document is parsed by the existing `1.0` parser unchanged; a `1.1` document is
parsed by the `1.1` parser. No field is reinterpreted across versions.

**2. Missing `steps`.** Absent or empty `steps` means **"not recorded by this contract version"** —
never "no steps occurred". The UI must render that as *"Step detail is not available for this
run"*, not as an empty timeline. This distinction is why `steps` is optional in `1.1` rather than
required: a `1.1` run whose check crashed before instrumentation still produces a valid document.

**3. Ordering.** `events[]` is ordered by `sequence`, ascending, gapless from 1. `steps[]` is
ordered by `sequence` within its test case, ascending, gapless from 1. `testCases[]` is in
discovery order. `testExecution[]` is in `CheckId` declaration order. Readers must not re-sort by
timestamp: two events can share a millisecond, and `sequence` is the authority.

**4. Timestamps.** ISO-8601 UTC with millisecond precision and a `Z` suffix —
`2026-08-13T09:14:22.481Z`. Identical to `1.0`. Produced by `Instant.toString()`, normalized to
exactly three fractional digits so string comparison and lexical sorting agree with chronological
order.

**5. Durations.** Always integer milliseconds, always the field suffix `Ms`, never negative. A
duration that cannot be measured is `0`, and the accompanying status makes the absence legible.

**6. Redaction boundary.** Redaction happens **at write time, in Java, before the value reaches
any record** — never at read time and never in the UI. Passwords, tokens, session identifiers and
any value from a field classified sensitive by `SensitiveDataFilter` never enter an
`ExecutionStep`, `RunEvent`, `TestCase` or the evidence stream in any form, including inside
`sanitizedLocator`, `actualObservation` and `sanitizedTechnicalData`. A locator that targets a
sensitive field is published as its selector only; the value is never recorded, not even redacted,
because a redacted value still leaks its length and presence. Credential **environment variable
names** may appear; credential values may not.

**7. Maximum technical-data size.** `sanitizedTechnicalData` is capped at 20 entries, each key ≤ 64
characters and each value ≤ 512 characters, truncated with a trailing `…` and an added
`truncated: "true"` entry. `actualObservation` and `expected` are capped at 2000 characters.
A single run's `summary.json` is capped at 8 MB; beyond that, `steps[]` is dropped from the
lowest-value test cases first (passed before skipped before failed) and a `RunEvent` records that
truncation occurred. The report must never become unparseable because a site was large.

**8. Screenshot policy.** Screenshots are captured on failure, and at most once per test case, plus
one at run start. They are referenced by artifact id from `evidenceRefs`, never embedded. Existing
`1.0` screenshot artifacts and their `screenshot-%03d` ids are unchanged, so `evidenceRefs` resolve
against the same artifact list the UI already renders.

**9. Atomic writes.** Unchanged from `1.0`: `summary.json` and `progress.json` are written through
`JsonSupport.writeAtomically` (temp file + atomic move), so a reader never observes a partial
document. `evidence.jsonl` is append-only, one complete JSON object per line, flushed per record —
a crash truncates at a line boundary and the existing `EvidenceFileReader` parse-error path already
reports an incomplete tail as a reporting problem rather than a website defect.

**10. How Java records evidence at action time.** Each check writes `ExecutionStep` records to
`evidence.jsonl` as the action completes, through the existing `EvidenceRecorder` with a new
`record` discriminator field. The recorder is already active during the Maven child run and already
writes to the run directory, so no new transport, no new file handle and no new process is
introduced. Steps are correlated to their test case by `testCaseId`, which the check allocates
before acting. **If the browser dies mid-check, every step already written survives** — that is the
central reason for recording at action time rather than assembling at the end.

**11. How Surefire contributes without inventing steps.** Surefire supplies exactly three things:
the JUnit outcome per test class, the failure or error message, and the skip reason. The summary
factory uses those to set `CheckExecution.status` and to classify `ErrorCategory`. It **never**
synthesizes `steps[]`, `testCases[]` or observations. If the evidence stream recorded no steps for
a check, the check publishes none — the report says the detail is unavailable rather than
manufacturing a plausible narrative. Class-to-check attribution goes through `CheckId`, so the
Surefire class name is used for identity only, never for content.

**12. How TypeScript distinguishes versions.** `parseAnalyzerSummary` reads `schemaVersion` first
and dispatches to `parseV1` or `parseV11`. Unknown versions fail loudly with the version in the
message rather than being parsed optimistically. The `1.1` parser validates enums strictly
(`Phase`, `ExecutionMethod`, `ErrorCategory`, `CheckId`) and rejects a document whose `sequence`
values are duplicated or non-gapless, because silent renumbering would corrupt the timeline.

**13. How old run IDs continue to render.** Every run currently on disk stays readable: its
`summary.json` still says `1.0`, still parses through the `1.0` path, and still renders in the
existing UI. The `1.1` UI additions are conditional on the parsed version, so an old run shows the
`1.0` view rather than an empty `1.1` view full of "not recorded". No historical artifact is
migrated, rewritten or backfilled — backfilling would fabricate exactly the evidence this contract
exists to make trustworthy.

**14. Required versus optional.**

| Record | Required | Optional (with default) |
|---|---|---|
| `RunEvent` | `sequence`, `timestamp`, `phase`, `action`, `status`, `plainLanguageMessage` | `durationMs` (0), `sanitizedTechnicalData` (`{}`) |
| `CheckExecution` | `checkId`, `selected`, `executed`, `status` | `startedAt`/`completedAt` (null), `durationMs` (0), `discoveryUnit` (null), all counts (0), `skipReason` (`""`), `testCases` (`[]`), `evidenceRefs` (`[]`) |
| `TestCase` | `testCaseId`, `checkId`, `name`, `status` | `sourceUrl`, `target`, `executionMethod`, timestamps, `durationMs`, `expected`, `actual`, `steps` (`[]`), `evidenceRefs` (`[]`), `errorCategory` (null when PASSED) |
| `ExecutionStep` | `sequence`, `phase`, `method`, `action`, `status` | `targetLabel`, `sanitizedLocator`, `expectedObservation`, `actualObservation` (`""`), `durationMs` (0), `evidenceRef` (`""`) |

The rule behind the split: a field is required only when its absence would let the report assert
something untrue. Everything describing *how well* the analyzer observed something is optional,
because "we did not record this" must always be expressible.

---

## 9. Invariants the implementation must enforce

1. `testedCount == passed + failed` for every check.
2. `discoveredCount == testedCount + skipped` for every check.
3. `status == PASSED` implies `executed == true` and `failed == 0` and `testedCount > 0`.
4. `status == SKIPPED` implies `skipReason` is non-blank.
5. A `TestCase` with `status == PASSED` has `errorCategory == null`.
6. `errorCategory == WEBSITE_FINDING` appears only inside a check that actually executed.
7. `sequence` is gapless and unique within its scope.
8. No record contains a credential value at any nesting depth.

Invariants 1–3 are the machine-checkable form of the rule this project has enforced by hand since
the SPA discovery defect: **a check that did not test anything is never reported as a pass.**

---

## 10. What 1.1 deliberately does not do

- No Run Timeline UI. The contract must be produced and validated before anything renders it.
- No performance instrumentation beyond `durationMs`, which is a byproduct of recording, not a
  profiling system.
- No new check types.
- No authenticated crawling or Playwright.
- No change to Maven profiles, tags or selectors.
- No migration of historical runs.

---

## 11. Known risk

The largest risk is **instrumentation drift**: a check that acts without recording produces a
report that looks complete and is quietly missing steps. The mitigation is invariant 3 plus a
per-check assertion in the engine's own test suite that a check which reports `testedCount > 0`
also produced at least one `ExecutionStep`. That test belongs in the same slice as the first
instrumented check, not later.

The second risk is that `steps[]` makes `summary.json` large enough to slow the UI. Section 8.7
caps it, but the cap must be exercised by a test with a synthetic large run, not assumed.

---

## 12. Recommended implementation slices

Each slice ends with a real analyzer run and is independently revertible.

| Slice | Scope | Contract version written |
|---|---|---|
| **A — done** | `CheckId` stable identity, no FQN substring matching | `1.0` |
| **B** | Java models: `RunEvent`, `ExecutionStep`, `TestCase`, `CheckExecution`, `Phase`, `ExecutionMethod`, `ErrorCategory`, plus their unit tests. Nothing writes them yet | `1.0` |
| **C** | `evidence.jsonl` gains the `record` discriminator; `EvidenceRecorder`/`EvidenceFileReader` read and write step records; existing `TestedItem` lines still parse | `1.0` |
| **D** | Instrument **one** check end to end — Links, because it has the clearest per-item boundary and needs no browser interaction beyond navigation | `1.0` |
| **E** | Summary factory assembles `events[]`, `testCases[]` and the extended `testExecution[]` from evidence only; writes `schemaVersion: "1.1"` behind a flag; both `testType` and `checkId` emitted | `1.0` → `1.1` behind flag |
| **F** | TypeScript `parseV11` and version dispatch, with fixture tests for a `1.0` document, a `1.1` document, and a `1.1` document with no steps | `1.1` behind flag |
| **G** | Instrument the remaining five checks | `1.1` behind flag |
| **H** | Flip the default to `1.1`; old runs verified still rendering | `1.1` |
| **I** | Run Timeline UI — separate milestone, not part of this contract | `1.1` |

Slice B is the correct next request. It is pure model code with no behavioural surface, so it can
be validated by unit tests alone and cannot affect a customer run.
