package com.amolpurohit.tesla.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val UNDERLINE_THICKNESS_PX = 3f

/**
 * A tappable command with its own in-flight/disabled/error state (spec §6, §8).
 *
 * While [pending] the label grows an ellipsis suffix and clicks are ignored.
 * While [!enabled] the button is lightened and clicks are ignored.
 * When [error] is non-null it is rendered beneath the button as lightened Detail text.
 */
@Composable
fun CommandButton(
    label: String,
    pending: Boolean,
    error: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val isInteractive = enabled && !pending
    val displayLabel = if (pending) "$label…" else label

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(enabled = isInteractive, onClick = onClick)
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        ) {
            LightText(
                text = displayLabel,
                variant = LightTextVariant.Button,
                align = TextAlign.Center,
                lighten = !isInteractive,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                    .background(if (isInteractive) colors.content else colors.contentSecondary),
            )
        }
        if (error != null) {
            LightText(
                text = error,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
            )
        }
    }
}
