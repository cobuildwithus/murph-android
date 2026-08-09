#!/usr/bin/env node

import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const RELEASE_PACKET_PATHS = [
  "play/release-facts.json",
  "play/listing/en-US/title.txt",
  "play/listing/en-US/short-description.txt",
  "play/listing/en-US/full-description.txt",
  "play/listing/en-US/release-notes-1.txt",
  "play/declarations/data-safety.md",
  "play/declarations/health-apps.md",
  "play/declarations/contacts.md",
  "play/release-checklist.md",
];
const BUNDLETOOL_MAIN_CLASS = "com.android.tools.build.bundletool.BundleToolMain";
const PLAY_ARTIFACT_INSPECTOR = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "PlayArtifactInspector.java",
);

function readText(rootDir, relativePath) {
  return fs.readFileSync(path.join(rootDir, relativePath), "utf8");
}

function readJson(rootDir, relativePath) {
  return JSON.parse(readText(rootDir, relativePath));
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function exactSourceHead(rootDir) {
  const status = execFileSync(
    "git",
    ["status", "--porcelain", "--untracked-files=normal"],
    { cwd: rootDir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
  ).trim();
  if (status) {
    throw new Error("Submission verification requires a clean exact-source checkout.");
  }
  const head = execFileSync(
    "git",
    ["rev-parse", "HEAD"],
    { cwd: rootDir, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
  ).trim();
  if (!/^[0-9a-f]{40}$/.test(head)) {
    throw new Error("Could not derive the exact source commit.");
  }
  return head;
}

export function validateArtifactSourceMetadata(metadata, sourceHead, configurationSha256) {
  if (!/^[0-9a-f]{64}$/.test(configurationSha256)) {
    throw new Error("Play submission requires the expected public-configuration digest.");
  }
  const expected = `schema=1\nsourceHead=${sourceHead}\nworkingTreeClean=true\n` +
    `configurationSha256=${configurationSha256}\n`;
  if (metadata !== expected) {
    throw new Error(
      "The signed Android App Bundle is not bound to this exact clean source commit.",
    );
  }
}

export function inspectAndroidBundle(
  releaseArtifactPath,
  bundletoolClasspath,
  runCommand = execFileSync,
) {
  if (!bundletoolClasspath) {
    throw new Error("Play submission requires the pinned bundletool classpath.");
  }
  try {
    runCommand(
      "java",
      [
        "-cp",
        bundletoolClasspath,
        BUNDLETOOL_MAIN_CLASS,
        "validate",
        `--bundle=${releaseArtifactPath}`,
      ],
      {
        encoding: "utf8",
        maxBuffer: 4 * 1024 * 1024,
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
  } catch {
    throw new Error("The release artifact is not a valid Android App Bundle.");
  }
  try {
    return runCommand(
      "java",
      [
        "-cp",
        bundletoolClasspath,
        BUNDLETOOL_MAIN_CLASS,
        "dump",
        "manifest",
        `--bundle=${releaseArtifactPath}`,
        "--module=base",
      ],
      {
        encoding: "utf8",
        maxBuffer: 4 * 1024 * 1024,
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
  } catch {
    throw new Error("The Android App Bundle base manifest could not be inspected.");
  }
}

function requireSignedAndroidBundle(
  releaseArtifactPath,
  sourceHead,
  configurationSha256,
  bundletoolClasspath,
  expectedSignerSha256,
) {
  if (path.extname(releaseArtifactPath).toLowerCase() !== ".aab") {
    throw new Error("Play submission requires the exact signed Android App Bundle (.aab).");
  }
  const artifactManifest = inspectAndroidBundle(releaseArtifactPath, bundletoolClasspath);
  verifyAndroidBundleSigners(releaseArtifactPath, expectedSignerSha256);
  let sourceMetadata = "";
  try {
    sourceMetadata = execFileSync(
      "unzip",
      ["-p", releaseArtifactPath, "base/assets/murph-play/source.properties"],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
    );
  } catch {
    throw new Error("The signed Android App Bundle has no source provenance metadata.");
  }
  validateArtifactSourceMetadata(sourceMetadata, sourceHead, configurationSha256);
  return artifactManifest;
}

function javaExecutable() {
  return process.env.MURPH_JAVA_EXECUTABLE ?? (
    process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java") : "java"
  );
}

export function verifyAndroidBundleSigners(
  releaseArtifactPath,
  expectedSignerSha256,
  runCommand = execFileSync,
) {
  if (!/^(?:[0-9a-f]{2}:){31}[0-9a-f]{2}$|^[0-9a-f]{64}$/i.test(expectedSignerSha256)) {
    throw new Error("Play submission requires the approved upload-certificate SHA-256.");
  }
  try {
    runCommand(
      javaExecutable(),
      [
        PLAY_ARTIFACT_INSPECTOR,
        "verify-signers",
        releaseArtifactPath,
        expectedSignerSha256,
      ],
      {
        encoding: "utf8",
        maxBuffer: 4 * 1024 * 1024,
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
  } catch {
    throw new Error(
      "The Android App Bundle is not completely signed by the approved upload certificate.",
    );
  }
}

export function releasePacketSha256(rootDir = process.cwd()) {
  const hash = crypto.createHash("sha256");
  for (const relativePath of RELEASE_PACKET_PATHS) {
    const contents = fs.readFileSync(path.join(rootDir, relativePath));
    hash.update(relativePath);
    hash.update("\0");
    hash.update(String(contents.length));
    hash.update("\0");
    hash.update(contents);
    hash.update("\0");
  }
  return hash.digest("hex");
}

function sortedUnique(values, label) {
  const sorted = [...new Set(values)].sort();
  if (sorted.length !== values.length) throw new Error(`${label} contains duplicates.`);
  return sorted;
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label} drifted: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}.`);
  }
}

function assertStringSet(actual, expected, label) {
  const actualSorted = sortedUnique(actual, `${label} actual values`);
  const expectedSorted = sortedUnique(expected, `${label} expected values`);
  if (JSON.stringify(actualSorted) !== JSON.stringify(expectedSorted)) {
    const missing = expectedSorted.filter((value) => !actualSorted.includes(value));
    const unexpected = actualSorted.filter((value) => !expectedSorted.includes(value));
    throw new Error(
      `${label} drifted. Missing: ${missing.join(", ") || "none"}. ` +
        `Unexpected: ${unexpected.join(", ") || "none"}.`,
    );
  }
}

function requiredMatch(source, expression, label) {
  const match = source.match(expression);
  if (!match) throw new Error(`Could not derive ${label} from source.`);
  return match[1];
}

function escapeRegularExpression(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function extractKotlinSetMembers(source, symbol, qualifier) {
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(symbol)) {
    throw new Error(`Invalid Kotlin set symbol: ${symbol}.`);
  }
  if (!/^[A-Za-z_][A-Za-z0-9_.]*$/.test(qualifier)) {
    throw new Error(`Invalid Kotlin member qualifier: ${qualifier}.`);
  }

  const sourceWithoutComments = source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/.*$/gm, "");
  const initializer = new RegExp(
    `\\b(?:val|var)\\s+${escapeRegularExpression(symbol)}\\b` +
      `(?:\\s*:[^=\\n]+)?\\s*=\\s*setOf\\s*\\(`,
    "g",
  );
  const matches = [...sourceWithoutComments.matchAll(initializer)];
  if (matches.length !== 1) {
    throw new Error(
      `Expected exactly one setOf initializer for ${symbol}; found ${matches.length}.`,
    );
  }

  const openingParenthesis = matches[0].index + matches[0][0].lastIndexOf("(");
  let depth = 0;
  let closingParenthesis = -1;
  for (let index = openingParenthesis; index < sourceWithoutComments.length; index += 1) {
    if (sourceWithoutComments[index] === "(") depth += 1;
    if (sourceWithoutComments[index] === ")") {
      depth -= 1;
      if (depth === 0) {
        closingParenthesis = index;
        break;
      }
    }
  }
  if (closingParenthesis < 0) {
    throw new Error(`Could not find the closing parenthesis for ${symbol}.`);
  }

  const remainderLines = sourceWithoutComments
    .slice(closingParenthesis + 1)
    .split(/\r?\n/);
  if (remainderLines.shift().trim() !== "") {
    throw new Error(`The ${symbol} initializer has an unsupported suffix.`);
  }
  const nextCodeLine = remainderLines.find((line) => line.trim() !== "");
  if (
    nextCodeLine &&
    !/^\s*(?:[@}\])]|(?:public|private|protected|internal|override|open|final|abstract|suspend|operator|infix|tailrec|external|lateinit|const|fun|val|var|class|object|companion|init|typealias)\b)/.test(
      nextCodeLine,
    )
  ) {
    throw new Error(`The ${symbol} initializer has an unsupported continuation.`);
  }

  const resourceBlock = sourceWithoutComments.slice(openingParenthesis + 1, closingParenthesis);
  const rawEntries = resourceBlock.split(",");
  if (rawEntries.at(-1)?.trim() === "") rawEntries.pop();
  if (rawEntries.length === 0 || rawEntries.some((entry) => entry.trim() === "")) {
    throw new Error(`The ${symbol} initializer must contain direct ${qualifier} members.`);
  }
  const directMember = new RegExp(
    `^${escapeRegularExpression(qualifier)}\\.([A-Za-z_][A-Za-z0-9_]*)$`,
  );
  const members = rawEntries.map((entry) => {
    const match = entry.trim().match(directMember);
    if (!match) {
      throw new Error(`The ${symbol} initializer must contain only direct ${qualifier} members.`);
    }
    return match[1];
  });
  if (members.length === 0) {
    throw new Error(`The ${symbol} initializer contains no direct ${qualifier} members.`);
  }
  return sortedUnique(members, `${symbol} members`);
}

export function extractManifestPermissions(source) {
  const requested = [];
  const removed = [];
  for (const match of source.matchAll(/<uses-permission(?:-sdk-\d+)?\b[\s\S]*?\/>/g)) {
    const tag = match[0];
    const name = tag.match(/android:name="([^"]+)"/)?.[1];
    if (!name) throw new Error("A uses-permission element has no android:name.");
    if (/tools:node="remove"/.test(tag)) removed.push(name);
    else requested.push(name);
  }
  return {
    requested: sortedUnique(requested, "manifest requested permissions"),
    removed: sortedUnique(removed, "manifest removed permissions"),
  };
}

const manifestContractCache = new Map();

export function releaseManifestContract(source, runCommand = execFileSync) {
  const cacheKey = sha256(source);
  if (runCommand === execFileSync && manifestContractCache.has(cacheKey)) {
    return manifestContractCache.get(cacheKey);
  }
  let contract;
  try {
    const output = runCommand(
      javaExecutable(),
      [PLAY_ARTIFACT_INSPECTOR, "manifest-contract"],
      {
        encoding: "utf8",
        input: source,
        maxBuffer: 4 * 1024 * 1024,
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
    contract = JSON.parse(output);
  } catch {
    throw new Error("The Android manifest security contract could not be parsed.");
  }
  if (runCommand === execFileSync) manifestContractCache.set(cacheKey, contract);
  return contract;
}

function assertAttributeExpectations(actual, expected, label) {
  for (const [name, expectedValue] of Object.entries(expected)) {
    if (expectedValue === null) {
      if (Object.hasOwn(actual, name)) {
        throw new Error(`${label} unexpectedly defines ${name}.`);
      }
      continue;
    }
    assertEqual(actual[name], expectedValue, `${label} ${name}`);
  }
}

function validateReleaseManifestContract(contract, facts, label) {
  assertEqual(contract.packageName, facts.application.applicationId, `${label} package`);
  assertEqual(contract.versionCode, facts.application.versionCode, `${label} versionCode`);
  assertEqual(contract.versionName, facts.application.versionName, `${label} versionName`);
  assertEqual(contract.minSdk, facts.application.minSdk, `${label} minSdk`);
  assertEqual(contract.targetSdk, facts.application.targetSdk, `${label} targetSdk`);
  assertEqual(
    JSON.stringify(contract.usesSdkAttributes),
    JSON.stringify(facts.releaseManifest.usesSdkAttributes),
    `${label} uses-sdk attributes`,
  );
  assertAttributeExpectations(
    contract.applicationSecurityAttributes,
    facts.releaseManifest.applicationSecurityAttributes,
    `${label} application`,
  );
  assertEqual(
    contract.applicationName,
    facts.releaseManifest.applicationClass,
    `${label} application owner`,
  );
  if (!contract.activities.some(
    (activity) => activity.name === facts.releaseManifest.launcherActivity,
  )) {
    throw new Error(`${label} is missing the expected launcher activity.`);
  }
  assertStringSet(
    contract.permissions.map((permission) => permission["android:name"]),
    facts.mergedManifestPermissions,
    `${label} permissions`,
  );

  const componentNames = [
    ...contract.activities,
    ...contract.activityAliases,
    ...contract.services,
    ...contract.receivers,
    ...contract.providers,
  ].map((component) => component.name);
  const forbiddenComponents = facts.releaseManifest.forbiddenComponents.filter(
    (name) => componentNames.includes(name),
  );
  if (forbiddenComponents.length > 0) {
    throw new Error(`${label} contains forbidden components: ${forbiddenComponents.join(", ")}.`);
  }
  assertEqual(
    JSON.stringify(contract.declaredPermissions),
    JSON.stringify(facts.releaseManifest.declaredPermissions),
    `${label} declared permissions`,
  );
  assertEqual(
    JSON.stringify(contract.permissionGroups),
    JSON.stringify(facts.releaseManifest.permissionGroups),
    `${label} permission groups`,
  );
  assertEqual(
    JSON.stringify(contract.permissionTrees),
    JSON.stringify(facts.releaseManifest.permissionTrees),
    `${label} permission trees`,
  );
  for (const expectedComponent of facts.releaseManifest.componentSecurityAttributes) {
    const component = contract[expectedComponent.componentType].find(
      (candidate) => candidate.name === expectedComponent.component,
    );
    if (!component) throw new Error(`${label} is missing an expected component.`);
    assertAttributeExpectations(
      component.securityAttributes,
      expectedComponent.attributes,
      `${label} ${expectedComponent.component}`,
    );
  }
  for (const requiredFilter of facts.releaseManifest.requiredIntentFilters) {
    const components = contract[requiredFilter.componentType];
    const component = components.find((candidate) => candidate.name === requiredFilter.component);
    if (!component) {
      throw new Error(`${label} is missing an intent-filter owner.`);
    }
    const present = component.intentFilters.some((filter) =>
      JSON.stringify(filter.actions) === JSON.stringify(requiredFilter.actions) &&
      JSON.stringify(filter.categories) === JSON.stringify(requiredFilter.categories) &&
      JSON.stringify(filter.data) === JSON.stringify(requiredFilter.data)
    );
    if (!present) throw new Error(`${label} is missing a required intent filter.`);
  }
}

export function validateArtifactManifest(artifactManifest, localManifest, facts) {
  const artifact = releaseManifestContract(artifactManifest);
  const local = releaseManifestContract(localManifest);
  validateReleaseManifestContract(artifact, facts, "signed AAB manifest");
  validateReleaseManifestContract(local, facts, "local merged release manifest");
  assertEqual(
    JSON.stringify(artifact),
    JSON.stringify(local),
    "signed AAB manifest contract",
  );
  return artifact;
}

export function validateOperatorAssertions(assertions, facts, evidence, now = new Date()) {
  if (assertions.schema !== 3) throw new Error("Unsupported operator-assertions schema.");
  assertEqual(assertions.releaseVersionCode, facts.application.versionCode, "asserted versionCode");
  assertEqual(assertions.releaseVersionName, facts.application.versionName, "asserted versionName");

  for (const name of facts.requiredOperatorAssertions) {
    if (assertions[name] !== true) throw new Error(`Operator assertion is not confirmed: ${name}.`);
  }

  const reviewedAt = /^\d{4}-\d{2}-\d{2}$/.test(assertions.googlePlayPolicyReviewedAt)
    ? new Date(`${assertions.googlePlayPolicyReviewedAt}T00:00:00Z`)
    : null;
  if (!reviewedAt || Number.isNaN(reviewedAt.valueOf())) {
    throw new Error("googlePlayPolicyReviewedAt must be an ISO date.");
  }
  const ageDays = (now.valueOf() - reviewedAt.valueOf()) / 86_400_000;
  if (ageDays < 0 || ageDays > 30) {
    throw new Error("Google Play policy review must be no more than 30 days old and not future-dated.");
  }

  assertEqual(
    assertions.sourceHead,
    evidence.sourceHead,
    "exact source commit",
  );
  assertEqual(
    assertions.artifactManifestSha256,
    evidence.artifactManifestSha256,
    "artifact-manifest SHA-256",
  );
  assertEqual(
    assertions.releaseArtifactSha256,
    evidence.releaseArtifactSha256,
    "release-artifact SHA-256",
  );
  assertEqual(
    assertions.releasePacketSha256,
    evidence.releasePacketSha256,
    "release-packet SHA-256",
  );
}

export function validateReleasePacket(options = {}) {
  const rootDir = options.rootDir ?? process.cwd();
  const facts = readJson(rootDir, "play/release-facts.json");
  if (facts.schema !== 1) throw new Error("Unsupported Play release-facts schema.");

  const build = readText(rootDir, "app/build.gradle.kts");
  const application = facts.application;
  assertEqual(
    requiredMatch(build, /namespace\s*=\s*"([^"]+)"/, "namespace"),
    application.namespace,
    "namespace",
  );
  assertEqual(
    requiredMatch(build, /applicationId\s*=\s*"([^"]+)"/, "applicationId"),
    application.applicationId,
    "applicationId",
  );
  for (const [field, expression] of [
    ["minSdk", /minSdk\s*=\s*(\d+)/],
    ["targetSdk", /targetSdk\s*=\s*(\d+)/],
    ["versionCode", /versionCode\s*=\s*(\d+)/],
  ]) {
    assertEqual(Number(requiredMatch(build, expression, field)), application[field], field);
  }
  assertEqual(
    requiredMatch(build, /versionName\s*=\s*"([^"]+)"/, "versionName"),
    application.versionName,
    "versionName",
  );

  const sourceManifest = readText(rootDir, "app/src/main/AndroidManifest.xml");
  const sourcePermissions = extractManifestPermissions(sourceManifest);
  assertStringSet(
    sourcePermissions.requested,
    facts.appManifestRequestedPermissions.map((permission) => permission.name),
    "app manifest permissions",
  );
  assertStringSet(
    sourcePermissions.removed,
    facts.appManifestRemovedPermissions,
    "app manifest permission removals",
  );

  const healthSource = readText(rootDir, facts.healthConnect.resourceOwner);
  const resources = extractKotlinSetMembers(
    healthSource,
    facts.healthConnect.resourceSetSymbol,
    "VitalResource",
  );
  assertStringSet(resources, facts.healthConnect.readResources, "Health Connect resources");
  assertEqual(
    resources.length,
    facts.healthConnect.readResourceCount,
    "Health Connect resource count",
  );
  const healthDataPermissions = sourcePermissions.requested.filter(
    (permission) =>
      permission.startsWith("android.permission.health.READ_") &&
      permission !== "android.permission.health.READ_HEALTH_DATA_HISTORY",
  );
  assertEqual(
    healthDataPermissions.length,
    facts.healthConnect.readDataPermissionCount,
    "Health Connect data permission count",
  );
  const historyRequested = sourcePermissions.requested.includes(
    "android.permission.health.READ_HEALTH_DATA_HISTORY",
  );
  assertEqual(
    historyRequested,
    facts.healthConnect.historyPermissionRequested,
    "Health Connect history permission",
  );
  assertEqual(
    Number(requiredMatch(healthSource, /backfillDays:\s*Int\s*=\s*(\d+)/, "backfill days")),
    facts.healthConnect.backfillDays,
    "Health Connect backfill days",
  );
  const syncOnAppStart = requiredMatch(
    healthSource,
    /syncOnAppStart\s*=\s*(true|false)/,
    "syncOnAppStart",
  ) === "true";
  assertEqual(syncOnAppStart, facts.healthConnect.syncOnAppStart, "syncOnAppStart");
  const backgroundRequested = sourcePermissions.requested.includes(
    "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
  );
  assertEqual(
    backgroundRequested,
    facts.healthConnect.backgroundPermissionRequested,
    "background Health Connect permission",
  );

  const configSource = readText(
    rootDir,
    "app/src/main/java/ai/withmurph/companion/app/AppConfig.kt",
  );
  const links = Object.fromEntries(
    [...configSource.matchAll(/const val ([A-Za-z]+) = "([^"]+)"/g)]
      .map((match) => [match[1], match[2]]),
  );
  assertEqual(JSON.stringify(links), JSON.stringify(facts.legalLinks), "legal links");

  const listingFiles = [
    ["title", "play/listing/en-US/title.txt", 30],
    ["short description", "play/listing/en-US/short-description.txt", 80],
    ["full description", "play/listing/en-US/full-description.txt", 4_000],
    ["release notes", "play/listing/en-US/release-notes-1.txt", 500],
  ];
  for (const [label, relativePath, limit] of listingFiles) {
    const copy = readText(rootDir, relativePath).trim();
    if (!copy) throw new Error(`${label} is empty.`);
    if ([...copy].length > limit) throw new Error(`${label} exceeds ${limit} characters.`);
    if (/<[^>]+>|\b(?:TODO|TBD)\b/i.test(copy)) {
      throw new Error(`${label} contains a placeholder.`);
    }
  }

  const declarations = [
    "play/declarations/data-safety.md",
    "play/declarations/health-apps.md",
    "play/declarations/contacts.md",
  ].map((relativePath) => readText(rootDir, relativePath)).join("\n");
  const declaredPermissionNames = [
    ...facts.appManifestRequestedPermissions.map((permission) => permission.name),
    ...facts.mergedManifestPermissions,
  ];
  for (const permission of new Set(declaredPermissionNames)) {
    if (!declarations.includes(permission)) {
      throw new Error(`Play declarations do not account for ${permission}.`);
    }
  }
  for (const resource of facts.healthConnect.readResources) {
    if (!declarations.includes(`VitalResource.${resource}`)) {
      throw new Error(`Health Apps declaration does not account for VitalResource.${resource}.`);
    }
  }

  let mergedManifest = null;
  if (options.mergedManifestPath) {
    mergedManifest = fs.readFileSync(options.mergedManifestPath, "utf8");
    validateReleaseManifestContract(
      releaseManifestContract(mergedManifest),
      facts,
      "local merged release manifest",
    );
  }

  const artifactManifest = options.artifactManifest ?? null;
  if (artifactManifest && mergedManifest) {
    validateArtifactManifest(artifactManifest, mergedManifest, facts);
  }

  let releaseArtifact = options.releaseArtifact ?? null;
  if (!releaseArtifact && options.releaseArtifactPath) {
    releaseArtifact = fs.readFileSync(options.releaseArtifactPath);
  }
  if (releaseArtifact) {
    if (releaseArtifact.length === 0) throw new Error("The release artifact is empty.");
  }

  const evidence = {
    sourceHead: options.sourceHead ?? null,
    artifactManifestSha256: artifactManifest ? sha256(artifactManifest) : null,
    releaseArtifactSha256: releaseArtifact ? sha256(releaseArtifact) : null,
    releasePacketSha256: releasePacketSha256(rootDir),
  };

  if (options.submission) {
    if (!evidence.sourceHead) throw new Error("Submission verification requires the exact source commit.");
    if (!mergedManifest) throw new Error("Submission verification requires the merged manifest.");
    if (!artifactManifest) throw new Error("Submission verification requires the signed AAB manifest.");
    if (!releaseArtifact) throw new Error("Submission verification requires the exact release artifact.");
    if (!options.assertions) throw new Error("Submission verification requires operator assertions.");
    validateOperatorAssertions(options.assertions, facts, evidence, options.now);
  }

  return { evidence, facts, artifactManifest, mergedManifest };
}

function parseArguments(argv) {
  const result = { submission: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--submission") {
      result.submission = true;
      continue;
    }
    if (argument === "--print-evidence-hashes") {
      result.print_evidence_hashes = true;
      continue;
    }
    if (["--merged-manifest", "--release-artifact", "--assertions"].includes(argument)) {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) throw new Error(`${argument} requires a path.`);
      result[argument.slice(2).replace("-", "_")] = value;
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${argument}`);
  }
  return result;
}

function main() {
  const arguments_ = parseArguments(process.argv.slice(2));
  const assertionsPath = arguments_.assertions ?? (
    arguments_.submission ? process.env.MURPH_PLAY_OPERATOR_ASSERTIONS_FILE : undefined
  );
  const releaseArtifactPath = arguments_.release_artifact ?? (
    arguments_.submission || arguments_.print_evidence_hashes
      ? process.env.MURPH_PLAY_RELEASE_ARTIFACT
      : undefined
  );
  let assertions = null;
  if (assertionsPath) {
    try {
      assertions = JSON.parse(fs.readFileSync(assertionsPath, "utf8"));
    } catch {
      throw new Error("The operator assertions file is unavailable or invalid.");
    }
  }
  let sourceHead = null;
  let artifactManifest = null;
  let releaseArtifact = null;
  if (arguments_.submission || arguments_.print_evidence_hashes) {
    if (!releaseArtifactPath) {
      throw new Error("Submission verification requires the exact release artifact.");
    }
    sourceHead = exactSourceHead(process.cwd());
    if (path.extname(releaseArtifactPath).toLowerCase() !== ".aab") {
      throw new Error("Play submission requires the exact signed Android App Bundle (.aab).");
    }
    releaseArtifact = fs.readFileSync(releaseArtifactPath);
    if (releaseArtifact.length === 0) throw new Error("The release artifact is empty.");
    const snapshotDirectory = fs.mkdtempSync(
      path.join(os.tmpdir(), "murph-play-artifact-"),
    );
    const snapshotPath = path.join(snapshotDirectory, "candidate.aab");
    try {
      fs.writeFileSync(snapshotPath, releaseArtifact, { mode: 0o600 });
      artifactManifest = requireSignedAndroidBundle(
        snapshotPath,
        sourceHead,
        process.env.MURPH_PLAY_EXPECTED_CONFIGURATION_SHA256 ?? "",
        process.env.MURPH_BUNDLETOOL_CLASSPATH ?? "",
        process.env.MURPH_PLAY_EXPECTED_UPLOAD_CERT_SHA256 ?? "",
      );
    } finally {
      fs.rmSync(snapshotDirectory, { force: true, recursive: true });
    }
  }
  const result = validateReleasePacket({
    artifactManifest,
    mergedManifestPath: arguments_.merged_manifest,
    releaseArtifact: releaseArtifact ?? undefined,
    releaseArtifactPath: releaseArtifact ? undefined : releaseArtifactPath,
    submission: arguments_.submission,
    assertions,
    sourceHead,
  });
  if (arguments_.print_evidence_hashes) {
    if (!result.evidence.artifactManifestSha256 || !result.evidence.releaseArtifactSha256) {
      throw new Error(
        "Evidence output requires the signed AAB manifest and exact release artifact.",
      );
    }
    process.stdout.write(`${JSON.stringify(result.evidence, null, 2)}\n`);
    return;
  }
  process.stdout.write(
    arguments_.submission
      ? "Google Play submission packet verified for the exact artifact and Console packet.\n"
      : "Google Play release packet verified.\n",
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`Google Play release verification failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
