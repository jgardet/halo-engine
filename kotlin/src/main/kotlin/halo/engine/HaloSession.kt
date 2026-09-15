package halo.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Raised when a streaming session would exceed an explicit byte ceiling.
 * This is a recoverable policy violation, not a BLE protocol or transport error.
 */
class HaloLimitException(message: String) : RuntimeException(message)

/**
 * Raised when the device reports an application-level failure via an
 * `ERROR` (0x71) message — e.g. an invalid command or a failed capability
 * start. The message is the device-supplied reason string.
 */
class HaloDeviceException(message: String) : RuntimeException(message)

/**
 * Generic request/response and streaming session over a [HaloBleTransport].
 *
 * This consolidates the chunk-collection logic used by microphone, camera,
 * battery, and other streaming capabilities. It is cancellation-aware, uses a
 * single [ByteArrayOutputStream] to avoid per-chunk allocations, and ensures
 * the stop command is sent even when the operation times out or is cancelled.
 */
class HaloSession(
    private val messages: Flow<HaloMessage>,
    private val send: suspend (Int, ByteArray) -> Unit,
    private val connectionEvents: Flow<Boolean>? = null,
) {

    constructor(transport: HaloBleTransport) : this(
        transport.messages,
        transport::sendMessage,
        transport.connectionEvents,
    )

    /**
     * Send [requestCode] with [requestPayload], wait up to [timeout] for a single
     * [responseCode] message, and return its payload.
     */
    suspend fun requestResponse(
        requestCode: Int,
        requestPayload: ByteArray,
        responseCode: Int,
        timeout: Duration,
    ): ByteArray = coroutineScope {
        currentCoroutineContext().ensureActive()
        val response = CompletableDeferred<ByteArray>()
        val waiter = async {
            messages
                .filter { it.code == responseCode || it.code == HaloProtocol.ERROR }
                .first()
                .let { message ->
                    if (message.code == HaloProtocol.ERROR) {
                        response.completeExceptionally(
                            HaloDeviceException(message.payload.toString(Charsets.UTF_8)),
                        )
                    } else {
                        response.complete(message.payload)
                    }
                }
        }
        val disconnected = connectionEvents?.let {
            async { it.filter { connected -> !connected }.first() }
        }
        try {
            yield()
            send(requestCode, requestPayload)
            withTimeout(timeout) {
                if (disconnected == null) {
                    response.await()
                } else {
                    select {
                        response.onAwait { it }
                        disconnected.onAwait { throw HaloTransportException("disconnected during request") }
                    }
                }
            }
        } finally {
            waiter.cancel()
            disconnected?.cancel()
        }
    }

    /**
     * Start a streaming session by sending [startCode]/[startPayload], collect
     * [chunkCode] payloads into a single [ByteArray], and finish when [finalCode]
     * is received. If [stopCode] is provided, it is sent in a `finally` block so
     * the device is always released on timeout or cancellation.
     *
     * If [maxBytes] is exceeded while collecting chunks, the session is aborted
     * and [HaloLimitException] is thrown so the device can be released.
     *
     * If [stopAfter] is set, [stopCode] is sent after that duration while
     * collection continues until [finalCode] arrives or [timeout] elapses. This
     * is required for microphone captures where the runtime only emits the final
     * frame after it has stopped recording.
     */
    suspend fun collect(
        startCode: Int,
        startPayload: ByteArray,
        stopCode: Int? = null,
        stopPayload: ByteArray = byteArrayOf(),
        chunkCode: Int,
        finalCode: Int,
        timeout: Duration,
        maxBytes: Long = Long.MAX_VALUE,
        stopAfter: Duration? = null,
        shouldStopEarly: (ByteArray) -> Boolean = { false },
    ): ByteArray = coroutineScope {
        currentCoroutineContext().ensureActive()
        val finalSignal = CompletableDeferred<Unit>()
        val output = ByteArrayOutputStream()
        var written: Long = 0
        var stopJob: Job? = null
        val stopSent = AtomicBoolean(false)

        val collector = async {
            messages
                .filter { it.code == chunkCode || it.code == finalCode || it.code == HaloProtocol.ERROR }
                .collect { message ->
                    yield()
                    currentCoroutineContext().ensureActive()
                    when (message.code) {
                        chunkCode -> {
                            val chunk = message.payload
                            val newSize = written + chunk.size
                            if (newSize > maxBytes) {
                                throw HaloLimitException("collected payload exceeds $maxBytes bytes")
                            }
                            output.write(chunk)
                            written += chunk.size
                            // Caller-side early stop (e.g. trailing-silence
                            // detection): send the stop code once and keep
                            // collecting until FINAL arrives.
                            if (stopCode != null && shouldStopEarly(chunk) &&
                                !finalSignal.isCompleted && stopSent.compareAndSet(false, true)) {
                                launch { runCatching { send(stopCode, stopPayload) } }
                            }
                        }
                        finalCode -> finalSignal.complete(Unit)
                        HaloProtocol.ERROR -> finalSignal.completeExceptionally(
                            HaloDeviceException(message.payload.toString(Charsets.UTF_8)),
                        )
                    }
                }
        }

        val disconnected = connectionEvents?.let {
            async { it.filter { connected -> !connected }.first() }
        }

        try {
            yield()
            send(startCode, startPayload)
            if (stopCode != null && stopAfter != null) {
                stopJob = launch {
                    delay(stopAfter)
                    if (!finalSignal.isCompleted && stopSent.compareAndSet(false, true)) {
                        withContext(NonCancellable) { runCatching { send(stopCode, stopPayload) } }
                    }
                }
            }
            withTimeout(timeout) {
                if (disconnected == null) {
                    finalSignal.await()
                } else {
                    select {
                        finalSignal.onAwait {}
                        disconnected.onAwait { throw HaloTransportException("disconnected during collect") }
                    }
                }
            }
            output.toByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            stopJob?.cancel()
            // Only skip the stop when the stream ended normally — on a device
            // ERROR the capability may still be running and must be released.
            val finishedNormally = finalSignal.isCompleted &&
                finalSignal.getCompletionExceptionOrNull() == null
            if (stopCode != null && !stopSent.get() && !finishedNormally) {
                if (stopSent.compareAndSet(false, true)) {
                    withContext(NonCancellable) { runCatching { send(stopCode, stopPayload) } }
                }
            }
            disconnected?.cancel()
            collector.cancel()
        }
    }
}
