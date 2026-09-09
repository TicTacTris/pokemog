package dev.pokemog.android

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import org.junit.Test

class PokeMogThemeTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first); val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    @Test fun bothPalettesKeepTextAndAccentLabelsReadable() {
        for (dark in listOf(false, true)) {
            val p = warmPalette(dark)
            for (surface in listOf(p.background, p.surface, p.elevated)) {
                for (text in listOf(p.text, p.muted, p.primary, p.gold, p.error)) {
                    assertTrue("Insufficient contrast in ${if (dark) "dark" else "light"} palette: ${contrast(text, surface)}", contrast(text, surface) >= 4.5)
                }
            }
            assertTrue(contrast(p.onPrimary, p.primary) >= 4.5)
        }
    }

    @Test fun shadowSurfacesAndFramesHaveAccessibleContrast() {
        for (dark in listOf(false, true)) {
            val p = warmPalette(dark)
            for (checked in listOf(false, true)) {
                val shadow = ShadowStyle.colors(checked, p)
                assertTrue(contrast(shadow.text, shadow.fill) >= 4.5)
                assertTrue(contrast(shadow.outline, shadow.fill) >= 3.0)
            }
            for (surface in listOf(p.background, p.surface, p.elevated)) {
                assertTrue(contrast(p.outline, surface) >= 3.0)
            }
            assertEquals(ShadowStyle.on, ShadowStyle.colors(true, p))
        }
    }

    @Test fun everyTextRoleUsesPixelTypographyWithoutShrinking() {
        val base = Typography()
        val retro = pokemogTypography(FontFamily.Monospace)
        val originals = listOf(base.displayLarge, base.displayMedium, base.displaySmall,
            base.headlineLarge, base.headlineMedium, base.headlineSmall,
            base.titleLarge, base.titleMedium, base.titleSmall,
            base.bodyLarge, base.bodyMedium, base.bodySmall,
            base.labelLarge, base.labelMedium, base.labelSmall)
        val pixels = listOf(retro.displayLarge, retro.displayMedium, retro.displaySmall,
            retro.headlineLarge, retro.headlineMedium, retro.headlineSmall,
            retro.titleLarge, retro.titleMedium, retro.titleSmall,
            retro.bodyLarge, retro.bodyMedium, retro.bodySmall,
            retro.labelLarge, retro.labelMedium, retro.labelSmall)
        originals.zip(pixels).forEach { (original, pixel) ->
            assertEquals(FontFamily.Monospace, pixel.fontFamily)
            assertEquals(original.fontSize, pixel.fontSize)
            assertEquals(original.lineHeight, pixel.lineHeight)
            assertEquals(original.letterSpacing, pixel.letterSpacing)
        }
    }
}
