import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import {
  evaluateLicenseMetadata,
  renderThirdPartyNotices,
} from "./check-third-party-licenses.mjs";

const policy = JSON.parse(
  fs.readFileSync(new URL("../config/third-party-license-policy.json", import.meta.url)),
);

function component(coordinate, name, url = "") {
  return {
    coordinate,
    licenses: name ? [{ name, url }] : [],
    pomResolved: true,
  };
}

test("allows known permissive licenses and renders their inventory", () => {
  const result = evaluateLicenseMetadata([
    component("example:apache:1.0", "Apache-2.0"),
    component("example:mit:1.0", "MIT License"),
  ], policy);

  assert.equal(result.componentCount, 2);
  const notices = renderThirdPartyNotices(result, policy);
  assert.match(notices, /example:apache:1\.0/);
  assert.match(notices, /License: Apache-2\.0/);
});

test("fails closed for unknown published licenses", () => {
  assert.throws(
    () => evaluateLicenseMetadata([
      component("example:unknown:1.0", "Custom Public License"),
    ], policy),
    /unmapped license/,
  );
});

test("fails closed when a POM omits its license", () => {
  assert.throws(
    () => evaluateLicenseMetadata([component("example:missing:1.0", "")], policy),
    /no published POM license/,
  );
});

test("rejects prohibited licenses without a narrow exception", () => {
  assert.throws(
    () => evaluateLicenseMetadata([
      component("example:gpl:1.0", "GNU General Public License v3.0"),
    ], policy),
    /prohibited license GPL-3\.0-only/,
  );
});

test("requires an explicit Android commercial assertion for Junction releases", () => {
  const metadata = [component(
    "io.tryvital:vital-client:5.0.2",
    "GNU Affero General Public License v3.0",
  )];

  const structural = evaluateLicenseMetadata(metadata, policy);
  assert.equal(structural.pendingAssertions[0].confirmed, false);
  assert.throws(
    () => evaluateLicenseMetadata(metadata, policy, { release: true }),
    /commercial grant/,
  );
  assert.doesNotThrow(() => evaluateLicenseMetadata(metadata, policy, {
    release: true,
    environment: { MURPH_JUNCTION_ANDROID_COMMERCIAL_LICENSE_CONFIRMED: "true" },
  }));
});

test("does not carry the Junction exception to a new version", () => {
  assert.throws(
    () => evaluateLicenseMetadata([
      component(
        "io.tryvital:vital-client:5.0.3",
        "GNU Affero General Public License v3.0",
      ),
    ], policy),
    /prohibited license AGPL-3\.0-only/,
  );
});
