package com.example.apneamonitor.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp
import com.example.apneamonitor.ui.theme.BackdropEnd
import com.example.apneamonitor.ui.theme.BackdropGlowBlue
import com.example.apneamonitor.ui.theme.BackdropGlowCyan
import com.example.apneamonitor.ui.theme.BackdropGlowPurple
import com.example.apneamonitor.ui.theme.BackdropMid
import com.example.apneamonitor.ui.theme.BackdropStart
import com.example.apneamonitor.ui.theme.GlassBorder
import com.example.apneamonitor.ui.theme.GlassBorderStrong
import com.example.apneamonitor.ui.theme.GlassHighlight
import com.example.apneamonitor.ui.theme.GlassScrim
import com.example.apneamonitor.ui.theme.GlassSurface
import com.example.apneamonitor.ui.theme.GlassSurfaceStrong

enum class GlassVariant {
    Standard,
    Prominent,
    Subtle
}

private data class GlassStyle(
    val brush: Brush,
    val borderColor: Color,
    val shadowElevation: androidx.compose.ui.unit.Dp
)

@Composable
private fun rememberGlassStyle(variant: GlassVariant): GlassStyle {
    return remember(variant) {
        when (variant) {
            GlassVariant.Standard -> GlassStyle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        GlassHighlight,
                        GlassSurfaceStrong,
                        GlassScrim,
                        GlassSurface
                    )
                ),
                borderColor = GlassBorder,
                shadowElevation = 8.dp
            )
            GlassVariant.Prominent -> GlassStyle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0x3BF5F7FA),
                        Color(0x3525314A),
                        Color(0x24111A2E),
                        Color(0x1AF5F7FA)
                    )
                ),
                borderColor = GlassBorderStrong,
                shadowElevation = 12.dp
            )
            GlassVariant.Subtle -> GlassStyle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0x1EF5F7FA),
                        Color(0x1A25314A),
                        Color(0x10111A2E)
                    )
                ),
                borderColor = Color(0x26EEF3FF),
                shadowElevation = 4.dp
            )
        }
    }
}

@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val baseGradient = remember {
        Brush.verticalGradient(
            colors = listOf(BackdropStart, BackdropMid, BackdropEnd)
        )
    }
    val upperGlow = remember {
        Brush.radialGradient(
            colors = listOf(BackdropGlowCyan, Color.Transparent),
            radius = 760f,
            tileMode = TileMode.Decal
        )
    }
    val lowerGlow = remember {
        Brush.radialGradient(
            colors = listOf(BackdropGlowBlue, BackdropGlowPurple, Color.Transparent),
            radius = 920f,
            tileMode = TileMode.Decal
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseGradient)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(upperGlow)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(lowerGlow)
        )
        content()
    }
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    padding: PaddingValues = PaddingValues(20.dp),
    variant: GlassVariant = GlassVariant.Standard,
    borderColor: Color? = null,
    revealOnFirstComposition: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val style = rememberGlassStyle(variant)
    var isRevealed by remember { mutableStateOf(!revealOnFirstComposition) }
    val revealProgress by animateFloatAsState(
        targetValue = if (isRevealed) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "GlassPanelReveal"
    )

    LaunchedEffect(revealOnFirstComposition) {
        if (revealOnFirstComposition) {
            isRevealed = true
        }
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                alpha = revealProgress
                translationY = (1f - revealProgress) * 14f
            }
            .shadow(style.shadowElevation, shape, clip = false)
            .clip(shape)
            .background(
                brush = style.brush,
                shape = shape
            )
            .border(1.dp, borderColor ?: style.borderColor, shape),
        color = Color.Transparent,
        contentColor = Color.Unspecified,
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            content = content
        )
    }
}
