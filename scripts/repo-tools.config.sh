#!/usr/bin/env bash

export COBUILD_AUDIT_CONTEXT_PREFIX="murph-android-review"
export COBUILD_AUDIT_CONTEXT_TITLE="Murph Android Review Bundle"
export COBUILD_AUDIT_CONTEXT_REPO_LABEL="murph-android"
export COBUILD_AUDIT_CONTEXT_INCLUDE_TESTS_DEFAULT="1"
export COBUILD_AUDIT_CONTEXT_INCLUDE_DOCS_DEFAULT="1"
export COBUILD_AUDIT_CONTEXT_INCLUDE_CI_DEFAULT="0"
export COBUILD_AUDIT_CONTEXT_EXCLUDE_SENSITIVE="1"
export COBUILD_AUDIT_CONTEXT_ALWAYS_PATHS=$'AGENTS.md\nARCHITECTURE.md\nIMPLEMENTATION_STATUS.md\nREADME.md\nSOURCE_BASES.md\napp/build.gradle.kts\napp/proguard-rules.pro\nbuild.gradle.kts\ngradle.properties\ngradle/libs.versions.toml\ngradle/wrapper/gradle-wrapper.properties\ngradlew\ngradlew.bat\npackage.json\npnpm-lock.yaml\nsettings.gradle.kts'
export COBUILD_AUDIT_CONTEXT_SCAN_SPECS=$'app/src/main\napp/src/debug\nscripts'
export COBUILD_AUDIT_CONTEXT_TEST_SCAN_SPECS=$'app/src/test\napp/src/androidTest'
export COBUILD_AUDIT_CONTEXT_DOC_SCAN_SPECS=""
export COBUILD_AUDIT_CONTEXT_CI_SCAN_SPECS=""
