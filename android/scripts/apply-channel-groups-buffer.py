#!/usr/bin/env python3
from pathlib import Path

def patch_extractor():
    path = Path("android/app/src/main/java/it/davidealpegiani/skyunoextractor/MainActivity.kt")
    text = path.read_text(encoding="utf-8")
    if "showChannelVariants" in text and "ChannelGrouping.groupEntries" in text:
        print("Extractor already patched")
        return False

    old_buf = (
        "setBufferDurationsMs(\n"
        "                15_000, // buffer minimo\n"
        "                60_000, // limite massimo: evita riserve eccessive\n"
        "                5_000, // avvio iniziale piu rapido\n"
        "                10_000 // ripresa dopo esaurimento del buffer\n"
        "            )\n"
        "            .setTargetBufferBytes(48 * 1024 * 1024)\n"
        "            .setPrioritizeTimeOverSizeThresholds(false)"
    )
    new_buf = (
        "setBufferDurationsMs(\n"
        "                25_000, // buffer minimo piu alto\n"
        "                120_000, // riserva massima piu ampia\n"
        "                3_000, // avvio non troppo lento\n"
        "                12_000 // ripresa dopo rebuffer con margine\n"
        "            )\n"
        "            .setTargetBufferBytes(64 * 1024 * 1024)\n"
        "            .setPrioritizeTimeOverSizeThresholds(true)"
    )
    if old_buf not in text:
        # try original accented version
        old_buf2 = old_buf.replace("piu rapido", "pi\u00f9 rapido")
        if old_buf2 in text:
            old_buf = old_buf2
            new_buf = new_buf.replace("piu alto", "pi\u00f9 alto").replace("piu ampia", "pi\u00f9 ampia")
        else:
            raise SystemExit("buffer Extractor non trovato")
    text = text.replace(old_buf, new_buf, 1)

    old_live = (
        "MediaItem.LiveConfiguration.Builder()\n"
        "                        .setTargetOffsetMs(45_000)\n"
        "                        .setMinOffsetMs(30_000)\n"
        "                        .setMaxOffsetMs(90_000)"
    )
    new_live = (
        "MediaItem.LiveConfiguration.Builder()\n"
        "                        .setTargetOffsetMs(55_000)\n"
        "                        .setMinOffsetMs(35_000)\n"
        "                        .setMaxOffsetMs(120_000)"
    )
    if old_live in text:
        text = text.replace(old_live, new_live)

    old_loop = (
        "node.entries.take(rowsToRender).forEach { entry ->\n"
        "                        renderedChannelRows++\n"
        "                        val holder = LinearLayout(this).apply {\n"
        "                            setPadding(dp(10 + (depth + 1) * 14), 0, 0, 0)\n"
        "                        }\n"
        "                        addChannelRow(entry.index, entry.value, holder)"
    )
    new_loop = (
        "val grouped = ChannelGrouping.groupEntries(node.entries)\n"
        "                    val groupsToRender = grouped.take(rowsToRender)\n"
        "                    groupsToRender.forEach { group ->\n"
        "                        renderedChannelRows++\n"
        "                        val primaryIndex = group.indices.first()\n"
        "                        val primary = channels[primaryIndex]\n"
        "                        val holder = LinearLayout(this).apply {\n"
        "                            setPadding(dp(10 + (depth + 1) * 14), 0, 0, 0)\n"
        "                        }\n"
        "                        addChannelRow(primaryIndex, primary, holder, group.indices, group.displayName)"
    )
    if old_loop not in text:
        raise SystemExit("loop entries non trovato")
    text = text.replace(old_loop, new_loop, 1)

    old_sig = "fun addChannelRow(index: Int, channel: TvChannel, target: LinearLayout) {"
    new_sig = "fun addChannelRow(index: Int, channel: TvChannel, target: LinearLayout, variantIndices: List<Int> = listOf(index), displayName: String = channel.name) {"
    if old_sig not in text:
        raise SystemExit("addChannelRow non trovato")
    text = text.replace(old_sig, new_sig, 1)

    old_play = (
        "val play = Button(this).apply {\n"
        "                tag = channel.name\n"
        "                text = channel.name\n"
        "                isAllCaps = false\n"
        "                textSize = 13f\n"
        "                maxLines = 2\n"
        "                ellipsize = android.text.TextUtils.TruncateAt.END\n"
        "                minWidth = 0\n"
        "                gravity = Gravity.CENTER_VERTICAL or Gravity.START\n"
        "                setPadding(dp(5), 0, dp(5), 0)\n"
        "                setTextColor(if (isPlayingChannel) CYAN else Color.WHITE)\n"
        "                setBackgroundColor(Color.TRANSPARENT)\n"
        "                contentDescription = if (isPlayingChannel) \"${channel.name}, in riproduzione\" else \"Riproduci ${channel.name}\"\n"
        "                if (isPlayingChannel) playingChannelButton = this\n"
        "                setOnClickListener { playChannel(index, this) }\n"
        "            }"
    )
    # Fix: contentDescription line uses Kotlin string templates - match file exactly
    old_play = None
    start = text.find("val play = Button(this).apply {")
    if start < 0:
        raise SystemExit("play button start non trovato")
    end = text.find("setOnClickListener { playChannel(index, this) }", start)
    if end < 0:
        raise SystemExit("play click non trovato")
    end = text.find("}", end) + 1
    # include closing of apply block - find next line with only spaces and }
    block_end = text.find("\n            }", end)
    if block_end < 0:
        raise SystemExit("play block end non trovato")
    block_end = block_end + len("\n            }")
    old_block = text[start:block_end]
    new_block = (
        "val variantCount = variantIndices.size\n"
        "            val label = if (variantCount > 1) \"$displayName · $variantCount versioni\" else displayName\n"
        "            val isGroupPlaying = variantIndices.any { channels.getOrNull(it)?.url == currentPlayingChannelUrl }\n"
        "            val play = Button(this).apply {\n"
        "                tag = displayName\n"
        "                text = label\n"
        "                isAllCaps = false\n"
        "                textSize = 13f\n"
        "                maxLines = 2\n"
        "                ellipsize = android.text.TextUtils.TruncateAt.END\n"
        "                minWidth = 0\n"
        "                gravity = Gravity.CENTER_VERTICAL or Gravity.START\n"
        "                setPadding(dp(5), 0, dp(5), 0)\n"
        "                setTextColor(if (isGroupPlaying || isPlayingChannel) CYAN else Color.WHITE)\n"
        "                setBackgroundColor(Color.TRANSPARENT)\n"
        "                contentDescription = if (variantCount > 1) \"Scegli versione di $displayName\" else if (isPlayingChannel) \"$displayName, in riproduzione\" else \"Riproduci $displayName\"\n"
        "                if (isGroupPlaying || isPlayingChannel) playingChannelButton = this\n"
        "                setOnClickListener {\n"
        "                    if (variantCount <= 1) playChannel(index, this)\n"
        "                    else showChannelVariants(displayName, variantIndices, this)\n"
        "                }\n"
        "            }"
    )
    text = text[:start] + new_block + text[block_end:]

    marker = "    private fun playChannel(index: Int, clickedButton: Button? = null) {"
    helper = (
        "    private fun showChannelVariants(displayName: String, variantIndices: List<Int>, clickedButton: Button? = null) {\n"
        "        val labels = variantIndices.map { idx ->\n"
        "            val ch = channels[idx]\n"
        "            val q = ChannelGrouping.qualityLabel(ch.name)\n"
        "            val src = ch.category.takeIf { it.isNotBlank() && it != \"Non classificati\" } ?: \"\"\n"
        "            buildString {\n"
        "                append(ch.name)\n"
        "                append(\"  ·  \")\n"
        "                append(q)\n"
        "                if (src.isNotBlank()) append(\"  ·  \").append(src)\n"
        "            }\n"
        "        }.toTypedArray()\n"
        "        AlertDialog.Builder(this)\n"
        "            .setTitle(\"Versioni di $displayName\")\n"
        "            .setItems(labels) { _, which ->\n"
        "                val chosen = variantIndices.getOrNull(which) ?: return@setItems\n"
        "                playChannel(chosen, clickedButton)\n"
        "            }\n"
        "            .setNegativeButton(\"Annulla\", null)\n"
        "            .show()\n"
        "    }\n\n"
    )
    if "showChannelVariants" not in text:
        if marker not in text:
            raise SystemExit("playChannel non trovato")
        text = text.replace(marker, helper + marker, 1)

    path.write_text(text, encoding="utf-8")
    print("Extractor patched", path.stat().st_size)
    return True

def patch_player():
    path = Path("android/playerlite/src/main/java/it/davidealpegiani/skylite/MainActivity.kt")
    text = path.read_text(encoding="utf-8")
    old = (
        "setBufferDurationsMs(\n"
        "                30_000,\n"
        "                90_000,\n"
        "                8_000,\n"
        "                15_000\n"
        "            )"
    )
    new = (
        "setBufferDurationsMs(\n"
        "                35_000,\n"
        "                150_000,\n"
        "                4_000,\n"
        "                15_000\n"
        "            )"
    )
    changed = False
    if old in text:
        text = text.replace(old, new, 1)
        changed = True
        print("player buffer durations")
    if "setPrioritizeTimeOverSizeThresholds(false)" in text:
        text = text.replace(
            "setPrioritizeTimeOverSizeThresholds(false)",
            "setPrioritizeTimeOverSizeThresholds(true)",
            1,
        )
        changed = True
        print("player prioritize")
    if changed:
        path.write_text(text, encoding="utf-8")
    else:
        print("Player already patched or markers missing")
    return changed

if __name__ == "__main__":
    a = patch_extractor()
    b = patch_player()
    if not (a or b):
        print("Nothing to do")
