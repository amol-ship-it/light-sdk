package com.amolpurohit.tesla.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.amolpurohit.tesla.ui.formatUpdatedAt
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Lightened Detail line showing when the vehicle state was last fetched, with an optional
 * [suffix] (e.g. " · stale") or [badge] (e.g. "Asleep").
 *
 * Renders a snapshot of "now" at composition time — not a live ticker; it only re-renders
 * when the surrounding state recomposes (e.g. after a refresh).
 */
@Composable
fun UpdatedAtLine(updatedAtMs: Long?, suffix: String? = null, badge: String? = null) {
    val text = when {
        updatedAtMs == null && badge != null -> badge
        updatedAtMs == null -> "—"
        badge != null -> "${formatUpdatedAt(System.currentTimeMillis(), updatedAtMs)} · $badge"
        else -> "${formatUpdatedAt(System.currentTimeMillis(), updatedAtMs)}${suffix.orEmpty()}"
    }
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
    )
}
