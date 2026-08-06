#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const REVIEW_CONTRACT = Object.freeze({
  schemaVersion: 1,
  promptId: "android-pr-review",
  promptVersion: 1,
  completionMarker: "ANDROID_REVIEW_COMPLETE",
});

const fullShaPattern = /^[0-9a-f]{40}$/u;
const repositoryPattern = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u;
const refControlPattern = /[\u0000-\u001f\u007f]/u;

function fail(message) {
  throw new Error(`Error: ${message}`);
}

export function sha256(contents) {
  return createHash("sha256").update(contents).digest("hex");
}

function requireString(value, label) {
  if (typeof value !== "string" || value.length === 0) {
    fail(`${label} must be a non-empty string.`);
  }
  return value;
}

function requireFullSha(value, label) {
  if (typeof value !== "string" || !fullShaPattern.test(value)) {
    fail(`${label} must be a full lowercase 40-character Git SHA.`);
  }
  return value;
}

function requireRef(value, label) {
  if (typeof value !== "string" || value.length === 0 || refControlPattern.test(value)) {
    fail(`${label} is not a safe Git ref name.`);
  }
  return value;
}

export function buildReviewContext({
  metadata,
  repository,
  promptBytes,
  reviewToolVersion,
}) {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) {
    fail("PR metadata must be a JSON object.");
  }
  if (typeof repository !== "string" || !repositoryPattern.test(repository)) {
    fail("canonical repository must be an owner/name pair.");
  }
  if (!Number.isSafeInteger(metadata.number) || metadata.number <= 0) {
    fail("PR number must be a positive integer.");
  }

  const expectedUrl = `https://github.com/${repository}/pull/${metadata.number}`;
  if (metadata.url !== expectedUrl) {
    fail("PR identity does not belong to the canonical repository.");
  }
  if (!Buffer.isBuffer(promptBytes) || promptBytes.length === 0) {
    fail("review prompt must be non-empty bytes.");
  }

  return {
    schemaVersion: REVIEW_CONTRACT.schemaVersion,
    repository,
    prNumber: metadata.number,
    prUrl: expectedUrl,
    base: {
      ref: requireRef(metadata.baseRefName, "PR base ref"),
      sha: requireFullSha(metadata.baseRefOid, "PR base head"),
    },
    head: {
      ref: requireRef(metadata.headRefName, "PR head ref"),
      sha: requireFullSha(metadata.headRefOid, "PR head"),
    },
    prBodySha256: sha256(Buffer.from(requireString(metadata.body, "PR body"), "utf8")),
    prompt: {
      id: REVIEW_CONTRACT.promptId,
      version: REVIEW_CONTRACT.promptVersion,
      sha256: sha256(promptBytes),
      mode: "fixed-preset-only",
    },
    reviewTool: {
      package: "@cobuild/review-gpt",
      version: requireString(reviewToolVersion, "ReviewGPT package version"),
    },
  };
}

export function serializeReviewContext(context) {
  return `${JSON.stringify(context, null, 2)}\n`;
}

export function reviewContextDigest(context) {
  return sha256(Buffer.from(serializeReviewContext(context), "utf8"));
}

function uniqueMatch(lines, pattern, label) {
  const matches = lines.map((line) => line.match(pattern)).filter(Boolean);
  if (matches.length !== 1) {
    fail(`ReviewGPT response must contain exactly one valid ${label}.`);
  }
  return matches[0];
}

function countText(contents, needle) {
  return contents.split(needle).length - 1;
}

export function validateReviewResponse({ response, context }) {
  if (typeof response !== "string" || response.trim().length === 0) {
    fail("ReviewGPT response must be non-empty text.");
  }

  const canonicalContext = serializeReviewContext(context);
  const parsedContext = JSON.parse(canonicalContext);
  const expectedDigest = reviewContextDigest(parsedContext);
  const normalizedResponse = response.replace(/\r\n?/gu, "\n");
  const lines = normalizedResponse.split("\n");
  const nonemptyLines = lines.filter((line) => line.trim().length > 0);

  if (countText(normalizedResponse, "REVIEW_CONTEXT_SHA256:") !== 1) {
    fail("ReviewGPT response must contain exactly one context digest field.");
  }
  const contextMatch = uniqueMatch(
    lines,
    /^REVIEW_CONTEXT_SHA256: ([0-9a-f]{64})$/u,
    "context digest",
  );
  if (contextMatch[1] !== expectedDigest) {
    fail("ReviewGPT response does not match the current PR review context.");
  }

  if (countText(normalizedResponse, "Checked Android head:") !== 1) {
    fail("ReviewGPT response must contain exactly one checked-head field.");
  }
  const headMatch = uniqueMatch(
    lines,
    /^Checked Android head: ([0-9a-f]{40})$/u,
    "checked Android head",
  );
  if (headMatch[1] !== context.head.sha) {
    fail("ReviewGPT response was produced for a different Android head.");
  }

  if (countText(normalizedResponse, "REVIEW_FINDINGS:") !== 1) {
    fail("ReviewGPT response must contain exactly one findings count.");
  }
  const findingsMatch = uniqueMatch(
    lines,
    /^REVIEW_FINDINGS: (0|[1-9][0-9]*)$/u,
    "findings count",
  );
  const findings = Number(findingsMatch[1]);

  if (countText(normalizedResponse, "REVIEW_OUTCOME:") !== 1) {
    fail("ReviewGPT response must contain exactly one review outcome.");
  }
  const outcomeMatch = uniqueMatch(
    lines,
    /^REVIEW_OUTCOME: (PASS|FINDINGS)$/u,
    "review outcome",
  );
  const outcome = outcomeMatch[1];

  if (countText(normalizedResponse, REVIEW_CONTRACT.completionMarker) !== 1) {
    fail("ReviewGPT response must contain exactly one completion marker.");
  }
  const markerMatches = lines.filter((line) => line === REVIEW_CONTRACT.completionMarker);
  if (markerMatches.length !== 1) {
    fail("ReviewGPT completion marker must appear on its own line.");
  }

  const findingHeaders = lines.filter((line) =>
    /^\[(Critical|High|Medium|Complexity Collapse)\] .+/u.test(line),
  ).length;
  if (findingHeaders !== findings) {
    fail("REVIEW_FINDINGS must equal the number of structured finding headers.");
  }
  if ((outcome === "PASS" && findings !== 0) || (outcome === "FINDINGS" && findings === 0)) {
    fail("Review outcome and findings count disagree.");
  }

  if (nonemptyLines.at(-1) !== REVIEW_CONTRACT.completionMarker) {
    fail("ANDROID_REVIEW_COMPLETE must be the final non-empty line.");
  }
  if (nonemptyLines.at(-2) !== `REVIEW_OUTCOME: ${outcome}`) {
    fail("Review outcome must immediately precede the completion marker.");
  }
  if (nonemptyLines.at(-3) !== `REVIEW_FINDINGS: ${findings}`) {
    fail("Findings count must immediately precede the review outcome.");
  }

  return { contextSha256: expectedDigest, findings, outcome };
}

function readJson(path, label) {
  let parsed;
  try {
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch {
    fail(`${label} must be valid JSON.`);
  }
  return parsed;
}

function runCreate(args) {
  if (args.length !== 5) {
    fail(
      "Usage: review-gpt-contract.mjs create <pr-metadata.json> <repository> <prompt-file> <review-tool-version> <output-dir>",
    );
  }
  const [metadataPath, repository, promptPath, reviewToolVersion, outputDir] = args;
  const context = buildReviewContext({
    metadata: readJson(metadataPath, "PR metadata"),
    repository,
    promptBytes: readFileSync(promptPath),
    reviewToolVersion,
  });
  const serialized = serializeReviewContext(context);
  const digest = reviewContextDigest(context);
  mkdirSync(outputDir, { recursive: false, mode: 0o700 });
  writeFileSync(resolve(outputDir, "review-context.json"), serialized, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600,
  });
  writeFileSync(resolve(outputDir, "review-context.sha256"), `${digest}  review-context.json\n`, {
    encoding: "utf8",
    flag: "wx",
    mode: 0o600,
  });
  process.stdout.write(`${digest}\n`);
}

function runValidate(args) {
  if (args.length !== 2) {
    fail("Usage: review-gpt-contract.mjs validate <response-file> <review-context.json>");
  }
  const [responsePath, contextPath] = args;
  const result = validateReviewResponse({
    response: readFileSync(responsePath, "utf8"),
    context: readJson(contextPath, "review context"),
  });
  process.stdout.write(
    `Validated Android ReviewGPT response: ${result.outcome} (${result.findings} findings).\n`,
  );
}

function main(argv) {
  const [command, ...args] = argv;
  if (command === "create") {
    runCreate(args);
    return;
  }
  if (command === "validate") {
    runValidate(args);
    return;
  }
  fail("Usage: review-gpt-contract.mjs <create|validate> ...");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
