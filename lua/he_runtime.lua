-- Halo device-side runtime (v3)
-- Supports HRP display, microphone, speaker, camera, battery, input events,
-- on-device sound effects, system/power control, clock sync, IMU reads, and
-- tap-detector tuning.

local CLEAR_DISPLAY = 0x10
local PLAIN_TEXT = 0x11
local CAPTURE_PHOTO = 0x20
local MICROPHONE_START = 0x30
local MICROPHONE_STOP = 0x31
local SPEAKER_START = 0x40
local SPEAKER_STOP = 0x41
local SOUND_PLAY = 0x50
local SYSTEM = 0x51
local SET_TIME = 0x52
local IMU_READ = 0x53
local TAP_CONFIG = 0x54
local AUDIO_CHUNK = 0x05
local AUDIO_FINAL = 0x06
local PHOTO_JPEG = 0x07
local PHOTO_FINAL = 0x08
local HRP_CODE = 0x60
local SPRITE_STORE = 0x61
local TAP_CODE = 0x09
local IMU_CODE = 0x0A
local BUTTON_CODE = 0x0B
local BATTERY_CODE = 0x72
local STATUS_CODE = 0x70
local ERROR_CODE = 0x71
local SPRITE_STORED = 0x73
local MAX_DATA_BYTES = 32768

-- Sprite file cache: packed assets persist as spr_<key> (hex-encoded) so
-- repeated presentations can use the cached-define HRP opcode instead of
-- re-uploading pixels. Bounded to keep flash usage predictable.
local MAX_SPRITE_CACHE = 32

-- Bumped on every runtime change; advertised in STATUS as ;rt=<version> so
-- hosts can skip re-upload when the autorunning runtime is already current.
local RUNTIME_VERSION = '3.2'

local QUALITIES = { 'VERY_LOW', 'LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH' }
local SOUND_PRESETS = { pickup = true, laser = true, explosion = true,
    powerup = true, hit = true, jump = true, blip = true }

local SYS_DISPLAY_SLEEP = 0x00
local SYS_DISPLAY_WAKE = 0x01
local SYS_STANDBY = 0x02
local SYS_LIGHT_SLEEP = 0x03
local SYS_STAY_AWAKE = 0x04
local SYS_SHIP_MODE = 0x05
local SYS_CHARGE = 0x06
local SYS_DEEP_SLEEP = 0x07
local SYS_CAMERA_POWER = 0x08

-- Minimal local equivalent of the official data.lua framing.
local pending = {}
local completed = {}
local completed_count = 0

-- frame.bluetooth.send fails while the radio is busy; retry briefly like the
-- vendor data.lua does, then give up so the caller can report an error.
local function bt_send(data)
    for _ = 1, 3 do
        local ok = pcall(frame.bluetooth.send, data)
        if ok then return true end
        frame.sleep(0.02)
    end
    return false
end

local function send_event(code, payload)
    bt_send(string.char(code) .. (payload or ''))
end

local function receive_data(packet)
    if packet == nil or #packet < 1 then return end
    local flag = string.byte(packet, 1)
    local item = pending[flag]
    if item == nil then
        item = { size = 0, received = 0, chunks = {} }
        pending[flag] = item
    end
    if item.received == 0 then
        if #packet < 3 then
            pending[flag] = nil
            send_event(ERROR_CODE, 'invalid first packet')
            return
        end
        item.size = string.byte(packet, 2) << 8 | string.byte(packet, 3)
        if item.size > MAX_DATA_BYTES then
            pending[flag] = nil
            send_event(ERROR_CODE, 'message exceeds runtime limit')
            return
        end
        item.chunks[1] = string.sub(packet, 4)
        item.received = #packet - 3
    else
        item.chunks[#item.chunks + 1] = string.sub(packet, 2)
        item.received = item.received + #packet - 1
    end
    if item.received == item.size then
        completed_count = completed_count + 1
        completed[completed_count] = { flag, table.concat(item.chunks) }
        pending[flag] = nil
    elseif item.received > item.size then
        pending[flag] = nil
        send_event(ERROR_CODE, 'message length overflow')
        return
    end
    bt_send('\x01\x00\x00')
end

local function process_raw_items()
    local items = completed
    completed = {}
    completed_count = 0
    collectgarbage('collect')
    return items
end

frame.bluetooth.receive_callback(receive_data)

local sprites = {}
local font_id = 0
local font_size = 8
local font_scale = 1

local function u16(s, p)
    return string.byte(s, p) << 8 | string.byte(s, p + 1)
end

local function color(s, p)
    return string.byte(s, p) << 16 | string.byte(s, p + 1) << 8 | string.byte(s, p + 2)
end

local function require_len(payload, position, needed)
    if position + needed - 1 > #payload then
        error('truncated command')
    end
end

-- HRP executor (kept from v1, 0-indexed coordinates converted to 1-indexed Lua API).
local function parse_sprite(payload)
    require_len(payload, 1, 9)
    local id = u16(payload, 1)
    local raw = string.sub(payload, 3)
    local width = u16(raw, 1)
    local height = u16(raw, 3)
    local compressed = string.byte(raw, 5) > 0
    local bpp = string.byte(raw, 6)
    local num_colors = string.byte(raw, 7)
    if bpp ~= 1 and bpp ~= 2 and bpp ~= 4 then error('invalid HRP sprite bpp') end
    local palette_start = 8
    local palette_len = num_colors * 3
    require_len(raw, palette_start, palette_len)
    local pixels = string.sub(raw, palette_start + palette_len)
    if compressed then
        if frame.compression == nil then error('compressed sprites require frame.compression') end
        local blocks = {}
        frame.compression.process_function(function(d) blocks[#blocks + 1] = d end)
        local ok, err = pcall(frame.compression.decompress, pixels, 4096)
        frame.compression.process_function(nil)
        if not ok then error('sprite decompress failed: ' .. tostring(err)) end
        pixels = table.concat(blocks)
    end
    sprites[id] = {
        width = width,
        height = height,
        bpp = bpp,
        num_colors = num_colors,
        palette_data = string.sub(raw, palette_start, palette_start + palette_len - 1),
        pixel_data = pixels
    }
end

-- Sprite file cache (spr_<key> files). Assets are stored hex-encoded so the
-- cache survives text-mode file APIs; keys are restricted to a filename-safe
-- alphabet and a small bound keeps flash usage predictable.
local function valid_cache_key(key)
    return #key >= 1 and #key <= 64 and key:match('^[A-Za-z0-9_%-]+$') ~= nil
end

local function to_hex(data)
    return (data:gsub('.', function(c)
        return string.format('%02x', string.byte(c))
    end))
end

local function from_hex(data)
    if #data % 2 ~= 0 then error('corrupt sprite cache entry') end
    return (data:gsub('..', function(cc)
        local b = tonumber(cc, 16)
        if b == nil then error('corrupt sprite cache entry') end
        return string.char(b)
    end))
end

local function sprite_cache_read(key)
    local ok, f = pcall(frame.file.open, 'spr_' .. key, 'r')
    if not ok or f == nil then return nil end
    local chunks = {}
    while true do
        local ok_r, chunk = pcall(function() return f:read() end)
        if not ok_r or chunk == nil then break end
        chunks[#chunks + 1] = chunk
    end
    f:close()
    if #chunks == 0 then return nil end
    local ok_h, asset = pcall(from_hex, table.concat(chunks))
    if not ok_h then return nil end
    return asset
end

-- Firmware returns a Lua table from listdir; the Python emulator bridges a
-- 0-based userdata list that raises past the end -- normalize both.
local function sprite_cache_names()
    local ok, names = pcall(frame.file.listdir)
    if not ok or names == nil then return {} end
    local out = {}
    if type(names) == 'table' then
        for _, name in ipairs(names) do out[#out + 1] = name end
    else
        local i = 0
        while true do
            local ok_i, name = pcall(function() return names[i] end)
            if not ok_i or name == nil then break end
            out[#out + 1] = name
            i = i + 1
        end
    end
    return out
end

local function sprite_cache_evict_one()
    local names = sprite_cache_names()
    table.sort(names)
    for _, name in ipairs(names) do
        if name:sub(1, 4) == 'spr_' then
            pcall(frame.file.remove, name)
            return
        end
    end
end

local function sprite_cache_count()
    local n = 0
    for _, name in ipairs(sprite_cache_names()) do
        if name:sub(1, 4) == 'spr_' then n = n + 1 end
    end
    return n
end

local function store_sprite(payload)
    require_len(payload, 1, 1)
    local key_len = string.byte(payload, 1)
    require_len(payload, 2, key_len)
    local key = string.sub(payload, 2, 1 + key_len)
    if not valid_cache_key(key) then error('invalid sprite cache key') end
    local asset = string.sub(payload, 2 + key_len)
    if #asset < 7 then error('sprite store: asset too small') end
    local name = 'spr_' .. key
    local ok_open, existing = pcall(frame.file.open, name, 'r')
    if ok_open and existing ~= nil then
        existing:close()
        return key
    end
    if sprite_cache_count() >= MAX_SPRITE_CACHE then
        sprite_cache_evict_one()
    end
    local ok_w, wf = pcall(frame.file.open, name, 'w')
    if not ok_w or wf == nil then error('sprite store: cannot write ' .. name) end
    wf:write(to_hex(asset))
    wf:close()
    return key
end

local function execute_hrp(payload)
    if #payload > MAX_DATA_BYTES then error('HRP frame exceeds runtime limit') end
    if string.sub(payload, 1, 4) ~= 'HRP1' or string.byte(payload, 5) ~= 0 then
        error('invalid HRP header')
    end
    local count = u16(payload, 6)
    local position = 8
    for _ = 1, count do
        require_len(payload, position, 3)
        local opcode = string.byte(payload, position)
        local length = u16(payload, position + 1)
        position = position + 3
        require_len(payload, position, length)
        local command = string.sub(payload, position, position + length - 1)
        position = position + length

        if opcode == 0x01 then
            require_len(command, 1, 3)
            frame.display.clear(color(command, 1))
        elseif opcode == 0x02 then
            require_len(command, 1, 1)
            frame.display.brightness(string.byte(command, 1))
        elseif opcode == 0x03 then
            require_len(command, 1, 7)
            frame.display.set_pixel(u16(command, 1) + 1, u16(command, 3) + 1, color(command, 5))
        elseif opcode == 0x04 then
            require_len(command, 1, 11)
            frame.display.line(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5) + 1, u16(command, 7) + 1, color(command, 9))
        elseif opcode == 0x05 then
            require_len(command, 1, 12)
            frame.display.rect(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5), u16(command, 7), color(command, 9), string.byte(command, 12) ~= 0)
        elseif opcode == 0x06 then
            require_len(command, 1, 10)
            frame.display.circle(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5), color(command, 7), string.byte(command, 10) ~= 0)
        elseif opcode == 0x07 then
            require_len(command, 1, 4)
            local points = string.byte(command, 1)
            if points > 64 then error('too many polygon points') end
            local expected = 1 + points * 4 + 3
            require_len(command, 1, expected)
            local coords = {}
            for i = 0, points - 1 do
                coords[i * 2 + 1] = u16(command, 2 + i * 4) + 1
                coords[i * 2 + 2] = u16(command, 4 + i * 4) + 1
            end
            frame.display.polygon(coords, color(command, 2 + points * 4))
        elseif opcode == 0x08 then
            require_len(command, 1, 3)
            font_id = string.byte(command, 1)
            font_size = string.byte(command, 2)
            font_scale = string.byte(command, 3)
            frame.display.set_font(font_id, font_size, font_scale)
        elseif opcode == 0x09 then
            require_len(command, 1, 9)
            local text_len = u16(command, 8)
            require_len(command, 1, 9 + text_len)
            frame.display.text(string.sub(command, 10, 9 + text_len), u16(command, 1) + 1, u16(command, 3) + 1, color(command, 5))
        elseif opcode == 0x0A then
            parse_sprite(command)
        elseif opcode == 0x0B then
            require_len(command, 1, 7)
            local sprite = sprites[u16(command, 1)]
            if sprite == nil then error('sprite resource not found') end
            -- frame.display.bitmap signature: (x, y, width, color_format, palette_offset, data, options)
            frame.display.bitmap(u16(command, 3) + 1, u16(command, 5) + 1, sprite.width, 2 ^ sprite.bpp, string.byte(command, 7), sprite.pixel_data, { palette_data = sprite.palette_data })
        elseif opcode == 0x0C then
            require_len(command, 1, 2)
            sprites[u16(command, 1)] = nil
            collectgarbage('collect')
        elseif opcode == 0x0D then
            -- dirty region hint, no-op
        elseif opcode == 0x0E then
            -- show() is no-op on Halo
        elseif opcode == 0x0F then
            -- feature negotiation, no-op in v2
        elseif opcode == 0x10 then
            -- cached sprite define: [id u16][key_len u8][key utf8]
            require_len(command, 1, 3)
            local id = u16(command, 1)
            local key_len = string.byte(command, 3)
            require_len(command, 4, key_len)
            local key = string.sub(command, 4, 3 + key_len)
            if not valid_cache_key(key) then error('invalid sprite cache key') end
            local asset = sprite_cache_read(key)
            if asset == nil then error('sprite cache miss: ' .. key) end
            parse_sprite(string.char(id >> 8, id & 0xFF) .. asset)
        else
            error('unknown HRP opcode ' .. tostring(opcode))
        end
    end
    if position ~= #payload + 1 then
        error('trailing HRP bytes')
    end
    collectgarbage('collect')
end

-- State for streaming features.
local micStreaming = false
local micConfig = {}
local photoPending = nil

-- Display helpers.
local COLORS = {
    0x000000, 0xFFFFFF, 0x808080, 0xFF0000, 0xFFC0CB, 0x654321, 0x964B00,
    0xFFA500, 0xFFFF00, 0x006400, 0x00FF00, 0x90EE90, 0x191970, 0x0000CD,
    0x87CEEB, 0xF0F8FF
}

local function draw_plain_text(payload)
    if #payload < 6 then return end
    local x = u16(payload, 1)
    local y = u16(payload, 3)
    local palette = string.byte(payload, 5) % 16 + 1
    local c = COLORS[palette]
    local spacing = string.byte(payload, 6)
    local text = string.sub(payload, 7)
    local i = 0
    for line in text:gmatch('([^\r\n]*)\r?\n?') do
        if line ~= '' then
            frame.display.text(line, x, y + i * spacing, c)
            i = i + 1
        end
    end
end

-- Audio helpers.
local function mic_chunk_size()
    local max = frame.bluetooth.max_length() - 1
    if max % 2 == 1 then max = max - 1 end
    return max
end

local function send_mic_chunks()
    if not micStreaming then return end
    local max = mic_chunk_size()
    for _ = 1, 10 do
        local data = frame.microphone.read(max)
        if data == nil then
            send_event(AUDIO_FINAL, '')
            micStreaming = false
            break
        end
        if data ~= '' then
            if not bt_send(string.char(AUDIO_CHUNK) .. data) then
                print('mic send error')
                send_event(ERROR_CODE, 'mic send failed')
                micStreaming = false
                break
            end
        end
    end
end

-- Camera helpers.
local function photo_chunk_size()
    return frame.bluetooth.max_length() - 1
end

local function send_photo()
    if not photoPending then return end
    if not frame.camera.image_ready() then return end
    local max = photo_chunk_size()
    while true do
        local chunk = frame.camera.read(max)
        if chunk == nil then
            send_event(PHOTO_FINAL, '')
            photoPending = nil
            pcall(frame.camera.power_save, true)
            collectgarbage('collect')
            break
        end
        if chunk ~= '' then
            if not bt_send(string.char(PHOTO_JPEG) .. chunk) then
                print('photo send error')
                send_event(ERROR_CODE, 'photo send failed')
                photoPending = nil
                pcall(frame.camera.power_save, true)
                break
            end
        end
    end
end

-- Battery helper.
local function send_battery()
    local level = frame.battery_level()
    local voltage = frame.battery_voltage()
    local charging = frame.battery_charging() and 1 or 0
    local payload = string.char(level) .. string.char(voltage >> 8) .. string.char(voltage & 0xFF) .. string.char(charging)
    send_event(BATTERY_CODE, payload)
end

-- Sound effect (SFXR preset). Payload: flags byte (b0=volume, b1=duration_ms,
-- b2=seed), optional fields in that order, then the preset name.
local function play_sound(payload)
    if #payload < 2 then error('sound payload too short') end
    local flags = string.byte(payload, 1)
    local pos = 2
    local opts = {}
    if flags & 1 ~= 0 then
        require_len(payload, pos, 1)
        opts.volume = string.byte(payload, pos)
        pos = pos + 1
    end
    if flags & 2 ~= 0 then
        require_len(payload, pos, 2)
        opts.duration_ms = u16(payload, pos)
        pos = pos + 2
    end
    if flags & 4 ~= 0 then
        require_len(payload, pos, 2)
        opts.seed = u16(payload, pos)
        pos = pos + 2
    end
    local name = string.sub(payload, pos)
    if not SOUND_PRESETS[name] then error('unknown sound preset') end
    local ok, err = frame.sound.play_async(name, opts)
    if not ok then error('sound failed: ' .. tostring(err)) end
end

-- System/power subcommands.
local function handle_system(payload)
    if #payload < 1 then error('system payload too short') end
    local sub = string.byte(payload, 1)
    if sub == SYS_DISPLAY_SLEEP then
        frame.display.power_save(true)
    elseif sub == SYS_DISPLAY_WAKE then
        frame.display.power_save(false)
    elseif sub == SYS_STANDBY then
        local sec = (#payload >= 3) and u16(payload, 2) or 0
        if sec > 0 then frame.standby(sec) else frame.standby() end
    elseif sub == SYS_LIGHT_SLEEP then
        local sec = (#payload >= 3) and u16(payload, 2) or 0
        if sec > 0 then frame.light_sleep(sec) else frame.light_sleep() end
    elseif sub == SYS_STAY_AWAKE then
        require_len(payload, 2, 1)
        frame.stay_awake(string.byte(payload, 2) ~= 0)
    elseif sub == SYS_SHIP_MODE then
        frame.ship_mode()
    elseif sub == SYS_CHARGE then
        require_len(payload, 2, 1)
        frame.charge(string.byte(payload, 2) ~= 0)
    elseif sub == SYS_DEEP_SLEEP then
        local sec = (#payload >= 3) and u16(payload, 2) or 0
        if sec > 0 then frame.sleep(sec) else frame.sleep() end
    elseif sub == SYS_CAMERA_POWER then
        require_len(payload, 2, 1)
        frame.camera.power_save(string.byte(payload, 2) ~= 0)
    else
        error('unknown system subcommand ' .. tostring(sub))
    end
end

-- Clock sync. Payload: u32 unix seconds (big-endian) + optional zone string.
local function handle_set_time(payload)
    if #payload < 4 then error('set_time payload too short') end
    local ts = (string.byte(payload, 1) << 24) | (string.byte(payload, 2) << 16)
        | (string.byte(payload, 3) << 8) | string.byte(payload, 4)
    frame.time.utc(ts)
    if #payload > 4 then
        frame.time.zone(string.sub(payload, 5))
    end
end

-- IMU snapshot: direction (pitch/roll, deg) + raw compass (µT) and
-- accelerometer (mg), as a ';'-separated string the host parses.
local function send_imu()
    local dir = frame.imu.direction()
    local raw = frame.imu.raw()
    local compass = raw.compass or {}
    local accel = raw.accelerometer or {}
    send_event(IMU_CODE, string.format('%.2f;%.2f;%.1f;%.1f;%.1f;%.1f;%.1f;%.1f',
        dir.pitch or 0, dir.roll or 0,
        compass.x or 0, compass.y or 0, compass.z or 0,
        accel.x or 0, accel.y or 0, accel.z or 0))
end

-- Tap detector tuning. Flags select which fields are present, in order:
-- b0=mode, b1=axis, b2=threshold(u16), b3=gesture_duration, b4=wait_for_timeout.
local TAP_MODES = { [0] = 'sensitive', 'normal', 'robust' }
local TAP_AXES = { [0] = 'x', 'y', 'z' }
local function handle_tap_config(payload)
    if #payload < 1 then error('tap_config payload too short') end
    local flags = string.byte(payload, 1)
    local pos = 2
    local opts = {}
    if flags & 1 ~= 0 then
        require_len(payload, pos, 1)
        opts.mode = TAP_MODES[string.byte(payload, pos)] or error('bad tap mode')
        pos = pos + 1
    end
    if flags & 2 ~= 0 then
        require_len(payload, pos, 1)
        opts.axis = TAP_AXES[string.byte(payload, pos)] or error('bad tap axis')
        pos = pos + 1
    end
    if flags & 4 ~= 0 then
        require_len(payload, pos, 2)
        opts.threshold = u16(payload, pos)
        pos = pos + 2
    end
    if flags & 8 ~= 0 then
        require_len(payload, pos, 1)
        opts.gesture_duration = string.byte(payload, pos)
        pos = pos + 1
    end
    if flags & 16 ~= 0 then
        require_len(payload, pos, 1)
        opts.wait_for_timeout = string.byte(payload, pos) ~= 0
        pos = pos + 1
    end
    frame.imu.tap_config(opts)
end

-- Optional mpix pipeline tail on CAPTURE_PHOTO. Byte 7 is an op count,
-- then per-op entries: 0x01 crop(x,y,w,h u16) · 0x02 resize_subsample(w,h
-- u16) · 0x03 denoise_3x3 · 0x04 denoise_5x5 · 0x05 conv3x3(kernel u8) ·
-- 0x06 conv5x5(kernel u8) · 0x07 jpeg_quality(u8). When ops are present the
-- default pipeline is rebuilt with them inserted before jpeg_encode.
local MPIX_KERNELS = { [0] = 'edge_detect', 'gaussian_blur', 'identity', 'sharpen' }
local mpixCustom = false

local function mpix_available()
    return frame.camera ~= nil and frame.camera.mpix ~= nil
end

local function apply_mpix_ops(payload, pos)
    if not mpix_available() then error('mpix unavailable') end
    local op = frame.camera.mpix.op
    require_len(payload, pos, 1)
    local count = string.byte(payload, pos)
    pos = pos + 1
    local ops = {}
    local ctrls = {}
    for _ = 1, count do
        require_len(payload, pos, 1)
        local code = string.byte(payload, pos)
        pos = pos + 1
        if code == 0x01 then
            require_len(payload, pos, 8)
            local x, y, w, h = u16(payload, pos), u16(payload, pos + 2),
                u16(payload, pos + 4), u16(payload, pos + 6)
            table.insert(ops, function(p) op.crop(p, x, y, w, h) end)
            pos = pos + 8
        elseif code == 0x02 then
            require_len(payload, pos, 4)
            local w, h = u16(payload, pos), u16(payload, pos + 2)
            table.insert(ops, function(p) op.resize_subsample(p, w, h) end)
            pos = pos + 4
        elseif code == 0x03 then
            table.insert(ops, function(p) op.kernel_denoise_3x3(p) end)
        elseif code == 0x04 then
            table.insert(ops, function(p) op.kernel_denoise_5x5(p) end)
        elseif code == 0x05 or code == 0x06 then
            require_len(payload, pos, 1)
            local kernel = MPIX_KERNELS[string.byte(payload, pos)]
                or error('bad mpix kernel')
            local conv = (code == 0x05) and op.kernel_convolve_3x3 or op.kernel_convolve_5x5
            table.insert(ops, function(p) conv(p, kernel) end)
            pos = pos + 1
        elseif code == 0x07 then
            require_len(payload, pos, 1)
            local q = string.byte(payload, pos)
            table.insert(ctrls, function()
                frame.camera.mpix.set_ctrl(frame.camera.mpix.cid.JPEG_QUALITY, q)
            end)
            pos = pos + 1
        else
            error('unknown mpix op ' .. tostring(code))
        end
    end
    for _, fn in ipairs(ctrls) do fn() end
    local pipeline = {}
    op.debayer_2x2(pipeline)
    op.correct_black_level(pipeline)
    op.correct_white_balance(pipeline)
    for _, fn in ipairs(ops) do fn(pipeline) end
    op.jpeg_encode(pipeline)
    frame.camera.mpix.set_pipeline(pipeline)
    mpixCustom = true
end

-- Restore the firmware default pipeline after a custom one was installed.
local function reset_mpix_pipeline()
    if not mpixCustom then return end
    if not mpix_available() then return end
    local op = frame.camera.mpix.op
    local pipeline = {}
    op.debayer_2x2(pipeline)
    op.correct_black_level(pipeline)
    op.correct_white_balance(pipeline)
    op.jpeg_encode(pipeline)
    pcall(frame.camera.mpix.set_pipeline, pipeline)
    mpixCustom = false
end

-- Capability announcement, sent at boot and in reply to a host STATUS query.
-- A query lets the host detect an already-running runtime (main.lua autorun)
-- whose boot STATUS went out before the BLE host connected.
local function send_status()
    local fw_version = 'unknown'
    pcall(function() fw_version = tostring(frame.FIRMWARE_VERSION or 'unknown') end)
    local eui_suffix = ''
    pcall(function() eui_suffix = ';eui=' .. tostring(frame.get_eui()) end)
    local wake_suffix = ''
    pcall(function() wake_suffix = ';wake=' .. tostring(frame.wakeup_source()) end)
    local mpix_suffix = ''
    pcall(function() if mpix_available() then mpix_suffix = ';mpix' end end)
    local lz4_suffix = ''
    pcall(function() if frame.compression ~= nil then lz4_suffix = ',lz4' end end)
    local cache_suffix = ''
    pcall(function() if frame.file ~= nil then cache_suffix = ',spritecache' end end)
    send_event(STATUS_CODE, 'HRP1;primitives,sprites,click,tap,mic,speaker,photo,battery,sound,system,time,imu'
        .. mpix_suffix .. lz4_suffix .. cache_suffix
        .. ';fw=' .. fw_version .. ';rt=' .. RUNTIME_VERSION .. eui_suffix .. wake_suffix)
end

-- Message dispatch.
local function handle_message(code, payload)
    if code == HRP_CODE then
        local ok, err = pcall(execute_hrp, payload)
        if not ok then
            send_event(ERROR_CODE, tostring(err))
            print(err)
        end
    elseif code == CLEAR_DISPLAY then
        frame.display.clear()
    elseif code == PLAIN_TEXT then
        pcall(draw_plain_text, payload)
    elseif code == MICROPHONE_START then
        micConfig = {
            encoder = 'pcm', sample_rate = 16000, bit_depth = 16,
            channels = 1, gain = 0, aec = true, voice = false,
        }
        if #payload >= 3 then
            -- The wire byte is the public gain value offset by +10.
            -- Public gain range is -10..10; decode before passing to firmware.
            local rawGain = string.byte(payload, 1)
            local gain = rawGain - 10
            if gain > 10 then gain = 10 end
            if gain < -10 then gain = -10 end
            micConfig.gain = gain
            micConfig.aec = string.byte(payload, 2) ~= 0
            micConfig.voice = string.byte(payload, 3) ~= 0
        end
        if #payload >= 7 then
            micConfig.encoder = (string.byte(payload, 4) == 1) and 'lc3' or 'pcm'
            micConfig.sample_rate = u16(payload, 5)
            micConfig.bit_depth = string.byte(payload, 7)
        end
        if #payload >= 8 then
            micConfig.channels = string.byte(payload, 8)
        end
        if micConfig.encoder == 'lc3' then
            micConfig.bit_depth = 16
            micConfig.duration = 1000
            if #payload >= 10 then micConfig.bitrate = u16(payload, 9) end
        end
        if micConfig.sample_rate ~= 8000 and micConfig.sample_rate ~= 16000 then
            send_event(ERROR_CODE, 'unsupported mic sample rate')
        elseif micConfig.bit_depth ~= 8 and micConfig.bit_depth ~= 16 then
            send_event(ERROR_CODE, 'unsupported mic bit depth')
        else
            local ok, err = pcall(frame.microphone.start, micConfig)
            if ok then
                micStreaming = true
            else
                print('mic start error: ' .. tostring(err))
                send_event(ERROR_CODE, 'mic start failed')
            end
        end
    elseif code == MICROPHONE_STOP then
        pcall(frame.microphone.stop)
    elseif code == SPEAKER_START then
        local config = { encoder = 'pcm', sample_rate = 16000, bit_depth = 16, channels = 1, volume = 80 }
        if #payload >= 1 then config.encoder = (string.byte(payload, 1) == 1) and 'lc3' or 'pcm' end
        if #payload >= 3 then config.sample_rate = u16(payload, 2) end
        if #payload >= 4 then config.bit_depth = string.byte(payload, 4) end
        if #payload >= 5 then config.channels = string.byte(payload, 5) end
        if #payload >= 6 then config.volume = string.byte(payload, 6) end
        if #payload >= 7 then
            local g = string.byte(payload, 7)
            if g > 12 then g = 12 end
            if g > 0 then config.gain = g end
        end
        if #payload >= 8 then
            local b = string.byte(payload, 8)
            if b > 100 then b = 100 end
            if b >= 10 then config.budget = b end
        end
        if config.encoder == 'lc3' then
            config.duration = 1000
            if #payload >= 10 then config.duration = u16(payload, 9) end
            if #payload >= 12 then config.bitrate = u16(payload, 11) end
        end
        local ok, err = pcall(frame.speaker.start, config)
        if not ok then
            print('speaker start error: ' .. tostring(err))
            send_event(ERROR_CODE, 'speaker start failed')
        end
    elseif code == SPEAKER_STOP then
        pcall(frame.speaker.stop)
    elseif code == CAPTURE_PHOTO then
        if photoPending then return end
        -- The wire quality index is 0-based (0=VERY_LOW through 4=VERY_HIGH).
        -- QUALITIES is 1-based, so add one when looking it up.
        local quality_index = 4
        local half_res = 256
        local pan_shifted = 140
        local raw = false
        if #payload >= 6 then
            quality_index = string.byte(payload, 1)
            half_res = u16(payload, 2)
            pan_shifted = u16(payload, 4)
            raw = string.byte(payload, 6) ~= 0
        end
        if quality_index < 0 then quality_index = 0 end
        if quality_index > 4 then quality_index = 4 end
        local quality = QUALITIES[quality_index + 1]
        local resolution = half_res * 2
        -- Halo's capture() accepts only resolution and quality; pan and raw
        -- are Frame-era fields kept on the wire for compatibility but never
        -- passed to the firmware (raw is a read mode, see read_raw()).
        local cfg = { resolution = resolution, quality = quality }
        if #payload >= 7 then
            local ok, err = pcall(apply_mpix_ops, payload, 7)
            if not ok then
                send_event(ERROR_CODE, 'mpix failed: ' .. tostring(err))
                return
            end
        else
            reset_mpix_pipeline()
        end
        -- The camera may be in power save after the previous capture.
        pcall(frame.camera.power_save, false)
        local ok, err = pcall(frame.camera.capture, cfg)
        if ok then
            photoPending = true
        else
            print('camera capture error: ' .. tostring(err))
            send_event(ERROR_CODE, 'camera capture failed')
        end
    elseif code == BATTERY_CODE then
        pcall(send_battery)
    elseif code == SOUND_PLAY then
        local ok, err = pcall(play_sound, payload)
        if not ok then send_event(ERROR_CODE, tostring(err)) end
    elseif code == SYSTEM then
        local ok, err = pcall(handle_system, payload)
        if not ok then send_event(ERROR_CODE, tostring(err)) end
    elseif code == SET_TIME then
        local ok, err = pcall(handle_set_time, payload)
        if not ok then send_event(ERROR_CODE, tostring(err)) end
    elseif code == IMU_READ then
        local ok, err = pcall(send_imu)
        if not ok then send_event(ERROR_CODE, tostring(err)) end
    elseif code == TAP_CONFIG then
        local ok, err = pcall(handle_tap_config, payload)
        if not ok then send_event(ERROR_CODE, tostring(err)) end
    elseif code == SPRITE_STORE then
        local ok, res = pcall(store_sprite, payload)
        if ok then
            send_event(SPRITE_STORED, res)
        else
            send_event(ERROR_CODE, tostring(res))
        end
    elseif code == STATUS_CODE then
        pcall(send_status)
    else
        -- ignore unknown
    end
end

-- Input callbacks.
frame.button.single(function() send_event(BUTTON_CODE, string.char(1)) end)
frame.button.double(function() send_event(BUTTON_CODE, string.char(2)) end)
frame.button.long(function() send_event(BUTTON_CODE, string.char(3)) end)
frame.imu.tap_callback(function(kind)
    local codes = { single = 1, double = 2, triple = 3 }
    send_event(TAP_CODE, string.char(codes[kind] or 1))
end)
frame.display.power_save(false)
send_status()
print('Halo Engine v' .. RUNTIME_VERSION .. ' ready')

while true do
    local ok, err = pcall(function()
        local items = process_raw_items()
        for i = 1, #items do
            handle_message(items[i][1], items[i][2])
        end
        if micStreaming then
            send_mic_chunks()
        end
        if photoPending then
            send_photo()
        end
        frame.sleep(0.001)
    end)
    if not ok then
        send_event(ERROR_CODE, tostring(err))
        print(err)
    end
end
