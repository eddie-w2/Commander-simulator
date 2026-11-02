package com.eddiew.commandersim.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eddiew.commandersim.data.DeckHistory
import com.eddiew.commandersim.ui.components.BarList
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.LineChart
import com.eddiew.commandersim.ui.components.Series
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import java.text.DateFormat
import java.util.Date

/** High-level record of a deck across every saved run, plus the list of those runs. */
@Composable
fun DeckTestHistory(h: DeckHistory, onOpenRun: (String) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    HudPanel(title = "Test history · ${h.runs.size} run${if (h.runs.size == 1) "" else "s"}", accent = Hud.Cyan) {
        if (h.runs.isEmpty()) {
            Text("This deck hasn't been in a simulation yet. Add it to the pool on the Simulate tab.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
            return@HudPanel
        }
        val (lo, hi) = h.ci
        TileRow(
            Triple("Win rate", pct(h.winRate, 0), "${pct(lo, 0)}–${pct(hi, 0)}"),
            Triple("Games", "${h.games}", "${h.wins} wins"),
            Triple("Avg place", num(h.avgPlace, 2), "of the table"),
        )
        Spacer(Modifier.height(8.dp))
        val delta = h.winRate - h.even
        Text(
            "An average deck wins about ${pct(h.even, 0)} at these table sizes; this deck is " +
                (if (lo > h.even) "clearly above that" else if (hi < h.even) "clearly below that" else "within the margin of error") +
                " (${if (delta >= 0) "+" else ""}${"%.0f".format(delta * 100)} pts).",
            style = MaterialTheme.typography.bodySmall, color = Hud.Muted,
        )
        if (h.byEngine.size > 1 || h.byEngine.keys.any { it != "Quick model" }) {
            Spacer(Modifier.height(8.dp))
            h.byEngine.forEach { (engine, gw) ->
                Text("$engine: ${pct(gw.second.toDouble() / gw.first.coerceAtLeast(1), 0)} win rate over ${gw.first} games",
                    style = MaterialTheme.typography.labelSmall, color = Hud.Ice)
            }
        }
        if (h.trend.size >= 2) {
            Spacer(Modifier.height(10.dp))
            Text("WIN RATE BY RUN (OLDEST → NEWEST)", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
            LineChart(
                listOf(Series("Win rate", h.trend, Hud.Cyan)), height = 120.dp, fixedMax = 1.0, yFormat = { pct(it, 0) },
                xLabel = "RUN", reference = Series("Average deck", List(h.trend.size) { h.even }, Hud.Warn),
            )
        }
        val vs = h.vs.filter { it.games >= 2 }
        if (vs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("WIN RATE WHEN AT THE TABLE WITH…", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
            Spacer(Modifier.height(6.dp))
            BarList(
                vs.sortedByDescending { it.wins.toDouble() / it.games }.take(8)
                    .map { "${it.name} (${it.games})" to it.wins.toDouble() / it.games },
                valueFormat = { pct(it, 0) }, maxValue = 1.0,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("RUNS", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val list = if (showAll) h.runs else h.runs.take(5)
        list.forEach { (run, stat) ->
            Row(Modifier.fillMaxWidth().clickable { onOpenRun(run.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmt.format(Date(run.createdAt)) + " · ${run.engineLabel}" + (stat.abSide?.let { " · A/B side $it" } ?: "") +
                        if (run.partial) " · partial" else "",
                        style = MaterialTheme.typography.bodySmall, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${stat.games} games · vs " + run.decks.filter { it.deckId != stat.deckId }.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(pct(stat.wins.toDouble() / stat.games, 0), fontFamily = Mono, fontWeight = FontWeight.Bold, color = Hud.Cyan)
                Text(" ›", color = Hud.Electric)
            }
        }
        if (!showAll && h.runs.size > 5) {
            Spacer(Modifier.height(4.dp))
            HudOutlineButton("Show all ${h.runs.size} runs", { showAll = true }, Modifier.fillMaxWidth())
        }
    }
}
