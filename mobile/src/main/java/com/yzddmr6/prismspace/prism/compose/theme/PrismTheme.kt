package com.yzddmr6.prismspace.prism.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LightColorScheme = lightColorScheme(
    primary              = PrismPrimary,
    onPrimary            = PrismOnPrimary,
    primaryContainer     = PrismPrimaryContainer,
    onPrimaryContainer   = PrismOnPrimaryContainer,
    background           = PrismBackground,
    surface              = PrismSurface,
    surfaceVariant       = PrismSurfaceVariant,
    onSurface            = PrismOnSurface,
    onSurfaceVariant     = PrismOnSurfaceVariant,
    outline              = PrismOutline,
    // outlineVariant rides the same slate hairline family as cardBorder — a screen never shows
    // the M3 default purple-grey divider/border next to the card border.
    outlineVariant       = PrismCardBorder,
    error                = PrismError,
    onError              = PrismOnError,
    errorContainer       = PrismErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary              = PrismPrimaryD,
    onPrimary            = PrismOnPrimaryD,
    primaryContainer     = PrismPrimaryContainerD,
    onPrimaryContainer   = PrismOnPrimaryContainerD,
    background           = PrismBackgroundD,
    surface              = PrismSurfaceD,
    surfaceVariant       = PrismSurfaceVariantD,
    onSurface            = PrismOnSurfaceD,
    onSurfaceVariant     = PrismOnSurfaceVariantD,
    outline              = PrismOutlineD,
    outlineVariant       = PrismCardBorderD,
    error                = PrismErrorD,
    onError              = PrismOnErrorD,
    errorContainer       = PrismErrorContainerD,
)

/**
 * PrismSpace theme — follows the system light/dark setting (was always-light). No dynamic color /
 * Material You, so the brand stays consistent. Non-scheme tokens (card border, segment track) flip
 * via [LocalPrismExtraColors].
 */
@Composable
fun PrismTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPrismExtraColors provides if (darkTheme) PrismExtraDark else PrismExtraLight,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography  = PrismTypography,
            content     = content,
        )
    }
}
