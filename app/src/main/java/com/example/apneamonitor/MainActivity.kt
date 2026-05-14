package com.example.apneamonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.example.apneamonitor.data.repository.SleepDataRepository
import com.example.apneamonitor.ui.components.GlassBackground
import com.example.apneamonitor.ui.components.GlassPanel
import com.example.apneamonitor.ui.components.GlassVariant
import com.example.apneamonitor.ui.screens.DashboardScreen
import com.example.apneamonitor.ui.screens.LiveMonitorScreen
import com.example.apneamonitor.ui.screens.ReportScreen
import com.example.apneamonitor.ui.theme.ApneaMonitorTheme
import com.example.apneamonitor.ui.theme.*
import com.example.apneamonitor.viewmodel.ApneaViewModel
import com.example.apneamonitor.viewmodel.ApneaViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

private enum class BlePermissionAction {
    Connect,
    StartSession
}

private fun hasRequiredPermissions(context: Context, permissions: Array<String>): Boolean {
    return permissions.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

private fun startApneaBleService(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, com.example.apneamonitor.service.ApneaBleService::class.java)
    )
}

@Composable
fun ApneaMainScaffold(viewModel: ApneaViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingPermissionAction by remember { mutableStateOf<BlePermissionAction?>(null) }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun runGrantedBleAction(action: BlePermissionAction) {
        when (action) {
            BlePermissionAction.Connect -> viewModel.connectOrSync()
            BlePermissionAction.StartSession -> {
                startApneaBleService(context)
                viewModel.connectOrSync()
                viewModel.startNewSession()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            runGrantedBleAction(pendingPermissionAction ?: BlePermissionAction.Connect)
        }
        pendingPermissionAction = null
    }

    fun requestBleAction(action: BlePermissionAction) {
        if (hasRequiredPermissions(context, requiredPermissions)) {
            runGrantedBleAction(action)
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(Unit) {
        delay(700)
        if (viewModel.hasSavedDevice() && hasRequiredPermissions(context, requiredPermissions)) {
            viewModel.connectOrSync() // Automatically scans & syncs in background if ring is near
        }
    }

    val screenMotionEasing = remember { CubicBezierEasing(0.2f, 0f, 0f, 1f) }
    val bottomRoutes = remember { setOf("dashboard", "live_monitor", "report") }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                AnimatedVisibility(
                    visible = currentRoute != null && bottomRoutes.contains(currentRoute),
                    enter = slideInVertically(
                        animationSpec = tween(320, easing = screenMotionEasing),
                        initialOffsetY = { it / 2 }
                    ) + fadeIn(animationSpec = tween(180)),
                    exit = slideOutVertically(
                        animationSpec = tween(220, easing = screenMotionEasing),
                        targetOffsetY = { it / 2 }
                    ) + fadeOut(animationSpec = tween(140))
                ) {
                    Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        GlassPanel(
                            modifier = Modifier
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            padding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            variant = GlassVariant.Prominent,
                            revealOnFirstComposition = false
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                contentColor = OffWhite,
                                tonalElevation = 0.dp
                            ) {
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
                                        indicatorColor = GlassSurface,
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
                                        indicatorColor = GlassSurface,
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
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Report") },
                                    label = { Text("Report") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = OffWhite,
                                        selectedTextColor = OffWhite,
                                        indicatorColor = GlassSurface,
                                        unselectedIconColor = MutedText,
                                        unselectedTextColor = MutedText
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "dashboard",
                modifier = Modifier.padding(innerPadding),
                enterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(360, easing = screenMotionEasing),
                        initialOffsetX = { it / 8 }
                    ) + fadeIn(animationSpec = tween(220))
                },
                exitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(260, easing = screenMotionEasing),
                        targetOffsetX = { -it / 10 }
                    ) + fadeOut(animationSpec = tween(160))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(360, easing = screenMotionEasing),
                        initialOffsetX = { -it / 8 }
                    ) + fadeIn(animationSpec = tween(220))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(260, easing = screenMotionEasing),
                        targetOffsetX = { it / 10 }
                    ) + fadeOut(animationSpec = tween(160))
                }
            ) {
            composable("dashboard") {
                val latestSession by viewModel.latestSession.collectAsStateWithLifecycle()
                val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
                val weeklyTrend by viewModel.weeklyTrend.collectAsStateWithLifecycle()
                val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
                val riskScore by viewModel.riskScore.collectAsStateWithLifecycle()
 
                DashboardScreen(
                    latestSession = latestSession,
                    activeSession = activeSession,
                    trendTuple = weeklyTrend,
                    connectionState = connectionState,
                    riskScore = riskScore,
                    onStartSession = { requestBleAction(BlePermissionAction.StartSession) },
                    onStopSession = { viewModel.stopSession() },
                    onManualConnectTap = {
                        requestBleAction(BlePermissionAction.Connect)
                        Toast.makeText(context, "Scanning for Ring...", Toast.LENGTH_SHORT).show()
                    },
                    onForceSyncTap = {
                        requestBleAction(BlePermissionAction.Connect)
                        Toast.makeText(context, "Scanning for Ring...", Toast.LENGTH_SHORT).show()
                    },
                    onDownloadReport = { ctx: android.content.Context ->
                        coroutineScope.launch {
                            val success = withContext(Dispatchers.IO) {
                                viewModel.generateSessionSummary(ctx)
                            }
                            if (success) {
                                Toast.makeText(ctx, "Report saved to Downloads", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, "Failed to generate report", Toast.LENGTH_SHORT).show()
                            }
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
}
