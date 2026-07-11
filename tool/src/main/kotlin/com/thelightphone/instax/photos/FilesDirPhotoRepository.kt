package com.thelightphone.instax.photos

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads JPEG/PNG files from `<filesDir>/photos/`. On the emulator, seed it via
 * adb (see tool/README.md):
 *
 *   adb push photo.jpg /data/local/tmp/ && \
 *   adb shell run-as com.thelightphone.instax sh -c \
 *     'mkdir -p files/photos && cp /data/local/tmp/photo.jpg files/photos/'
 *
 * ImageDecoder applies EXIF orientation automatically, so downstream image
 * prep never needs to rotate for orientation — only for portrait->landscape.
 */
class FilesDirPhotoRepository(filesDir: File) : PhotoRepository {

    private val photosDir = File(filesDir, "photos")

    override suspend fun photos(): List<Photo> = withContext(Dispatchers.IO) {
        val files = photosDir.listFiles { f ->
            f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS
        }.orEmpty()
        files.sortedByDescending { it.lastModified() }
            .map { Photo(fileName = it.name, takenAtMillis = it.lastModified()) }
    }

    override suspend fun thumbnail(photo: Photo): Bitmap? =
        decode(photo) { info, decoder ->
            val scale = maxOf(1, info.size.width / THUMBNAIL_WIDTH)
            decoder.setTargetSampleSize(scale)
        }

    override suspend fun fullImage(photo: Photo): Bitmap? = decode(photo) { _, _ -> }

    private suspend fun decode(
        photo: Photo,
        configure: (ImageDecoder.ImageInfo, ImageDecoder) -> Unit,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(photosDir, photo.fileName)
        if (!file.isFile) return@withContext null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                configure(info, decoder)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to decode ${photo.fileName}", e)
            null
        }
    }

    private companion object {
        const val TAG = "InstaxPhotos"
        const val THUMBNAIL_WIDTH = 320
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
