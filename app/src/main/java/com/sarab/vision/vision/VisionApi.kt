package com.sarab.vision.vision

import android.os.SystemClock
import com.sarab.vision.diagnostics.DiagnosticLog
import com.sarab.vision.platform.PlatformStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Uses the enrollment captured for this consent epoch; never logs images, questions, or responses. */
internal class VisionApi(private val store: PlatformStore) {
    suspend fun request(path: String, body: JSONObject, server: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        require(path == "/vision/analyze" || path == "/vision/ask")
        check(token.isNotBlank())
        coroutineContext.ensureActive()
        val connection = URL(PlatformStore.validatedServer(server) + "/api/v1" + path).openConnection() as HttpURLConnection
        // Cancellation must close a blocked socket rather than occupying the controller's
        // only request slot until the full read timeout after changing photo/source.
        val cancellationGuard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        val startedAt = SystemClock.elapsedRealtime()
        var phase = VisionTransportPhase.CONNECT
        var status = 0
        var payloadBytes = 0
        var uploadedBytes = 0
        var receivedBytes = 0
        val requestContext = coroutineContext
        fun trace(outcome: VisionTransportOutcome, error: VisionError? = null) {
            DiagnosticLog.record("VisionTransport", visionTransportMetadata(path == "/vision/ask", phase, outcome,
                status, SystemClock.elapsedRealtime() - startedAt, payloadBytes, uploadedBytes, receivedBytes, error))
        }
        fun serverFailure(): VisionRequestFailure {
            if (status == 401) store.invalidateSession(token)
            phase = VisionTransportPhase.READ
            val response = connection.errorStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (output.size() < 16_384) {
                    requestContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, 16_384 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    receivedBytes += count
                }
                output.toString(Charsets.UTF_8.name())
            }
            return VisionFailurePayloads.parse(status, response, connection.getHeaderField("Retry-After"))
        }
        try {
            connection.connectTimeout = 50_000
            // Covers server queue8s + upload50s + provider50s, with transport margin.
            // No hidden retry or shorter controller deadline is introduced.
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            require(payload.size <= 600_000)
            payloadBytes = payload.size
            connection.setFixedLengthStreamingMode(payload.size)
            coroutineContext.ensureActive()
            trace(VisionTransportOutcome.PHASE)
            connection.connect()
            phase = VisionTransportPhase.UPLOAD
            trace(VisionTransportOutcome.PHASE)
            try {
                connection.outputStream.use { output ->
                    var offset = 0
                    while (offset < payload.size) {
                        coroutineContext.ensureActive()
                        val count = minOf(8192, payload.size - offset)
                        output.write(payload, offset, count)
                        offset += count
                        uploadedBytes = offset
                    }
                }
            } catch (failure: IOException) {
                coroutineContext.ensureActive()
                // An early HTTP408/413 may close upload before its last chunk. Inspect the
                // same connection once; never resend, and do not extend a local socket timeout.
                if (failure !is SocketTimeoutException) {
                    val earlyStatus = runCatching { connection.responseCode }.getOrNull()
                    coroutineContext.ensureActive()
                    if (earlyStatus != null && earlyStatus in 400..599) {
                        status = earlyStatus
                        throw serverFailure()
                    }
                }
                throw failure
            }
            coroutineContext.ensureActive()
            phase = VisionTransportPhase.RESPONSE
            trace(VisionTransportOutcome.PHASE)
            status = connection.responseCode
            phase = VisionTransportPhase.READ
            trace(VisionTransportOutcome.PHASE)
            if (status !in 200..299) throw serverFailure()
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576)
                    output.write(buffer, 0, count)
                    receivedBytes += count
                }
                output.toByteArray()
            }
            val result = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse {
                throw VisionRequestFailure(VisionError.INVALID_RESPONSE, true)
            }
            trace(VisionTransportOutcome.SUCCESS)
            result
        } catch (failure: Exception) {
            if (!coroutineContext.isActive) {
                trace(VisionTransportOutcome.CANCELLED)
                coroutineContext.ensureActive()
            }
            val mapped = failure as? VisionRequestFailure
            val outcome = classifyVisionTransportFailure(failure)
            trace(outcome, mapped?.error ?: if (failure is SocketTimeoutException) VisionError.TIMEOUT else VisionError.NETWORK)
            throw failure
        } finally { cancellationGuard.cancel(); connection.disconnect() }
    }
}
