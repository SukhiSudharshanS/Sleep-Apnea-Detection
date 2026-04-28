package com.example.apneamonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.apneamonitor.data.repository.SleepDataRepository
import com.example.apneamonitor.ui.screens.DashboardScreen
import com.example.apneamonitor.ui.screens.LiveMonitorScreen
import com.example.apneamonitor.ui.screens.ReportScreen
import com.example.apneamonitor.ui.theme.ApneaMonitorTheme
import com.example.apneamonitor.ui.theme.DeepNavy
import com.example.apneamonitor.ui.theme.MutedText
import com.example.apneamonitor.ui.theme.OffWhite
import com.example.apneamonitor.ui.theme.SurfaceLighter
import com.example.apneamonitor.viewmodel.ApneaViewModel
import com.example.apneamonitor.viewmodel.ApneaViewModelFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Manual DI wiring for Architecture Migration
        val app = application as ApneaApplication
        val repository = SleepDataRepository(app.database.sleepSessionDao())
        val bluetoothManager = app.bluetoothManager
        val factory = ApneaViewModelFactory(repository, bluetoothManager)

        setContent {
            ApneaMonitorTheme {
                val viewModel: ApneaViewModel = viewModel(factory = factory)
                ApneaMainScaffold(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ApneaMainScaffold(viewModel: ApneaViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, 
                android.content.Intent(context, com.example.apneamonitor.service.ApneaBleService::class.java)
            )
            viewModel.connectOrSync()
        }
    }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Auto-Sync Hook Trigger (Lifecycle simulation)
    LaunchedEffect(Unit) {
        val hasPermissions = requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermissions) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, 
                android.content.Intent(context, com.example.apneamonitor.service.ApneaBleService::class.java)
            )
            viewModel.connectOrSync() // Automatically scans & syncs in background if ring is near
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            if (currentRoute != "splash") {
                NavigationBar(containerColor = DeepNavy, contentColor = OffWhite) {
                    NavigationBarItem(
                        selected = currentRoute == "dashboard",
                    onClick = {
                        navController.navigate("dashboard") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = OffWhite,
                        selectedTextColor = OffWhite,
                        indicatorColor = SurfaceLighter,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )

                NavigationBarItem(
                    selected = currentRoute == "live_monitor",
                    onClick = {
                        navController.navigate("live_monitor") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Info, contentDescription = "Live") },
                    label = { Text("Live Monitor") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = OffWhite,
                        selectedTextColor = OffWhite,
                        indicatorColor = SurfaceLighter,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )

                NavigationBarItem(
                    selected = currentRoute == "report",
                    onClick = {
                        navController.navigate("report") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.List, contentDescription = "Report") },
                    label = { Text("Report") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = OffWhite,
                        selectedTextColor = OffWhite,
                        indicatorColor = SurfaceLighter,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    )
                )
            }
            } // Close if block
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "splash",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("splash") {
                com.example.apneamonitor.ui.screens.SplashScreen(
                    onNavigateNext = {
                        navController.navigate("dashboard") {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                )
            }
            composable("dashboard") {
                val latestSession by viewModel.latestSession.collectAsStateWithLifecycle()
                val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
                val weeklyTrend by viewModel.weeklyTrend.collectAsStateWithLifecycle()
                val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
                val riskScore by viewModel.riskScore.collectAsStateWithLifecycle()
                val countdown by viewModel.timeUntilNextAssessment.collectAsStateWithLifecycle()
 
                DashboardScreen(
                    latestSession = latestSession,
                    activeSession = activeSession,
                    trendTuple = weeklyTrend,
                    connectionState = connectionState,
                    riskScore = riskScore,
                    countdown = countdown,
                    onStartSession = { viewModel.startNewSession() },
                    onStopSession = { viewModel.stopSession() },
                    onManualConnectTap = {
                        val hasPermissions = requiredPermissions.all { perm ->
                            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                        }
                        if (hasPermissions) {
                            androidx.core.content.ContextCompat.startForegroundService(
                                context,
                                android.content.Intent(context, com.example.apneamonitor.service.ApneaBleService::class.java)
                            )
                            viewModel.connectOrSync()
                            Toast.makeText(context, "Scanning for Ring...", Toast.LENGTH_SHORT).show()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    onForceSyncTap = {
                        val hasPermissions = requiredPermissions.all { perm ->
                            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                        }
                        if (hasPermissions) {
                            androidx.core.content.ContextCompat.startForegroundService(
                                context, 
                                android.content.Intent(context, com.example.apneamonitor.service.ApneaBleService::class.java)
                            )
                            viewModel.connectOrSync()
                            Toast.makeText(context, "Scanning for Ring...", Toast.LENGTH_SHORT).show()
                        }
                        else permissionLauncher.launch(requiredPermissions)
                    },
                    onDownloadReport = { ctx: android.content.Context ->
                        val success = viewModel.generateSessionSummary(ctx)
                        if (success) {
                            Toast.makeText(ctx, "Report saved to Downloads", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(ctx, "Failed to generate report", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            composable("live_monitor") {
                val spo2 by viewModel.liveSpo2.collectAsStateWithLifecycle()
                val bpm by viewModel.liveBpm.collectAsStateWithLifecycle()
                val movement by viewModel.liveMovement.collectAsStateWithLifecycle()
                val apneaAlert by viewModel.liveApneaAlert.collectAsStateWithLifecycle()
                val audioLevel by viewModel.liveAudioLevel.collectAsStateWithLifecycle()

                LiveMonitorScreen(
                    spo2 = spo2,
                    bpm = bpm,
                    movement = movement,
                    apneaAlert = apneaAlert,
                    audioLevel = audioLevel
                )
            }
            composable("report") {
                val latestSession by viewModel.latestSession.collectAsStateWithLifecycle()
                ReportScreen(
                    latestSession = latestSession 
                )
            }
        }
    }
}
