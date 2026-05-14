package com.example.apneamonitor.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.apneamonitor.data.local.WeeklyTrendTuple
import com.example.apneamonitor.ui.theme.RestlessOrange
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.chart.column.ColumnChart
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry

@Composable
fun InteractiveDualLineChart(
    spO2Data: List<Int>,
    bpmData: List<Int>,
    movementData: List<Int> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (spO2Data.isEmpty() || bpmData.isEmpty()) return

    val lineChartEntryModelProducer = remember(spO2Data, bpmData) {
        val displaySize = minOf(300, spO2Data.size, bpmData.size)
        val spo2Entries = List(displaySize) { FloatEntry(it.toFloat(), spO2Data[it].toFloat()) }
        val bpmEntries = List(displaySize) { FloatEntry(it.toFloat(), bpmData[it].toFloat()) }
        ChartEntryModelProducer(spo2Entries, bpmEntries)
    }

    Box(modifier = modifier.fillMaxWidth().height(250.dp)) {
        // --- 1. Background Column Layer (Actigraphy Movement) ---
        if (movementData.isNotEmpty()) {
            val movProducer = remember(movementData) {
                val movDisplaySize = minOf(300, movementData.size)
                val movEntries = List(movDisplaySize) { FloatEntry(it.toFloat(), movementData[it].toFloat()) }
                ChartEntryModelProducer(movEntries)
            }
            
            Chart(
                chart = columnChart(
                    columns = listOf(
                        LineComponent(
                            color = RestlessOrange.copy(alpha = 0.35f).toArgb(),
                            thicknessDp = 4f
                        )
                    )
                ),
                chartModelProducer = movProducer,
                startAxis = null, // Hide axis for background layer
                bottomAxis = null,
                modifier = Modifier.matchParentSize()
            )
        }

        // --- 2. Foreground Line Layer (Physiological Vitals) ---
        Chart(
            chart = lineChart(),
            chartModelProducer = lineChartEntryModelProducer,
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
            modifier = Modifier.matchParentSize()
        )
    }
}

@Composable
fun WeeklyTrendBarChart(
    trends: List<WeeklyTrendTuple>,
    modifier: Modifier = Modifier
) {
    if (trends.isEmpty()) return
    
    val chartEntryModelProducer = remember(trends) {
        val entries = trends.mapIndexed { index, tuple ->
            FloatEntry(index.toFloat(), tuple.totalApneaEvents.toFloat())
        }
        ChartEntryModelProducer(entries)
    }

    Chart(
        chart = columnChart(),
        chartModelProducer = chartEntryModelProducer,
        startAxis = rememberStartAxis(),
        bottomAxis = rememberBottomAxis(),
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    )
}
