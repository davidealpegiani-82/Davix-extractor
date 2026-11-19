import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const source = readFileSync("android/app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt", "utf8");

assert.ok(source.includes("private fun importUris(uris: List<Uri>)"), "File import entry point must remain available");
assert.ok(source.includes("val parsedFiles = mutableListOf<List<TvChannel>>()"), "Multi-file import must retain parsed channels only");
assert.ok(source.includes("parsedFiles.add(found)"), "Parsed channel batches must be retained without source byte arrays");
assert.ok((source.match(/android.os.SystemClock.uptimeMillis\(\) - batchStartedAt < 12L/g) || []).length >= 3, "URL, file and saved-playlist imports must use time-bounded UI batches");
assert.ok((source.match(/importHandler.postDelayed\(this, 8L\)/g) || []).length >= 3, "Large imports must yield between UI batches");
assert.ok(source.includes("val knownCategoriesSnapshot = categories.toHashSet()"), "Category membership must be snapshotted before background import processing");
assert.ok(source.includes('"DAVIX-Playlist-Analysis"') && source.includes('"DAVIX-File-Classification"'), "CPU-heavy URL and file classification must run off the UI thread");
assert.ok(!source.includes("mutableListOf<Triple<String, ByteArray, List<TvChannel>>>"), "Do not retain every source file byte array alongside parsed channels");
assert.ok(source.includes("Executors.newSingleThreadExecutor"), "Archive writes must use a single ordered background writer");
assert.ok(source.includes("private data class ChannelArchiveEntry"), "Archive worker must receive immutable channel snapshots");
assert.ok(source.includes("archiveExecutor.execute"), "Archive serialization must be queued off the UI thread");
assert.ok(source.includes("archiveExecutor.shutdown()"), "Archive worker must drain queued writes on Activity destruction");
assert.ok(source.includes("private fun saveCategorySettings()") && source.includes("channelCategorySnapshot"), "Category mapping must be snapshotted before background serialization");
assert.ok(source.includes("private fun readImportBytes("), "Bounded streaming file reader must remain in place");
assert.ok(source.includes("Il file supera il limite di importazione di 64 MB."), "File import size guard must remain in place");

console.log("DAVIX import memory-retention checks passed.");
