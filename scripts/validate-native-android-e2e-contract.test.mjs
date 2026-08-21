import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  CONTRACT_VERSION,
  MAX_DISPATCH_LEASE_SECONDS,
  STAGE_SUMMARY_STDOUT_PREFIX,
  extractStageSummaryFromInstrumentationLog,
  infrastructureFailureSummary,
  validateNativeAndroidE2EContract,
  validateStageSummary,
} from "./validate-native-android-e2e-contract.mjs";

const SHA = "a".repeat(40);
const ANDROID_SHA = "b".repeat(40);
const NOW = 2_000_000_000;
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function dispatch(overrides = {}) {
  return {
    android_sha: ANDROID_SHA,
    android_tag: "native-hosted-e2e/android-v1",
    contract_version: CONTRACT_VERSION,
    correlation_id: "murph-pr-123-safe",
    dispatch_expires_at: String(NOW + 900),
    identity_lifecycle: "orchestrator_owned_reset",
    mode: "pr",
    web_base_url: "https://candidate-123.vercel.app",
    web_sha: SHA,
    ...overrides,
  };
}

const PR_STAGES = [
  "contract_validation",
  "launch_live_app",
  "initial_privy_otp",
  "canonical_admission",
  "launch_consent_recovery",
  "server_owned_onboarding",
  "health_connect_handoff",
  "health_connect_permission_state",
  "connected_state",
  "sign_out",
  "returning_privy_otp",
  "returning_member_state",
];

const PRODUCTION_STAGES = [
  "contract_validation",
  "launch_live_app",
  "initial_privy_otp",
  "canonical_admission",
  "existing_member_state",
  "health_connect_handoff",
  "health_connect_permission_state",
  "connected_state",
  "sign_out",
  "returning_privy_otp",
  "returning_member_state",
];

function passedSummary(mode, names) {
  return {
    contractVersion: Number(CONTRACT_VERSION),
    mode,
    result: "passed",
    stages: names.map((name) => ({ name, status: "passed" })),
  };
}

test("dispatch contract binds exact Android source, hosted source, lifecycle, and mode", () => {
  assert.deepEqual(validateNativeAndroidE2EContract(dispatch(), NOW), {
    androidSha: ANDROID_SHA,
    androidTag: "native-hosted-e2e/android-v1",
    contractVersion: "1",
    correlationId: "murph-pr-123-safe",
    dispatchExpiresAt: NOW + 900,
    identityLifecycle: "orchestrator_owned_reset",
    mode: "pr",
    webBaseUrl: "https://candidate-123.vercel.app",
    webSha: SHA,
  });

  assert.equal(
    validateNativeAndroidE2EContract(dispatch({
      correlation_id: "murph-production-safe",
      identity_lifecycle: "non_destructive_existing_identity",
      mode: "production_canary",
      web_base_url: "https://www.withmurph.ai",
    }), NOW).mode,
    "production_canary",
  );
});

test("dispatch contract rejects every unbound or malformed source field", () => {
  const rejected = [
    { android_sha: "b".repeat(39) },
    { android_tag: "refs/tags/native-e2e" },
    { android_tag: "native//e2e" },
    { android_tag: "native/../e2e" },
    { contract_version: "2" },
    { correlation_id: "unsafe value" },
    { dispatch_expires_at: String(NOW) },
    { dispatch_expires_at: String(NOW + MAX_DISPATCH_LEASE_SECONDS + 1) },
    { dispatch_expires_at: "not-an-epoch" },
    { identity_lifecycle: "native_owned_reset" },
    { mode: "preview" },
    { web_base_url: "http://candidate.vercel.app" },
    { web_base_url: "https://candidate.vercel.app/path" },
    { web_base_url: "https://vercel.app" },
    { web_sha: "A".repeat(40) },
  ];
  for (const override of rejected) {
    assert.throws(() => validateNativeAndroidE2EContract(dispatch(override), NOW));
  }
  assert.throws(() => validateNativeAndroidE2EContract({
    ...dispatch(),
    unexpected: "field",
  }, NOW));
});

test("production canary requires exact production origin and non-destructive identity", () => {
  const production = {
    correlation_id: "murph-production-safe",
    identity_lifecycle: "non_destructive_existing_identity",
    mode: "production_canary",
    web_base_url: "https://www.withmurph.ai",
  };
  assert.doesNotThrow(() => validateNativeAndroidE2EContract(dispatch(production), NOW));
  assert.throws(() => validateNativeAndroidE2EContract(dispatch({
    ...production,
    identity_lifecycle: "orchestrator_owned_reset",
  }), NOW));
  assert.throws(() => validateNativeAndroidE2EContract(dispatch({
    ...production,
    web_base_url: "https://withmurph.ai",
  }), NOW));
});

test("stage summaries require complete ordered terminal proof for each mode", () => {
  assert.deepEqual(
    validateStageSummary(passedSummary("pr", PR_STAGES), "pr"),
    passedSummary("pr", PR_STAGES),
  );
  assert.deepEqual(
    validateStageSummary(
      passedSummary("production_canary", PRODUCTION_STAGES),
      "production_canary",
    ),
    passedSummary("production_canary", PRODUCTION_STAGES),
  );

  assert.throws(() => validateStageSummary(
    passedSummary("pr", PR_STAGES.slice(0, -1)),
    "pr",
  ));
  const reordered = [...PR_STAGES];
  [reordered[4], reordered[5]] = [reordered[5], reordered[4]];
  assert.throws(() => validateStageSummary(passedSummary("pr", reordered), "pr"));
  assert.throws(() => validateStageSummary(passedSummary("pr", PR_STAGES), "production_canary"));
});

test("a failed journey contains only the passed prefix and one allowlisted terminal failure", () => {
  const failed = {
    contractVersion: 1,
    mode: "pr",
    result: "failed",
    stages: [
      ...PR_STAGES.slice(0, 7).map((name) => ({ name, status: "passed" })),
      {
        code: "health_connect_permission_state_failed",
        name: "health_connect_permission_state",
        status: "failed",
      },
    ],
  };
  assert.deepEqual(validateStageSummary(failed, "pr"), failed);

  assert.throws(() => validateStageSummary({
    ...failed,
    stages: failed.stages.map((stage, index) => index === failed.stages.length - 1
      ? { ...stage, code: "connected_state_failed" }
      : stage),
  }, "pr"));
  assert.throws(() => validateStageSummary({
    ...failed,
    stages: [...failed.stages, { name: "connected_state", status: "passed" }],
  }, "pr"));
});

test("instrumentation transport accepts exactly one bounded allowlisted summary", () => {
  const summary = passedSummary("pr", PR_STAGES);
  const line = `${STAGE_SUMMARY_STDOUT_PREFIX}${JSON.stringify(summary)}`;
  assert.deepEqual(
    extractStageSummaryFromInstrumentationLog(`private provider prose\n${line}\n`, "pr"),
    summary,
  );
  assert.throws(() => extractStageSummaryFromInstrumentationLog("no summary", "pr"));
  assert.throws(() => extractStageSummaryFromInstrumentationLog(`${line}\n${line}`, "pr"));
  assert.throws(() => extractStageSummaryFromInstrumentationLog(
    `${STAGE_SUMMARY_STDOUT_PREFIX}{not-json}`,
    "pr",
  ));
});

test("instrumentation transport recovers one allowlisted journey failure", () => {
  assert.deepEqual(
    extractStageSummaryFromInstrumentationLog(
      "private provider prose\njava.lang.AssertionError: initial_privy_otp_failed\n",
      "production_canary",
    ),
    {
      contractVersion: 1,
      mode: "production_canary",
      result: "failed",
      stages: [
        { name: "contract_validation", status: "passed" },
        { name: "launch_live_app", status: "passed" },
        {
          code: "initial_privy_otp_failed",
          name: "initial_privy_otp",
          status: "failed",
        },
      ],
    },
  );
  assert.throws(() => extractStageSummaryFromInstrumentationLog(
    "launch_live_app_failed then initial_privy_otp_failed",
    "production_canary",
  ));
});

test("infrastructure summaries expose no provider or identity detail", () => {
  assert.deepEqual(infrastructureFailureSummary("pr", "gradle_failed"), {
    contractVersion: 1,
    mode: "pr",
    result: "failed",
    stages: [
      { name: "contract_validation", status: "passed" },
      { code: "gradle_failed", name: "infrastructure", status: "failed" },
    ],
  });
  assert.throws(() => infrastructureFailureSummary("pr", "provider_error_message"));
});

test("private workflow is source-bound, pinned, non-artifacting, and preserves synthetic smoke", async () => {
  const workflow = await readFile(
    path.join(ROOT, ".github", "workflows", "native-android-hosted-e2e.yml"),
    "utf8",
  );
  assert.match(workflow, /permissions:\n  contents: read/u);
  assert.match(workflow, /GITHUB_REF_TYPE.*tag/u);
  assert.match(workflow, /GITHUB_REF_NAME.*INPUT_ANDROID_TAG/u);
  assert.match(workflow, /GITHUB_SHA.*INPUT_ANDROID_SHA/u);
  assert.match(workflow, /INPUT_DISPATCH_EXPIRES_AT/u);
  assert.doesNotMatch(workflow, /\$\{\{\s*github\.ref_(?:name|type)\s*\}\}/u);
  assert.match(workflow, /persist-credentials: false/u);
  assert.match(workflow, /environment: native-android-hosted-e2e-/u);
  assert.match(workflow, /timeout-minutes: 55/u);
  assert.match(workflow, /NativeHostedE2EContractTest,ai\.withmurph\.companion\.e2e\.NativeHostedE2ETest/u);
  assert.match(workflow, /ANDROID_USER_HOME/u);
  assert.match(
    workflow,
    /privacy-safe summary finalizer below\.\n\s+set \+e\n\s+set -uo pipefail/u,
  );
  assert.doesNotMatch(workflow, /upload-artifact|download-artifact|\btee\b/u);
  assert.match(workflow, /rm -rf "\$\{RAW_LOG\}"/u);
  for (const line of workflow.split("\n").filter((value) => /^\s*uses:/u.test(value))) {
    assert.match(line, /uses: [^\s]+@[0-9a-f]{40}(?:\s|$)/u, line);
  }

  const smoke = await readFile(
    path.join(ROOT, ".github", "workflows", "android-instrumentation.yml"),
    "utf8",
  );
  assert.match(smoke, /https:\/\/example\.invalid/u);
  assert.match(smoke, /pixel2Api30SyntheticAndroidTest/u);

  await assert.rejects(readFile(
    path.join(
      ROOT,
      "app",
      "src",
      "androidTest",
      "java",
      "ai",
      "withmurph",
      "companion",
      "visual",
      "ScreenshotScenarioSmokeTest.kt",
    ),
    "utf8",
  ));
  const syntheticFixtureTest = await readFile(
    path.join(
      ROOT,
      "app",
      "src",
      "androidTestSynthetic",
      "java",
      "ai",
      "withmurph",
      "companion",
      "visual",
      "ScreenshotScenarioSmokeTest.kt",
    ),
    "utf8",
  );
  assert.match(syntheticFixtureTest, /ScreenshotActivity/u);
  const liveDriver = await readFile(
    path.join(
      ROOT,
      "app",
      "src",
      "androidTest",
      "java",
      "ai",
      "withmurph",
      "companion",
      "e2e",
      "NativeHostedE2ETest.kt",
    ),
    "utf8",
  );
  assert.match(liveDriver, /ActivityScenario\.launch\(MainActivity::class\.java\)/u);
  assert.match(liveDriver, /waitForText\("Send code", 60_000\)/u);
  assert.doesNotMatch(
    liveDriver,
    /waitForClickableText\("Send code", (?:45_000|60_000)\)/u,
  );
  assert.doesNotMatch(liveDriver, /ScreenshotActivity/u);

  const verify = await readFile(path.join(ROOT, "scripts", "verify.sh"), "utf8");
  assert.match(verify, /node --test scripts\/validate-native-android-e2e-contract\.test\.mjs/u);

  const gradle = await readFile(path.join(ROOT, "app", "build.gradle.kts"), "utf8");
  assert.match(gradle, /MURPH_ANDROID_TEST_BUILD_TYPE/u);
  assert.match(gradle, /\.orElse\("synthetic"\)/u);
  assert.match(gradle, /create\("hostedE2E"\)/u);
  assert.match(gradle, /initWith\(getByName\("debug"\)\)/u);
  assert.match(gradle, /create\("productionCanary"\)/u);
  assert.match(gradle, /setOf\("synthetic", "hostedE2E", "productionCanary"\)/u);
  assert.doesNotMatch(gradle, /setOf\("synthetic", "debug", "productionCanary"\)/u);
  assert.match(workflow, /pixel6Api35HostedE2EAndroidTest/u);
  assert.doesNotMatch(workflow, /pixel6Api35DebugAndroidTest/u);
});
