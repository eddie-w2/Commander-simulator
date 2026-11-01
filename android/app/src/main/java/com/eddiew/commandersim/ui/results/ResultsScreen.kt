package com.eddiew.commandersim.ui.results

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
import com.eddiew.commandersim.data.model.DeckRole
import com.eddiew.commandersim.data.model.SimulationReport
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.Heatmap
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.Sparkline
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.WinRateBar
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import java.text.DateFormat
import java.util.Date

@Composable
fun ResultsScreen(vm: AppViewModel, onOpenDeck: (String) -> Unit, onSimulate: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val r = report
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Results",
            r?.let { "${"%,d".format(it.meta.games)} games · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.createdAt)) },
        )
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
        ResultsBody(r, onOpenDeck)
    }
}

@Composable
private fun ResultsBody(r: SimulationReport, onOpenDeck: (String) -> Unit) {
    val ranked = r.rankedCandidates
    val opponents = r.decks.filter { it.role == DeckRole.OPPONENT }.sortedByDescending { it.outcome.winRate }
    val best = ranked.firstOrNull()
    val runnerUp = ranked.getOrNull(1)
    val scaleMax = (r.decks.maxOfOrNull { it.outcome.ci95High } ?: 0.5).coerceAtLeast(0.4) + 0.05

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (r.source == "sample") {
            item { Notice("Sample data from the simplified on-device model, not Forge. Use it to explore the dashboards; don't pick a deck from it yet.") }
        }
        if (best != null) {
            item {
                HudPanel(title = "Recommended deck", accent = Hud.Cyan, onClick = { onOpenDeck(best.deckId) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(best.name, style = MaterialTheme.typography.titleLarge, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(best.commander, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            ColorPips(best.colorIdentity)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(pct(best.outcome.winRate), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Hud.Cyan)
                            Text("WIN RATE", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(verdict(best, runnerUp), style = MaterialTheme.typography.bodySmall, color = Hud.Ice)
                }
            }
        }
        item {
            TileRow(
                Triple("Games", "%,d".format(r.meta.games), null),
                Triple("Avg length", num(r.meta.avgTurns, 1), "rounds"),
                Triple("Timeouts", pct(r.meta.timeouts.toDouble() / r.meta.games.coerceAtLeast(1)), "draws"),
            )
        }
        item {
            HudPanel(title = "Candidate ranking · win rate ±95% CI") {
                ranked.forEachIndexed { i, d -> RankRow(i + 1, d, scaleMax, highlight = i == 0) { onOpenDeck(d.deckId) } }
                Spacer(Modifier.height(4.dp))
                Text("Dashed line = 25%, the even-odds baseline in a 4-player pod.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }
        }
        if (ranked.isNotEmpty() && opponents.isNotEmpty()) {
            item {
                HudPanel(title = "Matchups · candidate win rate with each opponent at the table") {
                    Heatmap(
                        rowLabels = ranked.map { it.name },
                        colLabels = opponents.map { it.name },
                        values = ranked.map { c ->
                            opponents.map { o -> r.matchups.firstOrNull { it.deckId == c.deckId && it.opponentId == o.deckId }?.winRate }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Blue = above 25%, red = below. Shows which opponents each candidate struggles with.",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }
        }
        item {
            HudPanel(title = "Playgroup decks") {
                opponents.forEachIndexed { i, d -> RankRow(i + 1, d, scaleMax, highlight = false) { onOpenDeck(d.deckId) } }
            }
        }
        item {
            HudPanel(title = "Damage per turn · candidates") {
                ranked.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        item { Spacer(Modifier.height(4.dp)) }
    }
}

private fun verdict(best: DeckReport, runnerUp: DeckReport?): String {
    if (runnerUp == null) return "Only one candidate was simulated."
    val gap = best.outcome.winRate - runnerUp.outcome.winRate
    val overlap = best.outcome.ci95Low <= runnerUp.outcome.ci95High
    return if (overlap) {
        "Leads ${runnerUp.name} by ${"%.1f".format(gap * 100)} points, but the confidence intervals overlap. Run more games to separate them."
    } else {
        "Leads ${runnerUp.name} by ${"%.1f".format(gap * 100)} points, and the gap is outside the margin of error."
    }
}

@Composable
private fun RankRow(rank: Int, d: DeckReport, scaleMax: Double, highlight: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("%02d".format(rank), fontFamily = Mono, color = if (highlight) Hud.Cyan else Hud.Dim, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
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
