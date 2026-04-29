package com.example.apneamonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import android.content.Context
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apneamonitor.AppBluetoothManager
import com.example.apneamonitor.R
import com.example.apneamonitor.data.local.SleepSessionEntity
import com.example.apneamonitor.data.local.WeeklyTrendTuple
import com.example.apneamonitor.ui.components.GlassPanel
import com.example.apneamonitor.ui.components.InteractiveDualLineChart
import com.example.apneamonitor.ui.components.SleepScoreRing
import com.example.apneamonitor.ui.components.WeeklyTrendBarChart
import com.example.apneamonitor.ui.theme.*

@Composable
fun DashboardScreen(
    latestSession: SleepSessionEntity?,
    activeSession: SleepSessionEntity?,
    trendTuple: List<WeeklyTrendTuple>,
    connectionState: AppBluetoothManager.ConnectionState,
    riskScore: Int,
    countdown: Int,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onManualConnectTap: () -> Unit,
    onForceSyncTap: () -> Unit,
    onDownloadReport: (Context) -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // Single Source of Truth: Use active session if available, otherwise latest historical session
    val sessionSource = activeSession ?: latestSession
    val isSessionRunning = activeSession != null
    val safeMinSpo2 = sessionSource?.lowestSpO2 ?: 100

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_apnea_logo),
                    contentDescription = "Apnea Monitor Logo",
                    modifier = Modifier
                        .size(40.dp)
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

            FilledTonalIconButton(
                onClick = onManualConnectTap,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = GlassSurfaceStrong,
                    contentColor = OffWhite
                ),
                modifier = Modifier.border(1.dp, GlassBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Reconnect ring"
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val (pillText, pillBg, pillTextCol) = when (connectionState) {
            AppBluetoothManager.ConnectionState.CONNECTED -> Triple("Synced to Device", DarkGreen, LightGreen)
            AppBluetoothManager.ConnectionState.AUTO_SYNCING -> Triple("Auto-Syncing...", Color(0xFF4A4A00), SoftYellow)
            AppBluetoothManager.ConnectionState.CONNECTING -> Triple("Connecting...", Color(0xFF4A4A00), SoftYellow)
            AppBluetoothManager.ConnectionState.SCANNING -> Triple("Scanning...", GlassSurfaceStrong, OffWhite)
            else -> Triple("Disconnected (Tap to Sync)", GlassSurfaceStrong, OffWhite)
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = pillBg.copy(alpha = if (connectionState == AppBluetoothManager.ConnectionState.CONNECTED) 0.78f else 0.28f),
            tonalElevation = 0.dp,
            modifier = Modifier
                .clickable { onForceSyncTap() }
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).background(pillTextCol, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = pillText, color = pillTextCol, style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isSessionRunning) onStopSession() else onStartSession()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            colors = if (isSessionRunning) {
                ButtonDefaults.outlinedButtonColors(
                    containerColor = GlassSurfaceStrong,
                    contentColor = CoralRed
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = LightGreen.copy(alpha = 0.92f),
                    contentColor = BackdropStart
                )
            },
            border = if (isSessionRunning) BorderStroke(1.dp, CoralRed.copy(alpha = 0.6f)) else BorderStroke(1.dp, GlassBorder),
            shape = RoundedCornerShape(22.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text(
                text = if (isSessionRunning) "Stop Monitoring" else "Start New Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // --- HERO SECTION (RISK RING) ---
        val riskColor = when {
            riskScore <= 30 -> LightGreen
            riskScore <= 60 -> SoftYellow
            else -> CoralRed
        }

        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(36.dp),
            padding = PaddingValues(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Text(
                text = "Respiratory Snapshot",
                color = MutedText,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(20.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(228.dp)
                ) {
                    SleepScoreRing(
                        targetScore = riskScore,
                        color = riskColor,
                        showText = false,
                        modifier = Modifier.size(188.dp)
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "$riskScore%",
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 54.sp),
                            color = OffWhite
                        )
                        Text(
                            text = "RISK INDEX",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                            letterSpacing = 1.8.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = "Apnea Events",
                value = "${sessionSource?.totalApneaEvents ?: 0}",
                accentColor = CoralRed,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Restless Events",
                value = "${sessionSource?.totalRestlessEvents ?: 0}",
                accentColor = SoftYellow,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = "Avg Oxygen",
                value = "${sessionSource?.avgSpO2 ?: 0}%",
                accentColor = LightGreen,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Min SpO2",
                value = "${sessionSource?.lowestSpO2 ?: 0}%",
                accentColor = if (safeMinSpo2 in 1..89) CoralRed else LightGreen,
                valueColor = if (safeMinSpo2 in 1..89) CoralRed else OffWhite,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (latestSession != null && latestSession.spO2Array.isNotEmpty()) {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp)
            ) {
                Text("Overnight Cycle", color = OffWhite, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                InteractiveDualLineChart(
                    spO2Data = latestSession.spO2Array,
                    bpmData = latestSession.bpmArray,
                    movementData = latestSession.movementArray
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (trendTuple.isNotEmpty()) {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Text("Weekly Trend", color = OffWhite, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    WeeklyTrendBarChart(trends = trendTuple)
                }
            }
        } else {
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                shape = RoundedCornerShape(30.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(), 
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Vico Real-Time Mapping\n(Awaiting Valid Sensor Data)",
                        color = MutedText,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onDownloadReport(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GlassSurfaceStrong, contentColor = OffWhite),
            border = BorderStroke(1.dp, GlassBorder),
            shape = RoundedCornerShape(22.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_apnea_logo), 
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = CoralRed
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Download Clinical Summary",
                style = MaterialTheme.typography.titleMedium,
                color = OffWhite
            )
        }
        
        Spacer(modifier = Modifier.height(112.dp))
    }
}


@Composable
fun StatCard(
    title: String,
    value: String,
    accentColor: Color,
    valueColor: Color = OffWhite,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        modifier = modifier
            .height(136.dp),
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(accentColor, CircleShape)
            )
            Text(
                text = title,
                color = MutedText,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
