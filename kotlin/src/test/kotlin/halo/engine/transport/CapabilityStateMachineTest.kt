package halo.engine.transport

import halo.engine.HaloCommands
import halo.engine.HaloProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapabilityStateMachineTest {

    private fun messages(events: List<DeviceEvent>): List<DeviceEvent.Message> =
        events.filterIsInstance<DeviceEvent.Message>()

    @Test
    fun bootEmitsStatusAndReadyText() {
        val machine = CapabilityStateMachine()
        machine.boot()
        val events = machine.drainEvents()
        assertEquals(2, events.size)
        val status = events[0] as DeviceEvent.Message
        assertEquals(HaloProtocol.STATUS, status.code)
        val caps = status.payload.toString(Charsets.UTF_8)
        assertTrue(caps.startsWith("HRP1;"))
        assertTrue(caps.contains("sound"))
        assertTrue(caps.contains("imu"))
        assertTrue(caps.contains("fw="))
        assertTrue(caps.contains("eui="))
        val text = events[1] as DeviceEvent.Text
        assertEquals("Halo Engine v3 ready", text.value)
    }

    @Test
    fun microphoneStartEmitsChunksThenFinal() {
        val config = CapabilityConfig(
            micChunkBytes = ByteArray(4) { it.toByte() },
            micChunkCount = 3,
        )
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        assertTrue(machine.isMicStreaming())

        machine.tick()
        assertFalse(machine.isMicStreaming())

        val msgs = messages(machine.drainEvents())
        assertEquals(3, msgs.count { it.code == HaloProtocol.AUDIO_CHUNK })
        assertEquals(1, msgs.count { it.code == HaloProtocol.AUDIO_FINAL })
        val chunks = msgs.filter { it.code == HaloProtocol.AUDIO_CHUNK }
        assertContentEquals(ByteArray(4) { it.toByte() }, chunks[0].payload)
    }

    @Test
    fun microphoneStopEmitsFinalImmediately() {
        val config = CapabilityConfig(micChunkCount = 100)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        assertTrue(machine.isMicStreaming())

        machine.handleMessage(HaloProtocol.MICROPHONE_STOP, ByteArray(0))
        assertFalse(machine.isMicStreaming())

        val msgs = messages(machine.drainEvents())
        // After stop, the most recent event should be AUDIO_FINAL
        assertEquals(HaloProtocol.AUDIO_FINAL, msgs.last().code)
    }

    @Test
    fun microphoneStopAfterPartialChunks() {
        val config = CapabilityConfig(
            micChunkBytes = ByteArray(2) { 0xAA.toByte() },
            micChunkCount = 100,
        )
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        machine.tick()
        assertTrue(machine.isMicStreaming())

        machine.handleMessage(HaloProtocol.MICROPHONE_STOP, ByteArray(0))
        assertFalse(machine.isMicStreaming())

        // After stop, tick should not emit more chunks
        val beforeCount = messages(machine.drainEvents()).size
        machine.tick()
        val afterCount = messages(machine.drainEvents()).size
        assertEquals(0, afterCount)  // no new events after stop+tick
    }

    @Test
    fun photoCaptureEmitsChunksThenFinal() {
        val photoData = ByteArray(500) { (it % 256).toByte() }
        val config = CapabilityConfig(photoData = photoData, photoChunkSize = 200)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isPhotoPending())

        machine.tick()
        assertFalse(machine.isPhotoPending())

        val msgs = messages(machine.drainEvents())
        // 500 bytes / 200 chunk = 3 chunks (200, 200, 100) + final
        assertEquals(3, msgs.count { it.code == HaloProtocol.PHOTO_JPEG })
        assertEquals(1, msgs.count { it.code == HaloProtocol.PHOTO_FINAL })

        val reassembled = msgs.filter { it.code == HaloProtocol.PHOTO_JPEG }
            .fold(ByteArray(0)) { acc, msg -> acc + msg.payload }
        assertContentEquals(photoData, reassembled)
    }

    @Test
    fun photoCaptureWhileBusyIsIgnored() {
        val config = CapabilityConfig(photoData = ByteArray(1000), photoChunkSize = 100)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isPhotoPending())

        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isPhotoPending())
    }

    @Test
    fun photoEmptyDataEmitsFinalImmediately() {
        val config = CapabilityConfig(photoData = ByteArray(0))
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        machine.tick()
        assertFalse(machine.isPhotoPending())

        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.PHOTO_FINAL, msgs[0].code)
    }

    @Test
    fun batteryResponseHasExactPayload() {
        val config = CapabilityConfig(batteryLevel = 85, batteryVoltage = 4200, batteryCharging = true)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.DEVICE_STATUS, ByteArray(0))

        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.DEVICE_STATUS, msgs[0].code)
        // [level(1)] [voltageHi(1)] [voltageLo(1)] [charging(1)]
        assertContentEquals(byteArrayOf(85, 0x10.toByte(), 0x68, 1), msgs[0].payload)
    }

    @Test
    fun batteryNotChargingPayload() {
        val config = CapabilityConfig(batteryLevel = 50, batteryVoltage = 3500, batteryCharging = false)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.DEVICE_STATUS, ByteArray(0))

        val msgs = messages(machine.drainEvents())
        assertContentEquals(byteArrayOf(50, 0x0D.toByte(), 0xAC.toByte(), 0), msgs[0].payload)
    }

    @Test
    fun buttonEventEmitsCorrectCode() {
        val machine = CapabilityStateMachine()
        machine.buttonEvent(1)
        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.BUTTON, msgs[0].code)
        assertContentEquals(byteArrayOf(1), msgs[0].payload)
    }

    @Test
    fun tapEventEmitsCorrectCode() {
        val machine = CapabilityStateMachine()
        machine.tapEvent(2)
        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.TAP, msgs[0].code)
        assertContentEquals(byteArrayOf(2), msgs[0].payload)
    }

    @Test
    fun speakerStartAndStop() {
        val machine = CapabilityStateMachine()
        assertFalse(machine.isSpeakerActive())
        machine.handleMessage(HaloProtocol.SPEAKER_START, ByteArray(0))
        assertTrue(machine.isSpeakerActive())
        machine.handleMessage(HaloProtocol.SPEAKER_STOP, ByteArray(0))
        assertFalse(machine.isSpeakerActive())
    }

    @Test
    fun unknownMessageReturnsFalse() {
        val machine = CapabilityStateMachine()
        assertFalse(machine.handleMessage(0xFF, ByteArray(0)))
    }

    @Test
    fun resetClearsAllState() {
        val machine = CapabilityStateMachine(CapabilityConfig(micChunkCount = 100))
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        machine.handleMessage(HaloProtocol.SPEAKER_START, ByteArray(0))
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isMicStreaming())
        assertTrue(machine.isSpeakerActive())
        assertTrue(machine.isPhotoPending())

        machine.reset()
        assertFalse(machine.isMicStreaming())
        assertFalse(machine.isSpeakerActive())
        assertFalse(machine.isPhotoPending())
    }

    @Test
    fun microphoneChunksAreBoundedPerTick() {
        // he_runtime.lua sends at most 10 chunks per tick
        val config = CapabilityConfig(micChunkBytes = ByteArray(1), micChunkCount = 25)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        machine.tick()
        assertTrue(machine.isMicStreaming())  // 15 more to go
        machine.tick()
        assertTrue(machine.isMicStreaming())  // 5 more
        machine.tick()
        assertFalse(machine.isMicStreaming())  // done
    }

    // ------------------------------------------------------------------ display lifecycle

    private fun validHrp(): ByteArray {
        val hrp = ByteArray(10)
        "HRP1".toByteArray(Charsets.US_ASCII).copyInto(hrp, 0)
        hrp[4] = 0
        hrp[5] = 0; hrp[6] = 1  // 1 command
        // clear command: opcode=0x01, length=3, RGB=0,0,0
        hrp[7] = 0x01
        hrp[8] = 0; hrp[9] = 3
        return hrp
    }

    @Test
    fun displayStartsInactive() {
        val machine = CapabilityStateMachine()
        assertFalse(machine.isDisplayActive())
        assertFalse(machine.isDisplayCleared())
        assertEquals(null, machine.displayError())
        assertEquals(0, machine.hrpFramesRendered())
    }

    @Test
    fun hrpActivatesDisplay() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.HRP, validHrp())
        assertTrue(machine.isDisplayActive())
        assertFalse(machine.isDisplayCleared())
        assertEquals(null, machine.displayError())
        assertEquals(1, machine.hrpFramesRendered())
    }

    @Test
    fun multipleHrpFramesIncrementCounter() {
        val machine = CapabilityStateMachine()
        repeat(3) { machine.handleMessage(HaloProtocol.HRP, validHrp()) }
        assertEquals(3, machine.hrpFramesRendered())
        assertTrue(machine.isDisplayActive())
    }

    @Test
    fun clearDisplayDeactivatesAndMarksCleared() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.HRP, validHrp())
        assertTrue(machine.isDisplayActive())
        machine.handleMessage(0x10, ByteArray(0))  // CLEAR_DISPLAY
        assertFalse(machine.isDisplayActive())
        assertTrue(machine.isDisplayCleared())
        assertEquals(null, machine.displayError())
    }

    @Test
    fun plainTextActivatesDisplay() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(0x11, "Hello\nWorld".toByteArray())  // PLAIN_TEXT
        assertTrue(machine.isDisplayActive())
        assertFalse(machine.isDisplayCleared())
        assertEquals(2, machine.plainTextLines())
    }

    @Test
    fun plainTextWithBlankLinesOnlyCountsNonEmpty() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(0x11, "Hello\n\n\nWorld\n".toByteArray())
        assertEquals(2, machine.plainTextLines())
    }

    @Test
    fun invalidHrpHeaderEmitsError() {
        val machine = CapabilityStateMachine()
        val bad = ByteArray(7) { 0x58 }  // "XXXX" magic
        machine.handleMessage(HaloProtocol.HRP, bad)
        assertFalse(machine.isDisplayActive())
        assertNotNull(machine.displayError())
        val events = machine.drainEvents()
        val errorMsg = events.filterIsInstance<DeviceEvent.Message>().firstOrNull { it.code == HaloProtocol.ERROR }
        assertNotNull(errorMsg)
        assertTrue(errorMsg.payload.toString(Charsets.UTF_8).contains("invalid HRP header"))
    }

    @Test
    fun shortHrpPayloadEmitsError() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.HRP, ByteArray(3))
        assertNotNull(machine.displayError())
        val events = machine.drainEvents()
        val errorMsg = events.filterIsInstance<DeviceEvent.Message>().firstOrNull { it.code == HaloProtocol.ERROR }
        assertNotNull(errorMsg)
    }

    @Test
    fun clearAfterHrpResetsError() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.HRP, ByteArray(3))  // invalid
        assertNotNull(machine.displayError())
        machine.handleMessage(0x10, ByteArray(0))  // CLEAR_DISPLAY
        assertEquals(null, machine.displayError())
        assertTrue(machine.isDisplayCleared())
    }

    @Test
    fun newHrpAfterErrorRecovers() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.HRP, ByteArray(3))  // invalid
        assertNotNull(machine.displayError())
        machine.handleMessage(HaloProtocol.HRP, validHrp())  // valid
        assertEquals(null, machine.displayError())
        assertTrue(machine.isDisplayActive())
        assertEquals(1, machine.hrpFramesRendered())  // only valid frame counted
    }

    @Test
    fun resetClearsDisplayState() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.HRP, validHrp())
        machine.handleMessage(HaloProtocol.SPEAKER_START, ByteArray(0))
        assertTrue(machine.isDisplayActive())
        assertTrue(machine.isSpeakerActive())

        machine.reset()
        assertFalse(machine.isDisplayActive())
        assertFalse(machine.isDisplayCleared())
        assertEquals(null, machine.displayError())
        assertEquals(0, machine.hrpFramesRendered())
        assertEquals(0, machine.plainTextLines())
    }

    @Test
    fun displayLifecycleFullSequence() {
        val machine = CapabilityStateMachine()
        // 1. Start with HRP frame
        machine.handleMessage(HaloProtocol.HRP, validHrp())
        assertTrue(machine.isDisplayActive())
        assertEquals(1, machine.hrpFramesRendered())

        // 2. Clear display
        machine.handleMessage(0x10, ByteArray(0))
        assertTrue(machine.isDisplayCleared())
        assertFalse(machine.isDisplayActive())

        // 3. Plain text
        machine.handleMessage(0x11, "Status".toByteArray())
        assertTrue(machine.isDisplayActive())
        assertFalse(machine.isDisplayCleared())
        assertEquals(1, machine.plainTextLines())

        // 4. Another HRP frame
        machine.handleMessage(HaloProtocol.HRP, validHrp())
        assertTrue(machine.isDisplayActive())
        assertEquals(2, machine.hrpFramesRendered())

        // 5. Clear and reset
        machine.handleMessage(0x10, ByteArray(0))
        machine.reset()
        assertFalse(machine.isDisplayActive())
        assertEquals(0, machine.hrpFramesRendered())
    }

    // ------------------------------------------------------------------ v3 controls

    @Test
    fun soundPlayRecordsPresetName() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.SOUND_PLAY, HaloCommands.soundPlay("pickup", volume = 60))
        assertEquals(listOf("pickup"), machine.soundRequests())
        assertTrue(messages(machine.drainEvents()).none { it.code == HaloProtocol.ERROR })
    }

    @Test
    fun soundPlayUnknownPresetEmitsError() {
        val machine = CapabilityStateMachine()
        // flags=0 then a bogus name
        machine.handleMessage(HaloProtocol.SOUND_PLAY, byteArrayOf(0) + "nonsense".toByteArray())
        val errors = messages(machine.drainEvents()).filter { it.code == HaloProtocol.ERROR }
        assertEquals(1, errors.size)
        assertTrue(errors[0].payload.toString(Charsets.UTF_8).contains("unknown sound preset"))
    }

    @Test
    fun systemDisplayPowerSaveToggles() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.SYSTEM, HaloCommands.systemFlag(HaloProtocol.SYS_DISPLAY_SLEEP, true))
        // SLEEP takes no arg in the runtime, but the flag form is accepted here.
        assertTrue(machine.isDisplayPowerSave())
        machine.handleMessage(HaloProtocol.SYSTEM, HaloCommands.system(HaloProtocol.SYS_DISPLAY_WAKE))
        assertFalse(machine.isDisplayPowerSave())
    }

    @Test
    fun systemCameraPowerSaveFlag() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.SYSTEM, HaloCommands.systemFlag(HaloProtocol.SYS_CAMERA_POWER_SAVE, true))
        assertTrue(machine.isCameraPowerSave())
        machine.handleMessage(HaloProtocol.SYSTEM, HaloCommands.systemFlag(HaloProtocol.SYS_CAMERA_POWER_SAVE, false))
        assertFalse(machine.isCameraPowerSave())
    }

    @Test
    fun systemSleepSubcommandsRecorded() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.SYSTEM, HaloCommands.system(HaloProtocol.SYS_STANDBY))
        machine.handleMessage(HaloProtocol.SYSTEM, HaloCommands.systemSeconds(HaloProtocol.SYS_LIGHT_SLEEP, 30))
        assertEquals(listOf(HaloProtocol.SYS_STANDBY, HaloProtocol.SYS_LIGHT_SLEEP), machine.systemOps())
    }

    @Test
    fun setTimeParsesEpochAndZone() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.SET_TIME, HaloCommands.setTime(1_700_000_000L, "+02:00"))
        assertEquals(1_700_000_000L to "+02:00", machine.lastTimeSync())
    }

    @Test
    fun imuReadEmitsImuPayload() {
        val machine = CapabilityStateMachine()
        machine.handleMessage(HaloProtocol.IMU_READ, ByteArray(0))
        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.IMU, msgs[0].code)
        assertEquals("0.00;0.00;0.0;0.0;0.0;0.0;0.0;1000.0", msgs[0].payload.toString(Charsets.UTF_8))
    }

    @Test
    fun tapConfigIsRecorded() {
        val machine = CapabilityStateMachine()
        val payload = HaloCommands.tapConfig(mode = "sensitive", threshold = 12)
        machine.handleMessage(HaloProtocol.TAP_CONFIG, payload)
        assertContentEquals(payload, machine.lastTapConfig())
    }
}
