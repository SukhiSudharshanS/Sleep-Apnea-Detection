package com.example.apneamonitor.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.apneamonitor.data.local.SleepSessionEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class CsvGenerator(private val context: Context) {

    fun generateAndSaveCsv(session: SleepSessionEntity): Boolean {
        val stringBuilder = StringBuilder()
        
        // Add CSV Headers: Added RecordIndex for easier verification of 60s windows
        stringBuilder.append("RecordIndex,Timestamp,SpO2,BPM,MovementScore,AudioLevel\n")

        val size = maxOf(
            session.spO2Array.size, 
            session.bpmArray.size, 
            session.movementArray.size,
            session.audioArray.size
        )
        val durationMillis = session.endTimeStamp - session.startTimeStamp
        
        for (i in 0 until size) {
            val offsetMillis = if (size > 1) (durationMillis * i) / (size - 1) else 0L
            val currentTimestamp = session.startTimeStamp + offsetMillis
            
            // Higher precision for clinical data verification
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val timeString = timeFormat.format(Date(currentTimestamp))
            
            val spo2 = session.spO2Array.getOrNull(i) ?: 0
            val bpm = session.bpmArray.getOrNull(i) ?: 0
            val movement = session.movementArray.getOrNull(i) ?: 0
            val audio = session.audioArray.getOrNull(i) ?: 0
            
            val recordIndex = i + 1
            stringBuilder.append("$recordIndex,$timeString,$spo2,$bpm,$movement,$audio\n")
        }

        // Save using Scoped Storage (MediaStore)
        return saveCsvToMediaStore(stringBuilder.toString())
    }

    private fun saveCsvToMediaStore(csvData: String): Boolean {
        val resolver = context.contentResolver
        val timeStampForFile = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val fileName = "ApneaRawData_$timeStampForFile.csv"

        return try {
            val outputStream: OutputStream?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
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
                OutputStreamWriter(out).use { writer ->
                    writer.write(csvData)
                }
            }
            outputStream != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
