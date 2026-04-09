package com.example.apneamonitor

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.apneamonitor.data.local.AppDatabase
import com.example.apneamonitor.workers.ApneaSyncWorker
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ApneaApplication : Application() {
    
    // Lazy initialize the database so the logic is executed only when needed
    val database by lazy { AppDatabase.getDatabase(this) }
    
    // Application-scoped singleton for BLE
    val bluetoothManager by lazy { AppBluetoothManager(this) }
    
    override fun onCreate() {
        super.onCreate()
        scheduleBackgroundSync()
    }

    private fun scheduleBackgroundSync() {
        // Calculate initial delay to 7:00 AM
        val currentDate = Calendar.getInstance()
        val dueDate = Calendar.getInstance()
        
        dueDate.set(Calendar.HOUR_OF_DAY, 7)
        dueDate.set(Calendar.MINUTE, 0)
        dueDate.set(Calendar.SECOND, 0)
        
        // If it's already past 7:00 AM today, schedule for tomorrow
        if (dueDate.before(currentDate)) {
            dueDate.add(Calendar.HOUR_OF_DAY, 24)
        }
        
        val initialDelay = dueDate.timeInMillis - currentDate.timeInMillis
        
        // Setup Constraints (e.g. Needs battery not low)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
            
        val syncRequest = PeriodicWorkRequestBuilder<ApneaSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()
            
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ApneaSyncWorker",
            ExistingPeriodicWorkPolicy.UPDATE, // Uses UPDATE to gracefully revise existing policies in Android 12+
            syncRequest
        )
    }
}
