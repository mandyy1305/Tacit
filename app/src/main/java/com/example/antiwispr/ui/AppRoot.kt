package com.example.antiwispr.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.antiwispr.AppLog
import com.example.antiwispr.ProjectionService
import com.example.antiwispr.Toggles
import com.example.antiwispr.Transcripts
import com.example.antiwispr.ui.home.HomeScreen
import com.example.antiwispr.ui.library.LibraryScreen
import com.example.antiwispr.ui.onboarding.OnboardingScreen
import com.example.antiwispr.ui.search.SearchScreen
import com.example.antiwispr.ui.settings.LogScreen
import com.example.antiwispr.ui.settings.SettingsScreen
import com.example.antiwispr.ui.transcript.TranscriptDetailScreen

/** One-tap intents for every setup requirement; screens stay dumb. */
class SetupActions(
    val requestMic: () -> Unit,
    val requestOverlay: () -> Unit,
    val requestAllFiles: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
    val downloadModel: () -> Unit,
    val downloadLlm: () -> Unit,
    val buildIndex: () -> Unit,
    val startSession: () -> Unit,
    val stopSession: () -> Unit,
    val turnOn: () -> Unit,
)

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val setup by vm.setup.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val chainKeys by vm.chainKeys.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppLog.i("RECORD_AUDIO = ${if (granted) "granted" else "DENIED (capture cannot build AudioRecord)"}")
        vm.refresh()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppLog.i("POST_NOTIFICATIONS = ${if (granted) "granted" else "denied (FGS runs; notification hidden)"}")
        vm.refresh()
    }
    val settingsReturnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.onProjectionResult(res.resultCode, res.data)
    }

    val actions = remember {
        SetupActions(
            requestMic = {
                if (vm.setup.value.mic) AppLog.i("RECORD_AUDIO already granted.")
                else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            requestOverlay = {
                if (Settings.canDrawOverlays(context)) AppLog.i("overlay already granted.")
                else settingsReturnLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            },
            requestAllFiles = {
                if (Environment.isExternalStorageManager()) AppLog.i("all-files access already granted.")
                else try {
                    settingsReturnLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } catch (_: Exception) {
                    settingsReturnLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            },
            requestNotifications = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                    AppLog.i("POST_NOTIFICATIONS not needed below API 33.")
                else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            openAccessibility = {
                AppLog.i("opening Accessibility settings — enable \"TACIT\".")
                settingsReturnLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            downloadModel = { vm.downloadModel() },
            downloadLlm = { vm.downloadLlm() },
            buildIndex = { vm.buildIndex() },
            startSession = {
                when {
                    !vm.setup.value.mic -> {
                        AppLog.i("RECORD_AUDIO required first (AudioRecord can't build without it). Requesting…")
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    ProjectionService.sessionActive ->
                        AppLog.i("projection session already active — reusing it.")
                    else -> {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        if (mpm == null) AppLog.i("MediaProjectionManager unavailable.")
                        else {
                            AppLog.i("requesting projection consent (system dialog) — once per session.")
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    }
                }
            },
            stopSession = { vm.stopSession() },
            turnOn = { vm.setTacitEnabled(true) },
        )
    }

    // Re-check grants when the user returns from Settings deep-links (or anywhere else).
    LifecycleResumeEffect(Unit) {
        vm.onResumed()
        onPauseOrDispose { }
    }

    // Deep link from a "transcript ready" notification (MainActivity set the key): resolve the
    // record and open its detail screen, then clear the request so it fires once.
    val pendingKey = vm.pendingOpenKey
    LaunchedEffect(pendingKey) {
        val k = pendingKey ?: return@LaunchedEffect
        Transcripts.get(context).byKey(k)?.let {
            vm.selectedTranscript = it
            nav.navigate("transcript")
        }
        vm.pendingOpenKey = null
    }

    val start = remember {
        if (Toggles.onboardingDone || vm.setup.value.setupComplete) "home" else "onboarding"
    }

    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = {
            fadeIn(tween(240)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 10 }
        },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = {
            fadeIn(tween(240)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 10 }
        },
        popExitTransition = {
            fadeOut(tween(120)) + slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 10 }
        },
    ) {
        composable("onboarding") {
            OnboardingScreen(
                setup = setup,
                actions = actions,
                onFinished = {
                    vm.markOnboardingDone()
                    nav.navigate("home") { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable("home") {
            HomeScreen(
                setup = setup,
                recents = recents,
                chainKeys = chainKeys,
                actions = actions,
                onOpenSettings = { nav.navigate("settings") },
                onOpenSearch = { nav.navigate("search") },
                onOpenLibrary = { nav.navigate("library") },
                onOpenTranscript = { vm.selectedTranscript = it; nav.navigate("transcript") },
                onFinishSetup = { nav.navigate("onboarding") },
            )
        }
        composable("library") {
            LibraryScreen(
                library = history,
                chainKeys = chainKeys,
                onBack = { nav.popBackStack() },
                onOpen = { vm.selectedTranscript = it; nav.navigate("transcript") },
                onOpenSearch = { nav.navigate("search") },
            )
        }
        composable("search") {
            SearchScreen(
                query = vm.searchQuery,
                onQueryChange = { vm.searchQuery = it },
                chainKeys = chainKeys,
                onBack = { nav.popBackStack() },
                onOpen = { vm.selectedTranscript = it; nav.navigate("transcript") },
            )
        }
        composable("transcript") {
            TranscriptDetailScreen(
                transcript = vm.selectedTranscript,
                onBack = { nav.popBackStack() },
                onDelete = { vm.deleteTranscript(it) }, // screen pops itself via the null guard
                onRetranscribe = { vm.retranscribe(it) },
                retranscribing = vm.retranscribing,
                onOpenSettings = { nav.navigate("settings") },
                onOpenNote = { vm.selectedTranscript = it }, // swap the reader to a linked chain note in place
                onChainChanged = { vm.refresh() },           // detach/re-attach → refresh badges
            )
        }
        composable("settings") {
            SettingsScreen(
                setup = setup,
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenLog = { nav.navigate("log") },
            )
        }
        composable("log") {
            LogScreen(onBack = { nav.popBackStack() })
        }
    }
}
