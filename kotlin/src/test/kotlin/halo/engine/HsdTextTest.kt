package halo.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HsdTextTest {

    private fun lines(document: kotlinx.serialization.json.JsonObject) =
        document["scene"]!!.jsonObject["children"]!!.jsonArray.map {
            it.jsonObject["text"]!!.jsonPrimitive.content
        }

    @Test
    fun wrapRespectsPixelWidth() {
        val wrapped = HsdText.wrap(
            "the quick brown fox jumps over the lazy dog and keeps running through the forest",
            maxWidth = 100,
        )
        assertTrue(wrapped.size > 1)
        wrapped.forEach { assertTrue(HsdText.measureWidth(it) <= 100, "line too wide: $it") }
    }

    @Test
    fun wrapBreaksWordsWiderThanLine() {
        val wrapped = HsdText.wrap("x".repeat(300), maxWidth = 50)
        assertTrue(wrapped.size > 1)
        wrapped.forEach { assertTrue(HsdText.measureWidth(it) <= 50) }
    }

    @Test
    fun wrapPreservesNewlines() {
        val wrapped = HsdText.wrap("first\n\nthird", maxWidth = 208)
        assertEquals(listOf("first", "", "third"), wrapped)
    }

    @Test
    fun foldMapsNonAsciiToRenderable() {
        assertEquals("cafe creme - \"cake\" ... 180 deg ??", HsdText.fold("café crème — “cake” … 180° 🍰"))
    }

    @Test
    fun documentValidatesAndUsesLargestFittingSize() {
        val document = HsdText.document("Step 1: mix the batter.")
        HsdValidator().validate(document)
        val first = document["scene"]!!.jsonObject["children"]!!.jsonArray[0].jsonObject
        assertEquals(16, first["size"]!!.jsonPrimitive.int)
    }

    @Test
    fun longTextWrapsIntoManyLines() {
        val document = HsdText.document("word ".repeat(500))
        HsdValidator().validate(document)
        val texts = lines(document)
        assertTrue(texts.size > 5, "expected a full page of lines, got ${texts.size}")
        texts.forEach { assertTrue(HsdText.measureWidth(it) <= 208, "line too wide: $it") }
    }

    @Test
    fun overflowingTextIsTruncatedWithMarker() {
        val document = HsdText.document("word ".repeat(5000))
        HsdValidator().validate(document)
        val texts = lines(document)
        assertTrue(texts.last().endsWith(HsdText.TRUNCATION_MARKER))
    }

    @Test
    fun documentRejectsZeroWidthScene() {
        assertFailsWith<IllegalArgumentException> {
            HsdText.document("hello", width = 40)
        }
    }
}
