package com.alibardide.imagine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

object ImageCompressor {

    private const val TAG = "ImageCompressor"

    enum class Format(val mimeType: String) {
        JPEG("image/jpeg"),
        WEBP("image/webp"),
    }

    suspend fun roundDrawableFromUri(
        context: Context,
        uri: Uri,
        size: Int = 480,
    ): Drawable = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }

        val shortEdge = min(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortEdge / (sample * 2) >= size) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Decode failed")

        val matrix = Matrix().apply {
            postRotate(exifRotation(context, uri))
        }
        val bitmap = Bitmap.createBitmap(
            decoded,
            0, 0,
            decoded.width, decoded.height,
            matrix, true
        )
        if (bitmap !== decoded) decoded.recycle()

        RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
            isCircular = true
            cornerRadius = 20f
        }
    }

    fun exifRotation(context: Context, uri: Uri): Float =
        context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

    suspend fun compressToUri(
        context: Context,
        source: Uri,
        outputUri: Uri,
        quality: Int = 70,
        format: Format = Format.WEBP,
        onSuccess: (Uri) -> Unit = {},
        onFail: (Throwable) -> Unit = { Log.e(TAG, "Compression failed", it) },
    ) {
        reportResult(onSuccess, onFail) {
            withContext(Dispatchers.IO) {
                val encoded = encodeToCache(context, source, quality, format)
                try {
                    val out = context.contentResolver.openOutputStream(outputUri, "wt")
                        ?: error("Can't open output Uri")
                    out.use { o -> encoded.file.inputStream().use { it.copyTo(o) } }
                } finally {
                    encoded.file.delete()
                }
                outputUri
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun compressToMediaStore(
        context: Context,
        source: Uri,
        baseName: String,
        folder: String = "Imagine",
        quality: Int = 70,
        format: Format = Format.WEBP,
        onSuccess: (Uri) -> Unit = {},
        onFail: (Throwable) -> Unit = { Log.e(TAG, "Compression failed", it) },
    ) {
        reportResult(onSuccess, onFail) {
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val encoded = encodeToCache(context, source, quality, format)
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.${extensionFor(encoded.mimeType)}")
                        put(MediaStore.Images.Media.MIME_TYPE, encoded.mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folder")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
                    try {
                        val out = resolver.openOutputStream(uri) ?: error("Can't open MediaStore output")
                        out.use { o -> encoded.file.inputStream().use { it.copyTo(o) } }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        uri
                    } catch (e: Throwable) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } finally {
                    encoded.file.delete()
                }
            }
        }
    }

    private suspend fun reportResult(
        onSuccess: (Uri) -> Unit,
        onFail: (Throwable) -> Unit,
        block: suspend () -> Uri,
    ) {
        val uri = try {
            block()
        } catch (e: Exception) {
            onFail(e)
            return
        }
        onSuccess(uri)
    }

    private class Encoded(val file: File, val mimeType: String)

    private fun encodeToCache(
        context: Context,
        source: Uri,
        quality: Int,
        format: Format,
    ): Encoded {
        val resolver = context.contentResolver
        val originalSize = resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }

        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it)
        } ?: error("Decode failed")

        val orientation = resolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()

        val tmp = File.createTempFile("compress_", ".tmp", context.cacheDir)
        try {
            val compressFormat = when (format) {
                Format.JPEG -> Bitmap.CompressFormat.JPEG
                Format.WEBP ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                    else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            val ok = tmp.outputStream().use { bitmap.compress(compressFormat, quality, it) }
            check(ok) { "Encoding failed" }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            bitmap.recycle()
        }

        if (originalSize > 0 && tmp.length() >= originalSize) {
            resolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            return Encoded(tmp, resolver.getType(source) ?: format.mimeType)
        }
        return Encoded(tmp, format.mimeType)
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/png" -> "png"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
    }
}