package dev.pixelhome

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri

object TokenQrImage {
    fun read(resolver: ContentResolver, uri: Uri): String {
        require(uri.scheme == "content") { "Choose an image using Android's file picker." }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(true) {
                val count = input.read(buffer)
                if(count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "Choose a QR image smaller than 8 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Image unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image unavailable" }
        var sample = 1
        while((bounds.outWidth.toLong() + sample - 1) / sample > 2048 || (bounds.outHeight.toLong() + sample - 1) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Image unavailable")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            TokenQrDecoder.decode(pixels, bitmap.width, bitmap.height)
        } finally { bitmap.recycle() }
    }
}
