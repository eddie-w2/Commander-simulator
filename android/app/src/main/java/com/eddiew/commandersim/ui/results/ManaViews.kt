package com.eddiew.commandersim.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.ManaBaseStats
import com.eddiew.commandersim.sim.ManaBase
import com.eddiew.commandersim.ui.components.BarList
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.Expected
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.LineChart
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.Series
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.theme.Hud

/** "Mana" tab of the deck report: simulated colour/mana availability plus the static mana base. */
internal fun LazyListScope.manaTab(d: DeckReport) {
    val m = d.mana
    if (m == null) {
        item { Notice("Mana and colour stats come from the quick model. Run this pool with the quick model to see them.", color = Hud.Electric) }
        return
    }
    item {
        TileRow(
            Triple("Colour screw", pct(m.colorScrewRate, 0), "of games"),
            Triple("Land screw", pct(m.landScrewRate, 0), "<3 lands T4"),
            Triple("Flood", pct(m.floodRate, 0), "9+ lands drawn by T8"),
        )
    }
    item {
        TileRow(
            Triple("Commander on curve", pct(m.commanderOnCurveRate, 0), "castable by T${m.commanderManaValue}"),
            Triple("Castable turn", num(m.avgCommanderCastableTurn, 1), "avg"),
            Triple("Screwed turns", num(m.avgColorScrewTurns, 2), "per game, T1–8"),
            accent = Hud.Cyan,
        )
    }
    item {
        HudPanel(title = "Colours available by turn") {
            val series = m.colorByTurn.map { (c, v) -> Series(ManaBase.colorName(c), v, Hud.manaColor(c.first())) } +
                if (m.colorByTurn.size > 1) listOf(Series("All", m.allColorsByTurn, Hud.Cyan)) else emptyList()
            LineChart(series, fixedMax = 1.0, yFormat = { pct(it, 0) }, reference = Expected.constant(1.0, "Always"))
            Spacer(Modifier.height(6.dp))
            Text("Share of games where you could make each colour at the start of your main phase.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
        }
    }
    item {
        HudPanel(title = "Mana sources in play") {
            LineChart(listOf(Series("Sources", m.sourcesByTurn, Hud.Positive)), reference = Expected.perRound("1 land per turn"))
            Spacer(Modifier.height(6.dp))
            Text("Lands, mana rocks and dorks. Above the dashed line means your ramp is working.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
        }
    }
    if (m.colorLocked.isNotEmpty()) {
        item {
            HudPanel(title = "Stuck for colours") {
                Text("Share of games drawn where the card sat in hand with enough mana but not the right colours.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                Spacer(Modifier.height(8.dp))
                BarList(m.colorLocked.map { it.name to it.value }, color = Hud.Warn, valueFormat = { pct(it, 0) }, maxValue = 1.0, cardLabels = true)
            }
        }
    }
    item { ManaBaseView(m.base) }
}

/** Static mana base: pips vs sources per colour, warnings, roles and curve. */
@Composable
fun ManaBaseView(b: ManaBaseStats, showCurve: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        b.warnings.forEach { Notice(it) }
        HudPanel(title = "Pips vs sources") {
            if (b.colors.isEmpty()) Text("No coloured pips found.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
            b.colors.forEachIndexed { i, l ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Hud.manaColor(l.color.first())))
                    Spacer(Modifier.width(6.dp))
                    Text(ManaBase.colorName(l.color), style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f))
                    Text("${l.sources} sources", style = MaterialTheme.typography.labelMedium,
                        color = if (l.sources < l.suggested - 2) Hud.Warn else Hud.Ice)
                }
                Spacer(Modifier.height(4.dp))
                ShareBar("Pips", l.pipShare, "${l.pips}")
                ShareBar("Sources", l.sourceShare, "${l.sources}")
                Text(
                    "${l.cards} cards need it · up to ${l.heaviest} on one card · aim for ~${l.suggested} sources · ${l.landSources} from lands",
                    style = MaterialTheme.typography.labelSmall, color = Hud.Muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Rule of thumb: each colour's share of sources should be close to its share of pips. " +
                "Targets: ~12 sources for single-pip cards, ~18 for double, ~22 for triple.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        }
        TileRow(
            Triple("Lands", "${b.lands}", "${b.tappedLands} tapped"),
            Triple("Rocks + dorks", "${b.rocks + b.dorks}", "${b.rocks} / ${b.dorks}"),
            Triple("Land ramp", "${b.rampSpells}", "spells"),
        )
        if (b.roles.isNotEmpty()) {
            HudPanel(title = "Card roles") {
                BarList(b.roles.map { it.name to it.value }, valueFormat = { num(it, 0) })
                Spacer(Modifier.height(6.dp))
                Text("From Scryfall tags and rules text. A card can have several roles.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
            }
        }
        if (showCurve) {
            HudPanel(title = "Curve · avg ${num(b.avgManaValue, 2)}") {
                ColumnChart(b.curve.map { it.toDouble() }, List(8) { if (it == 7) "7+" else "$it" })
            }
        }
    }
}

@Composable
private fun ShareBar(label: String, share: Double, count: String) {
    Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.width(56.dp))
        NeonBar(share.toFloat(), Modifier.weight(1f), color = if (label == "Pips") Hud.Electric else Hud.Positive, height = 5.dp)
        Text(pct(share, 0), style = MaterialTheme.typography.labelSmall, color = Hud.Ice, modifier = Modifier.padding(start = 6.dp).width(40.dp))
        Text(count, style = MaterialTheme.typography.labelSmall, color = Hud.Dim, modifier = Modifier.width(28.dp))
    }
}
