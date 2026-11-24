package app.echo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * JVM 单测：强调色三角色、灰阶可读性与旧配色迁移的对比度边界。
 *
 * R15 起日间小字走 accentText（对最坏底色 >= 4.7），填充上的字走 onAccent；
 * R16 起自定义色在入库时先过 readableAccent，保证任意明度（含中灰）的填充
 * 与 onAccent 的组合仍 >= 4.7，选中边框/勾选也统一用 accentText（非文本 >= 3.0）。
 */
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

    private fun hex(color: Color): String = "0x${color.value.toString(16)}"

    @After
    fun resetTheme() {
        C.initDay(false)
        C.applyEye(0)
        C.applyCustomTheme(null)
    }

    @Test
    fun dayAccentTextMeetsContrast() {
        C.initDay(true)
        C.skins.forEachIndexed { index, skin ->
            val text = C.accentText(skin.day)
            assertContrast("day#$index/${skin.label}", text, C.textBackgrounds(skin.day), 4.7f)
        }
    }

    @Test
    fun nightAccentTextMeetsContrast() {
        C.initDay(false)
        C.skins.forEachIndexed { index, skin ->
            val text = C.accentText(skin.night)
            assertContrast("night#$index/${skin.label}", text, C.textBackgrounds(skin.night), 4.7f)
        }
    }

    @Test
    fun extremeCustomAccentsStayReadable() {
        val args = listOf(
            0xFF000000, 0xFFFFFFFF, 0xFF05060A, 0xFFF4F2ED, 0xFFFF0000,
            0xFF808080, 0xFF737373, 0xFF777777, 0xFF6F6F6F,
        )
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            args.forEach { argb ->
                val accent = Color(argb)
                assertContrast("$tag custom text=0x${argb.toString(16)}", C.accentText(accent), C.textBackgrounds(accent), 4.7f)
                // 填充：入库前会过 readableAccent，所以填充字与（归一后的）填充色必须够读。
                val fill = C.readableAccent(accent)
                val ink = C.onAccent(fill)
                val value = contrast(ink, fill)
                assertTrue("$tag filler 0x${argb.toString(16)} -> ${hex(fill)} 对比度 $value < 4.7", value >= 4.7f)
            }
        }
    }

    @Test
    fun readableAccentKeepsEveryGreyFilledTextReadable() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            var level = 0
            while (level <= 0xFF) {
                val grey = Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level)
                val fill = C.readableAccent(grey)
                val value = contrast(C.onAccent(fill), fill)
                assertTrue("$tag grey=$level -> ${hex(fill)} 对比度 $value < 4.7", value >= 4.7f)
                level += 0x11
            }
        }
    }

    @Test
    fun presetFillsAreAlreadyReadable() {
        C.skins.forEachIndexed { index, skin ->
            assertEquals("day#$index/${skin.label} 预设不应被 readableAccent 改动", skin.day, C.readableAccent(skin.day))
            assertEquals("night#$index/${skin.label} 预设不应被 readableAccent 改动", skin.night, C.readableAccent(skin.night))
        }
    }

    @Test
    fun selectedBorderAndCheckmarkMeetNonTextContrast() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            val accents = C.skins.map { if (day) it.day else it.night } + Color(0xFF808080) + Color(0xFF737373)
            accents.forEach { accent ->
                val border = C.accentText(accent)
                assertContrast("$tag border(${hex(accent)})", border, C.textBackgrounds(accent), 3f)
                assertContrast("$tag border-on-panel(${hex(accent)})", border, listOf(C.Void, C.Panel, C.PanelHigh), 3f)
            }
        }
    }

    @Test
    fun presetDayTextMapsToItsOwnSkin() {
        C.initDay(true)
        C.skins.forEachIndexed { index, skin ->
            assertEquals("skin#$index/${skin.label} 应映射到自身 dayText", index, C.nearestSkin(skin.day))
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
