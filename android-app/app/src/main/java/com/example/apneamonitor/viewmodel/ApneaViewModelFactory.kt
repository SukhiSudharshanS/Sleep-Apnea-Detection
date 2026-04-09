package com.example.apneamonitor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.apneamonitor.AppBluetoothManager
import com.example.apneamonitor.data.repository.SleepDataRepository

class ApneaViewModelFactory(
    private val repository: SleepDataRepository,
    private val bluetoothManager: AppBluetoothManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ApneaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ApneaViewModel(repository, bluetoothManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
