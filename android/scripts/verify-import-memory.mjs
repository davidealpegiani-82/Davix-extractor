import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const source = readFileSync("android/app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt", "utf8");

assert.ok(source.includes("private fun importUris(uris: List<Uri>)"), "File import entry point must remain available");
assert.ok(source.includes("val parsedFiles = mutableListOf<List<TvChannel>>()"), "Multi-file import must retain parsed channels only");
assert.ok(source.includes("parsedFiles.add(found)"), "Parsed channel batches must be retained without source byte arrays");
assert.ok(source.includes("val batchSize = 200"), "Channel insertion must be split into bounded UI batches");
assert.ok(source.includes("importHandler.postDelayed(this, 1L)"), "Large imports must yield between UI batches");
assert.ok(source.includes("val knownCategories = categories.toHashSet()"), "Category deduplication must use indexed membership during bulk import");
assert.ok(!source.includes("mutableListOf<Triple<String, ByteArray, List<TvChannel>>>"), "Do not retain every source file byte array alongside parsed channels");
assert.ok(source.includes("private fun readImportBytes("), "Bounded streaming file reader must remain in place");
assert.ok(source.includes("Il file supera il limite di importazione di 64 MB."), "File import size guard must remain in place");

console.log("DAVIX import memory-retention checks passed.");
