import assert from "node:assert/strict";
import test from "node:test";
import { deflateSync } from "node:zlib";

import {
  changedUiPathsAtRevisions,
  changedProtectedPaths,
  changedScreenshotPaths,
  changedUiPaths,
  comparisonRecords,
  isComposePath,
  isExplicitVisibleOwnerPath,
  isVisibleResourcePath,
  parseNameStatus,
  readGitBlobEntry,
  readPngDimensions,
  validateRenderedProof,
  validateLivePullRequest,
  validateScreenshotBlobs,
  validateScreenshotReuse,
} from "./check-android-visual-proof.mjs";

function validatePng(path, bytes, mode = "100644") {
  return validateScreenshotBlobs([{ bytes, mode, path }]);
}

function crc32(bytes) {
  let crc = 0xFFFFFFFF;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function pngChunk(type, data) {
  const typeBytes = Buffer.from(type, "ascii");
  const chunk = Buffer.alloc(12 + data.length);
  chunk.writeUInt32BE(data.length, 0);
  typeBytes.copy(chunk, 4);
  data.copy(chunk, 8);
  chunk.writeUInt32BE(crc32(Buffer.concat([typeBytes, data])), 8 + data.length);
  return chunk;
}

function makePng({ ancillaryChunks = [], colorType, pixelBytes }) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(1, 0);
  header.writeUInt32BE(1, 4);
  header[8] = 8;
  header[9] = colorType;
  return Buffer.concat([
    Buffer.from("89504e470d0a1a0a", "hex"),
    pngChunk("IHDR", header),
    ...ancillaryChunks,
    pngChunk("IDAT", deflateSync(Buffer.concat([Buffer.from([0]), pixelBytes]))),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

test("detects changed Compose sources and visible Android resources", () => {
  const records = parseNameStatus(
    "M\0app/src/main/java/example/Root.kt\0"
    + "M\0app/src/main/java/example/Plain.kt\0"
    + "D\0app/src/main/java/example/Removed.kt\0"
    + "R100\0app/src/main/java/example/Old.kt\0scripts/Old.kt\0"
    + "M\0app/src/debug/java/example/ScreenshotActivity.kt\0"
    + "M\0app/src/main/res/drawable-night/logo.xml\0"
    + "A\0app/src/release/res/values/strings.xml\0"
    + "M\0app/src/main/java/ai/withmurph/companion/app/AppSession.kt\0"
    + "M\0app/src/main/java/ai/withmurph/companion/app/AppUiState.kt\0"
    + "M\0app/src/main/java/ai/withmurph/companion/auth/LoginCoordinator.kt\0"
    + "M\0app/src/main/java/ai/withmurph/companion/auth/CountryDialCode.kt\0"
    + "M\0app/src/main/AndroidManifest.xml\0"
    + "M\0app/build.gradle.kts\0"
    + "M\0app/src/main/res/xml/data_extraction_rules.xml\0",
  );

  assert.deepEqual(changedUiPaths(records, (revision, path) => {
    if (path.endsWith("Root.kt")) {
      return "import androidx.activity.compose.setContent\n";
    }
    if (path.endsWith("Old.kt")) {
      return "import androidx.navigation.compose.NavHost\n";
    }
    if (revision === "base" && path.endsWith("Removed.kt")) {
      return "import androidx.compose.runtime.Composable\n";
    }
    return "import java.time.Instant\n";
  }), [
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/ai/withmurph/companion/app/AppSession.kt",
    "app/src/main/java/ai/withmurph/companion/app/AppUiState.kt",
    "app/src/main/java/ai/withmurph/companion/auth/CountryDialCode.kt",
    "app/src/main/java/ai/withmurph/companion/auth/LoginCoordinator.kt",
    "app/src/main/java/example/Removed.kt",
    "app/src/main/java/example/Root.kt",
    "app/src/main/res/drawable-night/logo.xml",
    "app/src/release/res/values/strings.xml",
    "scripts/Old.kt",
  ]);
});

test("recognizes non-Compose owners of shipped Android copy and presentation", () => {
  assert.equal(
    isExplicitVisibleOwnerPath(
      "app/src/main/java/ai/withmurph/companion/app/AppSession.kt",
    ),
    true,
  );
  assert.equal(
    isExplicitVisibleOwnerPath(
      "app/src/release/java/ai/withmurph/companion/ui/ReleaseScreen.kt",
    ),
    true,
  );
  assert.equal(
    isExplicitVisibleOwnerPath(
      "app/src/main/java/ai/withmurph/companion/auth/LoginCoordinator.kt",
    ),
    true,
  );
  assert.equal(
    isExplicitVisibleOwnerPath(
      "app/src/main/java/ai/withmurph/companion/auth/CountryDialCode.kt",
    ),
    true,
  );
  assert.equal(isExplicitVisibleOwnerPath("app/src/main/AndroidManifest.xml"), true);
  assert.equal(isExplicitVisibleOwnerPath("app/build.gradle.kts"), true);
  assert.equal(
    isExplicitVisibleOwnerPath("app/src/main/java/ai/withmurph/companion/api/Client.kt"),
    false,
  );
});

test("recognizes AndroidX Compose namespaces only in shipped Kotlin", () => {
  assert.equal(
    isComposePath(
      "app/src/main/java/example/Root.kt",
      "import androidx.lifecycle.compose.collectAsStateWithLifecycle\n",
    ),
    true,
  );
  assert.equal(
    isComposePath(
      "app/src/debug/java/example/ScreenshotActivity.kt",
      "import androidx.activity.compose.setContent\n",
    ),
    false,
  );
  assert.equal(
    isComposePath(
      "app/src/release/java/example/ReleaseOnly.kt",
      "import androidx.compose.material3.Text\n",
    ),
    true,
  );
  assert.equal(
    isComposePath(
      "app/src/main/java/example/Root.kt",
      "import androidx.lifecycle.ViewModel\n",
    ),
    false,
  );
  assert.equal(
    isComposePath(
      "app/src/main/java/example/FullyQualified.kt",
      "fun render() = androidx.compose.material3.Text(\"Ready\")\n",
    ),
    true,
  );
  assert.equal(
    isVisibleResourcePath("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"),
    true,
  );
  assert.equal(
    isVisibleResourcePath("app/src/main/res/interpolator-night/emphasized.xml"),
    true,
  );
  assert.equal(
    isVisibleResourcePath("app/src/release/res/values/strings.xml"),
    true,
  );
  assert.equal(
    isVisibleResourcePath("app/src/debug/res/values/strings.xml"),
    false,
  );
  assert.equal(
    isVisibleResourcePath("app/src/main/res/xml/data_extraction_rules.xml"),
    false,
  );
});

test("identifies visual-proof control-plane changes", () => {
  const records = parseNameStatus(
    "M\0.github/workflows/android-visual-proof.yml\0"
    + "M\0.github/workflows/android-ci.yml\0"
    + "M\0AGENTS.md\0"
    + "M\0docs/review-workflow.md\0"
    + "M\0package.json\0"
    + "M\0pnpm-lock.yaml\0"
    + "M\0scripts/chatgpt-review-presets/android-deep-review.md\0"
    + "M\0scripts/package-review-context.sh\0"
    + "M\0scripts/repo-tools.config.sh\0"
    + "M\0scripts/review-gpt-contract.mjs\0"
    + "M\0scripts/review-gpt-contract.test.mjs\0"
    + "M\0scripts/review-gpt.config.sh\0"
    + "M\0scripts/review-pr.sh\0"
    + "M\0scripts/validate-review-gpt-response.sh\0"
    + "M\0scripts/verify-review-workflow.sh\0"
    + "M\0scripts/verify.sh\0"
    + "R100\0scripts/check-android-visual-proof.mjs\0scripts/retired.mjs\0"
    + "M\0README.md\0",
  );
  assert.deepEqual(changedProtectedPaths(records), [
    ".github/workflows/android-ci.yml",
    ".github/workflows/android-visual-proof.yml",
    "AGENTS.md",
    "docs/review-workflow.md",
    "package.json",
    "pnpm-lock.yaml",
    "scripts/chatgpt-review-presets/android-deep-review.md",
    "scripts/check-android-visual-proof.mjs",
    "scripts/package-review-context.sh",
    "scripts/repo-tools.config.sh",
    "scripts/review-gpt-contract.mjs",
    "scripts/review-gpt-contract.test.mjs",
    "scripts/review-gpt.config.sh",
    "scripts/review-pr.sh",
    "scripts/validate-review-gpt-response.sh",
    "scripts/verify-review-workflow.sh",
    "scripts/verify.sh",
  ]);
});

test("rejects a rerun whose live pull request changed after the archived event", () => {
  const base = "a".repeat(40);
  const head = "b".repeat(40);
  const repository = "example/murph-android";
  const archivedEvent = {
    pull_request: {
      base: { repo: { full_name: repository }, sha: base },
      body: "current proof",
      head: { repo: { full_name: repository }, sha: head },
      number: 7,
    },
  };

  assert.deepEqual(validateLivePullRequest({
    archivedEvent,
    base,
    baseRepository: repository,
    head,
    headRepository: repository,
    livePullRequest: {
      base: { repo: { full_name: repository }, sha: base },
      body: "proof removed after the run",
      head: { repo: { full_name: repository }, sha: head },
    },
  }), ["The live pull request body changed after this workflow event was created."]);
});

test("accepts a live pull request bound to the archived candidate", () => {
  const base = "c".repeat(40);
  const head = "d".repeat(40);
  const repository = "example/murph-android";
  const pullRequest = {
    base: { repo: { full_name: repository }, sha: base },
    body: "exact proof",
    head: { repo: { full_name: repository }, sha: head },
    number: 8,
  };
  assert.deepEqual(validateLivePullRequest({
    archivedEvent: { pull_request: pullRequest },
    base,
    baseRepository: repository,
    head,
    headRepository: repository,
    livePullRequest: pullRequest,
  }), []);
});

test("accepts a fork head while binding the canonical base repository", () => {
  const base = "e".repeat(40);
  const head = "f".repeat(40);
  const baseRepository = "example/murph-android";
  const headRepository = "contributor/murph-android";
  const pullRequest = {
    base: { repo: { full_name: baseRepository }, sha: base },
    body: "fork proof",
    head: { repo: { full_name: headRepository }, sha: head },
    number: 9,
  };

  assert.deepEqual(validateLivePullRequest({
    archivedEvent: { pull_request: pullRequest },
    base,
    baseRepository,
    head,
    headRepository,
    livePullRequest: pullRequest,
  }), []);
  const path = "app-store-assets/review-evidence/fork/ready.png";
  assert.deepEqual(validateRenderedProof({
    head,
    renderedHtml: [
      "<h2>Android visual proof</h2>",
      "<ul>",
      `<li>Evidence head: <code>${head}</code></li>`,
      "<li>States covered: fork state</li>",
      `<li>Screenshots:<img src=\"https://raw.githubusercontent.com/${headRepository}/${head}/${path}\"></li>`,
      "<li>Physical-device gaps: None</li>",
      "</ul>",
    ].join(""),
    repository: headRepository,
    screenshotPaths: [path],
  }), []);
});

test("requires changed screenshots under the durable evidence directory", () => {
  const records = parseNameStatus(
    "A\0app-store-assets/review-evidence/onboarding/01-ready.png\0"
    + "D\0app-store-assets/review-evidence/onboarding/old.png\0"
    + "A\0screenshots/temporary.png\0",
  );
  assert.deepEqual(changedScreenshotPaths(records), [
    "app-store-assets/review-evidence/onboarding/01-ready.png",
  ]);
});

test("rejects Git copies, renames, and unchanged rendered pixels", () => {
  const original = "app-store-assets/review-evidence/old/ready.png";
  const added = "app-store-assets/review-evidence/new/copied.png";
  const modified = "app-store-assets/review-evidence/new/modified.png";
  const renamed = "app-store-assets/review-evidence/new/renamed.png";
  const records = parseNameStatus(
    `A\0${added}\0M\0${modified}\0R100\0${original}\0${renamed}\0`,
  );
  assert.deepEqual(validateScreenshotReuse({
    basePixelDigests: new Map([
      [original, "same-pixels"],
      [modified, "metadata-only"],
    ]),
    headPixelDigests: new Map([
      [added, "same-pixels"],
      [modified, "metadata-only"],
      [renamed, "fresh-pixels"],
    ]),
    records,
  }), [
    `${added} duplicates pixels already present at the comparison base.`,
    `${modified} changed without fresh screenshot pixels.`,
    `${renamed} must be a fresh capture, not a copied or renamed file.`,
  ]);
});

test("accepts a candidate raster distinct from comparison-base evidence", () => {
  const path = "app-store-assets/review-evidence/new/ready.png";
  assert.deepEqual(validateScreenshotReuse({
    basePixelDigests: new Map([["old.png", "old-pixels"]]),
    headPixelDigests: new Map([[path, "new-pixels"]]),
    records: parseNameStatus(`A\0${path}\0`),
  }), []);
});

test("accepts exact-head rendered proof with every changed image", () => {
  const head = "a".repeat(40);
  const repository = "example/murph-android";
  const path = "app-store-assets/review-evidence/onboarding/01-ready.png";
  const url = `https://raw.githubusercontent.com/${repository}/${head}/${path}`;
  const renderedHtml = [
    "<h2>Android visual proof</h2>",
    "<ul>",
    `<li>Evidence head: <code>${head}</code></li>`,
    "<li>States covered: reconnect and saved status</li>",
    `<li>Screenshots:<img src="https://camo.invalid/proxy" data-canonical-src="${url}"></li>`,
    "<li>Physical-device gaps: Health Connect authorization</li>",
    "</ul>",
    "<h2>Verification</h2>",
  ].join("");

  assert.deepEqual(validateRenderedProof({
    head,
    renderedHtml,
    repository,
    screenshotPaths: [path],
  }), []);
});

test("rejects stale heads and screenshots not embedded from the exact head", () => {
  const head = "b".repeat(40);
  const path = "app-store-assets/review-evidence/onboarding/01-ready.png";
  const errors = validateRenderedProof({
    head,
    renderedHtml: [
      "<h2>Android visual proof</h2>",
      "<ul>",
      `<li>Evidence head: <code>${"a".repeat(40)}</code></li>`,
      "<li>States covered: ready</li>",
      "<li>Physical-device gaps: None</li>",
      "</ul>",
    ].join(""),
    repository: "example/murph-android",
    screenshotPaths: [path],
  });

  assert.equal(errors.length, 2);
  assert.match(errors[0], /exact PR head/);
  assert.match(errors[1], /exact-head raw GitHub URL/);
});

test("rejects truncated fake PNG evidence", () => {
  const path = "truncated.png";
  const bytes = Buffer.alloc(24);
  Buffer.from("89504e470d0a1a0a", "hex").copy(bytes, 0);
  Buffer.from("IHDR").copy(bytes, 12);
  bytes.writeUInt32BE(1080, 16);
  bytes.writeUInt32BE(2400, 20);

  assert.deepEqual(validatePng(path, bytes), [
    `${path} is not a valid PNG.`,
  ]);
});

test("rejects invalid PNG chunk checksums", () => {
  const path = "bad-crc.png";
  const bytes = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    "base64",
  );
  bytes[bytes.length - 1] ^= 1;

  assert.deepEqual(validatePng(path, bytes), [
    `${path} has an invalid PNG chunk checksum.`,
  ]);
});

test("rejects framed PNGs whose pixels do not decode", () => {
  const path = "empty-idat.png";
  const bytes = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAUAAAAI4CAYAAAAbCTCbAAAAAElEQVQ1rwYeAAAAAElFTkSuQmCC",
    "base64",
  );

  assert.deepEqual(validatePng(path, bytes), [
    `${path} does not contain decodable PNG pixel data.`,
  ]);
});

test("rejects structurally valid but tiny PNG evidence", () => {
  const path = "tiny.png";
  const bytes = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    "base64",
  );

  assert.deepEqual(validatePng(path, bytes), [
    `${path} is 1x1; evidence must be at least 320x568.`,
  ]);
});

test("rejects symlink-mode PNG evidence", () => {
  const path = "linked.png";
  assert.deepEqual(
    validatePng(path, Buffer.from("target.png"), "120000"),
    [`${path} is not a regular, non-executable Git file.`],
  );
});

test("rejects unknown critical PNG chunks", () => {
  const path = "unknown-critical.png";
  const bytes = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAUAAAAI4CAAAAAA+Ym9HAAAAAEFCQ0TbFyClAAAAyElEQVR4nO3BAQ0AAADCoPdPbQ43oAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4M8AyFYAASLjo7YAAAAASUVORK5CYII=",
    "base64",
  );

  assert.deepEqual(validatePng(path, bytes), [
    `${path} contains an unknown critical PNG chunk.`,
  ]);
});

test("rejects textual and device metadata in screenshot PNGs", () => {
  const path = "text-metadata.png";
  const original = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    "base64",
  );
  const bytes = Buffer.concat([
    original.subarray(0, original.length - 12),
    pngChunk("tEXt", Buffer.from("device=private", "utf8")),
    original.subarray(original.length - 12),
  ]);

  assert.deepEqual(validatePng(path, bytes), [
    `${path} contains unsupported PNG metadata (tEXt).`,
  ]);
});

test("validates safe ancillary chunks by payload, multiplicity, and order", () => {
  const path = "safe-ancillary.png";
  const hiddenPayload = makePng({
    ancillaryChunks: [pngChunk("sRGB", Buffer.alloc(128, 7))],
    colorType: 6,
    pixelBytes: Buffer.from([10, 20, 30, 255]),
  });
  assert.deepEqual(validatePng(path, hiddenPayload), [
    `${path} has an invalid PNG sRGB chunk.`,
  ]);

  const duplicate = makePng({
    ancillaryChunks: [
      pngChunk("sBIT", Buffer.from([8, 8, 8, 8])),
      pngChunk("sBIT", Buffer.from([8, 8, 8, 8])),
    ],
    colorType: 6,
    pixelBytes: Buffer.from([10, 20, 30, 255]),
  });
  assert.deepEqual(validatePng(path, duplicate), [
    `${path} has an invalid PNG sBIT chunk.`,
  ]);

  const canonical = makePng({
    ancillaryChunks: [
      pngChunk("sRGB", Buffer.from([0])),
      pngChunk("sBIT", Buffer.from([8, 8, 8, 8])),
    ],
    colorType: 6,
    pixelBytes: Buffer.from([10, 20, 30, 255]),
  });
  assert.equal(readPngDimensions(path, canonical).width, 1);
});

test("hashes equivalent RGB and opaque RGBA rasters identically", () => {
  const rgb = makePng({
    colorType: 2,
    pixelBytes: Buffer.from([12, 34, 56]),
  });
  const rgba = makePng({
    colorType: 6,
    pixelBytes: Buffer.from([12, 34, 56, 255]),
  });

  assert.equal(
    readPngDimensions("rgb.png", rgb).pixelDigest,
    readPngDimensions("rgba.png", rgba).pixelDigest,
  );
});

test("rejects non-ASCII PNG chunk bytes", () => {
  const path = "non-ascii-chunk.png";
  const bytes = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAUAAAAI4CAYAAAAbCTCbAAAAAElEQVQ1rwYeAAAAAElFTkSuQmCC",
    "base64",
  );
  const idat = bytes.indexOf(Buffer.from("IDAT"));
  for (let index = idat; index < idat + 4; index += 1) {
    bytes[index] |= 0x80;
  }

  assert.deepEqual(validatePng(path, bytes), [
    `${path} contains an invalid PNG chunk type.`,
  ]);
});

test("validates candidate Git object bytes rather than checkout transforms", () => {
  const head = "d".repeat(40);
  const object = "e".repeat(40);
  const path = "app-store-assets/review-evidence/example/proof.png";
  const blobBytes = Buffer.from("not a PNG blob");
  const entry = readGitBlobEntry(
    head,
    path,
    (args) => {
      assert.deepEqual(args, [
        "--literal-pathspecs",
        "ls-tree",
        "--full-tree",
        "-z",
        head,
        "--",
        path,
      ]);
      return `100644 blob ${object}\t${path}\0`;
    },
    (args) => {
      assert.deepEqual(args, ["cat-file", "blob", object]);
      return blobBytes;
    },
  );

  assert.deepEqual(validateScreenshotBlobs([entry]), [
    `${path} is not a valid PNG.`,
  ]);
});

test("reads pre-change UI contents from the merge base", () => {
  const base = "a".repeat(40);
  const comparisonBase = "b".repeat(40);
  const head = "c".repeat(40);
  const path = "app/src/main/java/example/Removed.kt";
  const comparison = comparisonRecords(base, head, (args) => {
    if (args[0] === "merge-base") {
      assert.deepEqual(args, ["merge-base", base, head]);
      return `${comparisonBase}\n`;
    }
    assert.deepEqual(args, [
      "diff",
      "--name-status",
      "-z",
      "--find-renames",
      `${comparisonBase}..${head}`,
      "--",
    ]);
    return `D\0${path}\0`;
  });
  const uiPaths = changedUiPathsAtRevisions(
    comparison.records,
    comparison.comparisonBase,
    head,
    (args) => {
      assert.deepEqual(args, ["show", `${comparisonBase}:${path}`]);
      return "import androidx.compose.runtime.Composable\n";
    },
  );

  assert.deepEqual(uiPaths, [path]);
});

test("rejects untouched visual-proof placeholders", () => {
  const head = "c".repeat(40);
  const errors = validateRenderedProof({
    head,
    renderedHtml: [
      "<h2>Android visual proof</h2>",
      "<ul>",
      `<li>Evidence head: <code>${head}</code></li>`,
      "<li>States covered: </li>",
      "<li>Physical-device gaps: </li>",
      "</ul>",
    ].join(""),
    repository: "example/murph-android",
    screenshotPaths: [],
  });

  assert.deepEqual(errors, [
    "The visual-proof section must describe the states covered.",
    "The visual-proof section must name physical-device gaps or say `None`.",
  ]);
});
