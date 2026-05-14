package com.example.apneamonitor.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
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
import com.example.apneamonitor.ui.components.GlassPanel
import com.example.apneamonitor.ui.components.GlassVariant
import com.example.apneamonitor.ui.theme.*
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
                text = "Clinical Sleep Summary",
                color = OffWhite,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "A calmer, floating review of the latest recorded sleep session",
                color = MutedText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        if (latestSession != null) {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                padding = PaddingValues(22.dp),
                variant = GlassVariant.Standard
            ) {
                Text(
                    text = "Session Overview",
                    color = OffWhite,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))

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

                val movementStr = if (latestSession.totalRestlessEvents > 3) {
                    "Actigraphy sensors recorded ${latestSession.totalRestlessEvents} distinct restless events, which highly correlates with potential sleep fragmentation."
                } else {
                    "Your biomechanical baseline remained calm, recording minimal restless events throughout the cycle."
                }

                Text(text = eventsStr, color = MutedText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = oxygenStr, color = MutedText, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = movementStr, color = MutedText, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(modifier = Modifier.height(40.dp))

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
                    colors = ButtonDefaults.buttonColors(containerColor = GlassSurfaceStrong, contentColor = OffWhite),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = "PDF Summary",
                        style = MaterialTheme.typography.labelLarge,
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = OffWhite),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = "Export Raw CSV",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(112.dp))
        } else {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                padding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
                variant = GlassVariant.Subtle
            ) {
                Text(
                    text = "Sync an Edge device to generate a report.",
                    color = MutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
