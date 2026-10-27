// PhotoSource.kt -- a photo from the gallery or Files, as a file the engine reads.
//
// The picker and the document browser hand back content URIs; the model copies
// the photo into the cache and never touches the original, as the iOS app does.
// Then one of two decoders, the same split the desktop host and iOS make:
//
// - **Native** (studio.cpp, the desktop host's decoders): RAW and DNG through
//   LibRaw, scene-linear, which is what the film model must receive; TIFF,
//   JPEG and PNG with their ICC profiles honoured.
// - **The platform** (ImageDecoder): HEIC, HEIF, AVIF, WebP and anything the
//   native side refuses. These are already renderings, decoded to 8-bit sRGB
//   and handed over as pixels, with their EXIF read here for the export.
package app.spektralab.android.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.sqrt
import org.json.JSONObject

data class PhotoSource(val file: File, val displayName: String) {
    val extension get() = file.extension.lowercase()

    /** What the native decoders read; anything else goes to the platform first. */
    val isNative get() = extension in NATIVE

    val isRawName get() = extension in RAW

    companion object {
        val RAW = setOf("dng", "nef", "nrw", "arw", "srf", "sr2", "cr2", "cr3", "crw", "raf", "rw2", "orf", "pef",
                        "srw", "3fr", "iiq", "erf", "mos", "mrw", "x3f", "rwl", "kdc", "dcr")
        val NATIVE = RAW + setOf("tif", "tiff", "jpg", "jpeg", "png")

        /** Copy `uri` into the cache, named by its display name's extension. */
        fun copy(context: Context, uri: Uri): PhotoSource {
            val resolver = context.contentResolver
            var name = uri.lastPathSegment ?: "photo"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            var ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) {
                ext = when (resolver.getType(uri)) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/heic", "image/heif" -> "heic"
                    "image/webp" -> "webp"
                    "image/avif" -> "avif"
                    "image/tiff" -> "tif"
                    "image/x-adobe-dng" -> "dng"
                    else -> "img"
                }
            }
            val folder = File(context.cacheDir, "imports").apply { mkdirs() }
            val dest = File(folder, "${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("cannot read $name")
            // One photo at a time: the previous copy goes once the next is in.
            folder.listFiles()?.filter { it != dest }?.forEach { it.delete() }
            return PhotoSource(dest, name)
        }
    }
}

object PlatformDecoder {
    /** 8-bit sRGB, orientation applied, at most `maxPixels`. */
    fun decode(file: File, maxPixels: Long): Bitmap {
        val source = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width.toLong()
            val h = info.size.height.toLong()
            if (maxPixels > 0 && w * h > maxPixels) {
                val s = sqrt(maxPixels.toDouble() / (w * h))
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        }
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    }

    /** The shooting data studio.cpp writes into an export (`open_pixels`'s metadata). */
    fun metadata(file: File): String {
        val json = JSONObject()
        runCatching {
            val exif = ExifInterface(file)
            exif.getAttribute(ExifInterface.TAG_MAKE)?.let { json.put("make", it.trim()) }
            exif.getAttribute(ExifInterface.TAG_MODEL)?.let { json.put("model", it.trim()) }
            exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { json.put("lens", it.trim()) }
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { json.put("datetime_original", it) }
            exif.getAttributeDouble(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0.0).takeIf { it > 0 }?.let { json.put("iso", it) }
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { json.put("shutter_s", it) }
            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { json.put("aperture", it) }
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { json.put("focal_mm", it) }
        }
        return json.toString()
    }
}
