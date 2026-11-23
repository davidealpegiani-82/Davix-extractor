import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const workflow = readFileSync(".github/workflows/android-apk.yml", "utf8");
const playerGradle = readFileSync("android/playerlite/build.gradle.kts", "utf8");

assert.match(workflow, /Prepare automatic app versions/, "release workflow must prepare versions for both apps");
assert.match(workflow, /\("extractor", "DAVIX_EXTRACTOR"\)/, "Extractor version must be incremented");
assert.match(workflow, /\("player", "DAVIX_PLAYER"\)/, "Player version must be incremented");
assert.match(workflow, /print\(f"\{prefix\}_VERSION_CODE=/, "Both generated versionCodes must be exported through GITHUB_ENV");
assert.match(workflow, /print\(f"\{prefix\}_VERSION_NAME=/, "Both generated versionNames must be exported through GITHUB_ENV");
assert.match(playerGradle, /System\.getenv\("DAVIX_PLAYER_VERSION_CODE"\)/, "Player Gradle config must consume CI versionCode");
assert.match(playerGradle, /System\.getenv\("DAVIX_PLAYER_VERSION_NAME"\)/, "Player Gradle config must consume CI versionName");
assert.match(playerGradle, /versionCode = playerVersionCode/, "Player APK must use the generated versionCode");
assert.match(playerGradle, /versionName = playerVersionName/, "Player APK must use the generated versionName");

console.log("DAVIX Extractor and Player automatic versioning checks passed.");
