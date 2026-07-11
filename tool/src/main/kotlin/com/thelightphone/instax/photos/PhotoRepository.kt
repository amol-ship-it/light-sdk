package com.thelightphone.instax.photos

import android.graphics.Bitmap

data class Photo(val fileName: String, val takenAtMillis: Long)

/**
 * Where printable pictures come from.
 *
 * The tool sandbox blocks MediaStore/contentResolver entirely, and the SDK has
 * no Album-read API (design spec, constraints 1–2), so the only source today is
 * the tool's own files dir — seeded via adb on the emulator. The seam exists so
 * a DashboardPhotoRepository (Light web sync over HTTPS) can drop in for real
 * devices later.
 */
interface PhotoRepository {
    /** Newest first. */
    suspend fun photos(): List<Photo>
    suspend fun thumbnail(photo: Photo): Bitmap?
    suspend fun fullImage(photo: Photo): Bitmap?
}
