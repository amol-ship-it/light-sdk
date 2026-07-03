package com.amolpurohit.tesla.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * A two-column dashboard line: [label] left, [value] right, full width.
 * Pass "—" as value when the underlying data is unknown/loading — call sites share this convention.
 */
@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.weight(1f),
        )
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            align = TextAlign.End,
        )
    }
}
