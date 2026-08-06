#!/usr/bin/env node

import { execFileSync } from "node:child_process";
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
const SHIPPED_APP_PATH = /^app\/src\/(?:main|release)\//u;
const URL_SAFE_EVIDENCE_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._-]*$/u;

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

export function isShippedAppPath(path) {
  return SHIPPED_APP_PATH.test(path);
}

function isProtectedGatePath(path) {
  return path === ".github/workflows/android-visual-proof.yml"
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

  const idatChunks = [];
  let height = 0;
  let offset = 8;
  let stage = "header";
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
    const expectedCrc = bytes.readUInt32BE(crcOffset);
    const actualCrc = pngCrc32(bytes.subarray(typeStart, crcOffset));
    if (actualCrc !== expectedCrc) {
      throw new Error(`${path} has an invalid PNG chunk checksum.`);
    }

    if (
      !["IHDR", "sRGB", "sBIT", "IDAT", "IEND"].includes(type)
    ) {
      throw new Error(`${path} contains disallowed PNG chunk ${type}.`);
    }

    switch (type) {
      case "IHDR": {
        if (stage !== "header" || offset !== 8 || length !== 13) {
          throw new Error(`${path} has an invalid PNG header.`);
        }
        width = bytes.readUInt32BE(dataStart);
        height = bytes.readUInt32BE(dataStart + 4);
        const bitDepth = bytes[dataStart + 8];
        const colorType = bytes[dataStart + 9];
        const compressionMethod = bytes[dataStart + 10];
        const filterMethod = bytes[dataStart + 11];
        const interlaceMethod = bytes[dataStart + 12];
        if (
          width < 1
          || height < 1
          || width > MAX_SCREENSHOT_DIMENSION
          || height > MAX_SCREENSHOT_DIMENSION
        ) {
          throw new Error(`${path} has unreasonable PNG dimensions.`);
        }
        if (
          bitDepth !== 8
          || colorType !== 6
          || compressionMethod !== 0
          || filterMethod !== 0
          || interlaceMethod !== 0
        ) {
          throw new Error(
            `${path} is not an 8-bit non-interlaced RGBA emulator PNG.`,
          );
        }
        stage = "srgb";
        break;
      }
      case "sRGB":
        if (stage !== "srgb" || length !== 1 || bytes[dataStart] !== 0) {
          throw new Error(`${path} has an invalid emulator PNG sRGB chunk.`);
        }
        stage = "sbit";
        break;
      case "sBIT":
        if (
          stage !== "sbit"
          || length !== 4
          || !bytes.subarray(dataStart, crcOffset).every((value) => value === 8)
        ) {
          throw new Error(`${path} has an invalid emulator PNG sBIT chunk.`);
        }
        stage = "idat";
        break;
      case "IDAT":
        if (
          !["idat", "idat-data"].includes(stage)
          || length === 0
        ) {
          throw new Error(`${path} has invalid PNG image-data ordering.`);
        }
        idatChunks.push(bytes.subarray(dataStart, crcOffset));
        stage = "idat-data";
        break;
      case "IEND":
        if (
          stage !== "idat-data"
          || length !== 0
          || nextOffset !== bytes.length
        ) {
          throw new Error(`${path} has an invalid PNG terminator.`);
        }
        stage = "complete";
        break;
      default:
        throw new Error(`${path} contains an unexpected PNG chunk.`);
    }

    offset = nextOffset;
  }

  if (stage !== "complete") {
    throw new Error(`${path} is not a structurally complete PNG.`);
  }
  validateDecodedPixels({
    height,
    idatChunks,
    path,
    width,
  });
  return { height, width };
}

function validateDecodedPixels({
  height,
  idatChunks,
  path,
  width,
}) {
  const rowBytes = BigInt(width) * 4n;
  const expectedBytes = BigInt(height) * (rowBytes + 1n);
  if (expectedBytes > BigInt(MAX_DECODED_BYTES)) {
    throw new Error(`${path} expands beyond the PNG evidence limit.`);
  }

  const encoded = Buffer.concat(idatChunks);
  let decoded;
  let consumedBytes;
  try {
    const inflated = inflateSync(encoded, {
      info: true,
      maxOutputLength: Number(expectedBytes),
    });
    decoded = inflated.buffer;
    consumedBytes = inflated.engine.bytesWritten;
  } catch {
    throw new Error(`${path} does not contain decodable PNG pixel data.`);
  }
  if (consumedBytes !== encoded.length) {
    throw new Error(`${path} contains trailing PNG image data.`);
  }
  if (decoded.length !== Number(expectedBytes)) {
    throw new Error(`${path} has an invalid PNG pixel-data length.`);
  }

  const rowStride = Number(rowBytes) + 1;
  let previousAlpha = new Uint8Array(width);
  for (let row = 0; row < height; row += 1) {
    const filter = decoded[row * rowStride];
    if (filter > 4) {
      throw new Error(`${path} contains an invalid PNG row filter.`);
    }
    const currentAlpha = new Uint8Array(width);
    for (let pixel = 0; pixel < width; pixel += 1) {
      const filteredAlpha = decoded[
        row * rowStride + 1 + pixel * 4 + 3
      ];
      const left = pixel > 0 ? currentAlpha[pixel - 1] : 0;
      const above = row > 0 ? previousAlpha[pixel] : 0;
      const upperLeft = row > 0 && pixel > 0
        ? previousAlpha[pixel - 1]
        : 0;
      let predictor = 0;
      if (filter === 1) {
        predictor = left;
      } else if (filter === 2) {
        predictor = above;
      } else if (filter === 3) {
        predictor = Math.floor((left + above) / 2);
      } else if (filter === 4) {
        predictor = pngPaethPredictor(left, above, upperLeft);
      }
      const alpha = (filteredAlpha + predictor) & 0xFF;
      if (alpha !== 0xFF) {
        throw new Error(`${path} contains non-opaque PNG pixels.`);
      }
      currentAlpha[pixel] = alpha;
    }
    previousAlpha = currentAlpha;
  }
}

function pngPaethPredictor(left, above, upperLeft) {
  const estimate = left + above - upperLeft;
  const leftDistance = Math.abs(estimate - left);
  const aboveDistance = Math.abs(estimate - above);
  const upperLeftDistance = Math.abs(estimate - upperLeft);
  if (leftDistance <= aboveDistance && leftDistance <= upperLeftDistance) {
    return left;
  }
  return aboveDistance <= upperLeftDistance ? above : upperLeft;
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
      const evidenceSegments = path.split("/");
      if (evidenceSegments.some((segment) =>
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
      images.push({
        ...rendered,
        url: effectiveUrl,
      });
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
    if (style == null) {
      return false;
    }
    return !/^max-width\s*:\s*100%\s*;?$/iu.test(style.trim());
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

export async function main(environment = process.env) {
  const base = environment.GITHUB_BASE_SHA;
  const eventPath = environment.GITHUB_EVENT_PATH;
  const head = environment.GITHUB_HEAD_SHA;
  const repository = environment.GITHUB_REPOSITORY;
  const token = environment.GITHUB_TOKEN;
  if (!base || !eventPath || !head || !repository || !token) {
    throw new Error(
      "GITHUB_BASE_SHA, GITHUB_EVENT_PATH, GITHUB_HEAD_SHA, "
      + "GITHUB_REPOSITORY, and GITHUB_TOKEN are required.",
    );
  }

  const { records } = comparisonRecords(base, head);
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
  }

  const event = JSON.parse(readFileSync(eventPath, "utf8"));
  const renderedHtml = await renderMarkdown({
    body: event.pull_request?.body ?? "",
    repository,
    token,
  });
  errors.push(...validateRenderedProof({
    head,
    renderedHtml,
    repository,
    screenshotPaths,
  }));

  if (errors.length > 0) {
    throw new Error(errors.map((error) => `- ${error}`).join("\n"));
  }
  console.log(
    `Verified ${screenshotPaths.length} exact-head screenshot(s) `
    + `for ${shippedAppPaths.length} changed shipped app path(s).`,
  );
}

const invokedPath = process.argv[1];
if (invokedPath && fileURLToPath(import.meta.url) === invokedPath) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
