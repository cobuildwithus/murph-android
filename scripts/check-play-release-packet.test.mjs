import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
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
  verifyAndroidBundleSigners,
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
    minSdk: 28,
    targetSdk: 36,
    versionCode: 7,
    versionName: "1.2.3",
  },
  mergedManifestPermissionAttributes: [
    { "android:name": "android.permission.INTERNET" },
  ],
  releaseManifest: {
    applicationClass: "ai.withmurph.companion.MurphApplication",
    applicationSecurityAttributes: {
      "android:allowBackup": "false",
      "android:dataExtractionRules": "@xml/data_extraction_rules",
      "android:debuggable": null,
      "android:directBootAware": null,
      "android:fullBackupContent": "false",
      "android:networkSecurityConfig": null,
      "android:taskAffinity": null,
      "android:testOnly": null,
      "android:usesCleartextTraffic": null,
    },
    usesSdkAttributes: {
      "android:minSdkVersion": "28",
      "android:targetSdkVersion": "36",
    },
    declaredPermissions: [
      {
        "android:name": "ai.withmurph.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        "android:protectionLevel": "0x2",
      },
    ],
    permissionGroups: [],
    permissionTrees: [],
    launcherActivity: "ai.withmurph.companion.MainActivity",
    forbiddenComponents: [
      "ai.withmurph.companion.visual.ScreenshotActivity",
      "io.tryvital.vitalhealthconnect.SyncBroadcastReceiver",
      "io.tryvital.vitalhealthconnect.workers.SyncOnExactAlarmService",
    ],
    componentSecurityAttributes: [
      {
        componentType: "activities",
        component: "ai.withmurph.companion.MainActivity",
        attributes: {
          "android:exported": "true",
          "android:taskAffinity": null,
        },
      },
    ],
    directBootAwareComponents: [],
    requiredIntentFilters: [
      {
        componentType: "activities",
        component: "ai.withmurph.companion.MainActivity",
        actions: ["android.intent.action.MAIN"],
        categories: ["android.intent.category.LAUNCHER"],
        data: [],
      },
    ],
  },
};

function releaseManifest({
  packageName = "ai.withmurph.app",
  versionCode = 7,
  versionName = "1.2.3",
  minSdk = 28,
  targetSdk = 36,
  maxSdk = null,
  debuggable = false,
  permissions = ["android.permission.INTERNET"],
  permissionAttributes = "",
  allowBackup = "false",
  dataExtractionRules = "@xml/data_extraction_rules",
  fullBackupContent = "false",
  applicationAttributes = "",
  permissionProtectionLevel = "signature",
  launcherAttributes = ' android:exported="true"',
  launcherIntentFilters = `
    <intent-filter>
      <action android:name="android.intent.action.MAIN" />
      <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
  `,
  extraComponents = "",
} = {}) {
  const debuggableAttribute = debuggable ? ' android:debuggable="true"' : "";
  const permissionElements = permissions
    .map((permission, index) =>
      `<uses-permission android:name="${permission}"${index === 0 ? permissionAttributes : ""} />`
    )
    .join("\n");
  const maxSdkAttribute = maxSdk === null ? "" : ` android:maxSdkVersion="${maxSdk}"`;
  return `
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="${packageName}"
      android:versionCode="${versionCode}"
      android:versionName="${versionName}">
      <uses-sdk
        android:minSdkVersion="${minSdk}"
        android:targetSdkVersion="${targetSdk}"${maxSdkAttribute} />
      <permission
        android:name="ai.withmurph.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        android:protectionLevel="${permissionProtectionLevel}" />
      ${permissionElements}
      <application
        android:name="ai.withmurph.companion.MurphApplication"
        android:allowBackup="${allowBackup}"
        android:dataExtractionRules="${dataExtractionRules}"
        android:fullBackupContent="${fullBackupContent}"${debuggableAttribute}${applicationAttributes}>
        <activity android:name="ai.withmurph.companion.MainActivity"${launcherAttributes}>
          ${launcherIntentFilters}
        </activity>
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
    releaseManifest({ minSdk: 29 }),
    releaseManifest({ targetSdk: 35 }),
    releaseManifest({ maxSdk: 35 }),
    releaseManifest({ debuggable: true }),
    releaseManifest({ allowBackup: "true" }),
    releaseManifest({ dataExtractionRules: "@xml/other_extraction_rules" }),
    releaseManifest({ fullBackupContent: "true" }),
    releaseManifest({ applicationAttributes: ' android:testOnly="true"' }),
    releaseManifest({ permissionProtectionLevel: "normal" }),
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

  for (const changed of [
    releaseManifest({ permissionAttributes: ' android:maxSdkVersion="32"' }),
    releaseManifest({
      permissionAttributes: ' android:usesPermissionFlags="neverForLocation"',
    }),
    releaseManifest({ applicationAttributes: ' android:taskAffinity="example.task"' }),
    releaseManifest({ applicationAttributes: ' android:testOnly="true"' }),
    releaseManifest({ permissionProtectionLevel: "normal" }),
    releaseManifest({
      launcherAttributes: ' android:exported="true" android:directBootAware="true"',
    }),
  ]) {
    assert.throws(
      () => validateArtifactManifest(changed, changed, manifestFacts),
      /signed AAB manifest/,
    );
  }

  assert.throws(
    () => validateArtifactManifest(
      releaseManifest({ permissionAttributes: ' android:maxSdkVersion="32"' }),
      clean,
      manifestFacts,
    ),
    /permission attributes drifted/,
  );
  assert.throws(
    () => validateArtifactManifest(
      releaseManifest({ launcherIntentFilters: "" }),
      clean,
      manifestFacts,
    ),
    /required intent filter/,
  );
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
    /android:exported drifted/,
  );
  assert.throws(
    () => validateArtifactManifest(
      releaseManifest({
        launcherAttributes: ' android:exported="true" android:taskAffinity="example.task"',
      }),
      clean,
      manifestFacts,
    ),
    /unexpectedly defines android:taskAffinity/,
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

test("manifest parsing rejects document types and external entities", () => {
  assert.throws(
    () => releaseManifestContract(`
      <!DOCTYPE manifest [<!ENTITY probe SYSTEM "file:///etc/passwd">]>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        &probe;
      </manifest>
    `),
    /security contract could not be parsed/,
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
    assert.equal(contract.applicationSecurityAttributes["android:debuggable"], "true");
    assert.deepEqual(
      contract,
      releaseManifestContract(
        fs.readFileSync(process.env.MURPH_BUNDLETOOL_TEST_MANIFEST, "utf8"),
      ),
    );
  },
);

test(
  "every artifact entry must share the approved upload signer",
  { skip: !process.env.MURPH_KEYTOOL_EXECUTABLE },
  () => {
    const temporaryDirectory = fs.mkdtempSync(
      path.join(os.tmpdir(), "murph-play-signers-"),
    );
    const contents = path.join(temporaryDirectory, "contents");
    const artifact = path.join(temporaryDirectory, "candidate.aab");
    const approvedKeystore = path.join(temporaryDirectory, "approved.p12");
    const secondKeystore = path.join(temporaryDirectory, "second.p12");
    const password = "changeit";
    const run = (executable, arguments_, options = {}) => execFileSync(
      executable,
      arguments_,
      { stdio: ["ignore", "pipe", "pipe"], ...options },
    );
    const createKey = (keystore, alias) => run(
      process.env.MURPH_KEYTOOL_EXECUTABLE,
      [
        "-genkeypair",
        "-alias",
        alias,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "1",
        "-dname",
        `CN=${alias}`,
        "-keystore",
        keystore,
        "-storetype",
        "PKCS12",
        "-storepass",
        password,
        "-keypass",
        password,
        "-noprompt",
      ],
    );
    const sign = (keystore, alias) => run(
      process.env.MURPH_JARSIGNER_EXECUTABLE,
      ["-keystore", keystore, "-storepass", password, artifact, alias],
    );
    try {
      fs.mkdirSync(path.join(contents, "base", "assets", "murph-play"), {
        recursive: true,
      });
      fs.mkdirSync(path.join(contents, "META-INF", "services"), { recursive: true });
      fs.writeFileSync(path.join(contents, "app-code.bin"), "approved app code\n");
      fs.writeFileSync(
        path.join(contents, "META-INF", "services", "example.Service"),
        "example.ApprovedImplementation\n",
      );
      fs.writeFileSync(
        path.join(contents, "base", "assets", "murph-play", "source.properties"),
        "schema=1\nsourceHead=example\n",
      );
      run(process.env.MURPH_JAR_EXECUTABLE, [
        "--create",
        "--file",
        artifact,
        "-C",
        contents,
        ".",
      ]);
      createKey(approvedKeystore, "approved");
      sign(approvedKeystore, "approved");
      const approvedCertificate = run(process.env.MURPH_KEYTOOL_EXECUTABLE, [
        "-exportcert",
        "-alias",
        "approved",
        "-keystore",
        approvedKeystore,
        "-storepass",
        password,
      ]);
      const approvedFingerprint = new crypto.X509Certificate(approvedCertificate)
        .fingerprint256.replaceAll(":", "").toLowerCase();

      assert.doesNotThrow(() => verifyAndroidBundleSigners(artifact, approvedFingerprint));
      assert.throws(
        () => verifyAndroidBundleSigners(artifact, "00".repeat(32)),
        /approved upload certificate/,
      );

      fs.writeFileSync(
        path.join(contents, "META-INF", "services", "example.Service"),
        "example.UnsignedReplacement\n",
      );
      run(process.env.MURPH_JAR_EXECUTABLE, [
        "--update",
        "--file",
        artifact,
        "-C",
        contents,
        "META-INF/services/example.Service",
      ]);
      assert.throws(
        () => verifyAndroidBundleSigners(artifact, approvedFingerprint),
        /completely signed/,
      );
      sign(approvedKeystore, "approved");
      assert.doesNotThrow(() => verifyAndroidBundleSigners(artifact, approvedFingerprint));

      fs.writeFileSync(path.join(contents, "META-INF", "SIG-"), "custom control\n");
      run(process.env.MURPH_JAR_EXECUTABLE, [
        "--update",
        "--file",
        artifact,
        "-C",
        contents,
        "META-INF/SIG-",
      ]);
      assert.doesNotThrow(() => verifyAndroidBundleSigners(artifact, approvedFingerprint));

      fs.writeFileSync(
        path.join(contents, "META-INF", "SIG-CUSTOM.A1"),
        "custom signature control\n",
      );
      run(process.env.MURPH_JAR_EXECUTABLE, [
        "--update",
        "--file",
        artifact,
        "-C",
        contents,
        "META-INF/SIG-CUSTOM.A1",
      ]);
      assert.doesNotThrow(() => verifyAndroidBundleSigners(artifact, approvedFingerprint));

      fs.writeFileSync(
        path.join(contents, "META-INF", "SIG-PROVENANCE.BADX"),
        "ordinary unsigned content\n",
      );
      run(process.env.MURPH_JAR_EXECUTABLE, [
        "--update",
        "--file",
        artifact,
        "-C",
        contents,
        "META-INF/SIG-PROVENANCE.BADX",
      ]);
      assert.throws(
        () => verifyAndroidBundleSigners(artifact, approvedFingerprint),
        /completely signed/,
      );
      sign(approvedKeystore, "approved");
      assert.doesNotThrow(() => verifyAndroidBundleSigners(artifact, approvedFingerprint));

      fs.writeFileSync(
        path.join(contents, "base", "assets", "murph-play", "source.properties"),
        "schema=1\nsourceHead=spoofed-current-head\n",
      );
      run(process.env.MURPH_JAR_EXECUTABLE, [
        "--update",
        "--file",
        artifact,
        "-C",
        contents,
        "base/assets/murph-play/source.properties",
      ]);
      assert.throws(
        () => verifyAndroidBundleSigners(artifact, approvedFingerprint),
        /completely signed/,
      );

      createKey(secondKeystore, "second");
      sign(secondKeystore, "second");
      assert.throws(
        () => verifyAndroidBundleSigners(artifact, approvedFingerprint),
        /completely signed/,
      );
    } finally {
      fs.rmSync(temporaryDirectory, { force: true, recursive: true });
    }
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
