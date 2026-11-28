package it.davidealpegiani.skyunoextractor

import android.app.Activity
import android.app.AlertDialog

/**
 * Dialog per scegliere quale versione/sorgente di un canale raggruppato riprodurre.
 */
internal object ChannelVariantUi {
    fun show(
        activity: Activity,
        displayName: String,
        channels: List<TvChannel>,
        variantIndices: List<Int>,
        onPick: (channelIndex: Int) -> Unit
    ) {
        if (variantIndices.isEmpty()) return
        if (variantIndices.size == 1) {
            onPick(variantIndices.first())
            return
        }
        val labels = variantIndices.map { index ->
            val ch = channels.getOrNull(index) ?: return@map "Canale $index"
            val quality = ChannelGrouping.qualityLabel(ch.name)
            val cat = ch.category.trim().takeIf {
                it.isNotEmpty() && !it.equals("Non classificati", true)
            }
            buildString {
                append(ch.name)
                if (quality != "Standard") append(" · ").append(quality)
                if (cat != null) append(" · ").append(cat)
            }
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("$displayName · ${variantIndices.size} versioni")
            .setItems(labels) { _, which ->
                if (which in variantIndices.indices) {
                    onPick(variantIndices[which])
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
