package com.example.apneamonitor.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.apneamonitor.data.local.SleepSessionEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class PdfGenerator(private val context: Context) {

    fun generateAndSaveReport(session: SleepSessionEntity): Boolean {
        // Create an A4 sized page (595 x 842 points for PDF)
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        drawPdfContent(canvas, session)

        pdfDocument.finishPage(page)
        
        // Save using Scoped Storage (MediaStore)
        val success = savePdfToMediaStore(pdfDocument)
        pdfDocument.close()
        return success
    }

    private fun drawPdfContent(canvas: Canvas, session: SleepSessionEntity) {
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val headerPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.GRAY
            strokeWidth = 1f
        }
        val disclaimerPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 9f
            isAntiAlias = true
        }

        var currentY = 50f
        val startX = 50f
        val lineEndX = 545f

        // Document Header
        canvas.drawText("CLINICAL SLEEP SUMMARY", startX, currentY, titlePaint)
        currentY += 20f
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val generatedTime = sdf.format(Date())
        canvas.drawText("Generated: $generatedTime", startX, currentY, bodyPaint)
        canvas.drawText("Device: ApneaMonitor (MAX30102_Ring IoT Framework)", startX, currentY + 15f, bodyPaint)
        currentY += 35f
        
        canvas.drawLine(startX, currentY, lineEndX, currentY, linePaint)
        currentY += 25f

        // Section 1: Patient Details (Mock mapping for IoT sensor context)
        canvas.drawText("SESSION DETAILS", startX, currentY, headerPaint)
        currentY += 20f
        canvas.drawText("Recorded Date: ${session.dateString}", startX, currentY, bodyPaint)
        
        // Formulate 8-hour mock time span based on start timestamp
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val startStr = timeFormat.format(Date(session.startTimeStamp))
        val endStr = timeFormat.format(Date(session.endTimeStamp))
        canvas.drawText("Recording Window: $startStr to $endStr", startX, currentY + 15f, bodyPaint)
        currentY += 40f
        
        canvas.drawLine(startX, currentY, lineEndX, currentY, linePaint)
        currentY += 25f

        // Section 2: Clinical Metrics
        canvas.drawText("CLINICAL METRICS", startX, currentY, headerPaint)
        currentY += 25f
        
        // Tabular alignment mockup
        canvas.drawText("Total Apnea Events Detected:", startX, currentY, bodyPaint)
        canvas.drawText("${session.totalApneaEvents} Events", 250f, currentY, titlePaint)
        currentY += 25f
        
        canvas.drawText("Total Restless Epochs (Actigraphy):", startX, currentY, bodyPaint)
        canvas.drawText("${session.totalRestlessEvents} Events", 250f, currentY, titlePaint)
        currentY += 30f

        canvas.drawText("Average SpO2 Saturation:", startX, currentY, bodyPaint)
        canvas.drawText("${session.avgSpO2}%", 250f, currentY, bodyPaint)
        currentY += 20f

        canvas.drawText("Minimum SpO2 Recorded:", startX, currentY, bodyPaint)
        val minOxygenRisk = if (session.lowestSpO2 < 90) " (WARN: Hypoxemia Risk)" else ""
        canvas.drawText("${session.lowestSpO2}%$minOxygenRisk", 250f, currentY, bodyPaint)
        currentY += 20f

        canvas.drawText("Average Resting BPM:", startX, currentY, bodyPaint)
        canvas.drawText("${session.avgBpm} BPM", 250f, currentY, bodyPaint)
        currentY += 40f

        canvas.drawLine(startX, currentY, lineEndX, currentY, linePaint)
        currentY += 25f

        // Section 3: Diagnostic Inference
        canvas.drawText("ALGORITHMIC CONCLUSION", startX, currentY, headerPaint)
        currentY += 20f
        
        val riskLevel = if (session.totalApneaEvents > 5 || session.lowestSpO2 < 90) "ELEVATED RISK" else "NORMAL BASELINE"
        canvas.drawText("The Neural Fusion edge models evaluate this session as: $riskLevel", startX, currentY, bodyPaint)
        currentY += 20f
        canvas.drawText("Composite Sleep Score: ${session.sleepScore}/100", startX, currentY, bodyPaint)
        currentY += 50f
        
        // The Disclaimer Section - Must be bounded to the bottom of the A4 page (e.g. Y=780)
        val disclaimerY = 750f
        val disclaimerLines = listOf(
            "DISCLAIMER: This report is generated by an experimental IoT wearable sensor system.",
            "It is not an FDA-approved medical device and is not intended to diagnose, treat, cure, or",
            "prevent any disease. These metrics are for informational purposes only. Please consult a",
            "qualified sleep specialist or physician for a formal polysomnography (PSG) diagnosis."
        )
        
        var dy = disclaimerY
        disclaimerLines.forEach { line ->
            canvas.drawText(line, startX, dy, disclaimerPaint)
            dy += 12f
        }
    }

    private fun savePdfToMediaStore(pdfDocument: PdfDocument): Boolean {
        val resolver = context.contentResolver
        val timeStampForFile = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        val fileName = "ApneaReport_$timeStampForFile.pdf"

        return try {
            val outputStream: OutputStream?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                outputStream = uri?.let { resolver.openOutputStream(it) }
            } else {
                // Fallback for older devices handling Downloads natively
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                outputStream = FileOutputStream(file)
            }

            outputStream?.use { out ->
                pdfDocument.writeTo(out)
            }
            outputStream != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
