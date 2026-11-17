import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const files = {
  extractor: readFileSync("android/app/src/main/java/it/davidealpegiani/skyunoextractor/DavixUpdater.kt", "utf8"),
  player: readFileSync("android/playerlite/src/main/java/it/davidealpegiani/skylite/DavixUpdater.kt", "utf8"),
};

function section(source, start, end) {
  const from = source.indexOf(start);
  assert.notEqual(from, -1, `Missing source section: ${start}`);
  const to = source.indexOf(end, from + start.length);
  assert.notEqual(to, -1, `Missing end marker: ${end}`);
  return source.slice(from, to);
}

function assertBackgroundBefore(source, operation, label) {
  const work = source.indexOf("executor.execute");
  const action = source.indexOf(operation);
  assert.notEqual(work, -1, `${label}: executor.execute missing`);
  assert.notEqual(action, -1, `${label}: ${operation} missing`);
  assert.ok(work < action, `${label}: ${operation} must be dispatched after executor.execute`);
}

const extractorReceiver = section(files.extractor, "private val downloadReceiver", "fun onActivityResumed");
assertBackgroundBefore(extractorReceiver, "manager.query(", "Extractor download receiver");

const extractorCheck = section(files.extractor, "fun check(activity: Activity)", "private fun clearCompletedInstallIfApplied");
assertBackgroundBefore(extractorCheck, "hasPendingDownload()", "Extractor update check");

const extractorEnqueue = section(files.extractor, "private fun downloadUpdate(", "private fun showDownloadProgress");
assertBackgroundBefore(extractorEnqueue, "manager.enqueue(", "Extractor APK enqueue");

const extractorPolling = section(files.extractor, "private fun startProgressPolling(", "private fun stopProgressPolling");
assertBackgroundBefore(extractorPolling, "manager.query(", "Extractor progress polling");
assert.ok(extractorPolling.includes("1500L"), "Extractor polling interval must remain at least 1.5 seconds");
assert.ok(!extractorPolling.includes("400L"), "Extractor must not restore the 400 ms main-thread polling loop");

const extractorResume = section(files.extractor, "private fun resumePendingInstall(", "private fun dp(");
assertBackgroundBefore(extractorResume, "manager.query(", "Extractor pending-install recovery");

const playerReceiver = section(files.player, "private val downloadReceiver", "fun onActivityResumed");
assertBackgroundBefore(playerReceiver, "manager.query(", "Player download receiver");

const playerCheck = section(files.player, "fun check(activity: Activity)", "private fun hasPendingDownload");
assertBackgroundBefore(playerCheck, "hasPendingDownload()", "Player update check");

const playerEnqueue = section(files.player, "private fun downloadUpdate(", "private fun resumePendingInstall");
assertBackgroundBefore(playerEnqueue, "manager.enqueue(", "Player APK enqueue");

const playerResume = section(files.player, "private fun resumePendingInstall(", "private fun installOrRequestPermission");
assertBackgroundBefore(playerResume, "manager.query(", "Player pending-install recovery");

console.log("DAVIX updater background-I/O checks passed.");
