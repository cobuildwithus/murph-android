import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  REVIEW_CONTRACT,
  buildReviewContext,
  reviewContextDigest,
  validateReviewResponse,
} from "./review-gpt-contract.mjs";

const repository = "example/mobile-android";
const headSha = "2".repeat(40);
const baseSha = "1".repeat(40);

function context(overrides = {}) {
  return buildReviewContext({
    metadata: {
      baseRefName: "main",
      baseRefOid: baseSha,
      body: "## Why\n\nShip the narrow change.",
      headRefName: "feature/review-contract",
      headRefOid: headSha,
      number: 17,
      url: `https://github.com/${repository}/pull/17`,
      ...overrides.metadata,
    },
    promptBytes: Buffer.from(overrides.prompt ?? "fixed prompt", "utf8"),
    repository: overrides.repository ?? repository,
    reviewToolVersion: overrides.reviewToolVersion ?? "0.5.114",
  });
}

function response(reviewContext, { findings = [], outcome = findings.length ? "FINDINGS" : "PASS" } = {}) {
  const findingText = findings.length ? `\n${findings.join("\n\n")}\n` : "\n";
  return [
    `REVIEW_CONTEXT_SHA256: ${reviewContextDigest(reviewContext)}`,
    `Checked Android head: ${reviewContext.head.sha}`,
    findingText,
    `REVIEW_FINDINGS: ${findings.length}`,
    `REVIEW_OUTCOME: ${outcome}`,
    REVIEW_CONTRACT.completionMarker,
    "",
  ].join("\n");
}

test("accepts a zero-finding response bound to the complete review context", () => {
  const expected = context();
  assert.equal(expected.prompt.mode, "fixed-preset-only");
  assert.deepEqual(validateReviewResponse({ response: response(expected), context: expected }), {
    contextSha256: reviewContextDigest(expected),
    findings: 0,
    outcome: "PASS",
  });
});

test("accepts findings only when the count and terminal outcome agree", () => {
  const expected = context();
  const review = response(expected, {
    findings: [
      "[High] First reachable failure\n\nConcrete evidence and correction.",
      "[Complexity Collapse] Remove the duplicate owner\n\nEquivalent behavior with deletion.",
    ],
  });
  assert.equal(validateReviewResponse({ response: review, context: expected }).findings, 2);
});

for (const [label, movedContext] of [
  [
    "repository identity",
    () =>
      context({
        metadata: { url: "https://github.com/example/other-android/pull/17" },
        repository: "example/other-android",
      }),
  ],
  [
    "base head",
    () => context({ metadata: { baseRefOid: "3".repeat(40) } }),
  ],
  [
    "pushed head",
    () => context({ metadata: { headRefOid: "4".repeat(40) } }),
  ],
  ["PR body", () => context({ metadata: { body: "Changed after packaging." } })],
  ["prompt bytes", () => context({ prompt: "changed prompt" })],
  ["ReviewGPT version", () => context({ reviewToolVersion: "0.5.115" })],
]) {
  test(`rejects a response after the ${label} changes`, () => {
    const packaged = context();
    assert.throws(
      () => validateReviewResponse({ response: response(packaged), context: movedContext() }),
      /does not match the current PR review context/u,
    );
  });
}

test("rejects a checked head that disagrees with the context", () => {
  const expected = context();
  const wrongHeadResponse = response(expected).replace(
    `Checked Android head: ${headSha}`,
    `Checked Android head: ${"9".repeat(40)}`,
  );
  assert.throws(
    () => validateReviewResponse({ response: wrongHeadResponse, context: expected }),
    /different Android head/u,
  );
});

test("rejects missing completion and duplicate outcome markers", () => {
  const expected = context();
  assert.throws(
    () =>
      validateReviewResponse({
        response: response(expected).replace(REVIEW_CONTRACT.completionMarker, ""),
        context: expected,
      }),
    /completion marker/u,
  );
  assert.throws(
    () =>
      validateReviewResponse({
        response: response(expected).replace(
          "REVIEW_OUTCOME: PASS",
          "REVIEW_OUTCOME: PASS\nREVIEW_OUTCOME: PASS",
        ),
        context: expected,
      }),
    /exactly one review outcome/u,
  );
});

test("rejects a PASS with findings and a mismatched findings count", () => {
  const expected = context();
  const oneFinding = response(expected, {
    findings: ["[Medium] Reachable issue\n\nEvidence."],
  });
  assert.throws(
    () =>
      validateReviewResponse({
        response: oneFinding.replace("REVIEW_OUTCOME: FINDINGS", "REVIEW_OUTCOME: PASS"),
        context: expected,
      }),
    /outcome and findings count disagree/u,
  );
  assert.throws(
    () =>
      validateReviewResponse({
        response: oneFinding.replace("REVIEW_FINDINGS: 1", "REVIEW_FINDINGS: 2"),
        context: expected,
      }),
    /equal the number of structured finding headers/u,
  );
});

test("hosted workflows keep product and review verification on PR heads", () => {
  const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const androidWorkflow = readFileSync(resolve(repoRoot, ".github/workflows/android-ci.yml"), "utf8");
  const reviewWorkflow = readFileSync(
    resolve(repoRoot, ".github/workflows/review-tooling.yml"),
    "utf8",
  );

  assert.match(androidWorkflow, /^\s*pull_request:\s*$/mu);
  assert.match(androidWorkflow, /\.\/scripts\/verify\.sh/u);
  assert.doesNotMatch(androidWorkflow, /secrets\./u);
  assert.match(reviewWorkflow, /^\s*pull_request:\s*$/mu);
  assert.match(reviewWorkflow, /pnpm install --frozen-lockfile --ignore-scripts/u);
  assert.match(reviewWorkflow, /pnpm review:verify/u);
});
