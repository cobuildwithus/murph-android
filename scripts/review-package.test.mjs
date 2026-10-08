import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { createRequire } from "node:module";
import test from "node:test";
import { buildSnapshot, createArchive, verifyArchive, includePath, checkText, validateCapture } from "./review-package.mjs";

function fixture(t) {
  const root = mkdtempSync(resolve(tmpdir(), "review-package-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args) => execFileSync("git", args, { cwd: root, stdio: ["ignore", "pipe", "pipe"] }).toString().trim();
  git("init", "-q"); git("config", "user.name", "Fixture"); git("config", "user.email", "fixture@users.noreply.github.com");
  const write = (path, bytes) => { mkdirSync(resolve(root, path, ".."), { recursive: true }); writeFileSync(resolve(root, path), bytes); };
  const commit = () => { git("add", "-A"); git("commit", "-qm", "Fixture"); return git("rev-parse", "HEAD"); };
  write(".gitignore", "output-packages/\nlocal.properties\n");
  write("app/Main.kt", "fun main() = Unit\n");
  write("gradle.properties", "private local configuration\n");
  write(".env.test", "private configuration\n");
  write("app-store-assets/proof.png", Buffer.from([0, 1, 2]));
  const base = commit();
  write("app/Main.kt", "fun main() { println(42) }\n");
  const head = commit();
  const metadata = { baseRefName: "main", baseRefOid: base, headRefName: "topic", headRefOid: head, number: 1, url: "https://github.com/example/android/pull/1", body: "Synthetic PR" };
  const snapshot = () => buildSnapshot(root, metadata, "example/android", Buffer.from("fixed prompt"), "0.5.153");
  return { root, git, write, commit, metadata, snapshot };
}

test("ZIP includes exact committed text, metadata/diff and hashed manifest, excluding private/binary content", t => {
  const f = fixture(t); f.write("local.properties", "ignored local data");
  const s = f.snapshot();
  assert.equal(s.files.get("app/Main.kt").toString(), "fun main() { println(42) }\n");
  for (const excluded of ["gradle.properties", ".env.test", "app-store-assets/proof.png", "local.properties"]) assert.equal(s.files.has(excluded), false);
  assert.match(s.files.get("review-gpt-pr-context/pr.diff").toString(), /println\(42\)/u);
  const manifest = JSON.parse(s.files.get("review-gpt-pr-context/source-manifest.json"));
  assert.ok(manifest.some(entry => entry.path === "app/Main.kt" && /^[0-9a-f]{64}$/u.test(entry.sha256)));
  const archive = resolve(f.root, "output-packages/test.zip"); mkdirSync(resolve(f.root, "output-packages"));
  createArchive(f.root, archive, s); verifyArchive(f.root, archive, s);
  assert.ok(readFileSync(archive).length > 0);
  f.write("extra.txt", "not in manifest");
  execFileSync("zip", ["-q", archive, "extra.txt"], { cwd: f.root });
  assert.throws(() => verifyArchive(f.root, archive, s), /members differ/u);
});

test("refuses dirty and moved heads", t => {
  const f = fixture(t); f.write("app/Main.kt", "dirty\n"); assert.throws(f.snapshot, /clean worktree/u);
  f.commit(); assert.throws(f.snapshot, /heads differ/u);
});

test("refuses control-plane self-certification", t => {
  const f = fixture(t); f.write("scripts/review-pr.sh", "echo changed\n"); f.metadata.headRefOid = f.commit();
  assert.throws(f.snapshot, /independent local review/u);
});

test("refuses private changed paths and deleted secret content", t => {
  const f = fixture(t); f.write("gradle.properties", "changed\n"); f.metadata.headRefOid = f.commit();
  assert.throws(f.snapshot, /Sensitive or generated/u);
  const g = fixture(t); g.write("app/Removed.kt", 'val secret = "' + 'github_' + 'pat_' + 'a'.repeat(30) + '"\n');
  g.metadata.baseRefOid = g.commit(); rmSync(resolve(g.root, "app/Removed.kt")); g.metadata.headRefOid = g.commit();
  assert.throws(g.snapshot, /credential marker/u);
});

test("refuses symlinks, binary disguised as source, and archive byte substitutions", t => {
  const f = fixture(t); symlinkSync("Main.kt", resolve(f.root, "app/Link.kt")); f.metadata.headRefOid = f.commit();
  assert.throws(f.snapshot, /regular committed text/u);
  const g = fixture(t); g.write("app/Other.kt", Buffer.from([0, 255])); g.metadata.headRefOid = g.commit();
  assert.throws(g.snapshot, /Non-text/u);
  const h = fixture(t); const snapshot = h.snapshot(); mkdirSync(resolve(h.root, "output-packages")); const archive = resolve(h.root, "output-packages/test.zip");
  createArchive(h.root, archive, snapshot); h.write("app/Main.kt", "tampered"); execFileSync("zip", ["-q", archive, "app/Main.kt"], { cwd: h.root });
  assert.throws(() => verifyArchive(h.root, archive, snapshot), /bytes differ/u);
});

test("path and text guards reject unsafe names, private/build paths and credential markers", () => {
  for (const path of ["../escape.kt", "/absolute.kt", "line\nbreak.kt", "-option.kt"]) assert.throws(() => includePath(path), /Unsafe/u);
  for (const path of ["x/local.properties", "x/gradle.properties", "sign.jks", "app/build/a.kt", ".gradle/foo.json", "keys/service-account.json", "proof.png"]) assert.equal(includePath(path), false);
  for (const text of ['/' + 'Users/' + 'fixture/private', 'BEGIN ' + 'PRIVATE KEY', 'gh' + 'p_' + 'x'.repeat(30)]) assert.throws(() => checkText(Buffer.from(text)), /Private/u);
  assert.throws(() => checkText(Buffer.from([255])), /encoded/u);
});


test("capture validation requires a complete matching turn, response bytes and concrete model when present", async () => {
  const { sha256 } = await import("./review-gpt-contract.mjs");
  const { buildThreadCaptureIdentity } = createRequire(import.meta.url)("../node_modules/@cobuild/review-gpt/src/prepare-chatgpt-draft.js");
  const response = Buffer.from("complete response\n");
  const capture = buildThreadCaptureIdentity({
    browserEndpoint: "http://127.0.0.1:9444", chatUrl: "https://chatgpt.com/c/synthetic", targetId: "target",
    committedUserTurn: { turnId: "user", turnIndex: 0, signature: "submitted prompt" },
    assistantSnapshot: { assistantTurnId: "assistant", assistantTurnIndex: 1, precedingUserTurnId: "user", precedingUserTurnIndex: 0,
      precedingUserMessageSignature: "submitted prompt", text: response.toString(), signature: "completed response" },
  });
  const model = { requestedModel: "gpt-6-pro", responseModelSlug: "gpt-6-pro", responseSha256: sha256(response) };
  validateCapture(response, capture, model);
  validateCapture(response, capture, null); // Successful launcher enforces the timed fallback when attribution is absent.
  assert.throws(() => validateCapture(response, null, model), /completed-turn/u);
  assert.throws(() => validateCapture(response, { ...capture, assistantResponse: null }, model), /completed-turn/u);
  assert.throws(() => validateCapture(Buffer.from("other"), capture, model), /completed-turn/u);
  assert.throws(() => validateCapture(response, { ...capture, committedUserTurn: { ...capture.committedUserTurn, turnId: "other" } }, model), /completed-turn/u);
  assert.throws(() => validateCapture(response, capture, { ...model, responseModelSlug: "gpt-6" }), /model capture/u);
});

test("real pinned launcher dry-run parses the package, retains archive after aliasing and creates nested receipts", t => {
  const f = fixture(t);
  const sourceRoot = resolve(import.meta.dirname, "..");
  for (const file of ["scripts/review-package.mjs", "scripts/review-gpt-contract.mjs", "scripts/package-audit-context.sh", "scripts/chatgpt-review-presets/android-deep-review.md"]) f.write(file, readFileSync(resolve(sourceRoot, file)));
  f.write(".gitignore", "output-packages/\nnode_modules/\n");
  f.write("package.json", JSON.stringify({ devDependencies: { "@cobuild/review-gpt": "0.5.153" } }));
  f.write("fixture.config.sh", 'name_prefix="fixture"\npackage_script="scripts/package-audit-context.sh"\nattach_artifacts=1\ninclude_tests=1\ninclude_docs=1\napp_connector="current"\nmodel="gpt-6-pro"\npreset_dir="scripts/chatgpt-review-presets"\nreview_gpt_register_dir_preset "android-pr-review" "android-deep-review.md" "Review"\n');
  f.metadata.baseRefOid = f.commit();
  f.write("app/Main.kt", "fun main() = println(43)\n"); f.metadata.headRefOid = f.commit();
  mkdirSync(resolve(f.root, "node_modules/@cobuild"), { recursive: true });
  symlinkSync(resolve(sourceRoot, "node_modules/@cobuild/review-gpt"), resolve(f.root, "node_modules/@cobuild/review-gpt"));
  const stub = mkdtempSync(resolve(tmpdir(), "review-gh-fixture-")); t.after(() => rmSync(stub, { recursive: true, force: true }));
  writeFileSync(resolve(stub, "metadata.json"), JSON.stringify(f.metadata));
  writeFileSync(resolve(stub, "gh"), '#!/bin/sh\nif [ "$1" = "pr" ]; then cat "$REVIEW_TEST_META"; else echo example/android; fi\n', { mode: 0o700 });
  const receipt = resolve(f.root, "output-packages/nested/review.md.package.json");
  const output = execFileSync(resolve(sourceRoot, "node_modules/.bin/cobuild-review-gpt"), ["--config", "fixture.config.sh", "android-pr-review", "--dry-run"], {
    cwd: f.root, encoding: "utf8", env: { ...process.env, PATH: `${stub}:${process.env.PATH}`, REVIEW_TEST_META: resolve(stub, "metadata.json"), REVIEW_GPT_PR_URL: "1", REVIEW_GPT_PACKAGE_RECEIPT: receipt },
    stdio: ["ignore", "pipe", "pipe"],
  });
  assert.match(output, /ZIP:/u);
  const captured = JSON.parse(readFileSync(receipt));
  const archive = resolve(f.root, captured.archive);
  assert.ok(readFileSync(archive).length > 0);
  const expected = buildSnapshot(f.root, f.metadata, "example/android", readFileSync(resolve(f.root, "scripts/chatgpt-review-presets/android-deep-review.md")), "0.5.153");
  verifyArchive(f.root, archive, expected);
  rmSync(resolve(f.root, "output-packages/codebase.zip")); // Same cleanup as the real send path.
  verifyArchive(f.root, archive, expected);
});

test("source directories named home survive final manifest privacy scanning", t => {
  const f = fixture(t);
  const path = "app/src/main/java/example/ui/home/HomeScreen.kt";
  f.write(path, "fun home() = Unit\n");
  f.metadata.headRefOid = f.commit();
  const snapshot = f.snapshot();
  assert.ok(snapshot.files.has(path));
  checkText(snapshot.files.get("review-gpt-pr-context/source-manifest.json"));
  for (const prefix of ["", "file://", 'path="', "path = ", "("]) {
    assert.throws(() => checkText(Buffer.from(prefix + "/" + "home/" + "fixture/private")), /Private/u);
    assert.throws(() => checkText(Buffer.from(prefix + "/" + "Users/" + "fixture/private")), /Private/u);
  }
});
