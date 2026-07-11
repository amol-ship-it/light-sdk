package com.thelightphone.instax.ui

import android.graphics.Bitmap
import com.thelightphone.instax.InstaxApp
import com.thelightphone.instax.PrintUiState
import com.thelightphone.instax.PrinterUiStatus
import com.thelightphone.instax.photos.Photo
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Thin per-screen façade over [InstaxApp]. All hot state lives in the
 * singleton (each LightScreen is its own ViewModelStoreOwner, so viewmodel
 * instances can't share a print in flight); this class only forwards.
 */
class PrintFlowViewModel(filesDir: File) : LightViewModel<Unit>() {

    init {
        InstaxApp.init(filesDir)
    }

    val printerStatus: StateFlow<PrinterUiStatus> = InstaxApp.printerStatus
    val printState: StateFlow<PrintUiState> = InstaxApp.printState
    val photos: StateFlow<List<Photo>> = InstaxApp.photos
    val selectedPhoto: StateFlow<Photo?> = InstaxApp.selectedPhoto

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        InstaxApp.refreshPhotos()
        InstaxApp.connectIfNeeded()
    }

    fun selectPhoto(photo: Photo) {
        InstaxApp.selectedPhoto.value = photo
    }

    fun startPrint(photo: Photo) = InstaxApp.startPrint(photo)
    fun cancelPrint() = InstaxApp.cancelPrint()
    fun acknowledgeResult() = InstaxApp.acknowledgeResult()
    fun retryConnection() = InstaxApp.retryConnection()

    suspend fun thumbnail(photo: Photo): Bitmap? = InstaxApp.thumbnail(photo)
    suspend fun fullImage(photo: Photo): Bitmap? = InstaxApp.fullImage(photo)
}
