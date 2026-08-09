#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

function normalized(value) {
  return String(value ?? "").trim().toLowerCase().replace(/\s+/g, " ");
}

function matchingRule(rules, coordinate) {
  const matches = rules.filter((rule) => new RegExp(rule.coordinatePattern).test(coordinate));
  if (matches.length > 1) {
    throw new Error(`License policy has overlapping rules for ${coordinate}.`);
  }
  return matches[0] ?? null;
}

function publishedLicenses(component, policy) {
  if (!Array.isArray(component.licenses) || component.licenses.length === 0) {
    const fallback = matchingRule(policy.missingPomFallbacks ?? [], component.coordinate);
    if (!fallback) {
      throw new Error(
        `${component.coordinate} has no published POM license and no explicit policy fallback.`,
      );
    }
    return [{
      spdx: fallback.spdx,
      name: fallback.spdx,
      url: fallback.evidence,
      source: "policy fallback",
    }];
  }

  return component.licenses.map((license) => {
    const spdx = policy.licenseAliases[normalized(license.name)];
    if (!spdx) {
      throw new Error(
        `${component.coordinate} publishes an unmapped license: ` +
          `${JSON.stringify(license.name || license.url || "<empty>")}.`,
      );
    }
    return {
      spdx,
      name: String(license.name ?? "").trim(),
      url: String(license.url ?? "").trim(),
      source: "published POM",
    };
  });
}

export function evaluateLicenseMetadata(metadata, policy, options = {}) {
  if (!Array.isArray(metadata)) throw new Error("Dependency metadata must be an array.");
  if (policy.schema !== 1) throw new Error("Unsupported third-party license policy schema.");

  const release = options.release === true;
  const environment = options.environment ?? {};
  const allowed = new Set(policy.allowedSpdx ?? []);
  const trackedTerms = new Set(policy.trackedNonOssTerms ?? []);
  const prohibited = new Set(policy.prohibitedSpdx ?? []);
  const coordinates = new Set();
  const entries = [];
  const pendingAssertions = new Map();

  for (const component of metadata) {
    const coordinate = String(component.coordinate ?? "").trim();
    if (!/^[^:\s]+:[^:\s]+:[^:\s]+$/.test(coordinate)) {
      throw new Error(`Invalid dependency coordinate: ${JSON.stringify(coordinate)}.`);
    }
    if (coordinates.has(coordinate)) throw new Error(`Duplicate dependency: ${coordinate}.`);
    coordinates.add(coordinate);

    for (const license of publishedLicenses(component, policy)) {
      const exception = matchingRule(policy.commercialExceptions ?? [], coordinate);
      const usesException = exception?.publishedSpdx === license.spdx;

      if (prohibited.has(license.spdx) && !usesException) {
        throw new Error(`${coordinate} uses prohibited license ${license.spdx}.`);
      }
      if (!allowed.has(license.spdx) && !trackedTerms.has(license.spdx) && !usesException) {
        throw new Error(`${coordinate} uses license ${license.spdx}, which is not allowed.`);
      }

      let disposition = trackedTerms.has(license.spdx) ? "tracked non-OSS terms" : "allowed";
      if (usesException) {
        const assertion = exception.assertionEnvironment;
        const confirmed = normalized(environment[assertion]) === "true";
        pendingAssertions.set(assertion, exception.label);
        disposition = confirmed ? "commercial grant confirmed" : "commercial grant required";
        if (release && !confirmed) {
          throw new Error(
            `${coordinate} publishes ${license.spdx}. Release is blocked until ` +
              `${assertion}=true confirms Android coverage under the commercial grant.`,
          );
        }
      }

      entries.push({ coordinate, ...license, disposition });
    }
  }

  return {
    componentCount: coordinates.size,
    entries: entries.sort((left, right) =>
      left.coordinate.localeCompare(right.coordinate) || left.spdx.localeCompare(right.spdx)),
    pendingAssertions: [...pendingAssertions].map(([environmentName, label]) => ({
      environmentName,
      label,
      confirmed: normalized(environment[environmentName]) === "true",
    })),
  };
}

export function renderThirdPartyNotices(result, policy) {
  const lines = [
    "MURPH ANDROID THIRD-PARTY DEPENDENCY INVENTORY",
    "",
    "Generated from the Gradle-resolved releaseRuntimeClasspath and published Maven POM metadata.",
    "Do not edit this generated file. A release must also pass the commercial-license assertions.",
    "",
  ];

  for (const entry of result.entries) {
    const canonicalUrl = policy.licenseUrls?.[entry.spdx];
    lines.push(entry.coordinate);
    lines.push(`  License: ${entry.spdx}`);
    lines.push(`  Source: ${entry.source}`);
    lines.push(`  Disposition: ${entry.disposition}`);
    if (entry.name && entry.name !== entry.spdx) lines.push(`  Published name: ${entry.name}`);
    if (entry.url) lines.push(`  Published URL: ${entry.url}`);
    if (canonicalUrl && canonicalUrl !== entry.url) lines.push(`  License terms: ${canonicalUrl}`);
    lines.push("");
  }

  return `${lines.join("\n").trimEnd()}\n`;
}

function parseArguments(argv) {
  const result = { release: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--release") {
      result.release = true;
      continue;
    }
    if (["--metadata", "--policy", "--notices"].includes(argument)) {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) throw new Error(`${argument} requires a path.`);
      result[argument.slice(2)] = value;
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${argument}`);
  }
  if (!result.metadata || !result.policy) {
    throw new Error("Usage: check-third-party-licenses --metadata FILE --policy FILE [--notices FILE] [--release]");
  }
  return result;
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const policy = readJson(options.policy);
  const result = evaluateLicenseMetadata(readJson(options.metadata), policy, {
    release: options.release,
    environment: process.env,
  });

  if (options.notices) {
    fs.mkdirSync(path.dirname(options.notices), { recursive: true });
    fs.writeFileSync(options.notices, renderThirdPartyNotices(result, policy));
  }

  const pending = result.pendingAssertions.filter((assertion) => !assertion.confirmed);
  const suffix = pending.length === 0
    ? ""
    : `; ${pending.length} commercial release assertion pending`;
  process.stdout.write(
    `Third-party licenses verified for ${result.componentCount} release components${suffix}.\n`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`Third-party license verification failed: ${error.message}\n`);
    process.exitCode = 1;
  }
}
