#!/usr/bin/env node

import { appendFile, open, rm, writeFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const CONTRACT_VERSION = "1";
export const STAGE_SUMMARY_STDOUT_PREFIX =
  "MURPH_NATIVE_ANDROID_E2E_STAGE_SUMMARY_JSON:";
export const MAX_INSTRUMENTATION_LOG_BYTES = 64 * 1024 * 1024;
export const MAX_STAGE_SUMMARY_BYTES = 32 * 1024;
export const MAX_DISPATCH_LEASE_SECONDS = 35 * 60;

const DISPATCH_KEYS = [
  "android_sha",
  "android_tag",
  "contract_version",
  "correlation_id",
  "dispatch_expires_at",
  "identity_lifecycle",
  "mode",
  "web_base_url",
  "web_sha",
];

const STAGES_BY_MODE = Object.freeze({
  pr: Object.freeze([
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
  ]),
  production_canary: Object.freeze([
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
  ]),
});

const INFRASTRUCTURE_FAILURE_CODES = new Set([
  "gradle_failed",
  "missing_protected_configuration",
  "summary_missing_or_invalid",
]);

const FAILURE_CODES_BY_STAGE = new Map([
  ["contract_validation", new Set(["invalid_dispatch_contract"])],
  ["infrastructure", INFRASTRUCTURE_FAILURE_CODES],
  ["launch_live_app", new Set(["launch_live_app_failed"])],
  [
    "initial_privy_otp",
    new Set([
      "initial_privy_otp_failed",
      "initial_privy_otp_request_rejected",
      "initial_privy_otp_code_rejected",
    ]),
  ],
  ["canonical_admission", new Set(["canonical_admission_failed"])],
  [
    "launch_consent_recovery",
    new Set(["launch_consent_recovery_failed"]),
  ],
  [
    "server_owned_onboarding",
    new Set(["server_owned_onboarding_failed"]),
  ],
  ["existing_member_state", new Set(["existing_member_state_failed"])],
  [
    "health_connect_handoff",
    new Set([
      "health_connect_handoff_failed",
      "health_connect_surface_missing",
    ]),
  ],
  [
    "health_connect_permission_state",
    new Set([
      "health_connect_permission_state_failed",
      "health_connect_permission_surface_missing",
      "health_connect_permission_selection_missing",
      "health_connect_permission_approval_missing",
      "health_connect_permission_completion_failed",
      "health_connect_permission_completion_pending",
      "health_connect_permission_ui_projection_failed",
      "health_connect_permission_app_state_failed",
      "health_connect_permission_app_return_missing",
      "health_connect_permission_grant_classification_failed",
      "health_connect_permission_verification_failed",
      "health_connect_post_permission_reset_failed",
      "health_connect_post_permission_network_failed",
      "health_connect_post_permission_connection_failed",
      "health_connect_post_permission_setup_save_failed",
    ]),
  ],
  ["connected_state", new Set(["connected_state_failed"])],
  ["sign_out", new Set(["sign_out_failed"])],
  [
    "returning_privy_otp",
    new Set([
      "returning_privy_otp_failed",
      "returning_privy_otp_request_rejected",
      "returning_privy_otp_code_rejected",
    ]),
  ],
  [
    "returning_member_state",
    new Set(["returning_member_state_failed"]),
  ],
]);

export class DispatchContractError extends Error {
  constructor() {
    super("Native Android hosted E2E dispatch contract is invalid.");
    this.name = "DispatchContractError";
  }
}

/** @param {Record<string, unknown>} input @param {number} nowEpochSeconds */
export function validateNativeAndroidE2EContract(
  input,
  nowEpochSeconds = Math.floor(Date.now() / 1000),
) {
  assertExactKeys(input, DISPATCH_KEYS);

  const androidSha = readExactString(input.android_sha);
  const androidTag = readExactString(input.android_tag);
  const contractVersion = readExactString(input.contract_version);
  const correlationId = readExactString(input.correlation_id);
  const dispatchExpiresAtRaw = readExactString(input.dispatch_expires_at);
  const identityLifecycle = readExactString(input.identity_lifecycle);
  const mode = readExactString(input.mode);
  const webBaseUrl = readExactString(input.web_base_url);
  const webSha = readExactString(input.web_sha);

  if (
    contractVersion !== CONTRACT_VERSION
    || !isSha(androidSha)
    || !isSha(webSha)
    || !isSafeTag(androidTag)
    || !/^[A-Za-z0-9._:-]{1,120}$/u.test(correlationId)
    || !Number.isSafeInteger(nowEpochSeconds)
    || nowEpochSeconds < 1_000_000_000
    || !/^[1-9][0-9]{9}$/u.test(dispatchExpiresAtRaw)
  ) {
    throw new DispatchContractError();
  }

  const dispatchExpiresAt = Number(dispatchExpiresAtRaw);
  if (
    !Number.isSafeInteger(dispatchExpiresAt)
    || dispatchExpiresAt <= nowEpochSeconds
    || dispatchExpiresAt > nowEpochSeconds + MAX_DISPATCH_LEASE_SECONDS
  ) {
    throw new DispatchContractError();
  }

  const origin = parseExactHttpsOrigin(webBaseUrl);
  const hostname = new URL(origin).hostname;
  if (mode === "pr") {
    if (
      identityLifecycle !== "orchestrator_owned_reset"
      || hostname === "vercel.app"
      || !hostname.endsWith(".vercel.app")
    ) {
      throw new DispatchContractError();
    }
  } else if (mode === "production_canary") {
    if (
      identityLifecycle !== "non_destructive_existing_identity"
      || origin !== "https://www.withmurph.ai"
    ) {
      throw new DispatchContractError();
    }
  } else {
    throw new DispatchContractError();
  }

  return Object.freeze({
    androidSha,
    androidTag,
    contractVersion,
    correlationId,
    dispatchExpiresAt,
    identityLifecycle,
    mode,
    webBaseUrl: origin,
    webSha,
  });
}

/** @param {unknown} raw @param {string | undefined} expectedMode */
export function validateStageSummary(raw, expectedMode) {
  if (!isRecord(raw)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  assertSummaryExactKeys(raw, ["contractVersion", "mode", "result", "stages"]);
  if (raw.contractVersion !== Number(CONTRACT_VERSION)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }

  const mode = readSummaryString(raw.mode);
  if (expectedMode !== undefined && mode !== expectedMode) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  if (mode !== "invalid" && !(mode in STAGES_BY_MODE)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }

  const result = readSummaryString(raw.result);
  if (!new Set(["failed", "passed"]).has(result) || !Array.isArray(raw.stages)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  const stages = raw.stages.map(validateStage);

  if (mode === "invalid") {
    if (result !== "failed") {
      throw new Error("Invalid native Android hosted E2E stage summary.");
    }
    assertExactFailure(stages, "contract_validation", "invalid_dispatch_contract");
  } else {
    validateModeStages(mode, result, stages);
  }

  return Object.freeze({
    contractVersion: Number(CONTRACT_VERSION),
    mode,
    result,
    stages,
  });
}

/** @param {string} rawLog @param {string} expectedMode */
export function extractStageSummaryFromInstrumentationLog(rawLog, expectedMode) {
  if (
    typeof rawLog !== "string"
    || Buffer.byteLength(rawLog, "utf8") > MAX_INSTRUMENTATION_LOG_BYTES
  ) {
    throw new Error("Invalid native Android hosted E2E instrumentation log.");
  }

  const payloads = [];
  for (const line of rawLog.split(/\r?\n/u)) {
    let cursor = 0;
    while (cursor < line.length) {
      const prefixIndex = line.indexOf(STAGE_SUMMARY_STDOUT_PREFIX, cursor);
      if (prefixIndex === -1) break;
      payloads.push(
        line.slice(prefixIndex + STAGE_SUMMARY_STDOUT_PREFIX.length).trim(),
      );
      cursor = prefixIndex + STAGE_SUMMARY_STDOUT_PREFIX.length;
    }
  }

  if (payloads.length === 0) {
    return inferStageSummaryFromAllowlistedFailure(rawLog, expectedMode);
  }
  if (
    payloads.length !== 1
    || payloads[0].length === 0
    || Buffer.byteLength(payloads[0], "utf8") > MAX_STAGE_SUMMARY_BYTES
  ) {
    throw new Error("Invalid native Android hosted E2E instrumentation log.");
  }

  let rawSummary;
  try {
    rawSummary = JSON.parse(payloads[0]);
  } catch {
    throw new Error("Invalid native Android hosted E2E instrumentation log.");
  }
  return validateStageSummary(rawSummary, expectedMode);
}

/** @param {string} rawLog @param {string} expectedMode */
function inferStageSummaryFromAllowlistedFailure(rawLog, expectedMode) {
  const matches = [];
  for (const [stage, codes] of FAILURE_CODES_BY_STAGE) {
    for (const code of codes) {
      const pattern = new RegExp(`(^|[^a-z0-9_])${code}([^a-z0-9_]|$)`, "u");
      if (pattern.test(rawLog)) matches.push({ code, stage });
    }
  }
  if (matches.length !== 1 || !(expectedMode in STAGES_BY_MODE)) {
    throw new Error("Invalid native Android hosted E2E instrumentation log.");
  }

  const [{ code, stage }] = matches;
  if (stage === "infrastructure") {
    return validateStageSummary({
      contractVersion: Number(CONTRACT_VERSION),
      mode: expectedMode,
      result: "failed",
      stages: [
        { name: "contract_validation", status: "passed" },
        { code, name: stage, status: "failed" },
      ],
    }, expectedMode);
  }

  const failedIndex = STAGES_BY_MODE[expectedMode].indexOf(stage);
  if (failedIndex < 0) {
    throw new Error("Invalid native Android hosted E2E instrumentation log.");
  }
  return validateStageSummary({
    contractVersion: Number(CONTRACT_VERSION),
    mode: expectedMode,
    result: "failed",
    stages: [
      ...STAGES_BY_MODE[expectedMode]
        .slice(0, failedIndex)
        .map((name) => ({ name, status: "passed" })),
      { code, name: stage, status: "failed" },
    ],
  }, expectedMode);
}

/** @param {string} mode @param {string} code */
export function infrastructureFailureSummary(mode, code) {
  if (!(mode in STAGES_BY_MODE) || !INFRASTRUCTURE_FAILURE_CODES.has(code)) {
    throw new Error("Invalid native Android hosted E2E infrastructure failure.");
  }
  return {
    contractVersion: Number(CONTRACT_VERSION),
    mode,
    result: "failed",
    stages: [
      { name: "contract_validation", status: "passed" },
      { code, name: "infrastructure", status: "failed" },
    ],
  };
}

function validateModeStages(mode, result, stages) {
  const expected = STAGES_BY_MODE[mode];
  if (stages.length < 1 || stages.length > expected.length) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }

  if (
    stages.length === 2
    && stages[0].name === "contract_validation"
    && stages[0].status === "passed"
    && stages[1].name === "infrastructure"
    && stages[1].status === "failed"
  ) {
    if (result !== "failed") {
      throw new Error("Invalid native Android hosted E2E stage summary.");
    }
    return;
  }

  for (let index = 0; index < stages.length; index += 1) {
    if (stages[index].name !== expected[index]) {
      throw new Error("Invalid native Android hosted E2E stage summary.");
    }
  }

  const last = stages.at(-1);
  if (result === "passed") {
    if (
      stages.length !== expected.length
      || stages.some((stage) => stage.status !== "passed")
    ) {
      throw new Error("Invalid native Android hosted E2E stage summary.");
    }
    return;
  }

  if (
    last.status !== "failed"
    || stages.slice(0, -1).some((stage) => stage.status !== "passed")
  ) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
}

function validateStage(raw) {
  if (!isRecord(raw)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  const status = readSummaryString(raw.status);
  if (status === "passed") {
    assertSummaryExactKeys(raw, ["name", "status"]);
    return Object.freeze({
      name: readStageName(raw.name),
      status,
    });
  }
  if (status !== "failed") {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  assertSummaryExactKeys(raw, ["code", "name", "status"]);
  const name = readStageName(raw.name);
  const code = readSummaryString(raw.code);
  const allowed = FAILURE_CODES_BY_STAGE.get(name);
  if (!allowed?.has(code)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  return Object.freeze({ code, name, status });
}

function readStageName(value) {
  const name = readSummaryString(value);
  if (!FAILURE_CODES_BY_STAGE.has(name)) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  return name;
}

function assertExactFailure(stages, stage, code) {
  if (
    stages.length !== 1
    || stages[0].name !== stage
    || stages[0].status !== "failed"
    || stages[0].code !== code
  ) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
}

function invalidDispatchSummary() {
  return {
    contractVersion: Number(CONTRACT_VERSION),
    mode: "invalid",
    result: "failed",
    stages: [{
      code: "invalid_dispatch_contract",
      name: "contract_validation",
      status: "failed",
    }],
  };
}

function isSha(value) {
  return /^[0-9a-f]{40}$/u.test(value);
}

function isSafeTag(value) {
  return value.length <= 180
    && /^[A-Za-z0-9._/-]+$/u.test(value)
    && !value.startsWith("refs/")
    && !value.startsWith("/")
    && !value.endsWith("/")
    && !value.endsWith(".lock")
    && !value.includes("..")
    && !value.includes("//")
    && !value.includes("@{");
}

function parseExactHttpsOrigin(rawValue) {
  let parsed;
  try {
    parsed = new URL(rawValue);
  } catch {
    throw new DispatchContractError();
  }
  if (
    parsed.protocol !== "https:"
    || parsed.username
    || parsed.password
    || parsed.search
    || parsed.hash
    || parsed.pathname !== "/"
    || rawValue !== parsed.origin
  ) {
    throw new DispatchContractError();
  }
  return parsed.origin;
}

function readExactString(value) {
  if (typeof value !== "string" || value.length === 0 || value.trim() !== value) {
    throw new DispatchContractError();
  }
  return value;
}

function readSummaryString(value) {
  if (typeof value !== "string" || value.length === 0 || value.trim() !== value) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
  return value;
}

function assertExactKeys(value, expectedKeys) {
  if (!isRecord(value)) throw new DispatchContractError();
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  if (
    actual.length !== expected.length
    || actual.some((key, index) => key !== expected[index])
  ) {
    throw new DispatchContractError();
  }
}

function assertSummaryExactKeys(value, expectedKeys) {
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  if (
    actual.length !== expected.length
    || actual.some((key, index) => key !== expected[index])
  ) {
    throw new Error("Invalid native Android hosted E2E stage summary.");
  }
}

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function readBoundedUtf8File(filePath, maximumBytes) {
  const handle = await open(filePath, "r");
  try {
    const metadata = await handle.stat();
    if (!metadata.isFile() || metadata.size < 1 || metadata.size > maximumBytes) {
      throw new Error("Invalid native Android hosted E2E file.");
    }
    const buffer = await handle.readFile();
    if (buffer.length !== metadata.size || buffer.length > maximumBytes) {
      throw new Error("Invalid native Android hosted E2E file.");
    }
    return buffer.toString("utf8");
  } finally {
    await handle.close();
  }
}

async function publish(summary) {
  const validated = validateStageSummary(summary);
  const json = JSON.stringify(validated);
  process.stdout.write(`${json}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    await appendFile(
      process.env.GITHUB_STEP_SUMMARY,
      `\n\`\`\`json\n${json}\n\`\`\`\n`,
      "utf8",
    );
  }
}

function dispatchInputFromEnvironment() {
  return {
    android_sha: process.env.INPUT_ANDROID_SHA,
    android_tag: process.env.INPUT_ANDROID_TAG,
    contract_version: process.env.INPUT_CONTRACT_VERSION,
    correlation_id: process.env.INPUT_CORRELATION_ID,
    dispatch_expires_at: process.env.INPUT_DISPATCH_EXPIRES_AT,
    identity_lifecycle: process.env.INPUT_IDENTITY_LIFECYCLE,
    mode: process.env.INPUT_MODE,
    web_base_url: process.env.INPUT_WEB_BASE_URL,
    web_sha: process.env.INPUT_WEB_SHA,
  };
}

function requiredArgument(args, name) {
  const index = args.indexOf(`--${name}`);
  const value = index >= 0 ? args[index + 1] : undefined;
  if (!value || value.startsWith("--")) {
    throw new Error("Required native Android hosted E2E argument is missing.");
  }
  return value;
}

async function validateDispatchCommand() {
  try {
    validateNativeAndroidE2EContract(dispatchInputFromEnvironment());
  } catch {
    await publish(invalidDispatchSummary());
    process.exitCode = 1;
  }
}

async function writeInfrastructureFailureCommand(args) {
  const mode = requiredArgument(args, "mode");
  const code = requiredArgument(args, "code");
  const output = requiredArgument(args, "output");
  const summary = infrastructureFailureSummary(mode, code);
  await writeFile(output, `${JSON.stringify(summary)}\n`, {
    encoding: "utf8",
    mode: 0o600,
  });
}

async function extractStageSummaryCommand(args) {
  const mode = requiredArgument(args, "mode");
  const logPath = requiredArgument(args, "log");
  const output = requiredArgument(args, "output");
  await rm(output, { force: true });

  try {
    const rawLog = await readBoundedUtf8File(
      logPath,
      MAX_INSTRUMENTATION_LOG_BYTES,
    );
    const summary = extractStageSummaryFromInstrumentationLog(rawLog, mode);
    await writeFile(output, JSON.stringify(summary), {
      encoding: "utf8",
      mode: 0o600,
    });
  } catch {
    await rm(output, { force: true });
    process.exitCode = 1;
  }
}

async function finalizeRunCommand(args) {
  const mode = requiredArgument(args, "mode");
  const summaryPath = requiredArgument(args, "summary");
  const rawStatus = requiredArgument(args, "gradle-status");
  const gradleStatus = Number(rawStatus);
  if (!(mode in STAGES_BY_MODE) || !Number.isSafeInteger(gradleStatus) || gradleStatus < 0) {
    throw new Error("Invalid native Android hosted E2E finalization arguments.");
  }

  let summary;
  try {
    summary = validateStageSummary(
      JSON.parse(await readBoundedUtf8File(summaryPath, MAX_STAGE_SUMMARY_BYTES)),
      mode,
    );
  } catch {
    summary = infrastructureFailureSummary(
      mode,
      gradleStatus === 0 ? "summary_missing_or_invalid" : "gradle_failed",
    );
  }

  if (gradleStatus !== 0 && summary.result === "passed") {
    summary = infrastructureFailureSummary(mode, "gradle_failed");
  }

  await publish(summary);
  if (gradleStatus !== 0 || summary.result !== "passed") {
    process.exitCode = 1;
  }
}

async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === "validate-dispatch") {
    await validateDispatchCommand();
    return;
  }
  if (command === "write-infrastructure-failure") {
    await writeInfrastructureFailureCommand(args);
    return;
  }
  if (command === "extract-stage-summary") {
    await extractStageSummaryCommand(args);
    return;
  }
  if (command === "finalize-run") {
    await finalizeRunCommand(args);
    return;
  }
  throw new Error("Unknown native Android hosted E2E contract command.");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(async () => {
    try {
      await publish(invalidDispatchSummary());
    } finally {
      process.exitCode = 1;
    }
  });
}
