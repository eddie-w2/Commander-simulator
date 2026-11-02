package com.eddiew.commandersim

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.ui.components.CardInfoDialog
import com.eddiew.commandersim.ui.components.LocalCardInfo
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eddiew.commandersim.ui.components.gridBackground
import com.eddiew.commandersim.ui.decks.DeckDetailScreen
import com.eddiew.commandersim.ui.decks.DecksScreen
import com.eddiew.commandersim.ui.decks.ImportDeckScreen
import com.eddiew.commandersim.ui.decks.MoxfieldProfileScreen
import com.eddiew.commandersim.ui.decks.PreconsScreen
import com.eddiew.commandersim.ui.decks.VariantEditorScreen
import com.eddiew.commandersim.ui.results.CardDetailScreen
import com.eddiew.commandersim.ui.results.Metric
import com.eddiew.commandersim.ui.results.MetricDetailScreen
import com.eddiew.commandersim.ui.results.MetricNav
import com.eddiew.commandersim.ui.collection.CollectionScreen
import com.eddiew.commandersim.ui.settings.SettingsScreen
import com.eddiew.commandersim.ui.results.DeckReportScreen
import com.eddiew.commandersim.ui.results.ResultsScreen
import com.eddiew.commandersim.ui.simulate.SimulateScreen
import com.eddiew.commandersim.ui.theme.CommanderSimTheme
import com.eddiew.commandersim.ui.theme.Hud

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CommanderSimTheme { App() }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("decks", "Decks", Icons.AutoMirrored.Filled.List),
    Tab("simulate", "Simulate", Icons.Filled.PlayArrow),
    Tab("results", "Results", Icons.Filled.Star),
    Tab("collection", "Collection", Icons.Filled.Favorite),
)

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var cardPopup by remember { mutableStateOf<String?>(null) }
    val packOffer by vm.packOffer.collectAsStateWithLifecycle()
    val packDismissed by vm.packPromptDismissed.collectAsStateWithLifecycle()
    val appUpdate by vm.update.collectAsStateWithLifecycle()
    val updateDismissed by vm.updatePromptDismissed.collectAsStateWithLifecycle()
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    val sim by vm.sim.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Ask once for notification permission when a run starts, so its progress shows while the app is in the background.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {}
    val isRunning = sim is com.eddiew.commandersim.SimState.Running
    androidx.compose.runtime.LaunchedEffect(isRunning) {
        if (isRunning && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) runCatching { notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalCardInfo provides { name -> cardPopup = name }) {
    Scaffold(
        containerColor = Hud.Bg,
        bottomBar = { if (tabs.any { it.route == route }) BottomBar(nav, route) },
    ) { padding ->
        Box(Modifier.fillMaxSize().gridBackground().padding(padding)) {
            NavHost(nav, startDestination = "decks") {
                composable("decks") {
                    DecksScreen(vm, onImport = { nav.navigate("import") }, onOpen = { nav.navigate("deck/$it") }, onSettings = { nav.navigate("settings") }, onPrecons = { nav.navigate("precons") },
                        onMoxfield = { nav.navigate("moxfield") })
                }
                composable("import") {
                    ImportDeckScreen(vm, onDone = { nav.popBackStack() }, onProfile = { link ->
                        nav.navigate("moxfield?u=${Uri.encode(link)}") { popUpTo("import") { inclusive = true } }
                    })
                }
                composable("moxfield?u={u}", arguments = listOf(navArgument("u") { nullable = true; defaultValue = null })) { back ->
                    MoxfieldProfileScreen(vm, back.arguments?.getString("u"), onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
                }
                composable("deck/{id}") { back ->
                    DeckDetailScreen(
                        vm, back.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                        onVariant = { nav.navigate("variant/$it") },
                        onRunAb = { switchTab(nav, "simulate") },
                        onOpenRun = { runId ->
                            val deckId = back.arguments?.getString("id") ?: ""
                            scope.launch { if (vm.openRun(runId)) nav.navigate("report/$deckId") }
                        },
                    )
                }
                composable("variant/{id}") { back ->
                    VariantEditorScreen(
                        vm, back.arguments?.getString("id") ?: "",
                        onCancel = { nav.popBackStack() },
                        onSaved = { switchTab(nav, "simulate") },
                    )
                }
                composable("simulate") {
                    SimulateScreen(vm, onFinished = { switchTab(nav, "results") })
                }
                composable("results") {
                    ResultsScreen(
                        vm, onOpenDeck = { nav.navigate("report/$it") }, onSimulate = { switchTab(nav, "simulate") },
                        onGames = { nav.navigate("metric/_/${Metric.GAMES.name}") },
                    )
                }
                composable("report/{deckId}") { back ->
                    val deckId = back.arguments?.getString("deckId") ?: ""
                    DeckReportScreen(
                        vm, deckId,
                        onBack = { nav.popBackStack() },
                        onCard = { name -> nav.navigate("card/$deckId/${Uri.encode(name)}") },
                        onMetric = { m -> nav.navigate("metric/$deckId/${m.name}") },
                    )
                }
                composable("metric/{deckId}/{metric}") { back ->
                    val deckId = back.arguments?.getString("deckId") ?: ""
                    val metric = runCatching { Metric.valueOf(back.arguments?.getString("metric") ?: "") }.getOrDefault(Metric.WIN_RATE)
                    MetricDetailScreen(
                        vm, deckId, metric,
                        onBack = { nav.popBackStack() },
                        nav = MetricNav(
                            metric = { m -> nav.navigate("metric/$deckId/${m.name}") },
                            card = { name -> nav.navigate("card/$deckId/${Uri.encode(name)}") },
                            deck = { id -> nav.navigate(if (metric == Metric.GAMES) "report/$id" else "metric/$id/${metric.name}") },
                        ),
                    )
                }
                composable("collection") {
                    CollectionScreen(vm, onOpenDeck = { nav.navigate("deck/$it") })
                }
                composable("precons") {
                    PreconsScreen(vm, onBack = { nav.popBackStack() }, onOpenDeck = { nav.navigate("deck/$it") })
                }
                composable("settings") {
                    SettingsScreen(vm, onBack = { nav.popBackStack() })
                }
                composable("card/{deckId}/{name}") { back ->
                    CardDetailScreen(
                        vm,
                        back.arguments?.getString("deckId") ?: "",
                        Uri.decode(back.arguments?.getString("name") ?: ""),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
    }
    cardPopup?.let { name -> CardInfoDialog(name, vm::cardInfo) { cardPopup = null } }
    val rel = appUpdate
    if (rel != null && !updateDismissed) {
        AlertDialog(
            onDismissRequest = { if (updateProgress == null) vm.dismissUpdatePrompt() },
            containerColor = Hud.SurfaceHi,
            title = { Text("App update available", color = Hud.Text) },
            text = {
                Column {
                    Text("Version ${rel.version} is out (you have ${vm.updater.currentVersion}). Download and install it now?", color = Hud.Muted)
                    updateProgress?.let {
                        Spacer(Modifier.height(10.dp))
                        com.eddiew.commandersim.ui.components.NeonBar(it, color = Hud.Cyan)
                        Text("Downloading… ${(it * 100).toInt()}%", color = Hud.Dim, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = updateProgress == null, onClick = {
                    updateProgress = 0f
                    scope.launch {
                        runCatching {
                            val f = vm.updater.download(rel) { updateProgress = it }
                            if (vm.updater.canInstall()) vm.updater.install(f) else vm.updater.openInstallPermissionSettings()
                        }
                        updateProgress = null
                        vm.dismissUpdatePrompt()
                    }
                }) { Text("UPDATE", color = Hud.Cyan) }
            },
            dismissButton = { TextButton(onClick = { vm.dismissUpdatePrompt() }, enabled = updateProgress == null) { Text("LATER", color = Hud.Muted) } },
        )
    }
    val offer = packOffer
    // One prompt at a time: the app update comes first.
    if (offer != null && !packDismissed && (rel == null || updateDismissed)) {
        AlertDialog(
            onDismissRequest = { vm.dismissPackPrompt() },
            containerColor = Hud.SurfaceHi,
            title = { Text("New Forge engine available", color = Hud.Text) },
            text = {
                Text(
                    "${offer.manifest.label} (${"%.0f".format(offer.manifest.size / 1e6)} MB). It brings Forge's latest " +
                        "card support and rules fixes, and doesn't need an app update. Download it now?",
                    color = Hud.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { vm.downloadForgePack() } }) { Text("DOWNLOAD", color = Hud.Cyan) }
            },
            dismissButton = { TextButton(onClick = { vm.dismissPackPrompt() }) { Text("NOT NOW", color = Hud.Muted) } },
        )
    }
}

private fun switchTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    NavigationBar(containerColor = Hud.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route,
                onClick = { switchTab(nav, tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label.uppercase(), style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Hud.Cyan,
                    selectedTextColor = Hud.Cyan,
                    indicatorColor = Hud.Electric.copy(alpha = 0.16f),
                    unselectedIconColor = Hud.Muted,
                    unselectedTextColor = Hud.Muted,
                ),
            )
        }
    }
}
