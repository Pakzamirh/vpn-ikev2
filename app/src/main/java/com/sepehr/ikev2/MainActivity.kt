package com.sepehr.ikev2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sepehr.ikev2.ui.VpnViewModel
import com.sepehr.ikev2.ui.components.OrbitalShieldLoader
import com.sepehr.ikev2.ui.screens.LoginScreen
import com.sepehr.ikev2.ui.screens.VpnHomeScreen
import com.sepehr.ikev2.ui.theme.ObsidianBg
import com.sepehr.ikev2.ui.theme.SepehrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SepehrTheme {
                    val context = LocalContext.current
                    val vm: VpnViewModel = viewModel()
                    val uiState by vm.uiState.collectAsStateWithLifecycle()

                    val vpnConsentLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        vm.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
                    }

                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { _ -> }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }

                    // Auto-refresh servers & sync real VPN tunnel status on every open/foreground
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_START) {
                                vm.refreshServers()
                                vm.syncVpnState()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    Crossfade(
                        targetState = Triple(uiState.isCheckingSession, uiState.isAuthenticated, Unit),
                        label = "rootNav"
                    ) { (checking, authed, _) ->
                        when {
                            checking -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(ObsidianBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    OrbitalShieldLoader()
                                }
                            }
                            !authed -> {
                                LoginScreen(
                                    isLoading = uiState.isLoggingIn,
                                    errorMessage = uiState.loginError,
                                    onLogin = vm::login
                                )
                            }
                            else -> {
                                VpnHomeScreen(
                                    uiState = uiState,
                                    onSearchChange = vm::updateSearchQuery,
                                    onRefresh = vm::refreshServers,
                                    onPingAll = vm::pingAllServers,
                                    onPingCountry = vm::pingCountry,
                                    onPickBest = {
                                        vm.selectBestReachableServer { intent ->
                                            vpnConsentLauncher.launch(intent)
                                        }
                                    },
                                    onSelectNode = { node ->
                                        vm.selectNode(node) { intent ->
                                            vpnConsentLauncher.launch(intent)
                                        }
                                    },
                                    onToggleConnection = {
                                        vm.toggleConnection { intent ->
                                            vpnConsentLauncher.launch(intent)
                                        }
                                    },
                                    onLogout = vm::logout
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
