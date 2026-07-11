package com.thelightphone.instax.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.thelightphone.instax.PrinterUiStatus
import com.thelightphone.instax.photos.Photo
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@InitialScreen
class PhotosScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PrintFlowViewModel>(sealedActivity) {

    override val viewModelClass: Class<PrintFlowViewModel>
        get() = PrintFlowViewModel::class.java

    override fun createViewModel(): PrintFlowViewModel {
        return PrintFlowViewModel(lightContext.filesDir)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val photos by viewModel.photos.collectAsState()
        val printerStatus by viewModel.printerStatus.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(32.dp)
            ) {
                LightText(
                    text = "Print",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                StatusLine(printerStatus)

                if (photos.isEmpty()) {
                    LightText(
                        text = "No photos yet.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                        items(photos, key = { it.fileName }) { photo ->
                            PhotoRow(photo)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusLine(status: PrinterUiStatus) {
        when (status) {
            PrinterUiStatus.Idle, PrinterUiStatus.Connecting -> LightText(
                text = "Looking for printer…",
                variant = LightTextVariant.Detail,
                lighten = true,
            )
            is PrinterUiStatus.Connected -> LightText(
                text = "Printer: ${status.printsRemaining} prints left · battery ${status.batteryPercent}%",
                variant = LightTextVariant.Detail,
                lighten = true,
            )
            is PrinterUiStatus.Failed -> {
                val entry = ErrorCopy.entryFor(status.failure)
                LightText(
                    text = "${entry.headline} — tap to retry",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.lightClickable { viewModel.retryConnection() },
                )
            }
        }
    }

    @Composable
    private fun PhotoRow(photo: Photo) {
        val thumbnail by produceState<android.graphics.Bitmap?>(null, photo.fileName) {
            value = viewModel.thumbnail(photo)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable {
                    viewModel.selectPhoto(photo)
                    navigateTo(::PreviewScreen)
                }
                .padding(vertical = 8.dp),
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = photo.fileName,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(width = 64.dp, height = 43.dp),
                )
            }
            Column {
                LightText(text = photo.fileName, variant = LightTextVariant.Copy)
                LightText(
                    text = DATE_FORMAT.format(Date(photo.takenAtMillis)),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }
        }
    }

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.US)
    }
}
