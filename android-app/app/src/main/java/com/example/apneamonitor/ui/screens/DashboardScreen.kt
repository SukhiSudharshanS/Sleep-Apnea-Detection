package com.example.apneamonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.apneamonitor.ui.components.InteractiveDualLineChart
import com.example.apneamonitor.ui.components.SleepScoreRing
import com.example.apneamonitor.ui.components.WeeklyTrendBarChart
import com.example.apneamonitor.ui.theme.*

@Composable
fun DashboardScreen(
    latestSession: SleepSessionEntity?,
    trendTuple: List<WeeklyTrendTuple>,
    connectionState: AppBluetoothManager.ConnectionState,
    riskScore: Int,
    countdown: Int,
    onForceSyncTap: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Enforce Zero-State Defaults if DB is empty
    val safeScore = latestSession?.sleepScore ?: 0
    val safeApneas = latestSession?.totalApneaEvents ?: 0
    val safeAvgSpO2 = latestSession?.avgSpO2 ?: 0
    val safeMinSpO2 = latestSession?.lowestSpO2 ?: 0
    val safeBpm = latestSession?.avgBpm ?: 0
    val safeRestless = latestSession?.totalRestlessEvents ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBlue)
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- HEADER SECTION ---
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
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        
        // Dynamic Sync Pill
        val (pillText, pillBg, pillTextCol) = when (connectionState) {
            AppBluetoothManager.ConnectionState.CONNECTED -> Triple("Synced to Ring", DarkGreen, LightGreen)
            AppBluetoothManager.ConnectionState.AUTO_SYNCING -> Triple("Auto-Syncing...", Color(0xFF4A4A00), Color.Yellow)
            AppBluetoothManager.ConnectionState.CONNECTING -> Triple("Connecting...", Color(0xFF4A4A00), Color.Yellow)
            AppBluetoothManager.ConnectionState.SCANNING -> Triple("Scanning...", DeepNavy, Color.Gray)
            else -> Triple("Disconnected (Tap to Sync)", DeepNavy, Color.Gray)
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = pillBg),
            modifier = Modifier.clickable { onForceSyncTap() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).background(pillTextCol, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = pillText, color = pillTextCol, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        // --- HERO SECTION (APNEA RISK GAUGE) ---
        val riskColor = when {
            riskScore <= 30 -> LightGreen // Green
            riskScore <= 70 -> SoftYellow // Yellow
            else -> CoralRed             // Red
        }
        
        val riskStatus = when {
            riskScore <= 30 -> "Status: Normal Breathing"
            riskScore <= 70 -> "Status: Moderate Risk"
            else -> "Status: APNEA DETECTED"
        }

        Box(contentAlignment = Alignment.Center) {
            SleepScoreRing(
                targetScore = riskScore,
                color = riskColor,
                modifier = Modifier.width(180.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = riskStatus,
                color = riskColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(DeepNavy, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val timerText = if (countdown > 0) "Next update in: ${countdown}s" else "Analyzing Edge ML..."
            val timerColor = if (countdown > 0) Color.Gray else SoftYellow

            Text(
                text = timerText,
                color = timerColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        Spacer(modifier = Modifier.height(40.dp))

        // --- VITAL STATS GRID ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = "Apnea Events",
                value = "$safeApneas",
                iconColor = CoralRed,
                showAlertDot = safeApneas > 0,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Restless Events",
                value = "$safeRestless",
                iconColor = RestlessOrange,
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
                value = "$safeAvgSpO2%",
                iconColor = Cyan,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Min SpO2",
                value = "$safeMinSpO2%",
                iconColor = CoralRed,
                textColor = CoralRed,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = "Avg BPM",
                value = "$safeBpm",
                iconColor = SoftPurple,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.weight(1f))
        }
        
        Spacer(modifier = Modifier.height(40.dp))

        // --- INTERACTIVE CHARTS ---
        if (latestSession != null && latestSession.spO2Array.isNotEmpty()) {
            Text("Overnight Cycle", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(16.dp))
            InteractiveDualLineChart(
                spO2Data = latestSession.spO2Array,
                bpmData = latestSession.bpmArray,
                movementData = latestSession.movementArray
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (trendTuple.isNotEmpty()) {
                Text("Weekly Trend", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(16.dp))
                WeeklyTrendBarChart(trends = trendTuple)
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                colors = CardDefaults.cardColors(containerColor = DeepNavy),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(), 
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Vico Real-Time Mapping\n(Awaiting Valid Sensor Data)",
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}


@Composable
fun StatCard(
    title: String,
    value: String,
    iconColor: Color,
    textColor: Color = Color.White,
    showAlertDot: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DeepNavy),
        modifier = modifier.height(115.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            if (showAlertDot) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(CoralRed, CircleShape)
                        .align(Alignment.TopEnd)
                )
            }
            
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(iconColor.copy(alpha = 0.15f), CircleShape), 
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(12.dp).background(iconColor, CircleShape))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(value, color = textColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                
                Text(title, color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}
