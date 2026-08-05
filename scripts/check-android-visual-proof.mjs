#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { inflateSync } from "node:zlib";

const EVIDENCE_PREFIX = "app-store-assets/review-evidence/";
const GIT_MAX_BUFFER = 16 * 1024 * 1024;
const MAX_DECODED_BYTES = 128 * 1024 * 1024;
const MAX_SCREENSHOT_DIMENSION = 20_000;
const MIN_SCREENSHOT_WIDTH = 320;
const MIN_SCREENSHOT_HEIGHT = 568;
const SHIPPED_SOURCE_PATH = /^app\/src\/(?:main|release)\//u;
const VISIBLE_RESOURCE_PATH =
  /^app\/src\/(?:main|release)\/res\/(?:anim|animator|color|drawable|font|interpolator|layout|menu|mipmap|navigation|transition|values)(?:-[^/]+)?\//u;

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

export function isComposePath(path, contents) {
  return SHIPPED_SOURCE_PATH.test(path)
    && path.endsWith(".kt")
    && /androidx\.(?:[A-Za-z_][\w]*\.)*compose(?:\.|\b)/m.test(
      contents,
    );
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
      && (
        isVisibleResourcePath(basePath)
        || isComposePath(
          basePath,
          readRevisionFile("base", basePath),
        )
      )
    ) {
      paths.add(record.path);
    }
    if (record.status.startsWith("D")) {
      continue;
    }

    if (
      isVisibleResourcePath(record.path)
      || isComposePath(record.path, readRevisionFile("head", record.path))
    ) {
      paths.add(record.path);
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
  let paletteEntries = 0;
  let sawPlte = false;
  let sawIdat = false;
  let sawIend = false;
  let idatEnded = false;
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
    if (type === "PLTE") {
      if (sawPlte || sawIdat || length === 0 || length % 3 !== 0) {
        throw new Error(`${path} has an invalid PNG palette.`);
      }
      sawPlte = true;
      paletteEntries = length / 3;
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
  validateDecodedPixels({
    bitDepth,
    colorType,
    compressionMethod,
    filterMethod,
    height,
    idatChunks,
    interlaceMethod,
    paletteEntries,
    path,
    sawPlte,
    width,
  });
  return { height, width };
}

function validateDecodedPixels({
  bitDepth,
  colorType,
  compressionMethod,
  filterMethod,
  height,
  idatChunks,
  interlaceMethod,
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

  let decoded;
  try {
    decoded = inflateSync(Buffer.concat(idatChunks), {
      maxOutputLength: Number(expectedBytes),
    });
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

function imageUrls(html) {
  const urls = new Set();
  for (const image of html.matchAll(/<img\b[^>]*>/gi)) {
    for (const attribute of image[0].matchAll(
      /\b(?:src|data-canonical-src)=["']([^"']+)["']/gi,
    )) {
      urls.add(decodeHtml(attribute[1]));
    }
  }
  return urls;
}

function hasCompletedListValue(section, label) {
  const item = listItemText(section, label);
  if (!item) {
    return false;
  }
  const value = item.slice(label.length + 1).trim();
  return value.length > 0
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

  const urls = imageUrls(section);
  for (const path of screenshotPaths) {
    const expected =
      `https://raw.githubusercontent.com/${repository}/${head}/${path}`;
    if (!urls.has(expected)) {
      errors.push(`${path} must be embedded with its exact-head raw GitHub URL.`);
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

  const { comparisonBase, records } = comparisonRecords(base, head);
  const protectedPaths = changedProtectedPaths(records);
  if (protectedPaths.length > 0) {
    throw new Error(
      "Visual-proof control-plane changes require independent trusted review "
      + `and cannot self-certify: ${protectedPaths.join(", ")}`,
    );
  }
  const uiPaths = changedUiPathsAtRevisions(
    records,
    comparisonBase,
    head,
  );
  if (uiPaths.length === 0) {
    console.log("No user-visible Android UI changes detected.");
    return;
  }

  const screenshotPaths = changedScreenshotPaths(records);
  const errors = [];
  if (screenshotPaths.length === 0) {
    errors.push(
      `UI changed (${uiPaths.join(", ")}) but no PNG changed under ${EVIDENCE_PREFIX}.`,
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
    + `for ${uiPaths.length} changed UI path(s).`,
  );
}

const invokedPath = process.argv[1];
if (invokedPath && fileURLToPath(import.meta.url) === invokedPath) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
