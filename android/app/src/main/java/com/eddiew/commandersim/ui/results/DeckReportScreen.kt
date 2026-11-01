package com.eddiew.commandersim.ui.results

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.CardStat
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.SimulationReport
import com.eddiew.commandersim.ui.components.BarList
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.DonutChart
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.LineChart
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.RadarChart
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.Series
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.components.signedPct
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

private enum class ReportTab(val label: String) {
    OVERVIEW("Overview"), DAMAGE("Damage"), CARDS("Cards"), REMOVAL("Removal"), RESOURCES("Resources"), COMBAT("Combat & board")
}

@Composable
fun DeckReportScreen(vm: AppViewModel, deckId: String, onBack: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val r = report
    val d = r?.deck(deckId)
    var tab by rememberSaveable { mutableStateOf(ReportTab.OVERVIEW) }

    Column(Modifier.fillMaxSize()) {
        if (r == null || d == null) {
            ScreenHeader("No data")
            HudOutlineButton("← Back", onBack, Modifier.padding(16.dp), color = Hud.Muted)
            return@Column
        }
        ScreenHeader(d.name, d.commander, trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(pct(d.outcome.winRate), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Hud.Cyan)
                ColorPips(d.colorIdentity)
            }
        })
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            item { HudChip("←", false, onBack, color = Hud.Muted) }
            items(ReportTab.entries) { t -> HudChip(t.label, tab == t, { tab = t }) }
        }
        val names = r.decks.associate { it.deckId to it.name }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                ReportTab.OVERVIEW -> overview(r, d)
                ReportTab.DAMAGE -> damage(d, names)
                ReportTab.CARDS -> cards(d)
                ReportTab.REMOVAL -> removal(d, names)
                ReportTab.RESOURCES -> resources(d)
                ReportTab.COMBAT -> combat(d)
            }
        }
    }
}

// ───────────────────────────── Overview ─────────────────────────────

private fun profileAxes(r: SimulationReport, d: DeckReport): List<Pair<String, Double>> {
    fun norm(sel: (DeckReport) -> Double): Double {
        val max = r.decks.maxOf(sel).takeIf { it > 0 } ?: return 0.0
        return sel(d) / max
    }
    fun avgWinTurn(x: DeckReport): Double {
        val h = x.outcome.winTurnHistogram
        val n = h.sum()
        return if (n == 0) 99.0 else h.withIndex().sumOf { (i, c) -> (i + 1.0) * c } / n
    }
    val fastest = r.decks.minOf { avgWinTurn(it) }
    val avgPlace = d.outcome.placements.withIndex().sumOf { (i, c) -> (i + 1.0) * c } / d.outcome.games.coerceAtLeast(1)
    return listOf(
        "Speed" to (fastest / avgWinTurn(d)).coerceIn(0.0, 1.0),
        "Damage" to norm { it.damage.avgDealtToPlayers },
        "Interaction" to norm { it.removal.permanentsRemovedPerGame + it.removal.counterspellsPerGame },
        "Card flow" to norm { it.resources.avgCardsDrawn },
        "Survival" to ((4.0 - avgPlace) / 3.0).coerceIn(0.0, 1.0),
        "Board" to norm { it.board.avgPermanentsByTurn.getOrElse(6) { 0.0 } },
    )
}

private fun LazyListScope.overview(r: SimulationReport, d: DeckReport) {
    val o = d.outcome
    val avgPlace = o.placements.withIndex().sumOf { (i, c) -> (i + 1.0) * c } / o.games.coerceAtLeast(1)
    item {
        TileRow(
            Triple("Win rate", pct(o.winRate), "${pct(o.ci95Low, 0)}–${pct(o.ci95High, 0)}"),
            Triple("Avg place", num(avgPlace, 2), "of 4"),
            Triple("Games", "%,d".format(o.games), "${o.draws} draws"),
        )
    }
    item {
        TileRow(
            Triple("Eliminated", num(o.avgEliminationTurn, 1), "avg round"),
            Triple("Cmdr cast", num(d.commanderStats.avgFirstCastTurn, 1), "first turn"),
            Triple("WR cmdr ≤T5", pct(d.commanderStats.winRateWhenCastByT5, 0), null),
            accent = Hud.Cyan,
        )
    }
    item { HudPanel(title = "Deck profile · relative to the field") { RadarChart(profileAxes(r, d)) } }
    item {
        HudPanel(title = "Finishing position") {
            ColumnChart(o.placements.map { it.toDouble() / o.games.coerceAtLeast(1) }, listOf("1ST", "2ND", "3RD", "4TH"),
                valueFormat = { pct(it, 0) }, height = 130.dp)
        }
    }
    item {
        HudPanel(title = "Winning turn") {
            val last = (o.winTurnHistogram.indexOfLast { it > 0 } + 1).coerceAtLeast(5)
            ColumnChart(o.winTurnHistogram.take(last).map { it.toDouble() }, List(last) { "${it + 1}" }, height = 130.dp)
            Text("Wins by round number.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
        }
    }
    item {
        HudPanel(title = "How it wins") {
            DonutChart(o.winConditions.map { it.condition.label to it.count.toDouble() }, centerTop = "${o.wins}", centerBottom = "wins")
        }
    }
    item {
        HudPanel(title = "How it loses", accent = Hud.Negative) {
            DonutChart(o.lossCauses.map { it.condition.label to it.count.toDouble() },
                centerTop = "${o.games - o.wins}", centerBottom = "losses")
        }
    }
    item {
        HudPanel(title = "Win rate by seat (turn order)") {
            ColumnChart(o.seatWinRate, listOf("SEAT 1", "SEAT 2", "SEAT 3", "SEAT 4"), valueFormat = { pct(it, 0) },
                baseline = 0.25, height = 130.dp)
        }
    }
    item {
        HudPanel(title = "Commander") {
            val c = d.commanderStats
            TileRow(
                Triple("Casts/game", num(c.castsPerGame, 2), null),
                Triple("Tax paid", num(c.avgTaxPaid, 1), "mana/game"),
                Triple("Removed", num(c.removedPerGame, 2), "per game"),
            )
            Spacer(Modifier.height(8.dp))
            TileRow(Triple("Cmdr damage", num(c.damagePerGame, 1), "per game"), Triple("First cast", num(c.avgFirstCastTurn, 1), "turn"))
        }
    }
}

// ───────────────────────────── Damage ─────────────────────────────

private fun LazyListScope.damage(d: DeckReport, names: Map<String, String>) {
    val dm = d.damage
    item {
        TileRow(
            Triple("To players", num(dm.avgDealtToPlayers, 1), "per game"),
            Triple("Combat", num(dm.avgCombat, 1), pct(dm.avgCombat / dm.avgDealtToPlayers.coerceAtLeast(0.01), 0)),
            Triple("Non-combat", num(dm.avgNonCombat, 1), pct(dm.avgNonCombat / dm.avgDealtToPlayers.coerceAtLeast(0.01), 0)),
        )
    }
    item {
        TileRow(
            Triple("Commander", num(dm.avgCommanderDamage, 1), null),
            Triple("To creatures", num(dm.avgDealtToCreatures, 1), null),
            Triple("Taken", num(dm.avgTaken, 1), null),
            accent = Hud.Cyan,
        )
    }
    item { TileRow(Triple("Life gained", num(dm.avgLifeGained, 1), "per game"), accent = Hud.Positive) }
    item {
        HudPanel(title = "Damage dealt per turn") {
            LineChart(listOf(Series("Damage", dm.perTurnDealt, Hud.Electric)))
        }
    }
    item {
        HudPanel(title = "Which cards dealt it · per game") {
            BarList(dm.topSources.map { it.name to it.value })
        }
    }
    item {
        HudPanel(title = "Damage aimed at each opponent · per game together") {
            BarList(dm.byTarget.map { (names[it.name] ?: it.name) to it.value }, color = Hud.Cyan)
        }
    }
}

// ───────────────────────────── Cards ─────────────────────────────

private enum class CardSort(val label: String, val key: (CardStat) -> Double) {
    IMPACT("Impact", { it.impact ?: -9.0 }),
    PLAY("Play rate", { it.castRate }),
    DAMAGE("Damage", { it.damagePerGame }),
    REMOVAL("Removals", { it.removalsPerGame }),
    DEAD("Dead rate", { it.deadRate }),
    REMOVED("Got removed", { it.removedRate }),
}

private fun LazyListScope.cards(d: DeckReport) {
    item { CardsHeaderAndList(d) }
}

@Composable
private fun CardsHeaderAndList(d: DeckReport) {
    var sort by rememberSaveable { mutableStateOf(CardSort.IMPACT) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var hideLands by rememberSaveable { mutableStateOf(true) }
    val list = d.cards.filter { !hideLands || it.type != CardType.LAND }.sortedByDescending { sort.key(it) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HudPanel(title = "Sort cards by") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardSort.entries.take(3).forEach { s -> HudChip(s.label, sort == s, { sort = s }) }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardSort.entries.drop(3).forEach { s -> HudChip(s.label, sort == s, { sort = s }) }
            }
            Spacer(Modifier.height(6.dp))
            HudChip(if (hideLands) "Lands hidden" else "Lands shown", !hideLands, { hideLands = !hideLands }, color = Hud.Muted)
            Spacer(Modifier.height(8.dp))
            Text(
                "Impact = win rate when the card was cast minus win rate when it wasn't. " +
                    "Play rate = share of games it was cast. Dead rate = drawn but never cast.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
            )
        }
        val shown = if (showAll) list else list.take(25)
        HudPanel(title = "${list.size} cards") {
            shown.forEachIndexed { i, c ->
                CardRow(c)
                if (i < shown.lastIndex) Spacer(Modifier.height(10.dp))
            }
            if (!showAll && list.size > 25) {
                Spacer(Modifier.height(10.dp))
                HudOutlineButton("Show all ${list.size}", { showAll = true }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CardRow(c: CardStat) {
    val impact = c.impact
    val impactColor: Color = when {
        impact == null -> Hud.Dim
        impact > 0.02 -> Hud.Positive
        impact < -0.02 -> Hud.Negative
        else -> Hud.Muted
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.type.label.take(3).uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Electric, modifier = Modifier.width(30.dp))
            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(signedPct(impact), style = MaterialTheme.typography.labelMedium, color = impactColor)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(30.dp))
            Column(Modifier.weight(1f)) { NeonBar(c.castRate.toFloat(), height = 4.dp) }
            Text(" ${pct(c.castRate, 0)} played", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
        }
        Text(
            buildString {
                append("T${num(c.avgCastTurn, 1)} avg")
                append(" · seen ${pct(c.seenRate, 0)}")
                if (c.deadRate > 0) append(" · dead ${pct(c.deadRate, 0)}")
                if (c.damagePerGame > 0.05) append(" · ${num(c.damagePerGame, 1)} dmg")
                if (c.removalsPerGame > 0.01) append(" · ${num(c.removalsPerGame, 2)} removals")
                if (c.removedRate > 0.01) append(" · removed ${pct(c.removedRate, 0)}")
                if (c.counteredRate > 0.01) append(" · countered ${pct(c.counteredRate, 0)}")
            },
            style = MaterialTheme.typography.labelSmall, color = Hud.Dim, modifier = Modifier.padding(start = 30.dp, top = 2.dp),
        )
    }
}

// ───────────────────────────── Removal ─────────────────────────────

private fun LazyListScope.removal(d: DeckReport, names: Map<String, String>) {
    val rm = d.removal
    item {
        TileRow(
            Triple("Removal cast", num(rm.spellsPerGame, 2), "per game"),
            Triple("Removed", num(rm.permanentsRemovedPerGame, 2), "opp. perms"),
            Triple("Received", num(rm.receivedPerGame, 2), "own perms"),
        )
    }
    item {
        TileRow(
            Triple("Counters", num(rm.counterspellsPerGame, 2), "per game"),
            Triple("Board wipes", num(rm.boardWipesPerGame, 2), "per game"),
            Triple("Sacrificed", num(rm.sacrificedPerGame, 2), "own"),
            accent = Hud.Cyan,
        )
    }
    item { HudPanel(title = "What it removes · by type") { BarList(rm.removedByType.map { it.name to it.value }, valueFormat = { num(it, 2) }) } }
    item {
        HudPanel(title = "Removal MVPs · removals per game") {
            BarList(d.cards.filter { it.removalsPerGame > 0 }.sortedByDescending { it.removalsPerGame }.take(10)
                .map { it.name to it.removalsPerGame }, valueFormat = { num(it, 2) }, color = Hud.Cyan)
        }
    }
    item {
        HudPanel(title = "Who it targets · per game together") {
            BarList(rm.targetsByOpponent.map { (names[it.name] ?: it.name) to it.value }, valueFormat = { num(it, 2) })
        }
    }
    item {
        HudPanel(title = "Most-removed own cards · per game", accent = Hud.Negative) {
            BarList(rm.mostRemovedOwnCards.map { it.name to it.value }, valueFormat = { num(it, 2) }, color = Hud.Negative)
        }
    }
}

// ───────────────────────────── Resources ─────────────────────────────

private fun LazyListScope.resources(d: DeckReport) {
    val rs = d.resources
    item {
        TileRow(
            Triple("Cards drawn", num(rs.avgCardsDrawn, 1), "per game"),
            Triple("Max hand", num(rs.avgMaxHandSize, 1), "avg"),
            Triple("Discarded", num(rs.avgDiscarded, 1), "per game"),
        )
    }
    item {
        TileRow(
            Triple("Lands played", num(rs.avgLandsPlayed, 1), "per game"),
            Triple("Mulligan", pct(rs.mulliganRate, 0), "${num(rs.avgMulligans, 2)} avg"),
            Triple("Milled", num(rs.avgMilled, 1), null),
            accent = Hud.Cyan,
        )
    }
    item { HudPanel(title = "Hand size at start of turn") { LineChart(listOf(Series("Hand", rs.avgHandSizeByTurn, Hud.Electric))) } }
    item { HudPanel(title = "Cards drawn per turn") { LineChart(listOf(Series("Draws", rs.drawsPerTurn, Hud.Cyan)), yFormat = { num(it, 2) }) } }
    item {
        HudPanel(title = "Land drop hit rate") {
            LineChart(listOf(Series("Land drop", rs.landDropHitRate, Hud.Positive)), fixedMax = 1.0, yFormat = { pct(it, 0) })
        }
    }
    item { HudPanel(title = "Mana spent per turn") { LineChart(listOf(Series("Mana", rs.avgManaSpentByTurn, Hud.Ice))) } }
}

// ───────────────────────────── Combat & board ─────────────────────────────

private fun LazyListScope.combat(d: DeckReport) {
    val c = d.combat
    val b = d.board
    item {
        TileRow(
            Triple("Attacks", num(c.attacksPerGame, 1), "per game"),
            Triple("Attackers", num(c.avgAttackers, 1), "per attack"),
            Triple("Blocks", num(c.blocksPerGame, 1), "per game"),
        )
    }
    item {
        TileRow(
            Triple("Lost in combat", num(c.creaturesLostPerGame, 1), "creatures"),
            Triple("Tokens", num(b.tokensPerGame, 1), "per game"),
            Triple("Counters", num(b.countersPerGame, 1), "placed"),
            accent = Hud.Cyan,
        )
    }
    item {
        HudPanel(title = "Board size by turn") {
            LineChart(
                listOf(
                    Series("Permanents", b.avgPermanentsByTurn, Hud.Electric),
                    Series("Creatures", b.avgCreaturesByTurn, Hud.Cyan),
                ),
            )
        }
    }
}
