package com.homestock.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Decode, fix orientation, downscale to <= maxDim and JPEG-compress for upload. */
fun compressImageFile(file: File, maxDim: Int = 800, quality: Int = 80): ByteArray {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        ?: return file.readBytes()
    val rotated = applyExifRotation(file, bitmap)
    val scaled = downscale(rotated, maxDim)
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}

/**
 * Pull a picked-from-gallery image through the same compression pipeline as
 * a camera shot. Modern phone photos can be 5–10 MB which would waste NAS
 * disk and Android upload time; we copy the URI bytes to a temp file in the
 * app cache, run [compressImageFile], then delete the temp.
 */
fun compressImageUri(
    context: Context,
    uri: Uri,
    maxDim: Int = 800,
    quality: Int = 80,
): ByteArray? {
    val tmp = File.createTempFile("gallery", ".img", context.cacheDir)
    return try {
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        if (copied == null) null else compressImageFile(tmp, maxDim, quality)
    } catch (_: Exception) {
        null
    } finally {
        tmp.delete()
    }
}

private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val largest = maxOf(w, h)
    if (largest <= maxDim) return bitmap
    val ratio = maxDim.toFloat() / largest
    return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
}

private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
    val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return bitmap
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
    )
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
