package io.github.cobylinweiqi.fakeloc

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.cobylinweiqi.fakeloc.ui.FakeLocTheme
import io.github.cobylinweiqi.fakeloc.ui.HomeScreen
import io.github.cobylinweiqi.fakeloc.ui.MainViewModel
import io.github.cobylinweiqi.fakeloc.ui.MapPickerScreen
import io.github.cobylinweiqi.fakeloc.ui.SettingsScreen
import io.github.cobylinweiqi.fakeloc.ui.TargetAppsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FakeLocTheme {
                FakeLocApp()
            }
        }
    }
}

/** The four destinations. A nav graph would be pure overhead: no deep links. */
private enum class Screen { HOME, MAP, TARGETS, SETTINGS }

/**
 * Shell around the three screens.
 *
 * A single [Scaffold] hosts the snackbar, and the screen switch is an enum rather
 * than a navigation graph.
 *
 * The current screen is persisted as the enum's *name*. `rememberSaveable`
 * round-trips through a `Bundle`, and a String survives that on every API level
 * without a hand-written `Saver` — worth the two extra lines for a value that
 * only ever changes on a button press.
 */
@Composable
private fun FakeLocApp(viewModel: MainViewModel = viewModel()) {
    val config by viewModel.config.collectAsState()
    val serviceBound by viewModel.serviceBound.collectAsState()
    val targetApps by viewModel.targetApps.collectAsState()

    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val screen = Screen.valueOf(screenName)

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Drain the view model's one-shot messages into the snackbar.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    // The remote preferences only become readable once LSPosed's service binds,
    // which can happen after the first frame — pull the real config in then.
    LaunchedEffect(serviceBound) {
        if (serviceBound) viewModel.reload()
    }

    // The target list is only needed when the picker opens; loading it up front
    // would stall startup on a device with a lot of packages.
    LaunchedEffect(screenName) {
        if (screen == Screen.TARGETS) viewModel.loadTargetApps()
    }

    // System back leaves any sub-screen; on home it falls through so the app can
    // be backgrounded normally.
    BackHandler(enabled = screen != Screen.HOME) { screenName = Screen.HOME.name }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Either outcome routes through `useRealLocation`, which reports the
        // denial itself — one place owns that message.
        if (grants.values.any { it }) viewModel.useRealLocation() else viewModel.useRealLocation()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            when (screen) {
                Screen.MAP -> MapPickerScreen(
                    initialLatitude = config.latitude,
                    initialLongitude = config.longitude,
                    config = config,
                    onPicked = { latitude, longitude, address ->
                        viewModel.applyMapPick(latitude, longitude, address)
                        screenName = Screen.HOME.name
                    },
                    onOpenSettings = { screenName = Screen.SETTINGS.name },
                    onBack = { screenName = Screen.HOME.name },
                )

                Screen.TARGETS -> TargetAppsScreen(
                    apps = targetApps,
                    selected = config.targetPackages,
                    onToggle = viewModel::toggleTargetApp,
                    onBack = { screenName = Screen.HOME.name },
                )

                Screen.SETTINGS -> SettingsScreen(
                    config = config,
                    onUpdate = viewModel::update,
                    onBack = { screenName = Screen.HOME.name },
                )

                Screen.HOME -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    HomeScreen(
                        config = config,
                        serviceBound = serviceBound,
                        onUpdate = viewModel::update,
                        onTogglePlaying = viewModel::togglePlaying,
                        onUseRealGps = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onPaste = viewModel::applyClipboard,
                        onOpenTargets = { screenName = Screen.TARGETS.name },
                        onOpenMap = { screenName = Screen.MAP.name },
                        onOpenSettings = { screenName = Screen.SETTINGS.name },
                        onSaveCoordinates = viewModel::commitCoordinates,
                    )
                }
            }
        }
    }
}
