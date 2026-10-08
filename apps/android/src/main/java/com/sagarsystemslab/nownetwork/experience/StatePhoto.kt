package com.sagarsystemslab.nownetwork.experience

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Account-scoped, memory-only thumbnails. Signed URLs never enter persistent caches. */
class StatePhotoSource(private val fetch: suspend (String) -> String?) {
    private data class Entry(val bitmap: Bitmap?, val expiresAt: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val mutex = Mutex()

    suspend fun load(stateId: String): Bitmap? = mutex.withLock {
        cache[stateId]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }?.let { return@withLock it.bitmap }
        val bitmap = try {
            fetch(stateId)?.let { url -> withContext(Dispatchers.IO) { downloadThumbnail(url) } }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        if (cache.size >= 24) cache.remove(cache.keys.first())
        cache[stateId] = Entry(bitmap, SystemClock.elapsedRealtime() + 60_000)
        bitmap
    }
}

val LocalStatePhotoSource = staticCompositionLocalOf<StatePhotoSource?> { null }
val LocalEvidencePhotoSource = staticCompositionLocalOf<StatePhotoSource?> { null }

@Composable
fun StatePhoto(stateId: String?, title: String, modifier: Modifier, fallback: @Composable () -> Unit) {
    val source = LocalStatePhotoSource.current
    key(source, stateId) {
    val bitmap by produceState<Bitmap?>(null, source, stateId) {
        if (source != null && !stateId.isNullOrBlank()) value = source.load(stateId)
    }
    if (bitmap == null) fallback()
    else Image(bitmap!!.asImageBitmap(), "$title · verified photo", modifier, contentScale = ContentScale.Crop)
    }
}

private fun downloadThumbnail(value: String): Bitmap? {
    val url = URL(value)
    require(url.protocol == "https")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        useCaches = false
        instanceFollowRedirects = false
    }
    try {
        check(connection.responseCode == 200)
        check(connection.contentLengthLong <= 8 * 1024 * 1024)
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 8 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000)
        val options = BitmapFactory.Options().apply {
            inSampleSize = proofImageSampleSize(bounds.outWidth, bounds.outHeight, 384)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } finally { connection.disconnect() }
}
