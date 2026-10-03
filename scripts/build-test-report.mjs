#!/usr/bin/env node
/**
 * Generates a readable inventory of QANIZE's own build tests from real Surefire results.
 *
 * These are regression tests that verify QANIZE itself. They are NOT tests performed against a
 * customer website, and this report must never appear in the customer-facing Website Analyzer UI.
 *
 * Usage (from the repository root):
 *   node qa-automation/scripts/build-test-report.mjs
 *   node qa-automation/scripts/build-test-report.mjs --reports <dir> --out <file>
 *
 * Defaults:
 *   --reports  qa-automation/target/surefire-reports
 *   --out      qa-automation/target/build-test-report.md
 *
 * No dependencies: the Surefire XML is read with a small regex reader, matching the repository's
 * existing plain-Node script style (see apps/web/scripts/check-db.mjs).
 */

import { readdirSync, readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index !== -1 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const reportsDir = resolve(arg("reports", join("qa-automation", "target", "surefire-reports")));
const outFile = resolve(arg("out", join("qa-automation", "target", "build-test-report.md")));

/**
 * Which part of QANIZE a test class is responsible for. Ordered: the first match wins, so more
 * specific patterns must come first.
 */
const GROUPS = [
  [/config\.|ConfigLoader|Validator|SelectorRequirement/, "Configuration and validation"],
  [/runtime\.|Orchestrator|RuntimeConfig|ProfileIsolation|AutomationRunPaths/, "Runtime orchestration"],
  [/DiscoveryDiagnostics|PageContentReadiness|NavigationLinkInspector/, "Discovery and readiness"],
  [/DiscoveryEvidence|EvidenceFile|EvidenceRecorder/, "Evidence writing"],
  [/RunSummary|SurefireResultReader|Report/, "Summary and report contract"],
  [/tests\.generic\.|Generic.*Test/, "Generic website checks"],
  [/SensitiveDataFilter|Screenshot|SelectorUtils/, "Security and safety rules"],
];

function groupFor(className) {
  for (const [pattern, label] of GROUPS) if (pattern.test(className)) return label;
  return "Other";
}

/** Turns a camelCase JUnit method name into the sentence it was written as. */
function humanise(methodName) {
  const spaced = methodName.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/_/g, " ");
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

function attr(xml, name) {
  const match = xml.match(new RegExp(`\\b${name}="([^"]*)"`));
  return match ? match[1] : "";
}

function decode(value) {
  return value
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&");
}

function parseSuite(xml) {
  // The opening <testsuite> tag can span several lines, and the XML declaration comes first, so
  // the tag has to be located explicitly rather than by the first ">".
  const start = xml.indexOf("<testsuite");
  const header = start === -1 ? "" : xml.slice(start, xml.indexOf(">", start) + 1);
  const suite = {
    className: attr(header, "name"),
    time: attr(header, "time"),
    cases: [],
  };
  const casePattern = /<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g;
  let match;
  while ((match = casePattern.exec(xml)) !== null) {
    const head = match[1];
    const body = match[3] ?? "";
    let status = "passed";
    let detail = "";
    if (/<skipped\b/.test(body)) {
      status = "skipped";
      const message = body.match(/<skipped\b[^>]*message="([^"]*)"/);
      const cdata = body.match(/<skipped\b[^>]*>([\s\S]*?)<\/skipped>/);
      detail = decode(message?.[1] ?? cdata?.[1] ?? "").trim();
    } else if (/<failure\b/.test(body)) {
      status = "failed";
      detail = decode(body.match(/<failure\b[^>]*message="([^"]*)"/)?.[1] ?? "").trim();
    } else if (/<error\b/.test(body)) {
      status = "error";
      detail = decode(body.match(/<error\b[^>]*message="([^"]*)"/)?.[1] ?? "").trim();
    }
    suite.cases.push({
      name: attr(head, "name"),
      time: attr(head, "time"),
      status,
      // One line only: a stack trace would make the report unreadable.
      detail: detail.split("\n")[0].slice(0, 300),
    });
  }
  return suite;
}

if (!existsSync(reportsDir)) {
  console.error(`No Surefire reports at ${reportsDir}.`);
  console.error("Run `mvn.cmd -f qa-automation\\pom.xml test` first.");
  process.exit(1);
}

const suites = readdirSync(reportsDir)
  .filter((file) => file.startsWith("TEST-") && file.endsWith(".xml"))
  .map((file) => parseSuite(readFileSync(join(reportsDir, file), "utf8")))
  .sort((a, b) => a.className.localeCompare(b.className));

if (suites.length === 0) {
  console.error(`No TEST-*.xml files in ${reportsDir}.`);
  process.exit(1);
}

const all = suites.flatMap((suite) => suite.cases);
const count = (status) => all.filter((testCase) => testCase.status === status).length;
const totals = {
  total: all.length,
  passed: count("passed"),
  failed: count("failed"),
  errors: count("error"),
  skipped: count("skipped"),
};

const byGroup = new Map();
for (const suite of suites) {
  const label = groupFor(suite.className);
  if (!byGroup.has(label)) byGroup.set(label, []);
  byGroup.get(label).push(suite);
}

const lines = [];
lines.push("# QANIZE build test report");
lines.push("");
lines.push("These are **QANIZE's own regression tests**. They verify the analyzer's code.");
lines.push("They are **not** tests performed against any customer website, and they are never shown");
lines.push("in the Website Analyzer UI.");
lines.push("");
lines.push(`Generated: ${new Date().toISOString()}`);
lines.push(`Source: \`${reportsDir}\``);
lines.push("");
lines.push("| Total | Passed | Failed | Errors | Skipped |");
lines.push("| ----: | -----: | -----: | -----: | ------: |");
lines.push(`| ${totals.total} | ${totals.passed} | ${totals.failed} | ${totals.errors} | ${totals.skipped} |`);
lines.push("");

for (const [label, groupSuites] of [...byGroup].sort((a, b) => a[0].localeCompare(b[0]))) {
  const groupCases = groupSuites.flatMap((suite) => suite.cases);
  lines.push(`## ${label} (${groupCases.length})`);
  lines.push("");
  for (const suite of groupSuites) {
    lines.push(`### ${suite.className.split(".").pop()} — ${suite.cases.length} test(s), ${suite.time}s`);
    lines.push("");
    lines.push("| Test | Status | Duration | Notes |");
    lines.push("| ---- | ------ | -------: | ----- |");
    for (const testCase of suite.cases) {
      const status = { passed: "PASSED", failed: "FAILED", error: "ERROR", skipped: "SKIPPED" }[testCase.status];
      lines.push(`| ${humanise(testCase.name)} | ${status} | ${testCase.time}s | ${testCase.detail || "—"} |`);
    }
    lines.push("");
  }
}

mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, lines.join("\n"), "utf8");

console.log(`Build test report written to ${outFile}`);
console.log(
  `${totals.total} build tests: ${totals.passed} passed, ${totals.failed} failed, `
  + `${totals.errors} errors, ${totals.skipped} skipped.`,
);
console.log("These verify QANIZE itself, not any customer website.");
