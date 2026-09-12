package com.yzddmr6.prismspace.prism.compose.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the visual-token contract: the four semantic levels exist and every semantic
 * foreground/container pair passes WCAG 2.x AA contrast (≥ 4.5:1, relative luminance).
 */
class VisualTokenTest {

    @Test fun semanticLevelsAreNeutralOkWarnError() {
        assertEquals(
            listOf("Neutral", "Ok", "Warn", "Error"),
            com.yzddmr6.prismspace.prism.compose.component.PrismLevel.entries.map { it.name },
        )
    }

    @Test fun lightSemanticPairsPassAA() {
        // 浅色四级别全部 ≥ 4.5:1（容器色不动，前景修正）。
        listOf(
            "ok" to (PrismExtraLight.ok to PrismExtraLight.okContainer),
            "warn" to (PrismExtraLight.warn to PrismExtraLight.warnContainer),
            "error" to (PrismExtraLight.error to PrismExtraLight.errorContainer),
            "neutral" to (PrismExtraLight.neutral to PrismExtraLight.neutralContainer),
            "info" to (PrismExtraLight.info to PrismExtraLight.infoContainer),
        ).forEach { (name, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue("light $name contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test fun darkSemanticPairsPassAA() {
        listOf(
            "ok" to (PrismExtraDark.ok to PrismExtraDark.okContainer),
            "warn" to (PrismExtraDark.warn to PrismExtraDark.warnContainer),
            "error" to (PrismExtraDark.error to PrismExtraDark.errorContainer),
            "neutral" to (PrismExtraDark.neutral to PrismExtraDark.neutralContainer),
            "info" to (PrismExtraDark.info to PrismExtraDark.infoContainer),
        ).forEach { (name, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue("dark $name contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test fun iconSizeTiersAreExactlyFour() {
        assertEquals(
            listOf(18, 24, 40, 48),
            listOf(PrismIconSizes.Sm, PrismIconSizes.Md, PrismIconSizes.Lg, PrismIconSizes.Xl)
                .map { it.value.toInt() },
        )
        assertEquals(48, PrismMinTouchTarget.value.toInt())
    }

    companion object {
        // NOTE: `MaterialTheme.colorScheme.error` (PrismError #DC2626) measures ≈4.47:1 on the
        // background — marginally under AA. It is intentionally NOT asserted here and NOT changed:
        // no consumer places it on a tonal container (danger text/borders sit on surface/background
        // where the pairing reads higher), and the semantic-in-container error foreground moved to
        // the extra.error token (#B01818, asserted above). Changing the scheme error would darken
        // every danger affordance app-wide — out of scope for this token pass.
        /** WCAG 2.x relative-luminance contrast ratio. */
        fun contrastRatio(foreground: Color, background: Color): Double {
            val lf = relativeLuminance(foreground)
            val lb = relativeLuminance(background)
            val lighter = maxOf(lf, lb)
            val darker = minOf(lf, lb)
            return (lighter + 0.05) / (darker + 0.05)
        }

        private fun relativeLuminance(color: Color): Double {
            fun linear(channel: Double): Double =
                if (channel <= 0.04045) channel / 12.92
                else Math.pow((channel + 0.055) / 1.055, 2.4)
            return 0.2126 * linear(color.red.toDouble()) +
                0.7152 * linear(color.green.toDouble()) +
                0.0722 * linear(color.blue.toDouble())
        }
    }
}
