package com.amolpurohit.tesla.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A "-" / value / "+" row. The caller owns what each step means (e.g. charge limit
 * steps by 5, temperature by 0.5) — this component only renders the row and forwards taps.
 *
 * While [pending] both controls ignore clicks and the value renders lightened.
 */
@Composable
fun Stepper(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    pending: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
        )
        // U+2212 (visual minus), intentionally not ASCII hyphen
        Box(
            modifier = Modifier
                .size(2f.gridUnitsAsDp())
                .lightClickable(
                    enabled = !pending,
                    onClickLabel = "Decrease $label",
                    onClick = onDecrement,
                ),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "−",
                variant = LightTextVariant.Heading,
            )
        }
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            lighten = pending,
            modifier = Modifier.padding(horizontal = 0.5f.gridUnitsAsDp()),
        )
        Box(
            modifier = Modifier
                .size(2f.gridUnitsAsDp())
                .lightClickable(
                    enabled = !pending,
                    onClickLabel = "Increase $label",
                    onClick = onIncrement,
                ),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "+",
                variant = LightTextVariant.Heading,
            )
        }
    }
}
