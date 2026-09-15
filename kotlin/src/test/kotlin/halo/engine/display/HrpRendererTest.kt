package halo.engine.display

import halo.engine.HaloHost
import halo.engine.HrpBuilder
import halo.engine.HsdHrpCompiler
import halo.engine.SpritePacker
import halo.engine.StubSpritePacker
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HrpRendererTest {

    @Test
    fun rendersClearCommand() {
        val hrp = HrpBuilder().clear(0xFF0000).build()
        val renderer = HrpRenderer()
        val count = renderer.render(hrp)
        assertEquals(1, count)
        val px = renderer.snapshot()
        assertEquals(0xFFFF0000.toInt(), px[0])
    }

    @Test
    fun rendersPixelCommand() {
        val hrp = HrpBuilder().pixel(10, 20, 0xFFFFFF).build()
        val renderer = HrpRenderer()
        renderer.render(hrp)
        val px = renderer.snapshot()
        // HRP coords are 0-indexed; renderer adds 1 → DisplayBuffer.setPixel(11, 21) → pixel (10, 20)
        assertEquals(0xFFFFFFFF.toInt(), px[20 * 256 + 10])
    }

    @Test
    fun rendersLineCommand() {
        val hrp = HrpBuilder().line(0, 0, 4, 0, 0xFFFFFF).build()
        val renderer = HrpRenderer()
        renderer.render(hrp)
        val px = renderer.snapshot()
        for (x in 0..4) {
            assertEquals(0xFFFFFFFF.toInt(), px[x], "pixel ($x,0) should be white")
        }
    }

    @Test
    fun rendersRectFilledCommand() {
        val hrp = HrpBuilder().rect(0, 0, 3, 2, 0xFF0000, filled = true).build()
        val renderer = HrpRenderer()
        renderer.render(hrp)
        val px = renderer.snapshot()
        for (y in 0..1) {
            for (x in 0..2) {
                assertEquals(0xFFFF0000.toInt(), px[y * 256 + x], "($x,$y) should be red")
            }
        }
    }

    @Test
    fun rendersCircleFilledCommand() {
        val hrp = HrpBuilder().circle(128, 128, 10, 0x00FF00, filled = true).build()
        val renderer = HrpRenderer()
        renderer.render(hrp)
        val px = renderer.snapshot()
        // center should be green
        assertEquals(0xFF00FF00.toInt(), px[128 * 256 + 128])
    }

    @Test
    fun rendersTextCommand() {
        val hrp = HrpBuilder()
            .setFont(0, 8, 1)
            .text(10, 10, "A", 0xFFFFFF)
            .build()
        val renderer = HrpRenderer()
        renderer.render(hrp)
        val px = renderer.snapshot()
        // 'A' in Dogica should produce some non-black pixels near (10, 10)
        val hasWhite = (0 until 256).any { y ->
            (0 until 256).any { x ->
                px[y * 256 + x] == 0xFFFFFFFF.toInt()
            }
        }
        assertTrue(hasWhite, "text 'A' should produce visible pixels")
    }

    @Test
    fun rejectsInvalidMagic() {
        val bad = byteArrayOf(0x58, 0x58, 0x58, 0x58, 0, 0, 0)
        assertFailsWith<HrpFailure> {
            HrpRenderer().render(bad)
        }
    }

    @Test
    fun rejectsTruncatedCommand() {
        // HRP1 header with 1 command but no command data
        val truncated = byteArrayOf(
            'H'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(),
            0, 0, 1,
        )
        assertFailsWith<HrpFailure> {
            HrpRenderer().render(truncated)
        }
    }

    @Test
    fun rejectsTrailingBytes() {
        val hrp = HrpBuilder().clear(0).build()
        val withTrailing = hrp + byteArrayOf(0xFF.toByte())
        assertFailsWith<HrpFailure> {
            HrpRenderer().render(withTrailing)
        }
    }

    @Test
    fun rendersFullHsdSceneThroughHrp() {
        // Compile a simple HSD scene to HRP, then render it
        val json = Json { ignoreUnknownKeys = true }
        val scene = json.parseToJsonElement(
            """{"version":"1.0","device":"halo","mode":"runtime","scene":{
              "width":256,"height":256,"bg":"#000000",
              "children":[
                {"type":"circle","cx":128,"cy":128,"r":100,"color":"#0050A0","filled":false},
                {"type":"text","x":100,"y":120,"text":"Hi","font":1,"size":16,"color":"#FFFFFF"},
                {"type":"line","x0":50,"y0":200,"x1":206,"y1":200,"color":"#303030"}
              ]
            }}""".trimIndent()
        )
        val hrp = HsdHrpCompiler(StubSpritePacker()).compile(scene)
        val renderer = HrpRenderer()
        val count = renderer.render(hrp)
        assertTrue(count > 0, "should execute at least one command")

        // The background should be black
        val px = renderer.snapshot()
        assertEquals(0xFF000000.toInt(), px[0])

        // The scene has a circle outline at (128,128) r=100 and text at (100,120).
        // The center pixel may or may not be on the circle outline, so just check
        // that the overall scene produced visible content.
        val nonBlack = px.count { it != 0xFF000000.toInt() }
        assertTrue(nonBlack > 50, "scene should produce visible content, got $nonBlack non-black pixels")
    }

    @Test
    fun rendersRunningHudScene() {
        // Load and render the actual running_hud.json scene
        val sceneText = java.io.File("../scenes/running_hud.json").readText()
        val json = Json { ignoreUnknownKeys = true }
        val scene = json.parseToJsonElement(sceneText)
        val hrp = HsdHrpCompiler(StubSpritePacker()).compile(scene)
        val renderer = HrpRenderer()
        renderer.render(hrp)
        val px = renderer.snapshot()

        // Verify background is black
        assertEquals(0xFF000000.toInt(), px[0])

        // Verify some pixels are non-black (the HUD has circles, text, lines)
        val nonBlack = px.count { it != 0xFF000000.toInt() }
        assertTrue(nonBlack > 100, "running HUD should produce visible content, got $nonBlack non-black pixels")

        // Verify the blue circle is present — check a point on the circle radius
        // Circle at (128,128) r=125, color #0050A0 → check point (128, 128-125) = (128, 3)
        // HRP coords are 0-indexed; renderer adds 1 → DisplayBuffer(129, 4) → pixel (128, 3)
        val circlePixel = px[3 * 256 + 128]
        assertTrue(circlePixel != 0xFF000000.toInt(), "circle outline should be visible at (128,3)")
    }

    @Test
    fun cachedSpriteDefineResolvesStoredAsset() {
        // 2x2 1bpp, all pixels palette index 1 (white).
        val sprite = SpritePacker.Sprite(
            width = 2, height = 2, bpp = 1, numColors = 2,
            paletteData = byteArrayOf(0, 0, 0, -1, -1, -1),
            pixelData = byteArrayOf(1, 1, 1, 1),
        )
        val files = mutableMapOf("icon" to HaloHost.packSpriteAsset(sprite))
        val renderer = HrpRenderer(spriteFiles = files)

        val hrp = HrpBuilder().spriteDefineCached(3, "icon").spriteDraw(3, 10, 10).build()
        renderer.render(hrp)
        val px = renderer.snapshot()
        assertEquals(0xFFFFFFFF.toInt(), px[10 * 256 + 10], "cached sprite should draw at (10,10)")
    }

    @Test
    fun cachedSpriteDefineMissFails() {
        val renderer = HrpRenderer(spriteFiles = mutableMapOf())
        val hrp = HrpBuilder().spriteDefineCached(3, "absent").build()
        val failure = assertFailsWith<HrpFailure> { renderer.render(hrp) }
        assertEquals(HrpFailure.Category.MISSING_RESOURCE, failure.category)
    }
}
