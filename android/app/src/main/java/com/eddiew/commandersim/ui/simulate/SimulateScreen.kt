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
    val totalGames = if (ab) settings.gamesPerDeck * 2 else ceil(poolDecks.size * settings.gamesPerDeck / podSize.toDouble()).toInt()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ab) "Games per version" else "Games per deck", style = MaterialTheme.typography.bodyMedium,
                        color = Hud.Text, modifier = Modifier.weight(1f))
                    Text("%,d".format(settings.gamesPerDeck), fontFamily = Mono, fontWeight = FontWeight.Bold, color = Hud.Cyan)
                }
                Slider(
                    value = settings.gamesPerDeck.toFloat(),
                    onValueChange = { vm.setGames(((it / 100f).roundToInt() * 100).coerceIn(100, 5000)) },
                    valueRange = 100f..5000f,
                    enabled = !running,
                    colors = SliderDefaults.colors(thumbColor = Hud.Cyan, activeTrackColor = Hud.Electric, inactiveTrackColor = Hud.Grid),
                )
                Text(
                    "±${ciWidth(settings.gamesPerDeck)} points of win-rate uncertainty (95%) per deck." +
                        if (ab) " Paired games make A/B differences much more precise than that." else "",
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
                if (running) "Running…" else if (ab) "▶ Run A/B test" else "▶ Run simulation",
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
                Text("Running pods…", style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                Spacer(Modifier.height(8.dp))
                HudOutlineButton("Cancel", onCancel, color = Hud.Negative)
            }
        }
    }
}
