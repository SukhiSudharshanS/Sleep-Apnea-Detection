package com.example.apneamonitor.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apneamonitor.R
import com.example.apneamonitor.ui.components.GlassPanel
import com.example.apneamonitor.ui.components.GlassVariant
import com.example.apneamonitor.ui.theme.*

@Composable
fun LiveMonitorScreen(
    spo2: Int,
    bpm: Int,
    movement: Int,
    apneaAlert: Int,
    audioLevel: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(apneaAlert) {
        if (apneaAlert == 1) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_apnea_logo),
                contentDescription = "Apnea Monitor Logo",
                modifier = Modifier
                    .size(36.dp)
                    .padding(end = 8.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                text = "ApneaMonitor",
                color = OffWhite,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            variant = GlassVariant.Prominent
        ) {
            Text(
                text = "Live Diagnostics",
                color = OffWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Streaming direct telemetry from the connected device",
                color = MutedText,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LiveMetricCard(
                    title = "O2 %",
                    value = "$spo2",
                    accentColor = LightGreen,
                    modifier = Modifier.weight(1f)
                )

                LiveMetricCard(
                    title = "BPM",
                    value = "$bpm",
                    accentColor = GlassGlow,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LiveMetricCard(
                    title = "Motion",
                    value = "$movement",
                    accentColor = SoftYellow,
                    progress = movement / 10f,
                    modifier = Modifier.weight(1f)
                )

                LiveMetricCard(
                    title = "Audio Level",
                    value = "$audioLevel",
                    icon = Icons.Default.Mic,
                    accentColor = SoftPurple,
                    progress = audioLevel / 10f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(104.dp))
    }
}

@Composable
fun LiveMetricCard(
    title: String, 
    value: String, 
    accentColor: Color,
    icon: ImageVector? = null, 
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    val numericValue = value.toIntOrNull()
    val animatedValue by animateIntAsState(
        targetValue = numericValue ?: 0,
        animationSpec = tween(durationMillis = 240),
        label = "LiveMetricValue"
    )
    val animatedProgress by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 240),
        label = "LiveMetricProgress"
    )

    GlassPanel(
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(18.dp),
        variant = GlassVariant.Standard
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(accentColor, androidx.compose.foundation.shape.CircleShape)
                )
                Text(
                    text = title,
                    color = MutedText,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = numericValue?.let { animatedValue.toString() } ?: value,
                    color = OffWhite,
                    style = MaterialTheme.typography.displayMedium
                )
            }

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .border(1.dp, GlassBorder, RoundedCornerShape(50)),
                    color = accentColor,
                    trackColor = GlassSurfaceStrong,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

