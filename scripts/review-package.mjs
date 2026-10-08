#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildReviewContext, reviewContextDigest, serializeReviewContext, sha256 } from "./review-gpt-contract.mjs";

export const controlPaths = [
  "AGENTS.md", ".github/workflows/android-visual-proof.yml", "scripts/check-android-visual-proof.mjs", "scripts/check-android-visual-proof.test.mjs",
  ".github/workflows/android-ci.yml", ".github/workflows/review-tooling.yml",
  "docs/review-workflow.md", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml",
  "scripts/chatgpt-review-presets/android-deep-review.md", "scripts/package-audit-context.sh",
  "scripts/review-package.mjs", "scripts/review-package.test.mjs",
  "scripts/review-gpt-contract.mjs", "scripts/review-gpt-contract.test.mjs",
  "scripts/review-gpt.config.sh", "scripts/review-pr.sh",
  "scripts/validate-review-gpt-response.sh", "scripts/verify.sh", "scripts/verify-review-workflow.sh",
];
const prefix = "review-gpt-pr-context/";
const textPath = /(?:\.(?:kt|kts|java|xml|json|jsonc|md|txt|yml|yaml|toml|sh|bash|mjs|mts|js|ts|properties|pro)|(?:^|\/)(?:gradlew|LICENSE|Dockerfile|Makefile|\.gitignore|\.editorconfig))$/iu;
const privatePath = /(?:^|\/)(?:\.env[^/]*|\.npmrc|\.netrc|\.pypirc|\.dockercfg|\.aws|\.ssh|\.gnupg|local\.properties|gradle\.properties|keystore\.properties|operator-assertions\.json|(?:credentials|secrets|service-account)\.(?:json|ya?ml))(?:\/|$)|\.(?:jks|keystore|p12|pfx|pem|key|p8|mobileprovision)$/iu;
const generatedPath = /(?:^|\/)(?:\.git|\.gradle|\.kotlin|\.idea|\.artifacts|\.cxx|build|node_modules|output-packages|audit-packages|captures|externalNativeBuild|review-gpt-pr-context)(?:\/|$)/iu;
const fail = (message) => { throw new Error(message); };
function run(root, command, args, input) {
  const env = { ...process.env, GIT_NO_REPLACE_OBJECTS: "1", LC_ALL: "C" };
  for (const key of ["ZIPOPT", "UNZIPOPT", "ZIPINFOOPT", "GIT_EXTERNAL_DIFF"]) delete env[key];
  return execFileSync(command, args, { cwd: root, env, input, maxBuffer: 32 * 1024 * 1024, stdio: ["pipe", "pipe", "pipe"] });
}
const git = (root, ...args) => run(root, "git", args);
function plainPath(path) {
  if (!path || path.startsWith("/") || /[\x00-\x1f\x7f\\]/u.test(path) || path.split("/").some(p => p === ".." || p === ".") || path.startsWith("-")) fail("Unsafe repository path");
}
export function includePath(path) {
  plainPath(path);
  return textPath.test(path) && !privatePath.test(path) && !generatedPath.test(path);
}
export function checkText(bytes) {
  if (bytes.includes(0)) fail("Non-text content in review snapshot");
  const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  const home = new RegExp("/(?:" + "Users|home" + ")/[^<\\s/]+", "u");
  const credentials = /BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-(?:live|proj)-[A-Za-z0-9_-]{16,}|xox[baprs]-[A-Za-z0-9-]{16,}/u;
  if (home.test(text) || credentials.test(text)) fail("Private identifier or credential marker in review snapshot");
  return bytes;
}
export function buildSnapshot(root, metadata, repository, promptBytes, version) {
  const context = buildReviewContext({ metadata, repository, promptBytes, reviewToolVersion: version });
  if (git(root, "status", "--porcelain", "--untracked-files=all").length) fail("Review packaging requires a clean worktree");
  if (git(root, "rev-parse", "HEAD").toString().trim() !== context.head.sha) fail("Local and pushed review heads differ");
  const comparison = `${context.base.sha}...${context.head.sha}`;
  const changed = git(root, "diff", "--name-only", "--no-renames", "-z", comparison).toString().split("\0").filter(Boolean);
  if (!changed.length) fail("Empty review diff");
  if (changed.some(p => controlPaths.includes(p))) fail("Review control changes require independent local review");
  for (const path of changed) {
    plainPath(path);
    if (privatePath.test(path) || generatedPath.test(path)) fail("Sensitive or generated path changed; cannot package this PR");
  }
  const files = new Map();
  let total = 0;
  const tree = git(root, "ls-tree", "-rz", context.head.sha).toString().split("\0").filter(Boolean);
  for (const entry of tree) {
    const match = /^(\d+) (\w+) ([0-9a-f]{40})\t(.+)$/u.exec(entry);
    if (!match) fail("Invalid source tree entry");
    const [, mode, type, oid, path] = match;
    if (!includePath(path)) continue;
    if (type !== "blob" || !["100644", "100755"].includes(mode)) fail("Only regular committed text files may be packaged");
    const bytes = checkText(git(root, "cat-file", "blob", oid));
    total += bytes.length;
    if (bytes.length > 2 * 1024 * 1024 || total > 24 * 1024 * 1024) fail("Review snapshot exceeds bounded text size");
    files.set(path, bytes);
  }
  const reviewable = changed.filter(includePath);
  // Check both sides so deleted secrets, binary blobs and symlinks cannot leak through the diff.
  const mergeBase = git(root, "merge-base", context.base.sha, context.head.sha).toString().trim();
  for (const revision of [mergeBase, context.head.sha]) for (const path of reviewable) {
    const entry = git(root, "ls-tree", revision, "--", `:(literal)${path}`).toString();
    if (!entry) continue;
    const match = /^(100644|100755) blob ([0-9a-f]{40})\t/u.exec(entry);
    if (!match) fail("Changed review paths must be regular text files");
    checkText(git(root, "cat-file", "blob", match[2]));
  }
  const diff = reviewable.length ? git(root, "diff", "--no-ext-diff", "--no-textconv", "--no-renames", comparison, "--", ...reviewable.map(p => `:(literal)${p}`)) : Buffer.from("");
  files.set(`${prefix}pr.diff`, checkText(diff));
  files.set(`${prefix}pr-description.md`, checkText(Buffer.from(metadata.body)));
  files.set(`${prefix}changed-files.txt`, Buffer.from(changed.join("\n") + "\n"));
  files.set(`${prefix}omitted-paths.json`, Buffer.from(JSON.stringify(tree.map(e => e.slice(e.indexOf("\t") + 1)).filter(p => !includePath(p)), null, 2) + "\n"));
  files.set(`${prefix}review-context.json`, Buffer.from(serializeReviewContext(context)));
  files.set(`${prefix}source-manifest.json`, Buffer.from(JSON.stringify([...files].map(([path, bytes]) => ({ path, sha256: sha256(bytes) })), null, 2) + "\n"));
  for (const bytes of files.values()) checkText(bytes);
  return { context, files };
}
export function verifyArchive(root, archive, snapshot) {
  const names = run(root, "unzip", ["-Z1", archive]).toString().trimEnd().split("\n");
  if (JSON.stringify([...names].sort()) !== JSON.stringify([...snapshot.files.keys()].sort())) fail("ZIP members differ from guarded manifest");
  for (const [path, bytes] of snapshot.files) {
    if (!run(root, "unzip", ["-p", archive, path]).equals(bytes)) fail("ZIP bytes differ from committed review snapshot");
  }
}
export function createArchive(root, archive, snapshot) {
  if (existsSync(archive)) fail("Refusing to overwrite a review archive");
  const stage = mkdtempSync(resolve(tmpdir(), "android-review-zip-"));
  try {
    for (const [path, bytes] of snapshot.files) {
      mkdirSync(dirname(resolve(stage, path)), { recursive: true, mode: 0o700 });
      writeFileSync(resolve(stage, path), bytes, { flag: "wx", mode: 0o600 });
    }
    run(stage, "zip", ["-q", "-X", archive, "-@"], [...snapshot.files.keys()].sort().join("\n") + "\n");
    verifyArchive(root, archive, snapshot);
  } finally { rmSync(stage, { recursive: true, force: true }); }
}
export function validateCapture(response, capture, model) {
  const assistant = capture?.assistantResponse;
  const user = capture?.committedUserTurn;
  if (capture?.schemaVersion !== 2 || capture.conversationUrlPending ||
      !/^https:\/\/chatgpt\.com\/c\/[A-Za-z0-9-]+$/u.test(capture.chatUrl ?? "") ||
      !capture.targetId || !assistant?.assistantTurnId || !user?.turnId ||
      !Number.isInteger(user.turnIndex) || user.turnIndex < 0 ||
      !Number.isInteger(assistant.assistantTurnIndex) || assistant.assistantTurnIndex <= user.turnIndex ||
      assistant.precedingUserTurnId !== user.turnId ||
      assistant.precedingUserTurnIndex !== user.turnIndex ||
      !/^sha256:[0-9a-f]{64}$/u.test(user.signature ?? "") ||
      assistant.precedingUserMessageSignature !== user.signature ||
      assistant.responseSha256 !== sha256(response)) fail("Missing or mismatched completed-turn capture");
  if (model && (model.requestedModel !== "gpt-6-pro" ||
      !["gpt6pro", "pro"].includes((model.responseModelSlug ?? "").toLowerCase().replace(/^chatgpt\s+/u, "").replace(/[^a-z0-9]/gu, "")) ||
      model.responseSha256 !== sha256(response))) fail("Mismatched model capture");
}
function captureFiles(responsePath) {
  const response = readFileSync(responsePath);
  const capture = readFileSync(`${responsePath}.capture.json`);
  const modelPath = `${responsePath}.model-verification.json`;
  const model = existsSync(modelPath) ? readFileSync(modelPath) : null;
  validateCapture(response, JSON.parse(capture), model ? JSON.parse(model) : null);
  return { responseSha256: sha256(response), captureSha256: sha256(capture), modelSha256: model ? sha256(model) : null };
}
function currentSnapshot(root) {
  const ref = process.env.REVIEW_GPT_PR_URL;
  if (!ref) fail("REVIEW_GPT_PR_URL is required");
  const metadata = JSON.parse(run(root, "gh", ["pr", "view", ref, "--json", "baseRefName,baseRefOid,body,headRefName,headRefOid,number,url"]));
  const repository = run(root, "gh", ["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"]).toString().trim();
  const version = JSON.parse(readFileSync(resolve(root, "package.json"))).devDependencies["@cobuild/review-gpt"];
  const installed = JSON.parse(readFileSync(resolve(root, "node_modules/@cobuild/review-gpt/package.json"))).version;
  if (version !== installed) fail("Installed review tool must match pinned version");
  return buildSnapshot(root, metadata, repository, readFileSync(resolve(root, "scripts/chatgpt-review-presets/android-deep-review.md")), version);
}
function main(args) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  if (args[0] === "capture") {
    if (args.length !== 2) fail("capture requires a response path");
    const receiptPath = `${args[1]}.package.json`;
    const receipt = JSON.parse(readFileSync(receiptPath));
    // Called only after the waited concrete-model launcher exits successfully.
    receipt.capture = { ...captureFiles(args[1]), model: "gpt-6-pro", runnerCompleted: true };
    writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n", { mode: 0o600 });
    return;
  }
  const snapshot = currentSnapshot(root);
  const digest = reviewContextDigest(snapshot.context);
  if (args[0] === "verify") {
    if (args.length !== 2) fail("verify requires a package receipt");
    const receipt = JSON.parse(readFileSync(args[1]));
    if (receipt.contextSha256 !== digest || !/^output-packages\/[A-Za-z0-9_.-]+\.zip$/u.test(receipt.archive)) fail("Package receipt does not match current review");
    const archive = resolve(root, receipt.archive);
    if (sha256(readFileSync(archive)) !== receipt.archiveSha256) fail("ZIP hash changed after capture");
    verifyArchive(root, archive, snapshot);
    const responsePath = args[1].replace(/\.package\.json$/u, "");
    const observed = captureFiles(responsePath);
    if (receipt.capture?.runnerCompleted !== true || receipt.capture.model !== "gpt-6-pro" ||
        Object.entries(observed).some(([key, value]) => receipt.capture[key] !== value)) fail("Capture receipt changed or review did not complete");
    return;
  }
  if (!args.includes("--zip") || args.some((v, i) => !["--zip", "--with-tests", "--name"].includes(v) && args[i - 1] !== "--name")) fail("Usage: package-audit-context.sh --zip [--with-tests] [--name name]");
  if (process.env.REVIEW_GPT_EXPECTED_CONTEXT_DIGEST && process.env.REVIEW_GPT_EXPECTED_CONTEXT_DIGEST !== digest) fail("PR context changed before packaging");
  const output = resolve(root, "output-packages");
  mkdirSync(output, { recursive: true, mode: 0o700 });
  if (lstatSync(output).isSymbolicLink()) fail("Review output directory must not be a symlink");
  const archive = `output-packages/android-review-${snapshot.context.head.sha.slice(0, 12)}-${Date.now()}-${process.pid}.zip`;
  createArchive(root, resolve(root, archive), snapshot);
  const receipt = { contextSha256: digest, head: snapshot.context.head.sha, archive, archiveSha256: sha256(readFileSync(resolve(root, archive))) };
  const receiptPath = process.env.REVIEW_GPT_PACKAGE_RECEIPT || resolve(root, `${archive}.json`);
  mkdirSync(dirname(receiptPath), { recursive: true, mode: 0o700 });
  writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n", { mode: 0o600 });
  // The launcher moves and deletes its upload copy. Keep the reviewed bytes for replay validation.
  const upload = archive.replace(/\.zip$/u, ".upload.zip");
  copyFileSync(resolve(root, archive), resolve(root, upload), 1);
  console.log(`Included files: ${snapshot.files.size}\nZIP: ${upload} (${readFileSync(resolve(root, archive)).length} bytes)\nSHA-256: ${receipt.archiveSha256}`);
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(`Error: ${error instanceof Error ? error.message : String(error)}`); process.exitCode = 1; }
}
