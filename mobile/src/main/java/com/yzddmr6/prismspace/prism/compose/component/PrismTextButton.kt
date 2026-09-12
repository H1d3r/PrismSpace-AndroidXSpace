package com.yzddmr6.prismspace.prism.compose.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yzddmr6.prismspace.prism.compose.theme.PrismMinTouchTarget

/**
 * TextButton with the 48dp minimum touch height applied. Stock M3 TextButton is 40dp, under the
 * touch-target floor — every inline text action (dialog buttons, row-end actions, screen-level
 * text buttons) uses this wrapper so the floor lives in one place.
 */
@Composable
fun PrismTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = PrismMinTouchTarget),
        enabled = enabled,
        content = content,
    )
}
