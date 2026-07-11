package com.thelightphone.instax.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.instax.PrintUiState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

class ProgressScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PrintFlowViewModel>(sealedActivity) {

    override val viewModelClass: Class<PrintFlowViewModel>
        get() = PrintFlowViewModel::class.java

    override fun createViewModel(): PrintFlowViewModel {
        return PrintFlowViewModel(lightContext.filesDir)
    }

    /** No orphaned prints: back is swallowed while the transfer/print runs. */
    override fun goBack(result: Unit?) {
        when (viewModel.printState.value) {
            is PrintUiState.Transferring, PrintUiState.Printing, PrintUiState.Preparing -> Unit
            else -> super.goBack(result)
        }
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.printState.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "Printing",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                when (val s = state) {
                    PrintUiState.Idle -> Body("Cancelled — no film used.") {
                        BackRow("Back")
                    }
                    PrintUiState.Preparing -> Body("Preparing photo…") {
                        CancelRow()
                    }
                    is PrintUiState.Transferring -> Body("Sending to printer… ${s.percent}%") {
                        CancelRow()
                    }
                    PrintUiState.Printing -> Body("Printing… don't cover the film exit.") { }
                    PrintUiState.Done -> Body("Done — grab your photo.") {
                        BackRow("Back", acknowledge = true)
                    }
                    is PrintUiState.Failed -> FailedBody(s)
                }
            }
        }
    }

    @Composable
    private fun Body(message: String, actions: @Composable () -> Unit) {
        LightText(
            text = message,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        actions()
    }

    @Composable
    private fun FailedBody(failed: PrintUiState.Failed) {
        val entry = ErrorCopy.entryFor(failed.failure)
        var armed by remember { mutableStateOf(false) }

        LightText(text = entry.headline, variant = LightTextVariant.Copy)
        LightText(
            text = entry.detail,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        when (entry.action) {
            ErrorCopy.Action.RETRY -> ActionRow("Retry") { retry() }
            ErrorCopy.Action.RETRY_CONFIRM ->
                if (!armed) {
                    ActionRow("Retry") { armed = true }
                } else {
                    ActionRow("Tap again to use another sheet") { retry() }
                }
            null -> Unit
        }
        BackRow("Back", acknowledge = true)
    }

    private fun retry() {
        viewModel.acknowledgeResult()
        viewModel.selectedPhoto.value?.let { viewModel.startPrint(it) }
    }

    @Composable
    private fun ActionRow(label: String, onTap: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .lightClickable(onClick = onTap)
                .padding(vertical = 12.dp),
        )
    }

    @Composable
    private fun CancelRow() {
        ActionRow("Cancel") { viewModel.cancelPrint() }
    }

    @Composable
    private fun BackRow(label: String, acknowledge: Boolean = false) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier
                .lightClickable {
                    if (acknowledge) viewModel.acknowledgeResult()
                    super.goBack(null)
                }
                .padding(vertical = 12.dp),
        )
    }
}
