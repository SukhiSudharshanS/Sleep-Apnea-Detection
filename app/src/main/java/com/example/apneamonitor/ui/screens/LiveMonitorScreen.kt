package com.example.apneamonitor.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.apneamonitor.ui.theme.Cyan
import com.example.apneamonitor.ui.theme.DeepNavy
import com.example.apneamonitor.ui.theme.MidnightBlue

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
            .background(MidnightBlue)
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- BRANDED HEADER ---
        Row(
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
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Live Diagnostics",
            color = Cyan,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // 2x2 Live Metrics Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveMetricCard(
                    title = "O2 %",
                    value = "$spo2",
                    modifier = Modifier.weight(1f)
                )

                LiveMetricCard(
                    title = "BPM",
                    value = "$bpm",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveMetricCard(
                    title = "Motion",
                    value = "$movement",
                    progress = movement / 10f,
                    modifier = Modifier.weight(1f)
                )

                LiveMetricCard(
                    title = "Audio Level",
                    value = "$audioLevel",
                    icon = Icons.Default.Mic,
                    progress = audioLevel / 10f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

    }
}

@Composable
fun LiveMetricCard(
    title: String, 
    value: String, 
    icon: ImageVector? = null, 
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = DeepNavy),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = Cyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = value,
                color = Cyan,
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                color = Color.Gray,
                style = MaterialTheme.typography.labelMedium
            )

            if (progress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(4.dp),
                    color = Cyan,
                    trackColor = Color.Gray.copy(alpha = 0.2f),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

