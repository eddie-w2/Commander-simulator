package com.eddiew.commandersim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eddiew.commandersim.ui.components.gridBackground
import com.eddiew.commandersim.ui.decks.DeckDetailScreen
import com.eddiew.commandersim.ui.decks.DecksScreen
import com.eddiew.commandersim.ui.decks.ImportDeckScreen
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
)

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        containerColor = Hud.Bg,
        bottomBar = { if (tabs.any { it.route == route }) BottomBar(nav, route) },
    ) { padding ->
        Box(Modifier.fillMaxSize().gridBackground().padding(padding)) {
            NavHost(nav, startDestination = "decks") {
                composable("decks") {
                    DecksScreen(vm, onImport = { nav.navigate("import") }, onOpen = { nav.navigate("deck/$it") })
                }
                composable("import") {
                    ImportDeckScreen(vm, onDone = { nav.popBackStack() })
                }
                composable("deck/{id}") { back ->
                    DeckDetailScreen(vm, back.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
                }
                composable("simulate") {
                    SimulateScreen(vm, onFinished = { switchTab(nav, "results") })
                }
                composable("results") {
                    ResultsScreen(vm, onOpenDeck = { nav.navigate("report/$it") }, onSimulate = { switchTab(nav, "simulate") })
                }
                composable("report/{deckId}") { back ->
                    DeckReportScreen(vm, back.arguments?.getString("deckId") ?: "", onBack = { nav.popBackStack() })
                }
            }
        }
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
