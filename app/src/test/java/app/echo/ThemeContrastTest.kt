package app.echo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** JVM 单测：强调色三角色与旧配色迁移的对比度边界（R15 P2-2）。 */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun assertContrast(label: String, fg: Color, backgrounds: List<Color>, min: Float) {
        backgrounds.forEachIndexed { index, bg ->
            val value = contrast(fg, bg)
            assertTrue(
                "$label bg#$index 对比度 $value < $min (fg=0x${fg.value.toString(16)} bg=0x${bg.value.toString(16)})",
                value >= min,
            )
        }
    }

    @After
    fun resetTheme() {
        C.initDay(false)
    }

    @Test
    fun dayAccentTextMeetsContrast() {
        C.initDay(true)
        C.skins.forEachIndexed { index, skin ->
            val text = C.accentText(skin.day)
            assertContrast("day#${index}/${skin.label}", text, C.textBackgrounds(skin.day), 4.5f)
        }
    }

    @Test
    fun nightAccentTextMeetsContrast() {
        C.initDay(false)
        C.skins.forEachIndexed { index, skin ->
            val text = C.accentText(skin.night)
            assertContrast("night#${index}/${skin.label}", text, C.textBackgrounds(skin.night), 4.5f)
        }
    }

    @Test
    fun extremeCustomAccentsStayReadable() {
        val args = listOf(0xFF000000, 0xFFFFFFFF, 0xFF05060A, 0xFFF4F2ED, 0xFF808080, 0xFFFF0000)
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            args.forEach { argb ->
                val accent = Color(argb)
                assertContrast("$tag custom text=0x${argb.toString(16)}", C.accentText(accent), C.textBackgrounds(accent), 4.5f)
                val ink = C.onAccent(accent)
                val value = contrast(ink, accent)
                assertTrue("$tag onAccent 0x${argb.toString(16)} 对比度 $value < 4.5", value >= 4.5f)
            }
        }
    }

    @Test
    fun presetDayTextMapsToItsOwnSkin() {
        C.initDay(true)
        C.skins.forEachIndexed { index, skin ->
            assertEquals("skin#${index}/${skin.label} 应映射到自身 dayText", index, C.nearestSkin(skin.day))
            assertEquals(skin.dayText, C.accentText(skin.day))
        }
    }

    @Test
    fun skinSchemaMigrationIsIdempotent() {
        assertEquals(6 to 1, migrateSkinIndex(0, 5, 6))
        assertEquals(3 to 1, migrateSkinIndex(0, 3, 6))
        assertEquals(1 to 1, migrateSkinIndex(0, 99, 6))
        assertEquals(6 to 1, migrateSkinIndex(1, 6, 6))
        assertEquals(1 to 1, migrateSkinIndex(1, 7, 6))
        val once = migrateSkinIndex(0, 5, 6)
        assertEquals(once, migrateSkinIndex(once.second, once.first, 6))
    }
}
