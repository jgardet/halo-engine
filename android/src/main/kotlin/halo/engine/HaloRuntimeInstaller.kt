package halo.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class HaloRuntimeInstaller(
    private val transport: AndroidBleTransport,
    private val runtimeFileName: String = "halo_engine.lua",
    /**
     * Invoked when the compressed upload path is skipped or fails and the
     * install falls back to legacy escaped-string writes. Carries the
     * cause — a host-side compression failure or the device-side
     * timeout/rejection. Null disables reporting.
     */
    private val onFallback: ((cause: Throwable) -> Unit)? = null,
) {
    /**
     * Installs the runtime from a [HaloRuntimeSource] and starts it.
     *
     * Convenience overload that loads the source via [source.load()]
     * and delegates to [installAndStart]. Use this when you have a
     * structured runtime source (e.g. [halo.engine.android.AssetHaloRuntimeSource])
     * rather than a raw string.
     */
    suspend fun installAndStart(
        source: HaloRuntimeSource,
        timeoutMs: Long = 10_000,
        autorun: Boolean = false,
    ): String = installAndStart(source.load(), timeoutMs, autorun)

    /**
     * @param autorun when true, also writes a `main.lua` shim that boots the
     *   runtime (`require('<module>')`) on every power-on/reset/light-sleep
     *   wake, so the glasses come up running without a host install. Hosts
     *   detect the already-running runtime via a STATUS query instead of
     *   re-installing (see `PhysicalHaloEndpoint`'s runtime probe).
     */
    suspend fun installAndStart(
        source: String,
        timeoutMs: Long = 10_000,
        autorun: Boolean = false,
    ): String = coroutineScope {
        transport.sendControl(HaloProtocol.LUA_CTRL_INTERRUPT.toByte())
        delay(200)
        upload(source)
        if (autorun) writeAutorun()
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(timeoutMs) {
                transport.notifications.filterIsInstance<HaloNotification.Message>()
                    .first { it.code == HaloProtocol.STATUS }
                    .payload.toString(Charsets.UTF_8)
            }
        }
        val module = runtimeFileName.removeSuffix(".lua")
        transport.sendLua("package.loaded['$module']=nil require('$module')")
        ready.await()
    }

    /**
     * Remove `main.lua` and reset the Lua VM (firmware CTRL+E). After this
     * the glasses boot to a bare REPL until a runtime is installed again.
     */
    suspend fun removeAutorun() {
        transport.sendControl(HaloProtocol.LUA_CTRL_RESET.toByte())
    }

    private suspend fun writeAutorun() {
        val module = runtimeFileName.removeSuffix(".lua")
        val ack = "frame.bluetooth.send(string.char(${HaloProtocol.STATUS}) .. 'ok')"
        transport.sendLuaAwaitStatus(
            "f=frame.file.open('$AUTORUN_FILE_NAME','w');" +
                "f:write([[local ok,err=pcall(require,'$module') " +
                "if not ok then print('autorun failed: '..tostring(err)) end]]);" +
                "f:close();$ack",
            expectedPayload = "ok",
        )
    }

    private suspend fun upload(source: String) {
        // Prefer an LZ4-framed upload decoded by the stock firmware's
        // frame.compression API. The compressed bytes travel as hex inside
        // ordinary Lua string literals — 2x expansion still beats the
        // escaped-source path, and every line stays small enough for the
        // negotiated BLE payload. Any failure falls back to legacy upload
        // and is reported via onFallback so the degradation is observable.
        val lz4 = try {
            HaloLz4.compress(source.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            fallback("host LZ4 compression failed", e)
            null
        }
        if (lz4 != null) {
            try {
                uploadCompressed(lz4)
                return
            } catch (e: Throwable) {
                // Swallow only an inner failure (e.g. status timeout); a
                // cancelled install must still propagate.
                currentCoroutineContext().ensureActive()
                fallback("compressed runtime upload failed", e)
            }
        }
        uploadLegacy(source)
    }

    private fun fallback(reason: String, cause: Throwable) {
        android.util.Log.w("HaloRuntimeInstaller", "$reason — using legacy upload", cause)
        runCatching { onFallback?.invoke(cause) }
    }

    private suspend fun uploadCompressed(lz4: ByteArray) {
        val ack = "frame.bluetooth.send(string.char(${HaloProtocol.STATUS}) .. 'ok')"
        val overhead = "z=z..'';$ack".toByteArray(Charsets.UTF_8).size
        val chunkSize = transport.maxLuaPayload - overhead
        require(chunkSize > 0) { "Negotiated MTU is too small for runtime upload" }

        transport.sendLuaAwaitStatus("z='';$ack", expectedPayload = "ok")
        val hex = StringBuilder(lz4.size * 2)
        lz4.forEach { hex.append("%02x".format(it.toInt() and 0xFF)) }
        hex.chunked(chunkSize).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            transport.sendLuaAwaitStatus("z=z..'$chunk';$ack", expectedPayload = "ok")
        }
        transport.sendLuaAwaitStatus(
            "z=(z:gsub('..',function(h) return string.char(tonumber(h,16)) end));" +
                "f=frame.file.open('$runtimeFileName','w');" +
                "frame.compression.process_function(function(d) f:write(d) end);" +
                "local ok=pcall(frame.compression.decompress,z,4096);" +
                "frame.compression.process_function(nil);f:close();z=nil;" +
                "frame.bluetooth.send(string.char(${HaloProtocol.STATUS}) .. (ok and 'ok' or 'lz4fail'))",
            expectedPayload = "ok",
        )
    }

    private suspend fun uploadLegacy(source: String) {
        val escaped = source.replace("\r", "")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
        val ack = "frame.bluetooth.send(string.char(${HaloProtocol.STATUS}) .. 'ok')"
        transport.sendLuaAwaitStatus("z=nil;f=frame.file.open('$runtimeFileName','w');$ack", expectedPayload = "ok")
        val overhead = "f:write(\"\");$ack".toByteArray(Charsets.UTF_8).size
        val chunkSize = transport.maxLuaPayload - overhead
        require(chunkSize > 0) { "Negotiated MTU is too small for runtime upload" }
        utf8Chunks(escaped, chunkSize).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            transport.sendLuaAwaitStatus("f:write(\"$chunk\");$ack", expectedPayload = "ok")
        }
        transport.sendLuaAwaitStatus("f:close();$ack", expectedPayload = "ok")
    }

    private companion object {
        const val AUTORUN_FILE_NAME = "main.lua"
    }

    private fun utf8Chunks(value: String, maxBytes: Int): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var currentBytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val token = String(Character.toChars(codePoint))
            val tokenBytes = token.toByteArray(Charsets.UTF_8).size
            require(tokenBytes <= maxBytes) { "Negotiated MTU cannot carry one runtime character" }
            if (currentBytes + tokenBytes > maxBytes) {
                chunks += current.toString()
                current = StringBuilder()
                currentBytes = 0
            }
            current.append(token)
            currentBytes += tokenBytes
            offset += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
