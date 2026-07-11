package com.thelightphone.instax.imaging

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

class ImageTooLargeException :
    Exception("image cannot be compressed under $MAX_JPEG_BYTES bytes")

/** The WIDE print contract: 1260x840 JPEG, <= 65535 bytes. */
const val PRINT_WIDTH = 1260
const val PRINT_HEIGHT = 840
const val MAX_JPEG_BYTES = 65_535

/**
 * Turns any photo bitmap into printer-ready JPEG bytes: portrait auto-rotates
 * to landscape, center-crop to 3:2 (1260:840), scale to 1260x840, then a JPEG
 * quality ladder until the bytes fit the printer's size cap.
 *
 * The geometry (computeCrop) and the ladder (encodeUnderLimit) are pure Kotlin
 * and JVM-tested; only [prepare] touches android.graphics.
 */
object PrintImagePrep {

    /** Crop plan in SOURCE pixel space *after* [rotateDegrees] rotation. */
    data class CropPlan(
        val rotateDegrees: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    fun computeCrop(srcWidth: Int, srcHeight: Int): CropPlan {
        val rotate = srcHeight > srcWidth // portrait -> landscape, full-bleed
        val w = if (rotate) srcHeight else srcWidth
        val h = if (rotate) srcWidth else srcHeight
        val targetRatio = PRINT_WIDTH.toDouble() / PRINT_HEIGHT

        val cropWidth: Int
        val cropHeight: Int
        if (w.toDouble() / h > targetRatio) {
            cropHeight = h
            cropWidth = (h * targetRatio).toInt()
        } else {
            cropWidth = w
            cropHeight = (w / targetRatio).toInt()
        }
        return CropPlan(
            rotateDegrees = if (rotate) 90 else 0,
            left = (w - cropWidth) / 2,
            top = (h - cropHeight) / 2,
            width = cropWidth,
            height = cropHeight,
        )
    }

    /** Walks quality 95, 90, … 40; returns the first encoding that fits [limit]. */
    fun encodeUnderLimit(limit: Int, encode: (quality: Int) -> ByteArray): ByteArray {
        for (quality in 95 downTo 40 step 5) {
            val bytes = encode(quality)
            if (bytes.size <= limit) return bytes
        }
        throw ImageTooLargeException()
    }

    /** Full pipeline. [source] must already be EXIF-upright (ImageDecoder does this). */
    fun prepare(source: Bitmap): ByteArray {
        val cropped = cropForPrint(source)
        val scaled = if (cropped.width == PRINT_WIDTH && cropped.height == PRINT_HEIGHT) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, PRINT_WIDTH, PRINT_HEIGHT, true)
        }
        return encodeUnderLimit(MAX_JPEG_BYTES) { quality ->
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    /** The crop+rotate part of the pipeline, reused by the Preview screen so
     *  what you see is exactly what prints. */
    fun cropForPrint(source: Bitmap): Bitmap {
        val plan = computeCrop(source.width, source.height)
        val rotated = if (plan.rotateDegrees != 0) {
            val matrix = Matrix().apply { postRotate(plan.rotateDegrees.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }
        return Bitmap.createBitmap(rotated, plan.left, plan.top, plan.width, plan.height)
    }
}
