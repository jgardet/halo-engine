package halo.engine

/**
 * Message and control codes shared by the host, the device-side runtime, and
 * the firmware's BLE Lua service.
 *
 * App message codes are the first payload byte inside a data transfer (after
 * [LUA_CTRL_DATA_MARKER]). Control codes are written directly to the control
 * characteristic as a single byte and are interpreted by the firmware before
 * any REPL or data routing happens. The two namespaces intentionally overlap
 * numerically — the firmware distinguishes them by context (single-byte write
 * vs framed data marker), so always use the correctly-prefixed constant.
 */
object HaloProtocol {

    /** Firmware control codes sent as single-byte writes to the Lua control characteristic. */
    const val LUA_CTRL_DATA_MARKER = 0x01
    const val LUA_CTRL_REBOOT = 0x02
    const val LUA_CTRL_INTERRUPT = 0x03
    const val LUA_CTRL_RESTART = 0x04
    const val LUA_CTRL_RESET = 0x05
    const val LUA_CTRL_EXIT = 0x06
    const val LUA_CTRL_REMOVE_ALL = 0x07

    /** Application message codes, used as the first byte after [LUA_CTRL_DATA_MARKER]. */
    const val AUDIO_CHUNK = 0x05
    const val AUDIO_FINAL = 0x06
    const val PHOTO_JPEG = 0x07
    const val PHOTO_FINAL = 0x08
    const val TAP = 0x09
    const val IMU = 0x0A
    const val BUTTON = 0x0B
    const val CLEAR_DISPLAY = 0x10
    const val PLAIN_TEXT = 0x11
    const val CAPTURE_PHOTO = 0x20
    const val MICROPHONE_START = 0x30
    const val MICROPHONE_STOP = 0x31
    const val SPEAKER_START = 0x40
    const val SPEAKER_STOP = 0x41
    const val SOUND_PLAY = 0x50
    const val SYSTEM = 0x51
    const val SET_TIME = 0x52
    const val IMU_READ = 0x53
    const val TAP_CONFIG = 0x54
    const val HRP = 0x60
    /** host→dev: persist a packed sprite asset under `spr_<key>` for cached defines. */
    const val SPRITE_STORE = 0x61
    const val STATUS = 0x70
    const val ERROR = 0x71
    const val DEVICE_STATUS = 0x72
    /** dev→host: acknowledges a `SPRITE_STORE`; payload is the stored key. */
    const val SPRITE_STORED = 0x73

    /** Subcommand bytes for [SYSTEM] messages (see `he_runtime.lua` `handle_system`). */
    const val SYS_DISPLAY_SLEEP = 0x00
    const val SYS_DISPLAY_WAKE = 0x01
    const val SYS_STANDBY = 0x02
    const val SYS_LIGHT_SLEEP = 0x03
    const val SYS_STAY_AWAKE = 0x04
    const val SYS_SHIP_MODE = 0x05
    const val SYS_CHARGE = 0x06
    const val SYS_DEEP_SLEEP = 0x07
    const val SYS_CAMERA_POWER_SAVE = 0x08
}
