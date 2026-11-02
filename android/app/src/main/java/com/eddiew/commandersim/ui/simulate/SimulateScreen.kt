package com.eddiew.commandersim.ui.simulate

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.SimEngine
import com.eddiew.commandersim.SimMode
import com.eddiew.commandersim.SimState
import com.eddiew.commandersim.data.model.Deck
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
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun SimulateScreen(vm: AppViewModel, onFinished: () -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sim by vm.sim.collectAsStateWithLifecycle()
    val running = sim is SimState.Running
    val ab = settings.mode == SimMode.AB

    val a = decks.firstOrNull { it.id == settings.aId }
    val b = decks.firstOrNull { it.id == settings.bId }
    val field = decks.filter { it.id in settings.pool && it.id != a?.id && it.id != b?.id }
    val poolDecks = decks.filter { it.id in settings.pool }
    val podSize = minOf(4, if (ab) field.size + 1 else poolDecks.size).coerceAtLeast(2)
    val forge = settings.engine == SimEngine.FORGE
    val games = settings.effectiveGames
    val totalGames = if (ab) games * 2 else ceil(poolDecks.size * games / podSize.toDouble()).toInt()
    val ready = if (ab) a != null && b != null && a.id != b.id && field.isNotEmpty() else poolDecks.size >= 2

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Simulation", if (ab) "A/B test · two versions on identical games" else "Deck pool · every deck plays every other")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudChip("Deck pool", !ab, { vm.setMode(SimMode.POOL) }, Modifier.weight(1f))
                HudChip("A/B test", ab, { vm.setMode(SimMode.AB) }, Modifier.weight(1f), color = Hud.Cyan)
            }

            TileRow(
                Triple(if (ab) "Field" else "Decks", "${if (ab) field.size else poolDecks.size}", null),
                Triple("Pod size", "$podSize", "players"),
                Triple("Total games", "%,d".format(totalGames), null),
            )

            if (ab) {
                HudPanel(title = "A · original", accent = Hud.Electric) {
                    DeckPicker(decks, settings.aId, exclude = settings.bId) { vm.setA(it) }
                }
                HudPanel(title = "B · variant", accent = Hud.Cyan) {
                    DeckPicker(decks.sortedByDescending { it.parentId == settings.aId }, settings.bId, exclude = settings.aId) { vm.setB(it) }
                }
                HudPanel(title = "Field · decks A and B play against") {
                    PoolList(decks.filter { it.id != settings.aId && it.id != settings.bId }, settings.pool) { vm.togglePool(it) }
                    if (field.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Notice("Pick at least one other deck for A and B to play against.")
                    }
                }
                Text(
                    "Each game is played twice with the same opponents, seat and shuffle: once with A and once with B. " +
                        "Differences come from the swapped cards, not luck.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Dim,
                )
            } else {
                HudPanel(title = "Pool · tap to include") {
                    PoolList(decks, settings.pool) { vm.togglePool(it) }
                    if (poolDecks.size < 2) {
                        Spacer(Modifier.height(6.dp))
                        Notice("Pick at least two decks.")
                    }
                }
            }

            HudPanel(title = "Run settings") {
                Text("ENGINE", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudChip("Forge engine", forge, { if (vm.forgeAvailable) vm.setEngine(SimEngine.FORGE) }, Modifier.weight(1f),
                        color = if (vm.forgeAvailable) Hud.Cyan else Hud.Dim)
                    HudChip("Quick sample", !forge, { vm.setEngine(SimEngine.SAMPLE) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        forge -> "Real Magic rules and Forge's AI, run on this phone. Expect roughly 10–60 seconds per 4-player game, " +
                            "so start small. You can stop at any time and keep the results so far."
                        vm.forgeAvailable -> "A simplified model, for trying out the dashboards quickly. Its results don't reflect your real decks."
                        else -> "This build doesn't include the Forge engine; only the simplified sample model is available."
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (forge) Hud.Ice else Hud.Dim,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ab) "Games per version" else "Games per deck", style = MaterialTheme.typography.bodyMedium,
                        color = Hud.Text, modifier = Modifier.weight(1f))
                    Text("%,d".format(games), fontFamily = Mono, fontWeight = FontWeight.Bold, color = Hud.Cyan)
                }
                val range = if (forge) 4f..200f else 100f..5000f
                val stepSize = if (forge) 4 else 100
                Slider(
                    value = games.toFloat().coerceIn(range),
                    onValueChange = { vm.setGames(((it / stepSize).roundToInt() * stepSize).coerceIn(range.start.toInt(), range.endInclusive.toInt())) },
                    valueRange = range,
                    enabled = !running,
                    colors = SliderDefaults.colors(thumbColor = Hud.Cyan, activeTrackColor = Hud.Electric, inactiveTrackColor = Hud.Grid),
                )
                Text(
                    "±${ciWidth(games)} points of win-rate uncertainty (95%) per deck." +
                        if (ab) " Paired games make A/B differences more precise than that." else "",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rotate seats", style = MaterialTheme.typography.bodyMedium, color = Hud.Text)
                        Text("Shuffle turn order every game so going first evens out.", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                    }
                    Switch(
                        checked = settings.seatRotation, onCheckedChange = vm::setSeatRotation, enabled = !running,
                        colors = SwitchDefaults.colors(checkedThumbColor = Hud.Bg, checkedTrackColor = Hud.Electric, uncheckedTrackColor = Hud.Grid),
                    )
                }
            }

            when (val s = sim) {
                is SimState.Running -> RunningPanel(s, totalGames, onStop = { vm.stopEarly() }, onCancel = { vm.cancelSimulation() })
                is SimState.Failed -> Notice(s.message, color = Hud.Negative)
                else -> Unit
            }

            HudButton(
                if (running) "Running…" else if (ab) "▶ Run A/B test" + (if (forge) " · Forge" else "") else "▶ Run simulation" + (if (forge) " · Forge" else ""),
                onClick = { vm.runSimulation(onFinished) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                enabled = !running && ready,
            )
        }
    }
}

private fun ciWidth(n: Int): String = "%.1f".format(1.96 * kotlin.math.sqrt(0.25 * 0.75 / n) * 100)

@Composable
private fun PoolList(decks: List<Deck>, pool: Set<String>, onToggle: (String) -> Unit) {
    if (decks.isEmpty()) {
        Text("Import decks on the Decks tab.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        return
    }
    Column {
        decks.forEach { d ->
            val on = d.id in pool
            DeckLine(d, if (on) "◆" else "◇", if (on) Hud.Cyan else Hud.Dim) { onToggle(d.id) }
        }
    }
}

@Composable
private fun DeckPicker(decks: List<Deck>, selected: String?, exclude: String?, onPick: (String?) -> Unit) {
    val options = decks.filter { it.id != exclude }
    if (options.isEmpty()) {
        Text("Import decks on the Decks tab.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        return
    }
    Column {
        options.forEach { d ->
            val on = d.id == selected
            DeckLine(d, if (on) "●" else "○", if (on) Hud.Cyan else Hud.Dim) { onPick(if (on) null else d.id) }
        }
    }
}

@Composable
private fun DeckLine(d: Deck, mark: String, markColor: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = markColor, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.parentId != null) Text("VARIANT", style = MaterialTheme.typography.labelSmall, color = Hud.Electric)
        }
        ColorPips(d.colorIdentity)
    }
}

@Composable
private fun RunningPanel(state: SimState.Running, total: Int, onStop: () -> Unit, onCancel: () -> Unit) {
    val progress = state.progress
    val anim by animateFloatAsState(progress, tween(300), label = "p")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val elapsed = (System.currentTimeMillis() - state.startedAt) / 1000
    val done = (progress * total).roundToInt()
    val eta = if (done > 0) (elapsed * (total - done) / done) else null
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
                Text("%,d / %,d games".format(done, total), fontFamily = Mono, color = Hud.Ice)
                Text(state.status.ifBlank { "Running pods…" }, style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                Text(
                    "Elapsed ${fmt(elapsed)}" + (eta?.let { " · about ${fmt(it)} left" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("Stop & show results", onStop, Modifier.weight(1f), enabled = done > 0)
            HudOutlineButton("Cancel", onCancel, color = Hud.Negative)
        }
    }
}

private fun fmt(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
