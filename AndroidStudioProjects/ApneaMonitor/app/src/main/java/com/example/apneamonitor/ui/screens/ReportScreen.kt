package com.example.apneamonitor.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apneamonitor.R
import com.example.apneamonitor.data.local.SleepSessionEntity
import com.example.apneamonitor.ui.theme.Cyan
import com.example.apneamonitor.ui.theme.DeepNavy
import com.example.apneamonitor.ui.theme.MidnightBlue
import com.example.apneamonitor.utils.CsvGenerator
import com.example.apneamonitor.utils.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportScreen(
    latestSession: SleepSessionEntity?,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Clinical Sleep Summary",
            color = Cyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (latestSession != null) {
            // Conversational Data Translation UI Map
            Card(
                colors = CardDefaults.cardColors(containerColor = DeepNavy),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Session Overview",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Sentence parsing logic
                    val eventsStr = if (latestSession.totalApneaEvents == 0) {
                        "Excellent sleep cycle. No potential apneic anomalies were recorded by the neural framework."
                    } else {
                        "During the 8-hour session, the system detected ${latestSession.totalApneaEvents} potential physiological apnea indications."
                    }

                    val oxygenStr = if (latestSession.lowestSpO2 >= 90) {
                        "Your blood oxygen concentration held resiliently at an average of ${latestSession.avgSpO2}%, bottoming out safely at ${latestSession.lowestSpO2}%. This signifies healthy respiratory levels."
                    } else {
                        "Caution: Your blood oxygen dropped to a critical low of ${latestSession.lowestSpO2}%, indicating hypoxemia episodes directly correlated to breath disruption."
                    }

                    val scoreStr = "The local Random Forest edge model merged your vitals into a composite sleep viability score of ${latestSession.sleepScore} out of 100."
                    
                    val movementStr = if (latestSession.totalRestlessEvents > 3) {
                        "Actigraphy sensors recorded ${latestSession.totalRestlessEvents} distinct restless events, which highly correlates with potential sleep fragmentation."
                    } else {
                        "Your biomechanical baseline remained calm, recording minimal restless events throughout the cycle."
                    }

                    Text(text = eventsStr, color = Color.LightGray, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = oxygenStr, color = Color.LightGray, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = movementStr, color = Color.LightGray, fontSize = 15.sp, lineHeight = 22.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = scoreStr, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Action Triggers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            // Offload PDF Drawing & File IO to safely prevent jank
                            val success = withContext(Dispatchers.IO) {
                                val generator = PdfGenerator(context)
                                generator.generateAndSaveReport(latestSession)
                            }
                            
                            if (success) {
                                Toast.makeText(context, "Saved to Downloads folder", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Error saving PDF", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = MidnightBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text(
                        text = "PDF Summary",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                val generator = CsvGenerator(context)
                                generator.generateAndSaveCsv(latestSession)
                            }
                            
                            if (success) {
                                Toast.makeText(context, "Saved to Downloads folder", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Error saving CSV", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    // Outline style for the secondary action
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Cyan),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Cyan),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text(
                        text = "Export Raw CSV",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Null State Mapping
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Sync an Edge device to generate a report.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
