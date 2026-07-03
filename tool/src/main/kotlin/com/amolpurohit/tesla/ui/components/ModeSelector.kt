package com.amolpurohit.tesla.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * A row of tappable [options] under [label]; the active option renders in Heading variant,
 * others in Copy. While [!enabled] (e.g. a command is already pending) taps are ignored and
 * inactive options render lightened.
 */
@Composable
fun <T> ModeSelector(
    label: String,
    options: List<Pair<String, T>>,
    current: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp())) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 0.25f.gridUnitsAsDp())) {
            options.forEach { (optionLabel, mode) ->
                val isActive = mode == current
                LightText(
                    text = optionLabel,
                    variant = if (isActive) LightTextVariant.Heading else LightTextVariant.Copy,
                    lighten = !enabled && !isActive,
                    modifier = Modifier
                        .padding(end = 1f.gridUnitsAsDp())
                        .lightClickable(enabled = enabled, onClick = { onSelect(mode) }),
                )
            }
        }
    }
}
