package com.eddiew.commandersim.ui.simulate

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.SimState
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.data.model.DeckRole
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import kotlin.math.roundToInt

@Composable
fun SimulateScreen(vm: AppViewModel, onFinished: () -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.sim.collectAsStateWithLifecycle()

    val candidates = decks.filter { settings.roles[it.id] == DeckRole.CANDIDATE }
    val opponents = decks.filter { settings.roles[it.id] == DeckRole.OPPONENT }
    val totalGames = candidates.size * settings.gamesPerCandidate
    val running = sim is SimState.Running

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Simulation", "4-player Commander pods · candidates vs your playgroup")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileRow(
                Triple("Candidates", "${candidates.size}", null),
                Triple("Opponents", "${opponents.size}", null),
                Triple("Total games", "%,d".format(totalGames), null),
            )

            HudPanel(title = "Candidates · decks you're choosing between", accent = Hud.Cyan) {
                DeckList(candidates, "Mark decks as CANDIDATE on the Decks tab.")
            }
            HudPanel(title = "Opponents · your playgroup") {
                DeckList(opponents, "Mark your playgroup's decks as OPPONENT on the Decks tab.")
                if (opponents.size in 1..2) {
                    Spacer(Modifier.height(8.dp))
                    Notice("With ${opponents.size} opponent deck${if (opponents.size > 1) "s" else ""}, pods have ${opponents.size + 1} players instead of 4.")
                }
            }

            HudPanel(title = "Run settings") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Games per candidate", style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f))
                    Text("%,d".format(settings.gamesPerCandidate), fontFamily = Mono, fontWeight = FontWeight.Bold, color = Hud.Cyan)
                }
                Slider(
                    value = settings.gamesPerCandidate.toFloat(),
                    onValueChange = { vm.setGames((it / 100f).roundToInt() * 100) },
                    valueRange = 100f..3000f,
                    steps = 28,
                    enabled = !running,
                    colors = SliderDefaults.colors(
                        thumbColor = Hud.Cyan, activeTrackColor = Hud.Electric, inactiveTrackColor = Hud.Grid,
                        activeTickColor = Hud.Electric, inactiveTickColor = Hud.Outline,
                    ),
                )
                Text(
                    "±${ciWidth(settings.gamesPerCandidate)} points of win-rate uncertainty (95%) at this game count.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Seat rotation", style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                        Text("Every candidate plays from every seat, so turn order evens out.", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                    }
                    Switch(
                        checked = settings.seatRotation, onCheckedChange = vm::setSeatRotation, enabled = !running,
                        colors = SwitchDefaults.colors(checkedThumbColor = Hud.Bg, checkedTrackColor = Hud.Electric, uncheckedTrackColor = Hud.Grid),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("ENGINE", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("On-device sample", true, {})
                    HudChip("Forge server · soon", false, {}, color = Hud.Dim)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The sample engine is a simplified game model so you can try the app now. " +
                        "Real results will come from the Forge simulator once its server is running.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Dim,
                )
            }

            when (val s = sim) {
                is SimState.Running -> RunningPanel(s.progress, totalGames) { vm.cancelSimulation() }
                is SimState.Failed -> Notice(s.message, color = Hud.Negative)
                else -> Unit
            }

            HudButton(
                if (running) "Running…" else "▶ Run simulation",
                onClick = { vm.runSimulation(onFinished) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                enabled = !running && candidates.isNotEmpty() && opponents.isNotEmpty(),
            )
        }
    }
}

private fun ciWidth(n: Int): String = "%.1f".format(1.96 * kotlin.math.sqrt(0.25 * 0.75 / n) * 100)

@Composable
private fun DeckList(decks: List<Deck>, empty: String) {
    if (decks.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        decks.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ColorPips(d.colorIdentity)
            }
        }
    }
}

@Composable
private fun RunningPanel(progress: Float, total: Int, onCancel: () -> Unit) {
    val anim by animateFloatAsState(progress, tween(300), label = "p")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "a")
    HudPanel(title = "Simulating", accent = Hud.Cyan) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(96.dp)) {
                    val stroke = 6.dp.toPx()
                    val inset = stroke
                    val arc = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Hud.Grid, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(Hud.Electric.copy(alpha = 0.25f), -90f, 360f * anim, false, Offset(inset, inset), arc, style = Stroke(stroke * 2.2f))
                    drawArc(Brush.sweepGradient(listOf(Hud.ElectricDeep, Hud.Cyan)), -90f, 360f * anim, false, Offset(inset, inset), arc,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(Hud.Cyan, angle, 30f, false, Offset(inset * 2.6f, inset * 2.6f),
                        Size(size.width - inset * 5.2f, size.height - inset * 5.2f), style = Stroke(2.dp.toPx()))
                }
                Text("${(anim * 100).roundToInt()}%", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Hud.Text)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("%,d / %,d games".format((progress * total).roundToInt(), total), fontFamily = Mono, color = Hud.Ice)
                Text("Running pods with seat rotation…", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                Spacer(Modifier.height(8.dp))
                HudOutlineButton("Cancel", onCancel, color = Hud.Negative)
            }
        }
    }
}
