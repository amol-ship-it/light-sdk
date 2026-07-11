package com.thelightphone.instax.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.thelightphone.instax.PrinterUiStatus
import com.thelightphone.instax.imaging.PrintImagePrep
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows exactly what will print: the real crop pipeline, not an approximation. */
class PreviewScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PrintFlowViewModel>(sealedActivity) {

    override val viewModelClass: Class<PrintFlowViewModel>
        get() = PrintFlowViewModel::class.java

    override fun createViewModel(): PrintFlowViewModel {
        return PrintFlowViewModel(lightContext.filesDir)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val photo by viewModel.selectedPhoto.collectAsState()
        val printerStatus by viewModel.printerStatus.collectAsState()

        val preview by produceState<android.graphics.Bitmap?>(null, photo?.fileName) {
            val p = photo ?: return@produceState
            value = withContext(Dispatchers.Default) {
                viewModel.fullImage(p)?.let(PrintImagePrep::cropForPrint)
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = photo?.fileName ?: "Preview",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "print preview",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    )
                } ?: LightText(
                    text = "Loading preview…",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 24.dp),
                )

                ActionRow(printerStatus)

                LightText(
                    text = "Back",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier
                        .lightClickable { goBack() }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }

    @Composable
    private fun ActionRow(status: PrinterUiStatus) {
        val connected = status as? PrinterUiStatus.Connected
        when {
            connected == null -> LightText(
                text = "Waiting for printer…",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            connected.printsRemaining <= 0 -> {
                val entry = ErrorCopy.entryFor(
                    PrintFailure.Printer(com.thelightphone.instax.protocol.InstaxError.NO_FILM)
                )
                LightText(
                    text = "${entry.headline} — ${entry.detail}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            else -> LightText(
                text = "Print — ${connected.printsRemaining} sheets left",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .lightClickable {
                        viewModel.selectedPhoto.value?.let { photo ->
                            viewModel.startPrint(photo)
                            navigateTo(::ProgressScreen)
                        }
                    }
                    .padding(vertical = 12.dp),
            )
        }
    }
}
