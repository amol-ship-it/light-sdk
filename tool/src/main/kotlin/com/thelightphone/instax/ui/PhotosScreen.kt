package com.thelightphone.instax.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class PhotosViewModel : LightViewModel<Unit>()

@InitialScreen
class PhotosScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, PhotosViewModel>(sealedActivity) {

    override val viewModelClass: Class<PhotosViewModel>
        get() = PhotosViewModel::class.java

    override fun createViewModel(): PhotosViewModel {
        return PhotosViewModel()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

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
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}
