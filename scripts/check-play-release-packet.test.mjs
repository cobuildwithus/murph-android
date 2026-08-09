import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";

import {
  extractKotlinSetMembers,
  extractManifestPermissions,
  validateArtifactSourceMetadata,
  validateOperatorAssertions,
  validateReleasePacket,
} from "./check-play-release-packet.mjs";

test("the checked-in source packet matches the app", () => {
  assert.doesNotThrow(() => validateReleasePacket());
});

test("manifest extraction distinguishes requested and explicitly removed permissions", () => {
  const result = extractManifestPermissions(`
    <uses-permission android:name="example.REQUESTED" />
    <uses-permission android:name="example.REMOVED" tools:node="remove" />
  `);
  assert.deepEqual(result, {
    requested: ["example.REQUESTED"],
    removed: ["example.REMOVED"],
  });
});

test("Kotlin set extraction binds the named centralized set without visibility or layout assumptions", () => {
  const source = `
    // val requestedReadResources = setOf(VitalResource.Decoy)
    private val unrelated = setOf(VitalResource.Steps)

    internal
    val requestedReadResources = setOf(
      VitalResource.Sleep,
      VitalResource.Workout,
      /* VitalResource.CommentedOut, */
      VitalResource.Activity,
    )

    internal fun nextDeclaration() = Unit
  `;

  assert.deepEqual(
    extractKotlinSetMembers(source, "requestedReadResources", "VitalResource"),
    ["Activity", "Sleep", "Workout"],
  );
});

test("Kotlin set extraction rejects aliases, helpers, and initializer suffixes", () => {
  for (const source of [
    `
      val alias = VitalResource.Steps
      val scope = setOf(VitalResource.Sleep, alias)
      val next = Unit
    `,
    `
      val scope = setOf(VitalResource.Sleep, helper(VitalResource.Steps))
      val next = Unit
    `,
  ]) {
    assert.throws(
      () => extractKotlinSetMembers(source, "scope", "VitalResource"),
      /only direct/,
    );
  }

  for (const source of [
    `val scope = setOf(VitalResource.Sleep) + setOf(VitalResource.Steps)\nval next = Unit`,
    `
      val scope = setOf(VitalResource.Sleep)
        .plus(setOf(VitalResource.Steps))
      val next = Unit
    `,
  ]) {
    assert.throws(
      () => extractKotlinSetMembers(source, "scope", "VitalResource"),
      /unsupported (?:suffix|continuation)/,
    );
  }
});

test("Kotlin set extraction rejects a missing or ambiguous owner", () => {
  assert.throws(
    () => extractKotlinSetMembers("val other = setOf(VitalResource.Sleep)", "scope", "VitalResource"),
    /found 0/,
  );
  assert.throws(
    () => extractKotlinSetMembers(
      "val scope = setOf(VitalResource.Sleep)\nval scope = setOf(VitalResource.Workout)",
      "scope",
      "VitalResource",
    ),
    /found 2/,
  );
});

test("operator assertions bind the submission to the artifact, manifest, and Console packet", () => {
  const mergedManifest = "<manifest />\n";
  const releaseArtifact = Buffer.from("exact release artifact");
  const releasePacket = Buffer.from("exact Console packet");
  const facts = {
    application: { versionCode: 7, versionName: "1.2.3" },
    requiredOperatorAssertions: [
      "canonicalAccountAdmissionVerified",
      "accountOnboardingAndConsentDataSafetyReviewed",
      "systemTimeZoneTaxonomyDecided",
      "accountDeletionFlowVerified",
    ],
  };
  const evidence = {
    sourceHead: "1234567890abcdef1234567890abcdef12345678",
    mergedManifestSha256: crypto.createHash("sha256").update(mergedManifest).digest("hex"),
    releaseArtifactSha256: crypto.createHash("sha256").update(releaseArtifact).digest("hex"),
    releasePacketSha256: crypto.createHash("sha256").update(releasePacket).digest("hex"),
  };
  const assertions = {
    schema: 2,
    releaseVersionCode: 7,
    releaseVersionName: "1.2.3",
    googlePlayPolicyReviewedAt: "2026-08-01",
    ...evidence,
    canonicalAccountAdmissionVerified: true,
    accountOnboardingAndConsentDataSafetyReviewed: true,
    systemTimeZoneTaxonomyDecided: true,
    accountDeletionFlowVerified: true,
  };

  assert.doesNotThrow(() => validateOperatorAssertions(
    assertions,
    facts,
    evidence,
    new Date("2026-08-05T12:00:00Z"),
  ));
  for (const field of facts.requiredOperatorAssertions) {
    assert.throws(
      () => validateOperatorAssertions(
        { ...assertions, [field]: false },
        facts,
        evidence,
        new Date("2026-08-05T12:00:00Z"),
      ),
      new RegExp(`not confirmed: ${field}`),
    );
  }
  assert.throws(
    () => validateOperatorAssertions(
      { ...assertions, googlePlayPolicyReviewedAt: "2026-08-06" },
      facts,
      evidence,
      new Date("2026-08-05T12:00:00Z"),
    ),
    /not future-dated/,
  );
  for (const field of [
    "sourceHead",
    "mergedManifestSha256",
    "releaseArtifactSha256",
    "releasePacketSha256",
  ]) {
    assert.throws(
      () => validateOperatorAssertions(
        { ...assertions, [field]: "wrong" },
        facts,
        evidence,
        new Date("2026-08-05T12:00:00Z"),
      ),
      /(?:source commit|SHA-256)/,
    );
  }
});

test("signed bundle provenance must match the exact clean source commit", () => {
  const sourceHead = "1234567890abcdef1234567890abcdef12345678";
  const configurationSha256 = "ab".repeat(32);
  assert.doesNotThrow(() => validateArtifactSourceMetadata(
    `schema=1\nsourceHead=${sourceHead}\nworkingTreeClean=true\n` +
      `configurationSha256=${configurationSha256}\n`,
    sourceHead,
    configurationSha256,
  ));
  for (const metadata of [
    `schema=1\nsourceHead=${sourceHead}\nworkingTreeClean=false\n` +
      `configurationSha256=${configurationSha256}\n`,
    "schema=1\nsourceHead=abcdefabcdefabcdefabcdefabcdefabcdefabcd\n" +
      `workingTreeClean=true\nconfigurationSha256=${configurationSha256}\n`,
    `schema=1\nsourceHead=${sourceHead}\nworkingTreeClean=true\n` +
      `configurationSha256=${"cd".repeat(32)}\n`,
    "",
  ]) {
    assert.throws(
      () => validateArtifactSourceMetadata(metadata, sourceHead, configurationSha256),
      /exact clean source commit/,
    );
  }
  assert.throws(
    () => validateArtifactSourceMetadata("", sourceHead, "invalid"),
    /public-configuration digest/,
  );
});
