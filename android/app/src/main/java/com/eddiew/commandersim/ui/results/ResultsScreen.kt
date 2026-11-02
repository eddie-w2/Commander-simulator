package com.eddiew.commandersim.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.SimulationReport
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.Heatmap
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.Sparkline
import com.eddiew.commandersim.ui.components.Tile
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.WinRateBar
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import java.text.DateFormat
import java.util.Date

@Composable
fun ResultsScreen(vm: AppViewModel, onOpenDeck: (String) -> Unit, onSimulate: () -> Unit, onGames: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val resumable by vm.resumable.collectAsStateWithLifecycle()
    val r = report
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (r?.ab != null) "A/B results" else "Results",
            r?.let { "${"%,d".format(it.meta.games)} games · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.createdAt)) },
        )
        if (runs.isNotEmpty()) {
            RunSelector(runs, r?.id, onSelect = vm::selectRun, onDelete = vm::deleteRun, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
        if (r != null && r.partial) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                Notice("Partial run: ${r.meta.games} of ${r.plannedGames} games." +
                    if (resumable?.id == r.id) " Resume it from the Simulate tab to play the rest." else "")
                if (resumable?.id == r.id) {
                    Spacer(Modifier.height(6.dp))
                    HudButton("▶ Resume this run", { vm.resumeRun(); onSimulate() }, Modifier.fillMaxWidth())
                }
            }
        }
        if (r == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HudPanel(title = "No results yet") {
                    Text("Run a simulation to see win rates, matchups and detailed card statistics.",
                        style = MaterialTheme.typography.bodyMedium, color = Hud.Muted)
                }
                HudButton("Go to simulation", onSimulate, Modifier.fillMaxWidth())
            }
            return@Column
        }
        ResultsBody(r, onOpenDeck, onGames)
    }
}

@Composable
private fun ResultsBody(r: SimulationReport, onOpenDeck: (String) -> Unit, onGames: () -> Unit) {
    val ranked = r.ranked
    val top = ranked.firstOrNull()
    val scaleMax = (r.decks.maxOfOrNull { it.outcome.ci95High } ?: 0.5).coerceAtLeast(0.4) + 0.05

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (r.source == "sample") {
            item { Notice("Old sample data from the placeholder model, not your real cards. Run again to use the quick model.") }
        } else if (r.source == "quick") {
            item { Notice("Quick model: real costs, colours, power/toughness and the card text it can read (see each deck's coverage). No stack, politics or most combos: confirm close calls with Forge.", color = Hud.Electric) }
        }
        r.notes.forEach { note ->
            item { Notice(note, color = if (note.startsWith("Forge doesn't know") || note.startsWith("Stopped") || note.startsWith("Check:")) Hud.Warn else Hud.Electric) }
        }
        val ab = r.ab
        if (ab != null) {
            abSection(r, ab, onOpenDeck)
        } else if (top != null) {
            item {
                HudPanel(title = "Top deck in this pool", accent = Hud.Cyan, onClick = { onOpenDeck(top.deckId) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(top.name, style = MaterialTheme.typography.titleLarge, color = Hud.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!top.commander.equals(top.name, ignoreCase = true)) {
                                Text(top.commander, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(4.dp))
                            ColorPips(top.colorIdentity)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(pct(top.outcome.winRate), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Hud.Cyan)
                            Text("WIN RATE", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(verdict(top, ranked.getOrNull(1)), style = MaterialTheme.typography.bodySmall, color = Hud.Ice)
                }
            }
        }
        item {
            TileRow(
                Tile("Games", "%,d".format(r.meta.games), null, onGames),
                Tile("Avg length", num(r.meta.avgTurns, 1), "rounds", onGames),
                Tile("Timeouts", pct(r.meta.timeouts.toDouble() / r.meta.games.coerceAtLeast(1)), "draws", onGames),
            )
        }
        item {
            HudPanel(title = "Ranking · win rate ±95% CI") {
                ranked.forEachIndexed { i, d -> RankRow(i + 1, d, scaleMax, highlight = i == 0) { onOpenDeck(d.deckId) } }
                Spacer(Modifier.height(4.dp))
                Text("Dashed line = 25%, even odds in a 4-player pod. Tap a deck for its full report.",
                    style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }
        }
        if (ranked.size >= 2) {
            item {
                HudPanel(title = "Matchups · row deck's win rate when column deck is at the table") {
                    Heatmap(
                        rowLabels = ranked.map { it.name },
                        colLabels = ranked.map { it.name },
                        values = ranked.map { row ->
                            ranked.map { col ->
                                if (row.deckId == col.deckId) null
                                else r.matchups.firstOrNull { it.deckId == row.deckId && it.opponentId == col.deckId }
                                    ?.takeIf { it.gamesTogether > 0 }?.winRate
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Blue = above 25%, red = below. Read across a row to see which decks that deck struggles against.",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }
        }
        item {
            HudPanel(title = "Damage curve · avg damage per round") {
                ranked.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenDeck(d.deckId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.width(120.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Sparkline(d.damage.perTurnDealt.take(12), Modifier.weight(1f).height(24.dp), Hud.Series[i % Hud.Series.size])
                        Spacer(Modifier.width(8.dp))
                        Text(num(d.damage.avgDealtToPlayers, 0), style = MaterialTheme.typography.labelMedium, color = Hud.Ice)
                    }
                }
                Text("Number = average damage to players per game.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }
        }
    }
}

private fun verdict(best: DeckReport, runnerUp: DeckReport?): String {
    if (runnerUp == null) return "Only one deck in the results."
    val gap = best.outcome.winRate - runnerUp.outcome.winRate
    val overlap = best.outcome.ci95Low <= runnerUp.outcome.ci95High
    return if (overlap) {
        "Leads ${runnerUp.name} by ${"%.1f".format(gap * 100)} points, but the confidence intervals overlap. Run more games to separate them."
    } else {
        "Leads ${runnerUp.name} by ${"%.1f".format(gap * 100)} points, and the gap is outside the margin of error."
    }
}

@Composable
internal fun RankRow(rank: Int, d: DeckReport, scaleMax: Double, highlight: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("%02d".format(rank), fontFamily = Mono, color = if (highlight) Hud.Cyan else Hud.Dim, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            if (d.abSide != null) {
                Text(d.abSide, fontFamily = Mono, fontWeight = FontWeight.Bold, color = if (d.abSide == "A") Hud.Electric else Hud.Cyan)
                Spacer(Modifier.width(6.dp))
            }
            Text(d.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pct(d.outcome.winRate), fontFamily = Mono, fontWeight = FontWeight.Bold, color = if (highlight) Hud.Cyan else Hud.Ice)
            Text(" ›", color = Hud.Electric)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(26.dp))
            WinRateBar(d.outcome.winRate, d.outcome.ci95Low, d.outcome.ci95High, Modifier.weight(1f), scaleMax = scaleMax, highlight = highlight)
        }
    }
}

/** Drop-down of saved runs (most recent first), with delete for the selected one. */
@Composable
private fun RunSelector(
    runs: List<com.eddiew.commandersim.data.RunSummary>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = runs.firstOrNull { it.id == selected }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    fun title(run: com.eddiew.commandersim.data.RunSummary) =
        "${fmt.format(Date(run.createdAt))} · ${run.engineLabel}" + (if (run.ab) " · A/B" else "") + if (run.partial) " · partial" else ""
    fun sub(run: com.eddiew.commandersim.data.RunSummary) =
        "${"%,d".format(run.games)} games · " + run.decks.joinToString(", ") { (it.abSide?.let { s -> "$s: " } ?: "") + it.name }

    Box(modifier.fillMaxWidth()) {
        HudPanel(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RUN ${runs.indexOfFirst { it.id == selected }.let { if (it < 0) "—" else "${runs.size - it}" }} OF ${runs.size}",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Electric)
                    Text(current?.let(::title) ?: "Choose a run", style = MaterialTheme.typography.bodyMedium, color = Hud.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current?.let { Text(sub(it), style = MaterialTheme.typography.labelSmall, color = Hud.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (current != null) {
                    Text("✕", color = Hud.Muted, modifier = Modifier.padding(horizontal = 8.dp).clickable { confirmDelete = true })
                }
                Text("▾", color = Hud.Cyan)
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Hud.SurfaceHi)) {
            runs.forEachIndexed { i, run ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("#${runs.size - i}  " + title(run), style = MaterialTheme.typography.bodySmall,
                                color = if (run.id == selected) Hud.Cyan else Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sub(run), style = MaterialTheme.typography.labelSmall, color = Hud.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { open = false; onSelect(run.id) },
                )
            }
        }
    }
    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Hud.SurfaceHi,
            title = { Text("Delete this run?", color = Hud.Text) },
            text = { Text("${title(current)}\n${sub(current)}\n\nIts results are removed from the history and from each deck's test record.", color = Hud.Muted) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(current.id) }) { Text("DELETE", color = Hud.Negative) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = Hud.Muted) } },
        )
    }
}

