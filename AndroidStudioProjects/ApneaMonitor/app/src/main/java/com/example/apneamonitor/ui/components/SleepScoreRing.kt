package com.example.apneamonitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.apneamonitor.ui.theme.Cyan
import com.example.apneamonitor.ui.theme.DeepNavy

@Composable
fun SleepScoreRing(
    targetScore: Int,
    color: Color = Cyan,
    modifier: Modifier = Modifier
) {
    var animationPlayed by remember { mutableStateOf(false) }
    
    // Animate from 0 to the specific score over 1.5 seconds internally.
    val curPercentage = animateFloatAsState(
        targetValue = if (animationPlayed) targetScore.toFloat() else 0f,
        animationSpec = tween(durationMillis = 1500),
        label = "SleepRingScale"
    )

    LaunchedEffect(key1 = targetScore) {
        animationPlayed = true
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.aspectRatio(1f)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.width * 0.08f
            
            // Background Ghost Track
            drawArc(
                color = DeepNavy,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
                size = Size(size.width, size.height),
                topLeft = Offset(0f, 0f)
            )

            // Primary Animate Track
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (curPercentage.value / 100f),
                useCenter = false,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
                size = Size(size.width, size.height),
                topLeft = Offset(0f, 0f)
            )
        }

        Text(
            text = "${(curPercentage.value).toInt()}",
            color = color,
            fontSize = 64.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
