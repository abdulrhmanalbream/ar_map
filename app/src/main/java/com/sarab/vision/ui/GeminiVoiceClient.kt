package com.sarab.vision.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sarab.vision.platform.PlatformStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Authenticated, cancellable speech transport; text/audio stay in memory. */
internal class GeminiVoiceClient(context: Context) {
    private val store = PlatformStore.get(context)
    private val executor = Executors.newSingleThreadExecutor()
    suspend fun synthesize(text: String, language: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val token = store.token
        val server = store.server
        val socket = AtomicReference<HttpURLConnection?>()
        val task = executor.submit {
            try {
                check(token.isNotBlank() && text.isNotBlank() && text.length <= 1800)
                val connection = URL(PlatformStore.validatedServer(server) + "/api/v1/vision/speak").openConnection() as HttpURLConnection
                socket.set(connection)
                if (!continuation.isActive) { connection.disconnect(); return@submit }
                connection.connectTimeout = 10_000
                connection.readTimeout = 40_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "audio/pcm")
                connection.doOutput = true
                val body = JSONObject().put("text", text).put("language", language).toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                check(connection.responseCode == 200)
                check(connection.getHeaderField("X-Audio-Format") == "pcm_s16le")
                check(connection.getHeaderField("X-Sample-Rate") == "24000")
                val pcm = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 8 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(pcm.isNotEmpty() && pcm.size % 2 == 0 && store.token == token && store.server == server)
                if (continuation.isActive) continuation.resume(pcm)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Speech service unavailable"))
            } finally { socket.getAndSet(null)?.disconnect() }
        }
        continuation.invokeOnCancellation { socket.getAndSet(null)?.disconnect(); task.cancel(true) }
    }
    fun close() { executor.shutdownNow() }
}

/** Interruptible PCM playback with no temporary audio files or blocking main-thread writes. */
internal class VisionPcmPlayer {
    private val current = AtomicReference<AudioTrack?>()
    fun stop() { current.get()?.let { runCatching { it.pause(); it.flush() } } }
    suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(9600, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .build()
        current.set(track)
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            currentCoroutineContext().ensureActive()
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val written = track.write(pcm, offset, minOf(8192, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0)
                offset += written
                if (written == 0) delay(10)
            }
            while (track.playbackHeadPosition.toLong() < pcm.size / 2L) {
                currentCoroutineContext().ensureActive()
                delay(25)
            }
        } finally {
            current.compareAndSet(track, null)
            runCatching { track.pause(); track.flush(); track.release() }
        }
    }
}
