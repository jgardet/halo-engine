package halo.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HsdValidator(
    private val limits: HaloLimits = StockHaloLimits,
    private val maxElements: Int = 256,
    private val maxDepth: Int = 16,
    private val maxTextBytes: Int = 2_048,
) {
    fun validate(document: JsonElement) {
        val top = document as? JsonObject ?: fail("HSD root must be an object")
        val version = top.string("version", "1.0")
        require(version == "1.0") { "Unsupported HSD version: $version" }
        val device = top.string("device", "halo")
        require(device == "halo") { "Unsupported HSD device: $device" }
        val mode = top.string("mode", "repl")
        require(mode == "repl" || mode == "runtime") { "Unsupported HSD mode: $mode" }
        val scene = top["scene"] as? JsonObject ?: fail("HSD scene must be an object")
        val width = scene.int("width", limits.displayWidth)
        val height = scene.int("height", limits.displayHeight)
        require(width in 1..limits.displayWidth && height in 1..limits.displayHeight) {
            "Scene dimensions ${width}x$height exceed ${limits.displayWidth}x${limits.displayHeight}"
        }
        scene["bg"]?.let(HaloColor::parse)
        scene["brightness"]?.jsonPrimitive?.intOrNull?.let { require(it in 0..100) { "Brightness must be 0..100" } }
        scene["power_save"]?.jsonPrimitive?.booleanOrNull
            ?: require(scene["power_save"] == null) { "power_save must be boolean" }
        val children = scene["children"]?.let { it as? JsonArray ?: fail("scene.children must be an array") }
            ?: JsonArray(emptyList())
        var count = 0
        fun visit(element: JsonElement, depth: Int) {
            require(depth <= maxDepth) { "HSD nesting exceeds $maxDepth" }
            require(++count <= maxElements) { "HSD contains more than $maxElements elements" }
            val obj = element as? JsonObject ?: fail("HSD element must be an object")
            obj["visible"]?.jsonPrimitive?.booleanOrNull
                ?: require(obj["visible"] == null) { "visible must be boolean" }
            when (val type = obj.string("type")) {
                "group", "row", "column" -> {
                    obj.optionalInt("x")?.let { require(it >= 0) { "x must be non-negative" } }
                    obj.optionalInt("y")?.let { require(it >= 0) { "y must be non-negative" } }
                    obj.optionalInt("spacing")?.let { require(it >= 0) { "spacing must be non-negative" } }
                    val nested = obj["children"] as? JsonArray ?: fail("$type.children must be an array")
                    nested.forEach { visit(it, depth + 1) }
                }
                "text" -> {
                    obj.coordinate("x", width)
                    obj.coordinate("y", height)
                    val font = obj.int("font", 0)
                    val size = obj.int("size", 8)
                    val scale = obj.int("scale", 1)
                    require(font == 0 || font == 1) { "Text font must be 0 or 1" }
                    require(size in 8..255 && size % 8 == 0) { "Text size must be a multiple of 8 between 8 and 255" }
                    require(scale in 1..255) { "Text scale must be 1..255" }
                    require(obj.string("text", "").toByteArray(Charsets.UTF_8).size <= maxTextBytes) { "Text exceeds $maxTextBytes bytes" }
                    obj.color()
                }
                "rect" -> {
                    val x = obj.coordinate("x", width)
                    val y = obj.coordinate("y", height)
                    val w = obj.positive("w")
                    val h = obj.positive("h")
                    require(x + w <= width && y + h <= height) { "Rectangle exceeds scene bounds" }
                    obj.optionalBoolean("filled")
                    obj.color()
                }
                "circle" -> {
                    obj.coordinate("cx", width)
                    obj.coordinate("cy", height)
                    obj.positive("r")
                    obj.optionalBoolean("filled")
                    obj.color()
                }
                "line" -> {
                    obj.coordinate("x0", width)
                    obj.coordinate("y0", height)
                    obj.coordinate("x1", width)
                    obj.coordinate("y1", height)
                    obj.color()
                }
                "polygon" -> {
                    val points = obj["points"] as? JsonArray ?: fail("polygon.points must be an array")
                    require(points.size in 3..limits.maxPolygonPoints) { "Polygon must contain 3..${limits.maxPolygonPoints} points" }
                    points.forEach {
                        val point = it as? JsonArray ?: fail("Polygon point must be an array")
                        require(point.size == 2) { "Polygon point must contain x and y" }
                        require(point[0].jsonPrimitive.intOrNull in 0 until width) { "Polygon x is outside scene" }
                        require(point[1].jsonPrimitive.intOrNull in 0 until height) { "Polygon y is outside scene" }
                    }
                    obj.color()
                }
                "point", "pixel" -> {
                    obj.coordinate("x", width)
                    obj.coordinate("y", height)
                    obj.color()
                }
                "sprite" -> {
                    require(obj.string("src").isNotBlank()) { "Sprite src must not be blank" }
                    obj.coordinate("x", width)
                    obj.coordinate("y", height)
                    obj.optionalInt("w")?.let { require(it > 0 && it <= width) { "Invalid sprite width" } }
                    obj.optionalInt("h")?.let { require(it > 0 && it <= height) { "Invalid sprite height" } }
                    require(obj.int("bpp", 4) in setOf(1, 2, 4)) { "Sprite bpp must be 1, 2, or 4" }
                    require(obj.int("palette_offset", 0) in 0..15) { "Sprite palette_offset must be 0..15" }
                    require(obj.int("scale_x", 1) == 1 && obj.int("scale_y", 1) == 1) { "runtime/HRP sprites do not support scaling (use 1 or switch to repl mode)" }
                    require(obj.int("resource_id", 1) in 1..0xFFFF) { "Sprite resource_id must be 1..65535" }
                    obj["cache_key"]?.let {
                        require(it.jsonPrimitive.content.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                            "Sprite cache_key must match [A-Za-z0-9_-]{1,64}"
                        }
                    }
                }
                else -> fail("Unknown HSD element type: $type")
            }
        }
        children.forEach { visit(it, 1) }
    }

    private fun JsonObject.string(key: String, default: String? = null): String =
        this[key]?.jsonPrimitive?.content ?: default ?: fail("Missing $key")

    private fun JsonObject.int(key: String, default: Int? = null): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: default ?: fail("Missing integer $key")

    private fun JsonObject.optionalInt(key: String): Int? {
        val value = this[key] ?: return null
        return value.jsonPrimitive.intOrNull ?: fail("$key must be an integer")
    }

    private fun JsonObject.optionalBoolean(key: String) {
        val value = this[key] ?: return
        require(value.jsonPrimitive.booleanOrNull != null) { "$key must be boolean" }
    }

    private fun JsonObject.coordinate(key: String, upperBound: Int): Int = int(key).also {
        require(it in 0 until upperBound) { "$key is outside scene bounds" }
    }

    private fun JsonObject.positive(key: String): Int = int(key).also {
        require(it > 0) { "$key must be positive" }
    }

    private fun JsonObject.color() {
        this["color"]?.let(HaloColor::parse)
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
