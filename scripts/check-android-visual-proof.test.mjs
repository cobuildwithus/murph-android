import assert from "node:assert/strict";
import test from "node:test";
import { deflateSync } from "node:zlib";

import {
  changedProtectedPaths,
  changedScreenshotPaths,
  changedShippedAppPaths,
  comparisonRecords,
  isShippedAppPath,
  parseNameStatus,
  readGitBlobEntry,
  validateRenderedProof,
  validateScreenshotBlobs,
} from "./check-android-visual-proof.mjs";

function pngCrc32(bytes) {
  let crc = 0xFFFFFFFF;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function pngChunk(type, data = Buffer.alloc(0)) {
  const typeBytes = Buffer.from(type, "latin1");
  const result = Buffer.alloc(12 + data.length);
  result.writeUInt32BE(data.length, 0);
  typeBytes.copy(result, 4);
  data.copy(result, 8);
  result.writeUInt32BE(
    pngCrc32(Buffer.concat([typeBytes, data])),
    8 + data.length,
  );
  return result;
}

function emulatorPng({
  alpha = 255,
  bitDepth = 8,
  colorType = 6,
  compressionMethod = 0,
  extraAfterIdat = [],
  extraBeforeIdat = [],
  filterMethod = 0,
  firstPixelAlpha = alpha,
  height = 568,
  idatChunks,
  interlaceMethod = 0,
  sbit = Buffer.from([8, 8, 8, 8]),
  srgb = Buffer.from([0]),
  width = 320,
} = {}) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = bitDepth;
  header[9] = colorType;
  header[10] = compressionMethod;
  header[11] = filterMethod;
  header[12] = interlaceMethod;

  const rowBytes = width * 4 + 1;
  const encodedRows = Buffer.alloc(height * rowBytes);
  for (let row = 0; row < height; row += 1) {
    for (let pixel = 0; pixel < width; pixel += 1) {
      encodedRows[row * rowBytes + 1 + pixel * 4 + 3] = alpha;
    }
  }
  encodedRows[4] = firstPixelAlpha;
  const imageData = idatChunks ?? [deflateSync(encodedRows)];

  return Buffer.concat([
    Buffer.from("89504e470d0a1a0a", "hex"),
    pngChunk("IHDR", header),
    ...(srgb === null ? [] : [pngChunk("sRGB", srgb)]),
    ...(sbit === null ? [] : [pngChunk("sBIT", sbit)]),
    ...extraBeforeIdat.map(({ data, type }) => pngChunk(type, data)),
    ...imageData.map((data) => pngChunk("IDAT", data)),
    ...extraAfterIdat.map(({ data, type }) => pngChunk(type, data)),
    pngChunk("IEND"),
  ]);
}

function validatePng(path, bytes, mode = "100644") {
  return validateScreenshotBlobs([{ bytes, mode, path }]);
}

test("every shipped app path triggers proof, including rename and delete", () => {
  const records = parseNameStatus(
    "M\0app/src/main/java/example/AppSession.kt\0"
    + "M\0app/src/main/AndroidManifest.xml\0"
    + "M\0app/src/main/res/raw/onboarding.json\0"
    + "M\0app/src/main/assets/hero.png\0"
    + "A\0app/src/release/java/example/Release.kt\0"
    + "D\0app/src/main/java/example/Removed.kt\0"
    + "R100\0app/src/main/java/example/Old.kt\0scripts/Old.kt\0"
    + "R100\0scripts/New.kt\0app/src/main/java/example/New.kt\0"
    + "M\0app/src/debug/java/example/ScreenshotActivity.kt\0"
    + "M\0scripts/tool.mjs\0",
  );

  assert.deepEqual(changedShippedAppPaths(records), [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/assets/hero.png",
    "app/src/main/java/example/AppSession.kt",
    "app/src/main/java/example/New.kt",
    "app/src/main/java/example/Old.kt",
    "app/src/main/java/example/Removed.kt",
    "app/src/main/res/raw/onboarding.json",
    "app/src/release/java/example/Release.kt",
  ]);
});

test("recognizes only shipped main and release source sets", () => {
  assert.equal(
    isShippedAppPath("app/src/main/java/example/Plain.kt"),
    true,
  );
  assert.equal(
    isShippedAppPath("app/src/release/res/values/strings.xml"),
    true,
  );
  assert.equal(
    isShippedAppPath("app/src/debug/java/example/ScreenshotActivity.kt"),
    false,
  );
  assert.equal(isShippedAppPath("scripts/tool.mjs"), false);
});

test("identifies visual-proof control-plane changes", () => {
  const records = parseNameStatus(
    "M\0.github/workflows/android-visual-proof.yml\0"
    + "R100\0scripts/check-android-visual-proof.mjs\0scripts/retired.mjs\0"
    + "M\0README.md\0",
  );
  assert.deepEqual(changedProtectedPaths(records), [
    ".github/workflows/android-visual-proof.yml",
    "scripts/check-android-visual-proof.mjs",
  ]);
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

test("accepts exact-head rendered proof with every changed image", () => {
  const head = "a".repeat(40);
  const repository = "example/murph-android";
  const path = "app-store-assets/review-evidence/onboarding/01-ready.png";
  const url = `https://raw.githubusercontent.com/${repository}/${head}/${path}`;
  const renderedHtml = [
    "<h2>Android visual proof</h2>",
    "<ul>",
    `<li>Evidence head: <code class="notranslate">${head}</code></li>`,
    "<li>States covered: reconnect and saved status</li>",
    `<li>Screenshots:<img src="https://camo.githubusercontent.com/proxy" data-canonical-src="${url}" alt="Hidden menu" style="max-width: 100%;"></li>`,
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

test("rejects every unexpected image in the visual-proof section", () => {
  const head = "c".repeat(40);
  const repository = "example/murph-android";
  const path = "app-store-assets/review-evidence/onboarding/01-ready.png";
  const expected =
    `https://raw.githubusercontent.com/${repository}/${head}/${path}`;
  const unexpected = "https://example.invalid/untrusted.png";
  const errors = validateRenderedProof({
    head,
    renderedHtml: [
      "<h2>Android visual proof</h2>",
      "<ul>",
      `<li>Evidence head: <code>${head}</code></li>`,
      "<li>States covered: ready</li>",
      `<li>Screenshots:<img src="https://camo.githubusercontent.com/proxy" data-canonical-src="${expected}"><img src="${unexpected}"></li>`,
      "<li>Physical-device gaps: None</li>",
      "</ul>",
    ].join(""),
    repository,
    screenshotPaths: [path],
  });

  assert.deepEqual(errors, [
    "The visual-proof section contains an image that is not exact-head evidence.",
  ]);
});

test("rejects hidden or resized exact-head evidence", () => {
  const head = "e".repeat(40);
  const repository = "example/murph-android";
  const path = "app-store-assets/review-evidence/onboarding/01-ready.png";
  const expected =
    `https://raw.githubusercontent.com/${repository}/${head}/${path}`;
  const proof = (image) => [
    "<h2>Android visual proof</h2>",
    "<ul>",
    `<li>Evidence head: <code>${head}</code></li>`,
    "<li>States covered: ready</li>",
    `<li>Screenshots:${image}</li>`,
    "<li>Physical-device gaps: None</li>",
    "</ul>",
  ].join("");
  const validate = (image) => validateRenderedProof({
    head,
    renderedHtml: proof(image),
    repository,
    screenshotPaths: [path],
  });
  const visibilityError =
    "The visual-proof section must render evidence at its normal visible size.";

  assert.deepEqual(validate(`<img src="${expected}" width="1" height="1">`), [
    visibilityError,
  ]);
  assert.deepEqual(validate(`<span hidden><img src="${expected}"></span>`), [
    visibilityError,
  ]);
  assert.deepEqual(validate(`<details><img src="${expected}"></details>`), [
    visibilityError,
  ]);
  for (const style of [
    "display: none;",
    "visibility: hidden;",
    "opacity: 0;",
    "width: 1px; height: 1px;",
    "position: absolute; left: -9999px;",
  ]) {
    assert.deepEqual(validate(`<img src="${expected}" style="${style}">`), [
      visibilityError,
    ]);
  }
  for (const container of [
    "audio",
    "canvas",
    "datalist",
    "dialog",
    "math",
    "noscript",
    "object",
    "svg",
    "template",
    "textarea",
    "video",
  ]) {
    assert.deepEqual(validate(
      `<${container}><img src="${expected}" style="max-width: 100%;"></${container}>`,
    ), [visibilityError]);
  }
  assert.deepEqual(validate(
    `<picture><source srcset="https://example.invalid/stale.png"><img src="${expected}"></picture>`,
  ), [visibilityError]);
  assert.deepEqual(validate(
    `<img src="${expected}" srcset="https://example.invalid/stale.png 2x">`,
  ), [visibilityError]);
  assert.deepEqual(validate(
    `<img src="https://example.invalid/stale.png" data-canonical-src="${expected}">`,
  ), [visibilityError]);
});

test("rejects format-only and bidirectional proof descriptions", () => {
  const head = "d".repeat(40);
  const base = {
    head,
    repository: "example/murph-android",
    screenshotPaths: [],
  };
  const renderedHtml = (statesCovered) => [
    "<h2>Android visual proof</h2>",
    "<ul>",
    `<li>Evidence head: <code>${head}</code></li>`,
    `<li>States covered: ${statesCovered}</li>`,
    "<li>Physical-device gaps: None</li>",
    "</ul>",
  ].join("");

  assert.deepEqual(validateRenderedProof({
    ...base,
    renderedHtml: renderedHtml("\u200B"),
  }), ["The visual-proof section must describe the states covered."]);
  assert.deepEqual(validateRenderedProof({
    ...base,
    renderedHtml: renderedHtml("ready\u202E"),
  }), ["The visual-proof section must describe the states covered."]);
});

test("accepts an exact emulator-format PNG", () => {
  assert.deepEqual(validatePng("proof.png", emulatorPng()), []);
});

test("rejects transparent and partially opaque PNG evidence", () => {
  for (const [path, options] of [
    ["transparent.png", { alpha: 0 }],
    ["partially-opaque.png", { firstPixelAlpha: 254 }],
  ]) {
    assert.deepEqual(validatePng(path, emulatorPng(options)), [
      `${path} contains non-opaque PNG pixels.`,
    ]);
  }
});

test("rejects URL-reserved and non-ASCII evidence paths", () => {
  const bytes = emulatorPng();
  const invalidPaths = [
    "app-store-assets/review-evidence/example/proof?.png",
    "app-store-assets/review-evidence/example/proof#.png",
    "app-store-assets/review-evidence/example/proof%.png",
    "app-store-assets/review-evidence/example/proof space.png",
    "app-store-assets/review-evidence/example/prøof.png",
  ];
  const error =
    "Screenshot evidence paths must use URL-safe ASCII letters, numbers, dots, dashes, and underscores.";

  for (const path of invalidPaths) {
    assert.deepEqual(validatePng(path, bytes), [error]);
  }
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
  const bytes = emulatorPng();
  bytes[bytes.length - 1] ^= 1;

  assert.deepEqual(validatePng(path, bytes), [
    `${path} has an invalid PNG chunk checksum.`,
  ]);
});

test("rejects framed PNGs whose pixels do not decode", () => {
  const path = "invalid-idat.png";
  const bytes = emulatorPng({
    idatChunks: [Buffer.from([0])],
  });

  assert.deepEqual(validatePng(path, bytes), [
    `${path} does not contain decodable PNG pixel data.`,
  ]);
});

test("rejects trailing bytes or a second stream inside image data", () => {
  const encodedRows = Buffer.alloc(568 * (320 * 4 + 1));
  const imageData = deflateSync(encodedRows);
  for (const [path, trailer] of [
    ["trailing-bytes.png", Buffer.from("HIDDEN_TRAILER")],
    ["second-stream.png", deflateSync(encodedRows)],
  ]) {
    assert.deepEqual(
      validatePng(
        path,
        emulatorPng({
          idatChunks: [Buffer.concat([imageData, trailer])],
        }),
      ),
      [`${path} contains trailing PNG image data.`],
    );
  }
});

test("rejects structurally valid but tiny PNG evidence", () => {
  const path = "tiny.png";

  assert.deepEqual(validatePng(path, emulatorPng({ height: 1, width: 1 })), [
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

test("rejects metadata and private ancillary chunks", () => {
  const cases = [
    ["tEXt", Buffer.from("Comment\0synthetic fixture", "latin1")],
    ["zTXt", Buffer.from("Comment\0\0synthetic fixture", "latin1")],
    ["iTXt", Buffer.from("Comment\0\0\0\0\0synthetic fixture", "latin1")],
    ["eXIf", Buffer.from([0, 0, 0, 0])],
    ["iCCP", Buffer.from("profile\0\0synthetic fixture", "latin1")],
    ["vpAg", Buffer.from("private data", "latin1")],
  ];

  for (const [type, data] of cases) {
    const path = `${type}.png`;
    const bytes = emulatorPng({
      extraBeforeIdat: [{ data, type }],
    });
    assert.deepEqual(validatePng(path, bytes), [
      `${path} contains disallowed PNG chunk ${type}.`,
    ]);
  }
});

test("rejects non-emulator critical chunks", () => {
  const path = "palette.png";
  const bytes = emulatorPng({
    extraBeforeIdat: [{
      data: Buffer.from([0, 0, 0]),
      type: "PLTE",
    }],
  });

  assert.deepEqual(validatePng(path, bytes), [
    `${path} contains disallowed PNG chunk PLTE.`,
  ]);
});

test("rejects non-ASCII PNG chunk bytes", () => {
  const path = "non-ascii-chunk.png";
  const bytes = emulatorPng();
  const idat = bytes.indexOf(Buffer.from("IDAT"));
  for (let index = idat; index < idat + 4; index += 1) {
    bytes[index] |= 0x80;
  }

  assert.deepEqual(validatePng(path, bytes), [
    `${path} contains an invalid PNG chunk type.`,
  ]);
});

test("requires exact 8-bit non-interlaced RGBA header values", () => {
  for (const options of [
    { bitDepth: 16 },
    { colorType: 2 },
    { compressionMethod: 1 },
    { filterMethod: 1 },
    { interlaceMethod: 1 },
  ]) {
    assert.deepEqual(validatePng("format.png", emulatorPng(options)), [
      "format.png is not an 8-bit non-interlaced RGBA emulator PNG.",
    ]);
  }
});

test("requires exact emulator sRGB and sBIT chunks", () => {
  for (const [options, expected] of [
    [
      { srgb: Buffer.from([1]) },
      "color.png has an invalid emulator PNG sRGB chunk.",
    ],
    [
      { sbit: Buffer.from([8, 8, 8, 7]) },
      "color.png has an invalid emulator PNG sBIT chunk.",
    ],
    [
      { sbit: Buffer.from([8, 8, 8]) },
      "color.png has an invalid emulator PNG sBIT chunk.",
    ],
  ]) {
    assert.deepEqual(validatePng("color.png", emulatorPng(options)), [expected]);
  }
});

test("rejects missing, empty, or non-consecutive image data", () => {
  assert.deepEqual(
    validatePng("empty.png", emulatorPng({ idatChunks: [Buffer.alloc(0)] })),
    ["empty.png has invalid PNG image-data ordering."],
  );
  assert.deepEqual(
    validatePng("missing-color.png", emulatorPng({ srgb: null })),
    ["missing-color.png has an invalid emulator PNG sBIT chunk."],
  );
  assert.deepEqual(
    validatePng(
      "split.png",
      emulatorPng({
        extraAfterIdat: [{ data: Buffer.from([0]), type: "sRGB" }],
      }),
    ),
    ["split.png has an invalid emulator PNG sRGB chunk."],
  );
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

test("compares candidate changes from the merge base", () => {
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
  assert.equal(comparison.comparisonBase, comparisonBase);
  assert.deepEqual(comparison.records, [{ path, status: "D" }]);
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
