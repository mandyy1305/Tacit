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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.antiwispr.AppLog
import com.example.antiwispr.ProjectionService
import com.example.antiwispr.Toggles
import com.example.antiwispr.Transcripts
import com.example.antiwispr.cloud.CloudAuth
import kotlinx.coroutines.launch
import com.example.antiwispr.ui.ask.AskScreen
import com.example.antiwispr.ui.components.InkDivider
import com.example.antiwispr.ui.components.TacitIcons
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
    val turnOff: () -> Unit,
    val signIn: () -> Unit,
)

/** The three top-level tabs shown in the bottom navigation bar. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", TacitIcons.Home),
    Library("library", "Library", TacitIcons.Library),
    Ask("ask", "Ask", TacitIcons.Ask),
}

private val TAB_ROUTES = Tab.entries.map { it.route }.toSet()

private const val TAB_ANIM_MS = 300

/** Left-to-right position of a tab (for choosing slide direction); -1 for non-tab routes. */
private fun tabIndex(route: String?): Int = when (route) {
    Tab.Home.route -> 0
    Tab.Library.route -> 1
    Tab.Ask.route -> 2
    else -> -1
}

/**
 * Directional slide for tab-to-tab moves, matching the bottom bar's left-to-right order: going to
 * a tab further right slides content leftward, and vice-versa. Returns null when either end isn't a
 * tab (so detail push/pop keeps its own drill-down transition).
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnter(): EnterTransition? {
    val from = tabIndex(initialState.destination.route)
    val to = tabIndex(targetState.destination.route)
    if (from < 0 || to < 0) return null
    val dir = if (to >= from) AnimatedContentTransitionScope.SlideDirection.Left
    else AnimatedContentTransitionScope.SlideDirection.Right
    return slideIntoContainer(dir, tween(TAB_ANIM_MS, easing = FastOutSlowInEasing)) + fadeIn(tween(220))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExit(): ExitTransition? {
    val from = tabIndex(initialState.destination.route)
    val to = tabIndex(targetState.destination.route)
    if (from < 0 || to < 0) return null
    val dir = if (to >= from) AnimatedContentTransitionScope.SlideDirection.Left
    else AnimatedContentTransitionScope.SlideDirection.Right
    return slideOutOfContainer(dir, tween(TAB_ANIM_MS, easing = FastOutSlowInEasing)) + fadeOut(tween(220))
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            turnOff = { vm.setTacitEnabled(false) },
            signIn = {
                scope.launch {
                    CloudAuth.signIn(context)
                        .onSuccess { vm.onSignedIn() }
                        .onFailure { AppLog.w("[onboarding] sign-in failed: ${it.message}") }
                }
            },
        )
    }

    // Re-check grants when the user returns from Settings deep-links (or anywhere else).
    LifecycleResumeEffect(Unit) {
        vm.onResumed()
        onPauseOrDispose { }
    }

    // Switch top-level tabs: single-top, and save/restore each tab's own back stack + scroll.
    val selectTab: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
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

    // Launcher shortcut (Search / Ask / Library) → jump to the destination.
    val pendingDest = vm.pendingDest
    LaunchedEffect(pendingDest) {
        when (pendingDest) {
            "search" -> nav.navigate("search")
            "ask" -> selectTab("ask")
            "library" -> selectTab("library")
        }
        if (pendingDest != null) vm.pendingDest = null
    }

    val start = remember {
        if (Toggles.onboardingDone || vm.setup.value.setupComplete) "home" else "onboarding"
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The tab screens own their status-bar/side insets; the bar owns the bottom. Keep this
        // outer Scaffold inset-neutral so nothing is double-counted.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (showBottomBar) TacitBottomBar(currentRoute, selectTab) },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Mark the bar's space as consumed so a tab's imePadding() lifts the content by
                // (ime − bar), not (ime + bar) — otherwise the keyboard leaves a bar-sized gap.
                .consumeWindowInsets(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            // Tabs slide horizontally in bottom-bar order (tabEnter/tabExit); detail screens keep
            // the drill-down slide.
            enterTransition = {
                tabEnter() ?: (fadeIn(tween(240)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 10 })
            },
            exitTransition = {
                tabExit() ?: fadeOut(tween(120))
            },
            popEnterTransition = {
                tabEnter() ?: (fadeIn(tween(240)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 10 })
            },
            popExitTransition = {
                tabExit() ?: (fadeOut(tween(120)) + slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 10 })
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
                    onOpenLibrary = {
                        // "View all" always lands on newest-transcribed-first.
                        vm.librarySortField = LibrarySortField.TranscribedTime
                        vm.librarySortAsc = false
                        selectTab("library")
                    },
                    onOpenTranscript = { vm.selectedTranscript = it; nav.navigate("transcript") },
                    onFinishSetup = { nav.navigate("onboarding") },
                )
            }
            composable("library") {
                LibraryScreen(
                    library = history,
                    chainKeys = chainKeys,
                    sortField = vm.librarySortField,
                    sortAscending = vm.librarySortAsc,
                    onSortField = { vm.librarySortField = it },
                    onToggleSortDir = { vm.librarySortAsc = !vm.librarySortAsc },
                    onOpen = { vm.selectedTranscript = it; nav.navigate("transcript") },
                    onOpenSearch = { nav.navigate("search") },
                    onDelete = { vm.softDelete(it) },
                    onUndoDelete = { vm.undoDelete() },
                    onCommitDelete = { vm.commitPendingDeletes() },
                )
            }
            composable("ask") {
                AskScreen(
                    messages = vm.askMessages,
                    busy = vm.askBusy,
                    entered = vm.askEntered,
                    chainKeys = chainKeys,
                    onSend = { vm.ask(it) },
                    onClear = { vm.clearAsk() },
                    onOpen = { vm.selectedTranscript = it; nav.navigate("transcript") },
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
}

/**
 * Compact, Swiggy-style bottom bar: a hairline, then a short row of icon+label items. The active
 * tab is simply tinted (no chunky pill), which keeps the bar low. The bar owns the gesture-nav
 * inset at its bottom; AppRoot consumes that space so a tab's imePadding stays flush.
 */
@Composable
private fun TacitBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding(),
    ) {
        InkDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                val tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { if (!selected) onSelect(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}
