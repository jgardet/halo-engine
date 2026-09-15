package halo.engine

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HsdHrpCompiler(
    private val packer: SpritePacker,
    private val limits: HaloLimits = StockHaloLimits,
    private val validator: HsdValidator = HsdValidator(limits),
    private val lz4Sprites: Boolean = false,
    /**
     * When true, every sprite emits a cached define (opcode 0x10) keyed by a
     * content hash of its packed asset, and [CompiledHsd.spriteAssets] carries
     * the key→asset pairs the caller must persist on-device via `SPRITE_STORE`
     * before sending the frame. An explicit `cache_key` attribute overrides
     * the derived key while still returning the asset for ensure-store.
     */
    private val cacheSprites: Boolean = false,
) {
    /** Compiled scene plus the sprite assets its cached defines depend on. */
    data class CompiledHsd(
        val frame: ByteArray,
        val spriteAssets: Map<String, ByteArray>,
    )

    fun compile(document: JsonElement): ByteArray = compileDetailed(document).frame

    fun compileDetailed(document: JsonElement): CompiledHsd {
        validator.validate(document)
        val root = document.jsonObject["scene"]!!.jsonObject
        val builder = HrpBuilder(limits.maxHrpMessageBytes)
        val sprites = SpriteRegistry()
        val spriteAssets = linkedMapOf<String, ByteArray>()
        builder.clear(root["bg"] ?: "#000000")
        root["brightness"]?.jsonPrimitive?.intOrNull?.let(builder::brightness)
        root["children"]?.jsonArray?.forEach { compileElement(it.jsonObject, builder, 0, 0, sprites, spriteAssets) }
        val payload = builder.endFrame().build()
        validateHrpMessage(payload, limits)
        return CompiledHsd(payload, spriteAssets)
    }

    private fun compileElement(element: JsonObject, builder: HrpBuilder, dx: Int, dy: Int, sprites: SpriteRegistry, spriteAssets: MutableMap<String, ByteArray>) {
        if (element["visible"]?.jsonPrimitive?.booleanOrNull == false) return
        when (element.string("type")) {
            "group" -> {
                val x = dx + element.int("x", 0)
                val y = dy + element.int("y", 0)
                element.children().forEach { compileElement(it.jsonObject, builder, x, y, sprites, spriteAssets) }
            }
            "row" -> {
                var x = dx + element.int("x", 0)
                val y = dy + element.int("y", 0)
                val spacing = element.int("spacing", 0)
                element.children().forEach {
                    val child = it.jsonObject
                    compileElement(child, builder, x, y, sprites, spriteAssets)
                    x += HsdLayout.estimateWidth(child) + spacing
                }
            }
            "column" -> {
                val x = dx + element.int("x", 0)
                var y = dy + element.int("y", 0)
                val spacing = element.int("spacing", 0)
                element.children().forEach {
                    val child = it.jsonObject
                    compileElement(child, builder, x, y, sprites, spriteAssets)
                    y += HsdLayout.estimateHeight(child) + spacing
                }
            }
            "text" -> {
                builder.setFont(element.int("font", 0), element.int("size", 8), element.int("scale", 1))
                builder.text(element.int("x") + dx, element.int("y") + dy, element.string("text", ""), element["color"] ?: "#FFFFFF")
            }
            "rect" -> builder.rect(
                element.int("x") + dx,
                element.int("y") + dy,
                element.int("w"),
                element.int("h"),
                element["color"] ?: "#FFFFFF",
                element["filled"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "circle" -> builder.circle(
                element.int("cx") + dx,
                element.int("cy") + dy,
                element.int("r"),
                element["color"] ?: "#FFFFFF",
                element["filled"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            "line" -> builder.line(
                element.int("x0") + dx,
                element.int("y0") + dy,
                element.int("x1") + dx,
                element.int("y1") + dy,
                element["color"] ?: "#FFFFFF",
            )
            "polygon" -> builder.polygon(
                element["points"]!!.jsonArray.map {
                    val point = it.jsonArray
                    point[0].jsonPrimitive.int + dx to point[1].jsonPrimitive.int + dy
                },
                element["color"] ?: "#FFFFFF",
            )
            "point", "pixel" -> builder.pixel(
                element.int("x") + dx,
                element.int("y") + dy,
                element["color"] ?: "#FFFFFF",
            )
            "sprite" -> {
                val src = element.string("src")
                val cacheKey = element["cache_key"]?.jsonPrimitive?.contentOrNull
                val (id, isNew) = sprites.assign(src, element["resource_id"]?.jsonPrimitive?.intOrNull)
                if (isNew) {
                    if (cacheKey != null && !cacheSprites) {
                        // Caller asserts `spr_<cache_key>` is already stored
                        // on-device; emit a cached define and skip packing.
                        builder.spriteDefineCached(id, cacheKey)
                    } else if (cacheSprites || cacheKey != null) {
                        val packed = packSprite(element, src)
                        val key = cacheKey ?: spriteCacheKey(packed)
                        builder.spriteDefineCached(id, key)
                        spriteAssets[key] = packed
                    } else {
                        builder.spriteDefine(id, packSprite(element, src))
                    }
                }
                builder.spriteDraw(id, element.int("x") + dx, element.int("y") + dy, element.int("palette_offset", 0))
            }
        }
    }

    /**
     * Assigns sequential 16-bit resource IDs to sprite sources.
     * Explicit `resource_id` values are validated against collisions;
     * auto-generated IDs are unique and stable for the compile pass.
     */
    private class SpriteRegistry {
        private val srcToId = mutableMapOf<String, Int>()
        private val idToSrc = mutableMapOf<Int, String>()
        private var nextId = 1

        fun assign(src: String, explicitId: Int? = null): Pair<Int, Boolean> {
            if (explicitId != null) {
                val previous = idToSrc[explicitId]
                require(previous == null || previous == src) {
                    "Sprite resource ID $explicitId is used by multiple sources"
                }
                if (previous == null) {
                    idToSrc[explicitId] = src
                    srcToId[src] = explicitId
                }
                return explicitId to (previous == null)
            }

            val existing = srcToId[src]
            if (existing != null) return existing to false

            while (idToSrc.containsKey(nextId)) nextId++
            val id = nextId++
            idToSrc[id] = src
            srcToId[src] = id
            return id to true
        }
    }

    private fun packSprite(element: JsonObject, src: String): ByteArray {
        val sprite = packer.pack(
            src,
            element["w"]?.jsonPrimitive?.intOrNull,
            element["h"]?.jsonPrimitive?.intOrNull,
            element.int("bpp", 4),
        )
        val packed = HaloHost.packSpriteAsset(sprite, compress = lz4Sprites)
        require(packed.size <= limits.maxAssetBytes) { "Sprite exceeds conservative stock asset budget" }
        return packed
    }

    /** Device cache key derived from the packed asset bytes (content hash). */
    private fun spriteCacheKey(asset: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(asset)
        return "s" + digest.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.children(): JsonArray = this["children"]!!.jsonArray

    private fun JsonObject.string(key: String, default: String? = null): String =
        this[key]?.jsonPrimitive?.content ?: default ?: error("Missing $key")

    private fun JsonObject.int(key: String, default: Int? = null): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default ?: error("Missing integer $key")
}
