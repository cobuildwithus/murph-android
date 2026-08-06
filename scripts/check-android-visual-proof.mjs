#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const EVIDENCE_PREFIX = "app-store-assets/review-evidence/";
const GIT_MAX_BUFFER = 16 * 1024 * 1024;
const ALLOWED_RENDERED_IMAGE_ATTRIBUTES = new Set([
  "alt",
  "data-canonical-src",
  "src",
  "style",
]);
const MAX_DECODED_BYTES = 128 * 1024 * 1024;
const MAX_SCREENSHOT_DIMENSION = 20_000;
const MIN_SCREENSHOT_WIDTH = 320;
const MIN_SCREENSHOT_HEIGHT = 568;
const SHIPPED_SOURCE_PATH = /^app\/src\/(?:main|release)\//u;
const VISIBLE_RESOURCE_PATH =
  /^app\/src\/(?:main|release)\/res\/(?:anim|animator|color|drawable|font|interpolator|layout|menu|mipmap|navigation|transition|values)(?:-[^/]+)?\//u;
const EXPLICIT_VISIBLE_OWNER_PATH =
  /^app\/src\/(?:main|release)\/java\/ai\/withmurph\/companion\/(?:MainActivity\.kt|(?:app|auth|ui)\/.*\.kt)$/u;
const VISIBLE_MANIFEST_PATH =
  /^app\/src\/(?:main|release)\/AndroidManifest\.xml$/u;
const NON_VISIBLE_EVIDENCE_CONTAINERS = new Set([
  "audio",
  "canvas",
  "datalist",
  "details",
  "dialog",
  "iframe",
  "math",
  "noscript",
  "object",
  "picture",
  "script",
  "select",
  "source",
  "style",
  "svg",
  "template",
  "themed-picture",
  "textarea",
  "video",
]);
const SAFE_ANCILLARY_CHUNKS = new Set(["sBIT", "sRGB"]);
const URL_SAFE_EVIDENCE_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]*$/u;
const PROTECTED_GATE_PATHS = new Set([
  ".github/workflows/android-ci.yml",
  ".github/workflows/android-visual-proof.yml",
  ".github/workflows/review-tooling.yml",
  "AGENTS.md",
  "docs/review-workflow.md",
  "package.json",
  "pnpm-lock.yaml",
  "scripts/chatgpt-review-presets/android-deep-review.md",
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

export function parseNameStatus(raw) {
  const fields = raw.split("\0");
  const records = [];
  let index = 0;

  while (index < fields.length && fields[index]) {
    const status = fields[index++];
    if (status.startsWith("R") || status.startsWith("C")) {
      const oldPath = fields[index++];
      const path = fields[index++];
      records.push({ oldPath, path, status });
      continue;
    }
    records.push({ path: fields[index++], status });
  }

  return records;
}

export function isVisibleResourcePath(path) {
  return VISIBLE_RESOURCE_PATH.test(path);
}

export function isShippedAppPath(path) {
  return SHIPPED_SOURCE_PATH.test(path);
}

export function isComposePath(path, contents) {
  return SHIPPED_SOURCE_PATH.test(path)
    && path.endsWith(".kt")
    && /androidx\.(?:[A-Za-z_][\w]*\.)*compose(?:\.|\b)/m.test(
      contents,
    );
}

export function isExplicitVisibleOwnerPath(path) {
  return EXPLICIT_VISIBLE_OWNER_PATH.test(path)
    || VISIBLE_MANIFEST_PATH.test(path)
    || path === "app/build.gradle.kts";
}

function isUserVisiblePath(path, contents) {
  return isVisibleResourcePath(path)
    || isExplicitVisibleOwnerPath(path)
    || isComposePath(path, contents);
}

function isProtectedGatePath(path) {
  return PROTECTED_GATE_PATHS.has(path)
    || path.startsWith("scripts/check-android-visual-proof");
}

export function changedProtectedPaths(records) {
  const paths = new Set();
  for (const record of records) {
    for (const path of [record.oldPath, record.path]) {
      if (path && isProtectedGatePath(path)) {
        paths.add(path);
      }
    }
  }
  return [...paths].sort();
}

export function changedUiPaths(records, readRevisionFile) {
  const paths = new Set();

  for (const record of records) {
    const basePath = record.status.startsWith("R")
      ? record.oldPath
      : record.path;
    if (
      !record.status.startsWith("A")
      && !record.status.startsWith("C")
      && basePath
      && isUserVisiblePath(
        basePath,
        readRevisionFile("base", basePath),
      )
    ) {
      paths.add(record.path);
    }
    if (record.status.startsWith("D")) {
      continue;
    }

    if (
      isUserVisiblePath(record.path, readRevisionFile("head", record.path))
    ) {
      paths.add(record.path);
    }
  }

  return [...paths].sort();
}

export function changedShippedAppPaths(records) {
  const paths = new Set();
  for (const record of records) {
    for (const path of [record.oldPath, record.path]) {
      if (path && isShippedAppPath(path)) {
        paths.add(path);
      }
    }
  }
  return [...paths].sort();
}

export function changedScreenshotPaths(records) {
  return records
    .filter((record) =>
      !record.status.startsWith("D")
      && record.path.startsWith(EVIDENCE_PREFIX)
      && record.path.endsWith(".png")
    )
    .map((record) => record.path)
    .sort();
}

export function readPngDimensions(path, bytes) {
  const signature = "89504e470d0a1a0a";
  if (bytes.length < 33 || bytes.subarray(0, 8).toString("hex") !== signature) {
    throw new Error(`${path} is not a valid PNG.`);
  }

  let height = 0;
  let bitDepth = 0;
  let colorType = -1;
  let compressionMethod = -1;
  let filterMethod = -1;
  const idatChunks = [];
  let interlaceMethod = -1;
  let offset = 8;
  let paletteBytes = Buffer.alloc(0);
  let paletteEntries = 0;
  let sawPlte = false;
  let sawIdat = false;
  let sawIend = false;
  let idatEnded = false;
  let sawSbit = false;
  let sawSrgb = false;
  let width = 0;

  while (offset < bytes.length) {
    if (offset + 12 > bytes.length) {
      throw new Error(`${path} has a truncated PNG chunk.`);
    }
    const length = bytes.readUInt32BE(offset);
    const typeStart = offset + 4;
    const dataStart = typeStart + 4;
    const crcOffset = dataStart + length;
    const nextOffset = crcOffset + 4;
    if (nextOffset > bytes.length) {
      throw new Error(`${path} has a truncated PNG chunk.`);
    }

    const type = bytes.subarray(typeStart, dataStart).toString("latin1");
    if (
      !/^[A-Za-z]{4}$/u.test(type)
      || type[2] !== type[2].toUpperCase()
    ) {
      throw new Error(`${path} contains an invalid PNG chunk type.`);
    }
    if (type[0] === type[0].toLowerCase() && !SAFE_ANCILLARY_CHUNKS.has(type)) {
      throw new Error(`${path} contains unsupported PNG metadata (${type}).`);
    }
    const expectedCrc = bytes.readUInt32BE(crcOffset);
    const actualCrc = pngCrc32(bytes.subarray(typeStart, crcOffset));
    if (actualCrc !== expectedCrc) {
      throw new Error(`${path} has an invalid PNG chunk checksum.`);
    }

    if (offset === 8) {
      if (type !== "IHDR" || length !== 13) {
        throw new Error(`${path} is missing its required PNG header.`);
      }
      width = bytes.readUInt32BE(dataStart);
      height = bytes.readUInt32BE(dataStart + 4);
      bitDepth = bytes[dataStart + 8];
      colorType = bytes[dataStart + 9];
      compressionMethod = bytes[dataStart + 10];
      filterMethod = bytes[dataStart + 11];
      interlaceMethod = bytes[dataStart + 12];
    } else if (type === "IHDR") {
      throw new Error(`${path} contains more than one PNG header.`);
    }
    if (
      type[0] === type[0].toUpperCase()
      && !["IHDR", "PLTE", "IDAT", "IEND"].includes(type)
    ) {
      throw new Error(`${path} contains an unknown critical PNG chunk.`);
    }
    if (type === "sRGB") {
      if (
        sawSrgb
        || sawPlte
        || sawIdat
        || length !== 1
        || bytes[dataStart] > 3
      ) {
        throw new Error(`${path} has an invalid PNG sRGB chunk.`);
      }
      sawSrgb = true;
    }
    if (type === "sBIT") {
      const significantBits = bytes.subarray(dataStart, crcOffset);
      if (
        sawSbit
        || sawPlte
        || sawIdat
        || !validSignificantBits(significantBits, colorType, bitDepth)
      ) {
        throw new Error(`${path} has an invalid PNG sBIT chunk.`);
      }
      sawSbit = true;
    }
    if (type === "PLTE") {
      if (sawPlte || sawIdat || length === 0 || length % 3 !== 0) {
        throw new Error(`${path} has an invalid PNG palette.`);
      }
      sawPlte = true;
      paletteEntries = length / 3;
      paletteBytes = bytes.subarray(dataStart, crcOffset);
    }
    if (type === "IDAT") {
      if (idatEnded) {
        throw new Error(`${path} has non-consecutive PNG image data.`);
      }
      sawIdat = true;
      idatChunks.push(bytes.subarray(dataStart, crcOffset));
    } else if (sawIdat && type !== "IEND") {
      idatEnded = true;
    }
    if (type === "IEND") {
      if (length !== 0 || nextOffset !== bytes.length) {
        throw new Error(`${path} has an invalid PNG terminator.`);
      }
      sawIend = true;
    }

    offset = nextOffset;
  }

  if (!sawIdat || !sawIend) {
    throw new Error(`${path} is not a structurally complete PNG.`);
  }
  const pixelDigest = validateDecodedPixels({
    bitDepth,
    colorType,
    compressionMethod,
    filterMethod,
    height,
    idatChunks,
    interlaceMethod,
    paletteBytes,
    paletteEntries,
    path,
    sawPlte,
    width,
  });
  return { height, pixelDigest, width };
}

function validSignificantBits(values, colorType, bitDepth) {
  const requirements = new Map([
    [0, { length: 1, maximum: bitDepth }],
    [2, { length: 3, maximum: bitDepth }],
    [3, { length: 3, maximum: 8 }],
    [4, { length: 2, maximum: bitDepth }],
    [6, { length: 4, maximum: bitDepth }],
  ]);
  const requirement = requirements.get(colorType);
  return requirement !== undefined
    && values.length === requirement.length
    && [...values].every((value) => value >= 1 && value <= requirement.maximum);
}

function validateDecodedPixels({
  bitDepth,
  colorType,
  compressionMethod,
  filterMethod,
  height,
  idatChunks,
  interlaceMethod,
  paletteBytes,
  paletteEntries,
  path,
  sawPlte,
  width,
}) {
  const formats = new Map([
    [0, { channels: 1, depths: [1, 2, 4, 8, 16] }],
    [2, { channels: 3, depths: [8, 16] }],
    [3, { channels: 1, depths: [1, 2, 4, 8] }],
    [4, { channels: 2, depths: [8, 16] }],
    [6, { channels: 4, depths: [8, 16] }],
  ]);
  const format = formats.get(colorType);
  if (
    !format
    || !format.depths.includes(bitDepth)
    || compressionMethod !== 0
    || filterMethod !== 0
    || interlaceMethod !== 0
    || (colorType === 3 && (!sawPlte || paletteEntries > 2 ** bitDepth))
    || ([0, 4].includes(colorType) && sawPlte)
    || (sawPlte && paletteEntries > 256)
  ) {
    throw new Error(`${path} uses an unsupported PNG pixel format.`);
  }
  if (
    width < 1
    || height < 1
    || width > MAX_SCREENSHOT_DIMENSION
    || height > MAX_SCREENSHOT_DIMENSION
  ) {
    throw new Error(`${path} has unreasonable PNG dimensions.`);
  }

  const rowBytes = (
    BigInt(width) * BigInt(format.channels) * BigInt(bitDepth) + 7n
  ) / 8n;
  const expectedBytes = BigInt(height) * (rowBytes + 1n);
  if (expectedBytes > BigInt(MAX_DECODED_BYTES)) {
    throw new Error(`${path} expands beyond the PNG evidence limit.`);
  }

  let consumedBytes;
  let decoded;
  try {
    const encoded = Buffer.concat(idatChunks);
    const inflated = inflateSync(encoded, {
      info: true,
      maxOutputLength: Number(expectedBytes),
    });
    decoded = inflated.buffer;
    consumedBytes = inflated.engine.bytesWritten;
    if (consumedBytes !== encoded.length) {
      throw new Error(`${path} contains trailing PNG image data.`);
    }
  } catch {
    throw new Error(`${path} does not contain decodable PNG pixel data.`);
  }
  if (decoded.length !== Number(expectedBytes)) {
    throw new Error(`${path} has an invalid PNG pixel-data length.`);
  }

  const rowStride = Number(rowBytes) + 1;
  for (let row = 0; row < height; row += 1) {
    if (decoded[row * rowStride] > 4) {
      throw new Error(`${path} contains an invalid PNG row filter.`);
    }
  }
  const bytesPerPixel = Math.max(
    1,
    Math.ceil((format.channels * bitDepth) / 8),
  );
  const pixels = Buffer.alloc(Number(rowBytes) * height);
  for (let row = 0; row < height; row += 1) {
    const filteredOffset = row * rowStride;
    const pixelOffset = row * Number(rowBytes);
    for (let column = 0; column < Number(rowBytes); column += 1) {
      const filtered = decoded[filteredOffset + 1 + column];
      const left = column >= bytesPerPixel
        ? pixels[pixelOffset + column - bytesPerPixel]
        : 0;
      const up = row > 0
        ? pixels[pixelOffset + column - Number(rowBytes)]
        : 0;
      const upperLeft = row > 0 && column >= bytesPerPixel
        ? pixels[pixelOffset + column - Number(rowBytes) - bytesPerPixel]
        : 0;
      const predictor = pngFilterPredictor(
        decoded[filteredOffset],
        left,
        up,
        upperLeft,
      );
      pixels[pixelOffset + column] = (filtered + predictor) & 0xFF;
    }
  }
  const renderedPixels = canonicalRgbaPixels({
    bitDepth,
    colorType,
    height,
    paletteBytes,
    paletteEntries,
    path,
    pixels,
    rowBytes: Number(rowBytes),
    width,
  });
  return createHash("sha256")
    .update(pngDimensionBytes(width, height))
    .update(renderedPixels)
    .digest("hex");
}

function canonicalRgbaPixels({
  bitDepth,
  colorType,
  height,
  paletteBytes,
  paletteEntries,
  path,
  pixels,
  rowBytes,
  width,
}) {
  const renderedByteCount = BigInt(width) * BigInt(height) * 4n;
  if (renderedByteCount > BigInt(MAX_DECODED_BYTES)) {
    throw new Error(`${path} expands beyond the PNG evidence limit.`);
  }
  if (colorType === 6 && bitDepth === 8) {
    for (let offset = 3; offset < pixels.length; offset += 4) {
      if (pixels[offset] !== 0xFF) {
        throw new Error(`${path} contains non-opaque PNG pixels.`);
      }
    }
    return pixels;
  }
  const rendered = Buffer.alloc(Number(renderedByteCount));
  const channelCount = pngChannelCount(colorType);
  for (let row = 0; row < height; row += 1) {
    const encodedRow = pixels.subarray(row * rowBytes, (row + 1) * rowBytes);
    for (let column = 0; column < width; column += 1) {
      const target = (row * width + column) * 4;
      const sample = (channel) => readPngSample(
        encodedRow,
        column * channelCount + channel,
        bitDepth,
      );
      const scaled = (channel) => scalePngSample(sample(channel), bitDepth);
      switch (colorType) {
        case 0: {
          const gray = scaled(0);
          rendered.set([gray, gray, gray, 255], target);
          break;
        }
        case 2:
          rendered.set([scaled(0), scaled(1), scaled(2), 255], target);
          break;
        case 3: {
          const paletteIndex = sample(0);
          if (paletteIndex >= paletteEntries) {
            throw new Error(`${path} contains a PNG palette index out of range.`);
          }
          const paletteOffset = paletteIndex * 3;
          rendered.set([
            paletteBytes[paletteOffset],
            paletteBytes[paletteOffset + 1],
            paletteBytes[paletteOffset + 2],
            255,
          ], target);
          break;
        }
        case 4: {
          const gray = scaled(0);
          rendered.set([gray, gray, gray, scaled(1)], target);
          break;
        }
        case 6:
          rendered.set([scaled(0), scaled(1), scaled(2), scaled(3)], target);
          break;
        default:
          throw new Error(`${path} uses an unsupported PNG pixel format.`);
      }
    }
  }
  for (let offset = 3; offset < rendered.length; offset += 4) {
    if (rendered[offset] !== 0xFF) {
      throw new Error(`${path} contains non-opaque PNG pixels.`);
    }
  }
  return rendered;
}

function pngChannelCount(colorType) {
  return new Map([[0, 1], [2, 3], [3, 1], [4, 2], [6, 4]]).get(colorType);
}

function readPngSample(row, sampleIndex, bitDepth) {
  const bitOffset = sampleIndex * bitDepth;
  const byteOffset = Math.floor(bitOffset / 8);
  if (bitDepth === 16) {
    return row.readUInt16BE(byteOffset);
  }
  if (bitDepth === 8) {
    return row[byteOffset];
  }
  const shift = 8 - bitDepth - (bitOffset % 8);
  return (row[byteOffset] >>> shift) & (2 ** bitDepth - 1);
}

function scalePngSample(value, bitDepth) {
  return Math.round((value * 255) / (2 ** bitDepth - 1));
}

function pngDimensionBytes(width, height) {
  const dimensions = Buffer.alloc(8);
  dimensions.writeUInt32BE(width, 0);
  dimensions.writeUInt32BE(height, 4);
  return dimensions;
}

function pngFilterPredictor(filter, left, up, upperLeft) {
  switch (filter) {
    case 0: return 0;
    case 1: return left;
    case 2: return up;
    case 3: return Math.floor((left + up) / 2);
    case 4: {
      const estimate = left + up - upperLeft;
      const leftDistance = Math.abs(estimate - left);
      const upDistance = Math.abs(estimate - up);
      const upperLeftDistance = Math.abs(estimate - upperLeft);
      if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) return left;
      return upDistance <= upperLeftDistance ? up : upperLeft;
    }
    default: throw new Error("Unsupported PNG row filter.");
  }
}

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

export function validateScreenshotBlobs(entries) {
  const errors = [];

  for (const { bytes, mode, path } of entries) {
    try {
      if (path.split("/").some((segment) =>
        !URL_SAFE_EVIDENCE_SEGMENT.test(segment)
      )) {
        throw new Error(
          "Screenshot evidence paths must use URL-safe ASCII letters, numbers, dots, dashes, and underscores.",
        );
      }
      if (mode !== "100644") {
        throw new Error(`${path} is not a regular, non-executable Git file.`);
      }
      const { height, width } = readPngDimensions(path, bytes);
      if (width < MIN_SCREENSHOT_WIDTH || height < MIN_SCREENSHOT_HEIGHT) {
        errors.push(
          `${path} is ${width}x${height}; evidence must be at least `
          + `${MIN_SCREENSHOT_WIDTH}x${MIN_SCREENSHOT_HEIGHT}.`,
        );
      }
    } catch (error) {
      errors.push(error instanceof Error ? error.message : String(error));
    }
  }

  return errors;
}

export function validateScreenshotReuse({
  basePixelDigests,
  headPixelDigests,
  records,
}) {
  const errors = [];
  const baseDigests = new Set(basePixelDigests.values());
  for (const record of records) {
    if (
      record.status.startsWith("D")
      || !record.path.startsWith(EVIDENCE_PREFIX)
      || !record.path.endsWith(".png")
    ) {
      continue;
    }
    if (record.status.startsWith("R") || record.status.startsWith("C")) {
      errors.push(`${record.path} must be a fresh capture, not a copied or renamed file.`);
      continue;
    }
    const headDigest = headPixelDigests.get(record.path);
    if (!headDigest) continue;
    if (record.status.startsWith("A") && baseDigests.has(headDigest)) {
      errors.push(`${record.path} duplicates pixels already present at the comparison base.`);
    } else if (
      record.status.startsWith("M")
      && basePixelDigests.get(record.path) === headDigest
    ) {
      errors.push(`${record.path} changed without fresh screenshot pixels.`);
    }
  }
  return errors;
}

function decodeHtml(value) {
  return value
    .replaceAll("&amp;", "&")
    .replaceAll("&quot;", "\"")
    .replaceAll("&#39;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">");
}

function renderedSection(html) {
  const heading = /<h2\b[^>]*>\s*Android visual proof\s*<\/h2>/i.exec(html);
  if (!heading) {
    return null;
  }
  const remainder = html.slice(heading.index + heading[0].length);
  const nextHeading = /<h2\b[^>]*>/i.exec(remainder);
  return nextHeading ? remainder.slice(0, nextHeading.index) : remainder;
}

function visibleText(html) {
  return decodeHtml(
    html
      .replaceAll(/<[^>]+>/g, " ")
      .replaceAll(/\s+/g, " ")
      .trim(),
  );
}

function listItemText(section, label) {
  for (const item of section.matchAll(/<li\b[^>]*>([\s\S]*?)<\/li\s*>/gi)) {
    const text = visibleText(item[1]);
    if (text.startsWith(`${label}:`)) {
      return text;
    }
  }
  return null;
}

function renderedTag(tag) {
  const name = /^<([A-Za-z][^\s/>]*)/u.exec(tag)?.[1].toLowerCase() ?? "";
  const attributes = new Map();
  for (const attribute of tag.matchAll(
    /\s+([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/gu,
  )) {
    attributes.set(
      attribute[1].toLowerCase(),
      decodeHtml(attribute[2] ?? attribute[3] ?? attribute[4] ?? ""),
    );
  }
  return { attributes, name, tag };
}

function renderedImages(html) {
  const images = [];
  for (const image of html.matchAll(/<img\b[^>]*>/gi)) {
    const rendered = renderedTag(image[0]);
    const effectiveUrl = rendered.attributes.get("data-canonical-src")
      ?? rendered.attributes.get("src");
    if (effectiveUrl) {
      images.push({ ...rendered, url: effectiveUrl });
    }
  }
  return images;
}

function hasHiddenEvidencePresentation(section, images) {
  for (const match of section.matchAll(/<[A-Za-z][^>]*>/g)) {
    const { attributes, name } = renderedTag(match[0]);
    const className = attributes.get("class");
    if (
      NON_VISIBLE_EVIDENCE_CONTAINERS.has(name)
      || attributes.has("hidden")
      || attributes.has("inert")
      || (className != null && !(name === "code" && className === "notranslate"))
      || attributes.has("width")
      || attributes.has("height")
      || attributes.get("aria-hidden")?.toLowerCase() === "true"
      || (name !== "img" && attributes.has("style"))
    ) {
      return true;
    }
  }
  return images.some(({ attributes }) => {
    if (
      !attributes.has("src")
      || [...attributes.keys()].some((name) =>
        !ALLOWED_RENDERED_IMAGE_ATTRIBUTES.has(name)
      )
    ) {
      return true;
    }
    if (
      attributes.has("data-canonical-src")
      && !attributes.get("src")?.startsWith("https://camo.githubusercontent.com/")
    ) return true;
    const style = attributes.get("style");
    return style != null
      && !/^max-width\s*:\s*100%\s*;?$/iu.test(style.trim());
  });
}

function hasCompletedListValue(section, label) {
  const item = listItemText(section, label);
  if (!item) {
    return false;
  }
  const value = item.slice(label.length + 1).trim().normalize("NFKC");
  return /[\p{L}\p{N}]/u.test(value)
    && !/[\p{Cc}\p{Cf}]/u.test(value)
    && !/^<.*>$/u.test(value)
    && !/^(?:tbd|todo|placeholder)$/iu.test(value);
}

export function validateRenderedProof({
  head,
  renderedHtml,
  repository,
  screenshotPaths,
}) {
  const section = renderedSection(renderedHtml);
  if (!section) {
    return ["PR body is missing a rendered `## Android visual proof` section."];
  }

  const errors = [];
  if (listItemText(section, "Evidence head") !== `Evidence head: ${head}`) {
    errors.push(`Evidence head must be the exact PR head ${head}.`);
  }
  if (!hasCompletedListValue(section, "States covered")) {
    errors.push("The visual-proof section must describe the states covered.");
  }
  if (!hasCompletedListValue(section, "Physical-device gaps")) {
    errors.push(
      "The visual-proof section must name physical-device gaps or say `None`.",
    );
  }

  const images = renderedImages(section);
  const urls = new Set(images.map(({ url }) => url));
  if (hasHiddenEvidencePresentation(section, images)) {
    errors.push(
      "The visual-proof section must render evidence at its normal visible size.",
    );
  }
  const expectedUrls = new Map(screenshotPaths.map((path) => [
    `https://raw.githubusercontent.com/${repository}/${head}/${path}`,
    path,
  ]));
  for (const [expected, path] of expectedUrls) {
    if (!urls.has(expected)) {
      errors.push(`${path} must be embedded with its exact-head raw GitHub URL.`);
    }
  }
  for (const url of urls) {
    if (!expectedUrls.has(url)) {
      errors.push(
        "The visual-proof section contains an image that is not exact-head evidence.",
      );
    }
  }

  return errors;
}

export async function renderMarkdown({ body, repository, token }) {
  const response = await fetch("https://api.github.com/markdown", {
    body: JSON.stringify({
      context: repository,
      mode: "gfm",
      text: body,
    }),
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "User-Agent": "murph-android-visual-proof",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    method: "POST",
  });

  if (!response.ok) {
    throw new Error(
      `GitHub Markdown rendering failed (${response.status} ${response.statusText}).`,
    );
  }
  return response.text();
}

export function validateLivePullRequest({
  archivedEvent,
  base,
  baseRepository,
  head,
  headRepository,
  livePullRequest,
}) {
  const errors = [];
  const archived = archivedEvent.pull_request;
  if (!archived || archived.base?.sha !== base || archived.head?.sha !== head) {
    errors.push("The archived workflow event does not match the candidate base and head.");
  }
  if (archived?.base?.repo?.full_name !== baseRepository) {
    errors.push("The archived workflow event does not match the base repository.");
  }
  if (archived?.head?.repo?.full_name !== headRepository) {
    errors.push("The archived workflow event does not match the head repository.");
  }
  if (livePullRequest.base?.sha !== base || livePullRequest.head?.sha !== head) {
    errors.push("The live pull request no longer matches the candidate base and head.");
  }
  if (livePullRequest.base?.repo?.full_name !== baseRepository) {
    errors.push("The live pull request base repository no longer matches the candidate.");
  }
  if (livePullRequest.head?.repo?.full_name !== headRepository) {
    errors.push("The live pull request head repository no longer matches the candidate.");
  }
  if ((archived?.body ?? "") !== (livePullRequest.body ?? "")) {
    errors.push("The live pull request body changed after this workflow event was created.");
  }
  return errors;
}

async function fetchLivePullRequest({ event, repository, token }) {
  const number = event.pull_request?.number ?? event.number;
  if (!Number.isSafeInteger(number) || number < 1) {
    throw new Error("The workflow event is missing a valid pull-request number.");
  }
  const response = await fetch(
    `https://api.github.com/repos/${repository}/pulls/${number}`,
    {
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "User-Agent": "murph-android-visual-proof",
        "X-GitHub-Api-Version": "2022-11-28",
      },
    },
  );
  if (!response.ok) {
    throw new Error(
      `Live pull-request lookup failed (${response.status} ${response.statusText}).`,
    );
  }
  return response.json();
}

function git(args) {
  return gitBytes(args).toString("utf8");
}

function gitBytes(args) {
  return execFileSync("git", args, {
    maxBuffer: GIT_MAX_BUFFER,
  });
}

export function comparisonRecords(base, head, runGit = git) {
  const comparisonBase = runGit(["merge-base", base, head]).trim();
  if (!/^[0-9a-f]{40,64}$/u.test(comparisonBase)) {
    throw new Error("Git did not return a valid comparison-base commit.");
  }
  const records = parseNameStatus(
    runGit([
      "diff",
      "--name-status",
      "-z",
      "--find-renames",
      `${comparisonBase}..${head}`,
      "--",
    ]),
  );
  return { comparisonBase, records };
}

export function changedUiPathsAtRevisions(
  records,
  comparisonBase,
  head,
  runGit = git,
) {
  return changedUiPaths(records, (revision, path) => {
    const commit = revision === "base" ? comparisonBase : head;
    return runGit(["show", `${commit}:${path}`]);
  });
}

export function readGitBlobEntry(
  revision,
  path,
  runGit = git,
  readObject = gitBytes,
) {
  const record = runGit([
    "--literal-pathspecs",
    "ls-tree",
    "--full-tree",
    "-z",
    revision,
    "--",
    path,
  ]);
  const separator = record.indexOf("\t");
  const terminator = record.indexOf("\0");
  if (
    separator < 0
    || terminator !== record.length - 1
    || record.slice(separator + 1, terminator) !== path
  ) {
    throw new Error(`${path} is not an exact file in the candidate commit.`);
  }
  const match = /^(\d{6}) blob ([0-9a-f]{40,64})$/u.exec(
    record.slice(0, separator),
  );
  if (!match) {
    throw new Error(`${path} is not a Git blob in the candidate commit.`);
  }
  const [, mode, object] = match;
  return {
    bytes: readObject(["cat-file", "blob", object]),
    mode,
    path,
  };
}

function screenshotPixelDigests(revision) {
  const paths = git([
    "ls-tree",
    "-r",
    "--name-only",
    "-z",
    revision,
    "--",
    EVIDENCE_PREFIX,
  ]).split("\0").filter((path) => path.endsWith(".png"));
  return new Map(paths.map((path) => {
    const entry = readGitBlobEntry(revision, path);
    const { pixelDigest } = readPngDimensions(path, entry.bytes);
    return [path, pixelDigest];
  }));
}

export async function main(environment = process.env) {
  const base = environment.GITHUB_BASE_SHA;
  const baseRepository = environment.GITHUB_REPOSITORY;
  const eventPath = environment.GITHUB_EVENT_PATH;
  const head = environment.GITHUB_HEAD_SHA;
  const headRepository = environment.GITHUB_HEAD_REPOSITORY;
  const token = environment.GITHUB_TOKEN;
  if (!base || !baseRepository || !eventPath || !head || !headRepository || !token) {
    throw new Error(
      "GITHUB_BASE_SHA, GITHUB_EVENT_PATH, GITHUB_HEAD_SHA, "
      + "GITHUB_HEAD_REPOSITORY, GITHUB_REPOSITORY, and GITHUB_TOKEN are required.",
    );
  }

  const { comparisonBase, records } = comparisonRecords(base, head);
  const protectedPaths = changedProtectedPaths(records);
  if (protectedPaths.length > 0) {
    throw new Error(
      "Visual-proof control-plane changes require independent trusted review "
      + `and cannot self-certify: ${protectedPaths.join(", ")}`,
    );
  }
  const shippedAppPaths = changedShippedAppPaths(records);
  if (shippedAppPaths.length === 0) {
    console.log("No shipped Android app changes detected.");
    return;
  }

  const screenshotPaths = changedScreenshotPaths(records);
  const errors = [];
  if (screenshotPaths.length === 0) {
    errors.push(
      "Shipped Android app changed "
      + `(${shippedAppPaths.join(", ")}) but no PNG changed under `
      + `${EVIDENCE_PREFIX}.`,
    );
  } else {
    const entries = screenshotPaths.map((path) => readGitBlobEntry(head, path));
    errors.push(...validateScreenshotBlobs(entries));
    const headPixelDigests = new Map(entries.map((entry) => [
      entry.path,
      readPngDimensions(entry.path, entry.bytes).pixelDigest,
    ]));
    errors.push(...validateScreenshotReuse({
      basePixelDigests: screenshotPixelDigests(comparisonBase),
      headPixelDigests,
      records,
    }));
  }

  const event = JSON.parse(readFileSync(eventPath, "utf8"));
  const livePullRequest = await fetchLivePullRequest({
    event,
    repository: baseRepository,
    token,
  });
  errors.push(...validateLivePullRequest({
    archivedEvent: event,
    base,
    baseRepository,
    head,
    headRepository,
    livePullRequest,
  }));
  const renderedHtml = await renderMarkdown({
    body: livePullRequest.body ?? "",
    repository: baseRepository,
    token,
  });
  errors.push(...validateRenderedProof({
    head,
    renderedHtml,
    repository: headRepository,
    screenshotPaths,
  }));

  if (errors.length > 0) {
    throw new Error(errors.map((error) => `- ${error}`).join("\n"));
  }
  console.log(
    `Validated ${screenshotPaths.length} exact-head screenshot file(s) `
    + `for ${shippedAppPaths.length} changed shipped app path(s). `
    + "Capture provenance remains manual review evidence.",
  );
}

const invokedPath = process.argv[1];
if (invokedPath && fileURLToPath(import.meta.url) === invokedPath) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
