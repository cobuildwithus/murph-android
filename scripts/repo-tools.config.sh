#!/usr/bin/env bash

export COBUILD_AUDIT_CONTEXT_PREFIX="murph-android-review"
export COBUILD_AUDIT_CONTEXT_TITLE="Murph Android Review Bundle"
export COBUILD_AUDIT_CONTEXT_REPO_LABEL="murph-android"
export COBUILD_AUDIT_CONTEXT_INCLUDE_TESTS_DEFAULT="1"
export COBUILD_AUDIT_CONTEXT_INCLUDE_DOCS_DEFAULT="1"
export COBUILD_AUDIT_CONTEXT_INCLUDE_CI_DEFAULT="1"
export COBUILD_AUDIT_CONTEXT_EXCLUDE_SENSITIVE="1"
export COBUILD_AUDIT_CONTEXT_ALWAYS_PATHS=$'AGENTS.md\nARCHITECTURE.md\nIMPLEMENTATION_STATUS.md\nREADME.md\nSOURCE_BASES.md\napp/build.gradle.kts\napp/proguard-rules.pro\nbuild.gradle.kts\nconfig/third-party-license-policy.json\ngradle.properties\ngradle/libs.versions.toml\ngradle/play-release.gradle.kts\ngradle/wrapper/gradle-wrapper.jar\ngradle/wrapper/gradle-wrapper.properties\ngradlew\ngradlew.bat\npackage.json\nplay/declarations/contacts.md\nplay/declarations/data-safety.md\nplay/declarations/health-apps.md\nplay/listing/en-US/full-description.txt\nplay/listing/en-US/release-notes-1.txt\nplay/listing/en-US/short-description.txt\nplay/listing/en-US/title.txt\nplay/operator-assertions.example.json\nplay/release-checklist.md\nplay/release-facts.json\npnpm-lock.yaml\nsettings.gradle.kts'
export COBUILD_AUDIT_CONTEXT_SCAN_SPECS=$'app/src/main\napp/src/debug\napp/src/release\napp/src/synthetic\nscripts'
export COBUILD_AUDIT_CONTEXT_TEST_SCAN_SPECS=$'app/src/test\napp/src/androidTest'
export COBUILD_AUDIT_CONTEXT_DOC_SCAN_SPECS=""
export COBUILD_AUDIT_CONTEXT_CI_SCAN_SPECS=".github/workflows"
