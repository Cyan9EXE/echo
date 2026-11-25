package app.echo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        C.applyCustomThemes(null, null)
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
            0xFF3A6EA5, 0xFFB5651D, 0xFF7FBF7F, 0xFFD4A0A0, 0xFF4B0082, 0xFF9E9E9E,
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
            for (level in 0..0xFF) {
                val grey = Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level)
                val fill = C.readableAccent(grey)
                val value = contrast(C.onAccent(fill), fill)
                assertTrue("$tag grey=$level -> ${hex(fill)} 对比度 $value < 4.7", value >= 4.7f)
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

    /** 与生产共用同一份 sRGB 叠底，避免测试与真实绘制出现两套算法。 */
    private fun tint(base: Color, overlay: Color, alpha: Float = .16f): Color =
        C.blendSrgb(base, overlay, alpha)

    @Test
    fun selectedBorderOnTintedSurfacesMeetsNonTextContrast() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            val accents = C.skins.map { if (day) it.day else it.night } +
                listOf(Color(0xFF808080), Color(0xFF737373), Color(0xFF6F6F6F))
            accents.forEach { accent ->
                val border = C.accentText(accent)
                listOf(C.Void, C.Panel, C.PanelHigh).forEachIndexed { index, base ->
                    val bg = tint(base, accent)
                    val value = contrast(border, bg)
                    assertTrue(
                        "$tag tint-border base#$index(${hex(base)}) 对比度 $value < 3.0 " +
                            "(accent=${hex(accent)} border=${hex(border)})",
                        value >= 3f,
                    )
                }
            }
        }
    }

    private fun presetAccents(day: Boolean): List<Color> =
        C.skins.map { if (day) it.day else it.night }

    /** 固定锚点 + 固定种子随机色：覆盖纯色、灰阶与极端饱和色，避免只测预设。 */
    private fun stressAccents(): List<Color> {
        val anchors = listOf(
            Color(0xFFFD01FF.toInt()), Color(0xFFFF0000.toInt()), Color(0xFF00FF00.toInt()),
            Color(0xFF0000FF.toInt()), Color(0xFF808080.toInt()), Color(0xFF737373.toInt()),
            Color(0xFF6F6F6F.toInt()), Color(0xFF000000.toInt()), Color(0xFFFFFFFF.toInt()),
        )
        val random = java.util.Random(20261124L)
        return anchors + List(160) { Color(0xFF000000.toInt() or random.nextInt(0x1000000)) }
    }

    @Test
    fun selectedPillTextMeetsContrastOnActualTintedSurface() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            (presetAccents(day) + stressAccents()).forEach { accent ->
                val text = C.accentText(accent)
                listOf(C.Void, C.Panel, C.PanelHigh).forEachIndexed { index, base ->
                    assertContrast(
                        "$tag pill-text base#$index accent=${hex(accent)}",
                        text, listOf(tint(base, accent)), 4.5f,
                    )
                }
                assertContrast(
                    "$tag pill-text actual-panel accent=${hex(accent)}",
                    text, listOf(tint(C.Panel, accent)), 4.7f,
                )
            }
        }
    }

    @Test
    fun helpDialogCheckboxMeetsContrastOnTintedPanel() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            (presetAccents(day) + stressAccents()).forEach { accent ->
                val mark = C.accentText(accent)
                // 勾选框底：accentText 以 16% 叠在 Panel 卡片上。
                assertContrast(
                    "$tag help-check accent=${hex(accent)}",
                    mark, listOf(tint(C.Panel, mark)), 3f,
                )
            }
        }
    }

    @Test
    fun presetPreviewSelectedBorderMeetsNonTextContrast() {
        val looks = listOf(C.Look.PAPER, C.Look.INK, C.Look.WARM, C.Look.GREEN)
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            (presetAccents(day) + listOf(Color(0xFF808080.toInt()), Color(0xFFFD01FF.toInt())))
                .forEach { accent ->
                    looks.forEach { look ->
                        val card = C.previewVoid(look)
                        assertContrast(
                            "$tag preset-border ${look.zh} accent=${hex(accent)}",
                            C.accentOn(card, accent), listOf(card), 3f,
                        )
                    }
                }
        }
    }

    @Test
    fun presetPreviewSelectedLabelAndBorderMeetContrast() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            C.skins.forEach { skin ->
                val accent = if (day) skin.day else skin.night
                assertContrast("preset-label ${skin.label}", C.Ivory, listOf(C.Void), 4.5f)
                assertContrast(
                    "preset-border ${skin.label}",
                    C.accentText(accent), listOf(C.Void), 3f,
                )
            }
        }
    }

    @Test
    fun normalizedCustomAccentKeepsSelectedTextReadable() {
        listOf(true, false).forEach { day ->
            C.initDay(day)
            val tag = if (day) "day" else "night"
            for (level in 0..0xFF step 5) {
                val raw = Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level)
                val fill = C.readableAccent(raw)
                val text = C.accentText(fill)
                assertContrast(
                    "$tag normalized grey=$level",
                    text, listOf(tint(C.Panel, fill)), 4.5f,
                )
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
    fun customDarkBackgroundBuildsDarkSurfacesAndReadableText() {
        C.initDay(false)
        listOf(0xFF101820.toInt(), 0xFF1B2430.toInt(), 0xFF0E1220.toInt(), 0xFF2A1F3D.toInt()).forEach { argb ->
            C.applyCustomThemes(0xFFF4F2ED.toInt(), argb)
            val tag = "custom dark 0x" + argb.toString(16)
            assertTrue(tag + " 应判为深色界面", C.isDarkBackground)
            assertEquals(tag + " 底色应为所选颜色", Color(argb), C.customField())
            assertEquals(tag + " 夜间那套底色", Color(argb), C.customFieldFor(false))
            assertTrue(tag + " 面板应比底色亮", C.Panel.luminance() > C.Void.luminance())
            assertTrue(tag + " 高亮面板应比面板亮", C.PanelHigh.luminance() > C.Panel.luminance())
            assertContrast(tag + " 正文", C.Ivory, listOf(C.Void, C.Panel, C.PanelHigh), 4.5f)
            assertContrast(tag + " 次级字", C.Muted, listOf(C.Void, C.Panel, C.PanelHigh), 3.0f)
            C.skins.forEachIndexed { index, skin ->
                assertEquals(tag + " preset 应取夜间色", skin.night, C.presetAccent(index))
                assertContrast(tag + " accent text", C.accentText(skin.night), C.textBackgrounds(skin.night), 4.7f)
            }
        }
    }

    @Test
    fun customLightBackgroundKeepsPaperLikeSurfaces() {
        C.initDay(true)
        listOf(0xFFF4F2ED.toInt(), 0xFFF2E8D5.toInt(), 0xFFEAF1F7.toInt(), 0xFFFFF9E8.toInt()).forEach { argb ->
            C.applyCustomThemes(argb, 0xFF101820.toInt())
            val tag = "custom light 0x" + argb.toString(16)
            assertTrue(tag + " 应判为浅色界面", !C.isDarkBackground)
            assertEquals(tag + " 底色应为所选颜色", Color(argb), C.customField())
            assertEquals(tag + " 日间那套底色", Color(argb), C.customFieldFor(true))
            assertTrue(tag + " 浅色面板应比底色暗", C.Panel.luminance() < C.Void.luminance())
            assertContrast(tag + " 正文", C.Ivory, listOf(C.Void, C.Panel, C.PanelHigh), 4.5f)
            assertContrast(tag + " 次级字", C.Muted, listOf(C.Void, C.Panel, C.PanelHigh), 3.0f)
            C.skins.forEachIndexed { index, skin ->
                assertEquals(tag + " preset 应取日间色", skin.day, C.presetAccent(index))
                assertContrast(tag + " accent text", C.accentText(skin.day), C.textBackgrounds(skin.day), 4.7f)
            }
        }
    }

    @Test
    fun resettingCustomThemeRestoresPresetSurfaces() {
        C.initDay(true)
        val before = listOf(C.Void, C.Panel, C.PanelHigh, C.Hair, C.Ivory, C.Muted)
        C.applyCustomThemes(0xFF101820.toInt(), 0xFF101820.toInt())
        C.applyCustomThemes(null, null)
        assertEquals(before, listOf(C.Void, C.Panel, C.PanelHigh, C.Hair, C.Ivory, C.Muted))
        assertEquals(Color(0xFFF4F2ED), C.customField())
        assertTrue("清除后明暗应重新跟随日夜", C.isDarkBackground == !C.isDay)
    }

    /** 浅色与深色是两套独立底色：切日夜只换用哪一套，不互相覆盖。 */
    @Test
    fun lightAndDarkCustomThemesAreIndependent() {
        C.initDay(true)
        C.applyCustomThemes(0xFFF7F2E8.toInt(), 0xFF101820.toInt())
        assertEquals(Color(0xFFF7F2E8), C.Void)
        assertFalse(C.isDarkBackground)
        assertEquals(Color(0xFF101820), C.customFieldFor(false))
        assertEquals(C.Look.CUSTOM, C.look)

        C.initDay(false)
        assertEquals(Color(0xFF101820), C.Void)
        assertTrue(C.isDarkBackground)
        assertEquals(Color(0xFFF7F2E8), C.customFieldFor(true))
        assertEquals(C.Look.CUSTOM, C.look)

        // 自定义模式在日间也返回 CUSTOM，不再被「夜间优先」挡掉。
        C.applyCustomThemes(null, null)
        C.initDay(true)
        assertEquals(C.Look.PAPER, C.look)
    }

    /** 草稿预览：极深/极浅都判对极性，正文与次级字在任意 L* 下都可读。 */
    @Test
    fun previewSurfacePolarityAndBodyTextReadable() {
        listOf(8f, 20f, 35f).forEach { lstar ->
            assertTrue("L*$lstar 应判深色", C.previewSurface(212f, 45f, lstar).isDark)
        }
        listOf(65f, 80f, 97f).forEach { lstar ->
            assertFalse("L*$lstar 应判浅色", C.previewSurface(212f, 45f, lstar).isDark)
        }
        for (lstar in 0..100 step 10) {
            listOf(0f, 38f, 120f, 212f, 300f).forEach { hue ->
                val surface = C.previewSurface(hue, 50f, lstar.toFloat())
                assertContrast(
                    "preview h=$hue L*=$lstar",
                    surface.ivory, listOf(surface.void, surface.panel, surface.panelHigh), 4.5f,
                )
                assertTrue(
                    "preview h=$hue L*=$lstar 正文对比 ${C.bodyContrast(surface)} < 4.5",
                    C.bodyContrast(surface) >= 4.5f,
                )
            }
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
