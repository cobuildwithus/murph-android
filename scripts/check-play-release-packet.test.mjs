import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import test from "node:test";

import {
  extractKotlinSetMembers,
  extractManifestPermissions,
  inspectAndroidBundle,
  releaseManifestContract,
  validateArtifactManifest,
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

const manifestFacts = {
  application: {
    applicationId: "ai.withmurph.app",
    versionCode: 7,
    versionName: "1.2.3",
  },
  mergedManifestPermissions: ["android.permission.INTERNET"],
  releaseManifest: {
    applicationClass: "ai.withmurph.companion.MurphApplication",
    launcherActivity: "ai.withmurph.companion.MainActivity",
    forbiddenComponents: [
      "ai.withmurph.companion.visual.ScreenshotActivity",
      "io.tryvital.vitalhealthconnect.SyncBroadcastReceiver",
      "io.tryvital.vitalhealthconnect.workers.SyncOnExactAlarmService",
    ],
  },
};

function releaseManifest({
  packageName = "ai.withmurph.app",
  versionCode = 7,
  versionName = "1.2.3",
  debuggable = false,
  permissions = ["android.permission.INTERNET"],
  launcherAttributes = "",
  extraComponents = "",
} = {}) {
  const debuggableAttribute = debuggable ? ' android:debuggable="true"' : "";
  const permissionElements = permissions
    .map((permission) => `<uses-permission android:name="${permission}" />`)
    .join("\n");
  return `
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="${packageName}"
      android:versionCode="${versionCode}"
      android:versionName="${versionName}">
      ${permissionElements}
      <application android:name="ai.withmurph.companion.MurphApplication"${debuggableAttribute}>
        <activity android:name="ai.withmurph.companion.MainActivity"${launcherAttributes} />
        ${extraComponents}
      </application>
    </manifest>
  `;
}

test("artifact manifest must match the local release boundary", () => {
  const clean = releaseManifest();
  assert.deepEqual(
    validateArtifactManifest(clean, clean, manifestFacts),
    releaseManifestContract(clean),
  );

  for (const changed of [
    releaseManifest({ packageName: "ai.withmurph.other" }),
    releaseManifest({ versionCode: 8 }),
    releaseManifest({ versionName: "1.2.4" }),
    releaseManifest({ debuggable: true }),
    releaseManifest({
      permissions: [
        "android.permission.INTERNET",
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
      ],
    }),
  ]) {
    assert.throws(
      () => validateArtifactManifest(changed, clean, manifestFacts),
      /signed AAB manifest/,
    );
  }

  assert.throws(
    () => validateArtifactManifest(
      releaseManifest({ extraComponents: '<service android:name="example.Unexpected" />' }),
      clean,
      manifestFacts,
    ),
    /manifest contract drifted/,
  );
  assert.throws(
    () => validateArtifactManifest(
      releaseManifest({ launcherAttributes: ' android:exported="false"' }),
      clean,
      manifestFacts,
    ),
    /manifest contract drifted/,
  );
  assert.throws(
    () => validateArtifactManifest(
      releaseManifest({
        extraComponents:
          '<receiver android:name="io.tryvital.vitalhealthconnect.SyncBroadcastReceiver" />',
      }),
      clean,
      manifestFacts,
    ),
    /forbidden components/,
  );
});

test("bundletool validation is authoritative before its manifest is accepted", () => {
  const calls = [];
  const runCommand = (command, arguments_) => {
    calls.push([command, arguments_]);
    if (arguments_.includes("validate")) return "App Bundle is valid\n";
    return releaseManifest();
  };
  assert.match(
    inspectAndroidBundle("candidate.aab", "bundletool-classpath", runCommand),
    /ai\.withmurph\.app/,
  );
  assert.deepEqual(calls.map(([, arguments_]) => arguments_[3]), ["validate", "dump"]);

  assert.throws(
    () => inspectAndroidBundle("renamed-zip.aab", "bundletool-classpath", () => {
      throw new Error("not an App Bundle");
    }),
    /not a valid Android App Bundle/,
  );
});

test(
  "the pinned official bundletool validates and dumps a real debug App Bundle",
  { skip: !process.env.MURPH_BUNDLETOOL_TEST_BUNDLE },
  () => {
    const manifest = inspectAndroidBundle(
      process.env.MURPH_BUNDLETOOL_TEST_BUNDLE,
      process.env.MURPH_BUNDLETOOL_CLASSPATH,
    );
    const contract = releaseManifestContract(manifest);
    assert.equal(contract.packageName, "ai.withmurph.app.dev");
    assert.equal(contract.debuggable, true);
    assert.deepEqual(
      contract,
      releaseManifestContract(
        fs.readFileSync(process.env.MURPH_BUNDLETOOL_TEST_MANIFEST, "utf8"),
      ),
    );
  },
);

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
    artifactManifestSha256: crypto.createHash("sha256").update(mergedManifest).digest("hex"),
    releaseArtifactSha256: crypto.createHash("sha256").update(releaseArtifact).digest("hex"),
    releasePacketSha256: crypto.createHash("sha256").update(releasePacket).digest("hex"),
  };
  const assertions = {
    schema: 3,
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
    "artifactManifestSha256",
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
