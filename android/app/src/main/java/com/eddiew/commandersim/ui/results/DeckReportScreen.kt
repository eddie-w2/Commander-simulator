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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.CardStat
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.CardVerdict
import com.eddiew.commandersim.data.model.DeckReport
import com.eddiew.commandersim.data.model.InsightKind
import com.eddiew.commandersim.data.model.SimulationReport
import com.eddiew.commandersim.ui.components.BarList
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.DonutChart
import com.eddiew.commandersim.ui.components.Heatmap
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
    OVERVIEW("Overview"), DAMAGE("Damage"), CARDS("Cards"), WINLOSS("Wins vs losses"), THREATS("Threats"),
    REMOVAL("Removal"), RESOURCES("Resources"), COMBAT("Combat & board")
}

@Composable
fun DeckReportScreen(vm: AppViewModel, deckId: String, onBack: () -> Unit, onCard: (String) -> Unit) {
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
        ScreenHeader(
            (d.abSide?.let { "$it · " } ?: "") + d.name,
            d.commander.takeIf { !it.equals(d.name, ignoreCase = true) },
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(pct(d.outcome.winRate), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Hud.Cyan)
                    ColorPips(d.colorIdentity)
                }
            },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            item { HudChip("←", false, onBack, color = Hud.Muted) }
            items(ReportTab.entries) { t -> HudChip(t.label, tab == t, { tab = t }) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                ReportTab.OVERVIEW -> overview(r, d, onCard)
                ReportTab.DAMAGE -> damage(r, d, onCard)
                ReportTab.CARDS -> item { CardsTab(d, onCard) }
                ReportTab.WINLOSS -> winLoss(d)
                ReportTab.THREATS -> threats(r, d)
                ReportTab.REMOVAL -> removal(r, d, onCard)
                ReportTab.RESOURCES -> resources(d)
                ReportTab.COMBAT -> combat(d)
            }
        }
    }
}

/** "Card|deckId" → "Card · Deck name". */
internal fun cardFrom(r: SimulationReport, key: String): String {
    val card = key.substringBeforeLast("|")
    val deck = key.substringAfterLast("|", "")
    return if (deck.isEmpty()) card else "$card · ${r.name(deck)}"
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
    return listOf(
        "Speed" to (fastest / avgWinTurn(d)).coerceIn(0.0, 1.0),
        "Damage" to norm { it.damage.avgDealtToPlayers },
        "Interaction" to norm { it.removal.permanentsRemovedPerGame + it.removal.counterspellsPerGame },
        "Card flow" to norm { it.resources.avgCardsDrawn },
        "Survival" to ((4.0 - d.outcome.avgPlace) / 3.0).coerceIn(0.0, 1.0),
        "Board" to norm { it.board.avgPermanentsByTurn.getOrElse(5) { 0.0 } },
    )
}

private fun LazyListScope.overview(r: SimulationReport, d: DeckReport, onCard: (String) -> Unit) {
    val o = d.outcome
    item {
        TileRow(
            Triple("Win rate", pct(o.winRate), "${pct(o.ci95Low, 0)}–${pct(o.ci95High, 0)}"),
            Triple("Avg place", num(o.avgPlace, 2), "of 4"),
            Triple("Games", "%,d".format(o.games), "${o.draws} draws"),
        )
    }
    item {
        TileRow(
            Triple("Kills/game", num(d.damage.killsPerGame, 2), null),
            Triple("Eliminated", num(o.avgEliminationTurn, 1), "avg round"),
            Triple("Cmdr cast", num(d.commanderStats.avgFirstCastTurn, 1), "first round"),
            accent = Hud.Cyan,
        )
    }
    if (d.insights.isNotEmpty()) {
        item {
            HudPanel(title = "Key findings") {
                d.insights.forEachIndexed { i, ins ->
                    val color = when (ins.kind) { InsightKind.GOOD -> Hud.Positive; InsightKind.BAD -> Hud.Negative; InsightKind.INFO -> Hud.Electric }
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(when (ins.kind) { InsightKind.GOOD -> "▲"; InsightKind.BAD -> "▼"; InsightKind.INFO -> "●" },
                            color = color, fontSize = 12.sp, modifier = Modifier.width(18.dp))
                        Column {
                            Text(ins.title, style = MaterialTheme.typography.titleSmall, color = Hud.Text)
                            Text(ins.detail, style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
                        }
                    }
                    if (i < d.insights.lastIndex) Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
    item {
        HudPanel(title = "Cards that win · biggest positive impact") {
            ImpactList(d.cards.filter { it.type != CardType.LAND && it.impact != null }.sortedByDescending { it.impact }.take(6), onCard)
        }
    }
    item {
        HudPanel(title = "Weak points · biggest negative impact", accent = Hud.Negative) {
            ImpactList(d.cards.filter { it.type != CardType.LAND && it.impact != null }.sortedBy { it.impact }.take(6), onCard)
        }
    }
    item { HudPanel(title = "Deck profile · relative to the field") { RadarChart(profileAxes(r, d)) } }
    item {
        HudPanel(title = "Finishing position") {
            ColumnChart(o.placements.map { it.toDouble() / o.games.coerceAtLeast(1) }, listOf("1ST", "2ND", "3RD", "4TH"),
                valueFormat = { pct(it, 0) }, height = 130.dp)
        }
    }
    item {
        HudPanel(title = "Winning round") {
            val last = (o.winTurnHistogram.indexOfLast { it > 0 } + 1).coerceAtLeast(5)
            ColumnChart(o.winTurnHistogram.take(last).map { it.toDouble() }, List(last) { "${it + 1}" }, height = 130.dp)
        }
    }
    item {
        HudPanel(title = "How it wins") {
            DonutChart(o.winConditions.map { it.condition.label to it.count.toDouble() }, centerTop = "${o.wins}", centerBottom = "wins")
        }
    }
    item {
        HudPanel(title = "How it loses", accent = Hud.Negative) {
            DonutChart(o.lossCauses.map { it.condition.label to it.count.toDouble() }, centerTop = "${o.games - o.wins}", centerBottom = "losses")
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
            TileRow(
                Triple("WR cast ≤R5", pct(c.winRateWhenCastByT5, 0), null),
                Triple("WR never cast", pct(c.winRateWhenNeverCast, 0), null),
                Triple("Cmdr damage", num(c.damagePerGame, 1), "per game"),
            )
        }
    }
}

@Composable
private fun ImpactList(cards: List<CardStat>, onCard: (String) -> Unit) {
    if (cards.isEmpty()) {
        Text("Not enough games yet.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        return
    }
    cards.forEach { c ->
        Row(Modifier.fillMaxWidth().clickable { onCard(c.name) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            VerdictTag(c.verdict)
            Spacer(Modifier.width(8.dp))
            Text(signedPct(c.impact), style = MaterialTheme.typography.labelMedium, color = impactColor(c.impact),
                modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        }
    }
}

internal fun impactColor(i: Double?): Color = when {
    i == null -> Hud.Dim
    i > 0.02 -> Hud.Positive
    i < -0.02 -> Hud.Negative
    else -> Hud.Muted
}

@Composable
internal fun VerdictTag(v: CardVerdict) {
    val (text, color) = when (v) {
        CardVerdict.WINNER -> "WINS" to Hud.Positive
        CardVerdict.WEAK -> "WEAK" to Hud.Negative
        CardVerdict.NEUTRAL -> "" to Hud.Dim
        CardVerdict.TOO_FEW_GAMES -> "FEW GAMES" to Hud.Dim
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

// ───────────────────────────── Damage ─────────────────────────────

private fun LazyListScope.damage(r: SimulationReport, d: DeckReport, onCard: (String) -> Unit) {
    val dm = d.damage
    fun share(x: Double) = pct(x / dm.avgDealtToPlayers.coerceAtLeast(0.01), 0)
    item { Text("DEALT", style = MaterialTheme.typography.labelMedium, color = Hud.Electric) }
    item {
        TileRow(
            Triple("To players", num(dm.avgDealtToPlayers, 1), "per game"),
            Triple("Combat", num(dm.avgCombat, 1), share(dm.avgCombat)),
            Triple("Non-combat", num(dm.avgNonCombat, 1), share(dm.avgNonCombat)),
        )
    }
    item {
        TileRow(
            Triple("Commander", num(dm.avgCommanderDamage, 1), share(dm.avgCommanderDamage)),
            Triple("To creatures", num(dm.avgDealtToCreatures, 1), null),
            Triple("Kills", num(dm.killsPerGame, 2), "per game"),
        )
    }
    item {
        HudPanel(title = "Damage by card · per game (tap for detail)") {
            Row {
                Text("CARD", style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.weight(1f))
                listOf("TOTAL", "COMBAT", "OTHER", "KILLS").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.width(50.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(4.dp))
            val max = dm.sources.maxOfOrNull { it.perGame } ?: 1.0
            dm.sources.forEach { s ->
                Column(Modifier.fillMaxWidth().clickable { onCard(s.card) }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.card, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        listOf(num(s.perGame, 1), num(s.combat, 1), num(s.nonCombat, 1), "${s.killShots}").forEachIndexed { i, v ->
                            Text(v, style = MaterialTheme.typography.labelMedium, color = if (i == 0) Hud.Cyan else Hud.Ice,
                                modifier = Modifier.width(50.dp), textAlign = TextAlign.End)
                        }
                    }
                    NeonBar((s.perGame / max).toFloat(), height = 3.dp)
                }
            }
            if (dm.sources.isEmpty()) Text("No damage recorded.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        }
    }
    val opponents = dm.byTarget.map { it.name }
    val topSources = dm.sources.take(8)
    if (opponents.isNotEmpty() && topSources.isNotEmpty()) {
        item {
            HudPanel(title = "Who each card hits · per game together") {
                Heatmap(
                    rowLabels = topSources.map { it.card },
                    colLabels = opponents.map { r.name(it) },
                    values = topSources.map { s -> opponents.map { o -> s.perOpponent.firstOrNull { it.name == o }?.value ?: 0.0 } },
                    mid = 0.0, span = (topSources.maxOf { s -> s.perOpponent.maxOfOrNull { it.value } ?: 0.0 }).coerceAtLeast(0.1),
                    format = { num(it, 1) },
                )
            }
        }
    }
    item {
        HudPanel(title = "Damage per round · dealt vs taken") {
            LineChart(listOf(Series("Dealt", dm.perTurnDealt, Hud.Electric), Series("Taken", dm.perTurnTaken, Hud.Negative)))
        }
    }
    item {
        HudPanel(title = "Damage dealt to each opponent · per game together") {
            BarList(dm.byTarget.map { r.name(it.name) to it.value }, color = Hud.Cyan)
        }
    }
    item { Text("TAKEN", style = MaterialTheme.typography.labelMedium, color = Hud.Negative, modifier = Modifier.padding(top = 6.dp)) }
    item {
        TileRow(
            Triple("Taken", num(dm.avgTaken, 1), "per game"),
            Triple("Combat", num(dm.avgTakenCombat, 1), null),
            Triple("Non-combat", num(dm.avgTakenNonCombat, 1), null),
            accent = Hud.Negative,
        )
    }
    item {
        TileRow(
            Triple("Cmdr taken", num(dm.avgTakenCommander, 1), null),
            Triple("Life gained", num(dm.avgLifeGained, 1), "per game"),
            accent = Hud.Positive,
        )
    }
    item {
        HudPanel(title = "Life total by round", accent = Hud.Positive) {
            LineChart(
                listOf(
                    Series("You", d.life.avgLifeByTurn, Hud.Positive),
                    Series("Opponents", d.life.opponentsAvgLifeByTurn, Hud.Negative),
                ),
            )
        }
    }
    item {
        HudPanel(title = "Who hurts you · damage per game together", accent = Hud.Negative) {
            BarList(dm.takenByDeck.map { r.name(it.name) to it.value }, color = Hud.Negative)
        }
    }
    item {
        HudPanel(title = "Opposing cards that hurt you most · per game", accent = Hud.Negative) {
            BarList(dm.takenByCard.map { cardFrom(r, it.name) to it.value }, color = Hud.Negative)
        }
    }
    item {
        HudPanel(title = "Final blow against you · count", accent = Hud.Negative) {
            BarList(dm.lethalAgainstUs.map { "${it.card} · ${r.name(it.deckId)}" to it.count.toDouble() }, color = Hud.Negative,
                valueFormat = { num(it, 0) })
        }
    }
}

// ───────────────────────────── Cards ─────────────────────────────

private enum class CardSort(val label: String, val key: (CardStat) -> Double) {
    IMPACT("Impact", { it.impact ?: -9.0 }),
    PLAY("Play rate", { it.castRate }),
    DAMAGE("Damage", { it.damagePerGame }),
    KILLS("Kill shots", { it.killShots.toDouble() }),
    REMOVAL("Removals", { it.removalsPerGame }),
    DEAD("Dead rate", { it.deadRate }),
    HELD("Held in hand", { it.avgTurnsInHand ?: -1.0 }),
    REMOVED("Got removed", { it.removedRate }),
}

private enum class CardFilter(val label: String) { ALL("All"), WINNERS("Drives wins"), WEAK("Weak points"), NO_LANDS("No lands") }

@Composable
private fun CardsTab(d: DeckReport, onCard: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(CardSort.IMPACT) }
    var filter by rememberSaveable { mutableStateOf(CardFilter.NO_LANDS) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val list = d.cards.filter {
        when (filter) {
            CardFilter.ALL -> true
            CardFilter.WINNERS -> it.verdict == CardVerdict.WINNER
            CardFilter.WEAK -> it.verdict == CardVerdict.WEAK
            CardFilter.NO_LANDS -> it.type != CardType.LAND
        }
    }.sortedByDescending { sort.key(it) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HudPanel(title = "Show") {
            CardFilter.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { f -> HudChip(f.label, filter == f, { filter = f }, Modifier.weight(1f), color = Hud.Cyan) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("SORT BY", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
            Spacer(Modifier.height(6.dp))
            CardSort.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { s -> HudChip(s.label, sort == s, { sort = s }, Modifier.weight(1f)) }
                }
            }
            Text(
                "Impact = win rate when cast minus win rate when not cast. WINS / WEAK means the 95% interval is entirely " +
                    "above / below zero. Tap a card for its full breakdown.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
            )
        }
        val shown = if (showAll) list else list.take(30)
        HudPanel(title = "${list.size} cards") {
            shown.forEachIndexed { i, c ->
                CardRow(c) { onCard(c.name) }
                if (i < shown.lastIndex) Spacer(Modifier.height(10.dp))
            }
            if (!showAll && list.size > 30) {
                Spacer(Modifier.height(10.dp))
                HudOutlineButton("Show all ${list.size}", { showAll = true }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CardRow(c: CardStat, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.type.label.take(3).uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Electric, modifier = Modifier.width(30.dp))
            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            VerdictTag(c.verdict)
            Spacer(Modifier.width(6.dp))
            Text(signedPct(c.impact), style = MaterialTheme.typography.labelMedium, color = impactColor(c.impact))
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(30.dp))
            Column(Modifier.weight(1f)) { NeonBar(c.castRate.toFloat(), height = 4.dp) }
            Text(" ${pct(c.castRate, 0)} played", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
        }
        Text(
            buildString {
                append("R${num(c.avgCastTurn, 1)} avg")
                append(" · WR cast ${pct(c.winRateWhenCast, 0)}")
                if (c.deadRate > 0.05) append(" · dead ${pct(c.deadRate, 0)}")
                if (c.damagePerGame > 0.05) append(" · ${num(c.damagePerGame, 1)} dmg")
                if (c.killShots > 0) append(" · ${c.killShots} kills")
                if (c.removalsPerGame > 0.01) append(" · ${num(c.removalsPerGame, 2)} removals")
                if (c.removedRate > 0.01) append(" · removed ${pct(c.removedRate, 0)}")
            },
            style = MaterialTheme.typography.labelSmall, color = Hud.Dim, modifier = Modifier.padding(start = 30.dp, top = 2.dp),
        )
    }
}

// ───────────────────────────── Wins vs losses ─────────────────────────────

private fun LazyListScope.winLoss(d: DeckReport) {
    val w = d.winLoss
    item {
        TileRow(
            Triple("Wins", "${w.wins}", null),
            Triple("Losses", "${w.losses}", null),
            Triple("Draws", "${d.outcome.draws}", "timeouts"),
        )
    }
    item {
        HudPanel(title = "What's different when you win") {
            Row {
                Spacer(Modifier.weight(1f))
                Text("WINS", style = MaterialTheme.typography.labelSmall, color = Hud.Positive, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
                Text("LOSSES", style = MaterialTheme.typography.labelSmall, color = Hud.Negative, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
            }
            w.scalars.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f))
                    Text(num(s.wins, 1), style = MaterialTheme.typography.labelMedium, color = Hud.Positive, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
                    Text(num(s.losses, 1), style = MaterialTheme.typography.labelMedium, color = Hud.Negative, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
                }
            }
            Text("Per-game averages. Big gaps point at what the deck needs to happen to win.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
        }
    }
    w.series.forEach { s ->
        item {
            HudPanel(title = "${s.name} · wins vs losses") {
                LineChart(listOf(Series("Wins", s.wins, Hud.Positive), Series("Losses", s.losses, Hud.Negative)))
            }
        }
    }
}

// ───────────────────────────── Threats ─────────────────────────────

private fun LazyListScope.threats(r: SimulationReport, d: DeckReport) {
    item {
        HudPanel(title = "Nemesis cards · your win rate when an opponent resolves them", accent = Hud.Negative) {
            NemesisList(r, d, d.threats.nemesisCards)
            Text("Compared with this deck's overall win rate of ${pct(d.outcome.winRate)}.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
        }
    }
    item {
        HudPanel(title = "Cards you beat · opponents' cards that don't hurt you", accent = Hud.Positive) {
            NemesisList(r, d, d.threats.favourableCards)
        }
    }
    item {
        HudPanel(title = "Final blow against you", accent = Hud.Negative) {
            BarList(d.damage.lethalAgainstUs.map { "${it.card} · ${r.name(it.deckId)}" to it.count.toDouble() },
                color = Hud.Negative, valueFormat = { num(it, 0) })
        }
    }
    item {
        HudPanel(title = "Your cards opponents remove most · per game", accent = Hud.Negative) {
            BarList(d.removal.mostRemovedOwnCards.map { it.name to it.value }, valueFormat = { num(it, 2) }, color = Hud.Negative)
        }
    }
    item {
        HudPanel(title = "Who targets you · removal aimed at you per game together") {
            val against = r.decks.filter { it.deckId != d.deckId }.mapNotNull { o ->
                o.removal.targetsByOpponent.firstOrNull { it.name == d.deckId }?.let { o.name to it.value }
            }.sortedByDescending { it.second }
            BarList(against, valueFormat = { num(it, 2) }, color = Hud.Negative)
        }
    }
}

@Composable
private fun NemesisList(r: SimulationReport, d: DeckReport, items: List<com.eddiew.commandersim.data.model.NemesisStat>) {
    if (items.isEmpty()) {
        Text("Not enough games yet.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        return
    }
    items.forEach { n ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.card, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.name(n.deckId)} · ${n.gamesFaced} games", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }
            Text(pct(n.winRateWhenCast, 0), style = MaterialTheme.typography.labelMedium, color = Hud.Ice, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            Text(signedPct(n.delta), style = MaterialTheme.typography.labelMedium, color = impactColor(n.delta),
                modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        }
    }
}

// ───────────────────────────── Removal ─────────────────────────────

private fun LazyListScope.removal(r: SimulationReport, d: DeckReport, onCard: (String) -> Unit) {
    val rm = d.removal
    item {
        TileRow(
            Triple("Removal cast", num(rm.spellsPerGame, 2), "per game"),
            Triple("Removed", num(rm.permanentsRemovedPerGame, 2), "opp. perms"),
            Triple("Lost", num(rm.receivedPerGame, 2), "own perms"),
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
            val mvps = d.cards.filter { it.removalsPerGame > 0 }.sortedByDescending { it.removalsPerGame }.take(10)
            mvps.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { onCard(c.name) }.padding(vertical = 4.dp)) {
                    Text(c.name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(num(c.removalsPerGame, 2), style = MaterialTheme.typography.labelMedium, color = Hud.Cyan)
                    Text(" · ${signedPct(c.impact)}", style = MaterialTheme.typography.labelMedium, color = impactColor(c.impact))
                }
            }
            if (mvps.isEmpty()) Text("No removal recorded.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        }
    }
    item {
        HudPanel(title = "Who it targets · per game together") {
            BarList(rm.targetsByOpponent.map { r.name(it.name) to it.value }, valueFormat = { num(it, 2) })
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
    item { HudPanel(title = "Hand size at start of round") { LineChart(listOf(Series("Hand", rs.avgHandSizeByTurn, Hud.Electric))) } }
    item { HudPanel(title = "Cards drawn per round") { LineChart(listOf(Series("Draws", rs.drawsPerTurn, Hud.Cyan)), yFormat = { num(it, 2) }) } }
    item { HudPanel(title = "Lands in play") { LineChart(listOf(Series("Lands", rs.landsInPlayByTurn, Hud.Positive))) } }
    item {
        HudPanel(title = "Land drop hit rate") {
            LineChart(listOf(Series("Land drop", rs.landDropHitRate, Hud.Positive)), fixedMax = 1.0, yFormat = { pct(it, 0) })
        }
    }
    item { HudPanel(title = "Mana spent per round") { LineChart(listOf(Series("Mana", rs.avgManaSpentByTurn, Hud.Ice))) } }
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
        HudPanel(title = "Board size by round") {
            LineChart(listOf(Series("Permanents", b.avgPermanentsByTurn, Hud.Electric), Series("Creatures", b.avgCreaturesByTurn, Hud.Cyan)))
        }
    }
}
