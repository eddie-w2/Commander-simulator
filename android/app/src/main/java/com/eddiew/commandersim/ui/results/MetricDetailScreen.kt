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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.DonutChart
import com.eddiew.commandersim.ui.components.Heatmap
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.LineChart
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.Series
import com.eddiew.commandersim.ui.components.Tile
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.components.signedPct
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

/** Navigation callbacks shared by every detail section. */
class MetricNav(
    val metric: (Metric) -> Unit,
    val card: (String) -> Unit,
    val deck: (String) -> Unit,
)

@Composable
fun MetricDetailScreen(vm: AppViewModel, deckId: String, metric: Metric, onBack: () -> Unit, nav: MetricNav) {
    val report by vm.report.collectAsStateWithLifecycle()
    val r = report
    val d = r?.deck(deckId)
    Column(Modifier.fillMaxSize()) {
        if (r == null || (d == null && metric.family != MetricFamily.GAMES)) {
            ScreenHeader("No data")
            HudOutlineButton("← Back", onBack, Modifier.padding(16.dp), color = Hud.Muted)
            return@Column
        }
        ScreenHeader(metric.title, if (d != null && metric.family != MetricFamily.GAMES) d.name else "All decks in this run")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { HudChip("← Back", false, onBack, color = Hud.Muted) }
            if (metric.family == MetricFamily.GAMES || d == null) {
                gamesDetail(r, nav)
                return@LazyColumn
            }
            hero(r, d, metric, nav)
            when (metric.family) {
                MetricFamily.OUTCOME -> outcomeDetail(r, d, nav)
                MetricFamily.DEALT -> dealtDetail(r, d, metric, nav)
                MetricFamily.TAKEN -> takenDetail(r, d, nav)
                MetricFamily.REMOVAL -> removalDetail(r, d, metric, nav)
                MetricFamily.CARD_FLOW -> cardFlowDetail(d, metric, nav)
                MetricFamily.MANA -> manaDetail(d, nav)
                MetricFamily.COMMANDER -> commanderDetail(d, nav)
                MetricFamily.COMBAT -> combatDetail(d, nav)
                MetricFamily.BOARD -> boardDetail(d, nav)
                MetricFamily.GAMES -> Unit
            }
        }
    }
}

// ───────────────────────────── Shared hero ─────────────────────────────

private fun LazyListScope.hero(r: SimulationReport, d: DeckReport, m: Metric, nav: MetricNav) {
    val v = m.value(d)
    val all = r.decks.mapNotNull { x -> m.value(x)?.let { x to it } }
    val ordered = if (m.higherIsBetter) all.sortedByDescending { it.second } else all.sortedBy { it.second }
    val rank = ordered.indexOfFirst { it.first.deckId == d.deckId } + 1
    val avg = all.map { it.second }.average().takeIf { all.isNotEmpty() }
    item {
        HudPanel(title = m.title, accent = Hud.Cyan) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(m.format(v), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Hud.Cyan)
                Spacer(Modifier.width(8.dp))
                Text(m.unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (rank > 0) append("#$rank of ${ordered.size} decks")
                    if (avg != null) append(" · pool average ${m.format(avg)}")
                    append(if (m.higherIsBetter) " · higher is better" else " · lower is better")
                },
                style = MaterialTheme.typography.bodySmall, color = Hud.Ice,
            )
        }
    }
    val siblings = Metric.entries.filter { it.family == m.family && it != m }
    if (siblings.isNotEmpty()) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(siblings) { s -> HudChip(s.title, false, { nav.metric(s) }) }
            }
        }
    }
    if (m.winLossKey != null) {
        d.winLoss.scalars.firstOrNull { it.name == m.winLossKey }?.let { s ->
            item {
                TileRow(
                    Triple("In wins", num(s.wins, 1), "per game"),
                    Triple("In losses", num(s.losses, 1), "per game"),
                    Triple("Gap", (if (s.wins - s.losses >= 0) "+" else "") + num(s.wins - s.losses, 1), "wins − losses"),
                    accent = Hud.Positive,
                )
            }
        }
    }
    item {
        HudPanel(title = "Across all decks · tap to compare") {
            val max = all.maxOfOrNull { kotlin.math.abs(it.second) }?.takeIf { it > 0 } ?: 1.0
            ordered.forEachIndexed { i, (deck, value) ->
                val me = deck.deckId == d.deckId
                Column(Modifier.fillMaxWidth().clickable { nav.deck(deck.deckId) }.padding(vertical = 4.dp)) {
                    Row {
                        Text("%02d".format(i + 1), fontFamily = Mono, fontSize = 11.sp, color = if (me) Hud.Cyan else Hud.Dim, modifier = Modifier.width(24.dp))
                        Text(deck.name, style = MaterialTheme.typography.bodySmall, color = if (me) Hud.Cyan else Hud.Text,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.format(value), style = MaterialTheme.typography.labelMedium, color = if (me) Hud.Cyan else Hud.Ice)
                    }
                    NeonBar((value / max).toFloat(), color = if (me) Hud.Cyan else Hud.Electric, height = 4.dp)
                }
            }
        }
    }
}

// ───────────────────────────── Card table used by several families ─────────────────────────────

@Composable
private fun CardTable(
    cards: List<CardStat>,
    columns: List<Pair<String, (CardStat) -> String>>,
    onCard: (String) -> Unit,
    bar: ((CardStat) -> Double)? = null,
) {
    if (cards.isEmpty()) {
        Text("Nothing recorded.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        return
    }
    Row {
        Text("CARD", style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.weight(1f))
        columns.forEach { (h, _) ->
            Text(h, style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.width(54.dp), textAlign = TextAlign.End)
        }
    }
    val max = bar?.let { f -> cards.maxOfOrNull(f)?.takeIf { it > 0 } } ?: 1.0
    cards.forEach { c ->
        Column(Modifier.fillMaxWidth().clickable { onCard(c.name) }.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                columns.forEachIndexed { i, (_, f) ->
                    Text(f(c), style = MaterialTheme.typography.labelMedium, color = if (i == 0) Hud.Cyan else Hud.Ice,
                        modifier = Modifier.width(54.dp), textAlign = TextAlign.End)
                }
            }
            if (bar != null) NeonBar((bar(c) / max).toFloat(), height = 3.dp)
        }
    }
}

// ───────────────────────────── Outcome ─────────────────────────────

private fun LazyListScope.outcomeDetail(r: SimulationReport, d: DeckReport, nav: MetricNav) {
    val o = d.outcome
    item {
        TileRow(
            Triple("Wins", "${o.wins}", "of ${o.games}"),
            Triple("95% range", "${pct(o.ci95Low, 0)}–${pct(o.ci95High, 0)}", null),
            Triple("Draws", "${o.draws}", "timeouts"),
        )
    }
    item {
        HudPanel(title = "Win rate with each deck at the table") {
            val rows = r.matchups.filter { it.deckId == d.deckId && it.gamesTogether > 0 }.sortedByDescending { it.winRate }
            rows.forEach { m ->
                Column(Modifier.fillMaxWidth().clickable { nav.deck(m.opponentId) }.padding(vertical = 4.dp)) {
                    Row {
                        Text(r.name(m.opponentId), style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${m.gamesTogether} g · ", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                        Text(pct(m.winRate, 0), style = MaterialTheme.typography.labelMedium, color = impactColor(m.winRate - 0.25))
                    }
                    NeonBar((m.winRate / 0.6).toFloat(), color = if (m.winRate >= 0.25) Hud.Electric else Hud.Negative, height = 4.dp)
                }
            }
        }
    }
    item {
        HudPanel(title = "Win rate by seat") {
            ColumnChart(o.seatWinRate, listOf("SEAT 1", "SEAT 2", "SEAT 3", "SEAT 4"), valueFormat = { pct(it, 0) }, baseline = 0.25, height = 130.dp)
        }
    }
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
        HudPanel(title = "Cards most tied to winning") {
            CardTable(
                d.cards.filter { it.type != CardType.LAND && it.impact != null }.sortedByDescending { it.impact }.take(10),
                listOf("IMPACT" to { c: CardStat -> signedPct(c.impact) }, "WR CAST" to { c: CardStat -> pct(c.winRateWhenCast, 0) }),
                nav.card,
            )
        }
    }
    item {
        HudPanel(title = "Cards most tied to losing", accent = Hud.Negative) {
            CardTable(
                d.cards.filter { it.type != CardType.LAND && it.impact != null }.sortedBy { it.impact }.take(10),
                listOf("IMPACT" to { c: CardStat -> signedPct(c.impact) }, "WR CAST" to { c: CardStat -> pct(c.winRateWhenCast, 0) }),
                nav.card,
            )
        }
    }
    item {
        HudPanel(title = "Life total · wins vs losses") {
            LineChart(listOf(Series("Wins", d.life.avgLifeByTurnWins, Hud.Positive), Series("Losses", d.life.avgLifeByTurnLosses, Hud.Negative)))
        }
    }
}

// ───────────────────────────── Damage dealt ─────────────────────────────

private fun LazyListScope.dealtDetail(r: SimulationReport, d: DeckReport, m: Metric, nav: MetricNav) {
    val dm = d.damage
    fun share(x: Double) = pct(x / dm.avgDealtToPlayers.coerceAtLeast(0.01), 0)
    item {
        TileRow(
            Tile("Total", num(dm.avgDealtToPlayers, 1), "per game") { nav.metric(Metric.DAMAGE_DEALT) },
            Tile("Combat", num(dm.avgCombat, 1), share(dm.avgCombat)) { nav.metric(Metric.DAMAGE_COMBAT) },
            Tile("Non-combat", num(dm.avgNonCombat, 1), share(dm.avgNonCombat)) { nav.metric(Metric.DAMAGE_NONCOMBAT) },
        )
    }
    item {
        TileRow(
            Tile("Commander", num(dm.avgCommanderDamage, 1), share(dm.avgCommanderDamage)) { nav.metric(Metric.DAMAGE_COMMANDER) },
            Tile("To creatures", num(dm.avgDealtToCreatures, 1), null) { nav.metric(Metric.DAMAGE_CREATURES) },
            Tile("Kills", num(dm.killsPerGame, 2), "per game") { nav.metric(Metric.KILLS) },
            accent = Hud.Cyan,
        )
    }
    item {
        HudPanel(title = "Damage split") {
            DonutChart(
                listOf("Combat (non-commander)" to (dm.avgCombat - dm.avgCommanderDamage).coerceAtLeast(0.0),
                    "Commander" to dm.avgCommanderDamage, "Non-combat" to dm.avgNonCombat),
                centerTop = num(dm.avgDealtToPlayers, 0), centerBottom = "per game",
            )
        }
    }
    val sorted = when (m) {
        Metric.DAMAGE_COMBAT, Metric.DAMAGE_COMMANDER -> dm.sources.sortedByDescending { it.combat }
        Metric.DAMAGE_NONCOMBAT -> dm.sources.sortedByDescending { it.nonCombat }
        Metric.KILLS -> dm.sources.sortedByDescending { it.killShots }
        else -> dm.sources
    }
    item {
        HudPanel(title = "Which cards dealt it · per game") {
            val byName = d.cards.associateBy { it.name }
            CardTable(
                sorted.mapNotNull { byName[it.card] },
                listOf(
                    "TOTAL" to { c: CardStat -> num(c.damagePerGame, 1) },
                    "COMBAT" to { c: CardStat -> num(c.combatDamagePerGame, 1) },
                    "OTHER" to { c: CardStat -> num(c.nonCombatDamagePerGame, 1) },
                    "KILLS" to { c: CardStat -> "${c.killShots}" },
                ),
                nav.card,
                bar = when (m) {
                    Metric.DAMAGE_COMBAT, Metric.DAMAGE_COMMANDER -> { c: CardStat -> c.combatDamagePerGame }
                    Metric.DAMAGE_NONCOMBAT -> { c: CardStat -> c.nonCombatDamagePerGame }
                    Metric.KILLS -> { c: CardStat -> c.killShots.toDouble() }
                    else -> { c: CardStat -> c.damagePerGame }
                },
            )
        }
    }
    item {
        HudPanel(title = "Damage per round · wins vs losses") {
            val split = d.winLoss.series.firstOrNull { it.name.startsWith("Damage") }
            LineChart(
                listOfNotNull(
                    Series("All games", dm.perTurnDealt, Hud.Electric),
                    split?.let { Series("Wins", it.wins, Hud.Positive) },
                    split?.let { Series("Losses", it.losses, Hud.Negative) },
                ),
            )
        }
    }
    val top = dm.sources.take(4)
    if (top.any { it.byRound.isNotEmpty() }) {
        item {
            HudPanel(title = "Top damage cards by round") {
                LineChart(top.mapIndexed { i, s -> Series(s.card.take(14), s.byRound, Hud.Series[i % Hud.Series.size]) })
            }
        }
    }
    item {
        HudPanel(title = "Damage to each opponent · per game together") {
            BarList(dm.byTarget.map { r.name(it.name) to it.value }, color = Hud.Cyan)
        }
    }
    val opponents = dm.byTarget.map { it.name }
    val sources = sorted.take(8)
    if (opponents.isNotEmpty() && sources.isNotEmpty()) {
        item {
            HudPanel(title = "Who each card hits") {
                Heatmap(
                    rowLabels = sources.map { it.card },
                    colLabels = opponents.map { r.name(it) },
                    values = sources.map { s -> opponents.map { o -> s.perOpponent.firstOrNull { it.name == o }?.value ?: 0.0 } },
                    mid = 0.0, span = sources.maxOf { s -> s.perOpponent.maxOfOrNull { it.value } ?: 0.0 }.coerceAtLeast(0.1),
                    format = { num(it, 1) },
                )
            }
        }
    }
    item {
        HudPanel(title = "Kill shots by card · total") {
            BarList(dm.sources.filter { it.killShots > 0 }.sortedByDescending { it.killShots }.map { it.card to it.killShots.toDouble() },
                valueFormat = { num(it, 0) })
        }
    }
    item {
        HudPanel(title = "How it wins") {
            DonutChart(d.outcome.winConditions.map { it.condition.label to it.count.toDouble() }, centerTop = "${d.outcome.wins}", centerBottom = "wins")
        }
    }
}

// ───────────────────────────── Damage taken ─────────────────────────────

private fun LazyListScope.takenDetail(r: SimulationReport, d: DeckReport, nav: MetricNav) {
    val dm = d.damage
    item {
        TileRow(
            Tile("Taken", num(dm.avgTaken, 1), "per game") { nav.metric(Metric.DAMAGE_TAKEN) },
            Tile("Combat", num(dm.avgTakenCombat, 1), null) { nav.metric(Metric.TAKEN_COMBAT) },
            Tile("Non-combat", num(dm.avgTakenNonCombat, 1), null) { nav.metric(Metric.TAKEN_NONCOMBAT) },
            accent = Hud.Negative,
        )
    }
    item {
        TileRow(
            Tile("Commander", num(dm.avgTakenCommander, 1), null) { nav.metric(Metric.TAKEN_COMMANDER) },
            Tile("Life gained", num(dm.avgLifeGained, 1), "per game") { nav.metric(Metric.LIFE_GAINED) },
            accent = Hud.Positive,
        )
    }
    item {
        HudPanel(title = "Damage taken per round", accent = Hud.Negative) {
            LineChart(listOf(Series("Taken", dm.perTurnTaken, Hud.Negative), Series("Dealt", dm.perTurnDealt, Hud.Electric)))
        }
    }
    item {
        HudPanel(title = "Life total by round", accent = Hud.Positive) {
            LineChart(
                listOf(
                    Series("Wins", d.life.avgLifeByTurnWins, Hud.Positive),
                    Series("Losses", d.life.avgLifeByTurnLosses, Hud.Negative),
                    Series("Opponents", d.life.opponentsAvgLifeByTurn, Hud.Muted),
                ),
            )
        }
    }
    item {
        HudPanel(title = "Who hurts you · per game together", accent = Hud.Negative) {
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
    item {
        HudPanel(title = "How it loses", accent = Hud.Negative) {
            DonutChart(d.outcome.lossCauses.map { it.condition.label to it.count.toDouble() },
                centerTop = "${d.outcome.games - d.outcome.wins}", centerBottom = "losses")
        }
    }
    item {
        HudPanel(title = "Your life-gain cards") {
            Text("Cards that gained life aren't tracked separately yet; see total life gained above.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
        }
    }
}

// ───────────────────────────── Removal ─────────────────────────────

private fun LazyListScope.removalDetail(r: SimulationReport, d: DeckReport, m: Metric, nav: MetricNav) {
    val rm = d.removal
    item {
        TileRow(
            Tile("Cast", num(rm.spellsPerGame, 2), "per game") { nav.metric(Metric.REMOVAL_CAST) },
            Tile("Removed", num(rm.permanentsRemovedPerGame, 2), "opp. perms") { nav.metric(Metric.REMOVED) },
            Tile("Lost", num(rm.receivedPerGame, 2), "own perms") { nav.metric(Metric.LOST) },
        )
    }
    item {
        TileRow(
            Tile("Counters", num(rm.counterspellsPerGame, 2), "per game") { nav.metric(Metric.COUNTERSPELLS) },
            Tile("Wipes", num(rm.boardWipesPerGame, 2), "per game") { nav.metric(Metric.WIPES) },
            Tile("Sacrificed", num(rm.sacrificedPerGame, 2), "own") { nav.metric(Metric.SACRIFICED) },
            accent = Hud.Cyan,
        )
    }
    if (m == Metric.LOST || m == Metric.SACRIFICED) {
        lostSections(r, d, nav)
        offenseSections(r, d, nav)
    } else {
        offenseSections(r, d, nav)
        lostSections(r, d, nav)
    }
}

private fun LazyListScope.offenseSections(r: SimulationReport, d: DeckReport, nav: MetricNav) {
    val rm = d.removal
    item {
        HudPanel(title = "Your removal cards · removals per game") {
            CardTable(
                d.cards.filter { it.removalsPerGame > 0 }.sortedByDescending { it.removalsPerGame },
                listOf(
                    "PER GAME" to { c: CardStat -> num(c.removalsPerGame, 2) },
                    "PLAYED" to { c: CardStat -> pct(c.castRate, 0) },
                    "IMPACT" to { c: CardStat -> signedPct(c.impact) },
                ),
                nav.card, bar = { it.removalsPerGame },
            )
        }
    }
    item { HudPanel(title = "What it removes · by type") { BarList(rm.removedByType.map { it.name to it.value }, valueFormat = { num(it, 2) }) } }
    item {
        HudPanel(title = "Who it targets · per game together") {
            BarList(rm.targetsByOpponent.map { r.name(it.name) to it.value }, valueFormat = { num(it, 2) })
        }
    }
}

private fun LazyListScope.lostSections(r: SimulationReport, d: DeckReport, nav: MetricNav) {
    val rm = d.removal
    item {
        HudPanel(title = "Your cards that get removed", accent = Hud.Negative) {
            CardTable(
                d.cards.filter { it.removedRate > 0 }.sortedByDescending { it.removedRate * it.castsPerGame },
                listOf("PER CAST" to { c: CardStat -> pct(c.removedRate, 0) }, "PLAYED" to { c: CardStat -> pct(c.castRate, 0) }),
                nav.card, bar = { it.removedRate * it.castsPerGame },
            )
        }
    }
    item {
        HudPanel(title = "Opposing cards that remove yours · total", accent = Hud.Negative) {
            val agg = HashMap<String, Double>()
            d.cards.forEach { c -> c.removedBy.forEach { agg.merge(it.name, it.value, Double::plus) } }
            BarList(agg.entries.sortedByDescending { it.value }.take(10).map { cardFrom(r, it.key) to it.value },
                color = Hud.Negative, valueFormat = { num(it, 0) })
        }
    }
    item {
        HudPanel(title = "Who targets you · removal per game together", accent = Hud.Negative) {
            val against = r.decks.filter { it.deckId != d.deckId }.mapNotNull { o ->
                o.removal.targetsByOpponent.firstOrNull { it.name == d.deckId }?.let { o.name to it.value }
            }.sortedByDescending { it.second }
            BarList(against, valueFormat = { num(it, 2) }, color = Hud.Negative)
        }
    }
    item {
        HudPanel(title = "Your spells that get countered", accent = Hud.Negative) {
            CardTable(
                d.cards.filter { it.counteredRate > 0 }.sortedByDescending { it.counteredRate },
                listOf("PER CAST" to { c: CardStat -> pct(c.counteredRate, 0) }),
                nav.card,
            )
        }
    }
    item { HudPanel(title = "Own cards removed most · per game") { BarList(rm.mostRemovedOwnCards.map { it.name to it.value }, valueFormat = { num(it, 2) }, color = Hud.Negative) } }
}

// ───────────────────────────── Card flow ─────────────────────────────

private fun LazyListScope.cardFlowDetail(d: DeckReport, m: Metric, nav: MetricNav) {
    val rs = d.resources
    item {
        TileRow(
            Tile("Drawn", num(rs.avgCardsDrawn, 1), "per game") { nav.metric(Metric.CARDS_DRAWN) },
            Tile("Max hand", num(rs.avgMaxHandSize, 1), "avg") { nav.metric(Metric.MAX_HAND) },
            Tile("Discarded", num(rs.avgDiscarded, 1), null) { nav.metric(Metric.DISCARDED) },
        )
    }
    item { TileRow(Tile("Milled", num(rs.avgMilled, 1), "per game") { nav.metric(Metric.MILLED) }, accent = Hud.Cyan) }
    item { HudPanel(title = "Cards drawn per round") { LineChart(listOf(Series("Draws", rs.drawsPerTurn, Hud.Cyan)), yFormat = { num(it, 2) }) } }
    item {
        val split = d.winLoss.series.firstOrNull { it.name == "Hand size" }
        HudPanel(title = "Hand size at start of round · wins vs losses") {
            LineChart(
                listOfNotNull(
                    Series("All", rs.avgHandSizeByTurn, Hud.Electric),
                    split?.let { Series("Wins", it.wins, Hud.Positive) },
                    split?.let { Series("Losses", it.losses, Hud.Negative) },
                ),
            )
        }
    }
    item {
        HudPanel(title = "Dead cards · drawn but never cast", accent = Hud.Negative) {
            CardTable(
                d.cards.filter { it.type != CardType.LAND && it.gamesSeen >= 5 }.sortedByDescending { it.deadRate }.take(12),
                listOf("DEAD" to { c: CardStat -> pct(c.deadRate, 0) }, "SEEN" to { c: CardStat -> pct(c.seenRate, 0) }),
                nav.card, bar = { it.deadRate },
            )
        }
    }
    item {
        HudPanel(title = "Held longest before casting") {
            CardTable(
                d.cards.filter { it.avgTurnsInHand != null && it.type != CardType.LAND }.sortedByDescending { it.avgTurnsInHand }.take(12),
                listOf("ROUNDS" to { c: CardStat -> num(c.avgTurnsInHand, 1) }, "MV" to { c: CardStat -> num(c.manaValue, 0) }),
                nav.card, bar = { it.avgTurnsInHand ?: 0.0 },
            )
        }
    }
    item {
        HudPanel(title = "Win rate when drawn vs not drawn") {
            CardTable(
                d.cards.filter { it.winRateWhenSeen != null && it.winRateWhenNotSeen != null && it.type != CardType.LAND }
                    .sortedByDescending { it.winRateWhenSeen!! - it.winRateWhenNotSeen!! }.take(12),
                listOf(
                    "DRAWN" to { c: CardStat -> pct(c.winRateWhenSeen, 0) },
                    "NOT" to { c: CardStat -> pct(c.winRateWhenNotSeen, 0) },
                ),
                nav.card,
            )
        }
    }
    if (m == Metric.CARDS_DRAWN) {
        item {
            HudPanel(title = "Opening hand win rate") {
                CardTable(
                    d.cards.filter { it.winRateInOpeningHand != null && it.type != CardType.LAND }.sortedByDescending { it.winRateInOpeningHand }.take(10),
                    listOf("WR" to { c: CardStat -> pct(c.winRateInOpeningHand, 0) }, "OPENER" to { c: CardStat -> pct(c.openingHandRate, 0) }),
                    nav.card,
                )
            }
        }
    }
}

// ───────────────────────────── Mana ─────────────────────────────

private fun LazyListScope.manaDetail(d: DeckReport, nav: MetricNav) {
    val rs = d.resources
    item {
        TileRow(
            Tile("Lands played", num(rs.avgLandsPlayed, 1), "per game") { nav.metric(Metric.LANDS_PLAYED) },
            Tile("Mulligan", pct(rs.mulliganRate, 0), "${num(rs.avgMulligans, 2)} avg") { nav.metric(Metric.MULLIGANS) },
            Tile("Mana R1–6", num(rs.avgManaSpentByTurn.take(6).sum(), 1), "spent") { nav.metric(Metric.MANA_SPENT) },
        )
    }
    item {
        val split = d.winLoss.series.firstOrNull { it.name == "Lands in play" }
        HudPanel(title = "Lands in play · wins vs losses") {
            LineChart(listOfNotNull(Series("All", rs.landsInPlayByTurn, Hud.Electric),
                split?.let { Series("Wins", it.wins, Hud.Positive) }, split?.let { Series("Losses", it.losses, Hud.Negative) }))
        }
    }
    item {
        HudPanel(title = "Land drop hit rate") {
            LineChart(listOf(Series("Land drop", rs.landDropHitRate, Hud.Positive)), fixedMax = 1.0, yFormat = { pct(it, 0) })
        }
    }
    item {
        val split = d.winLoss.series.firstOrNull { it.name == "Mana spent per round" }
        HudPanel(title = "Mana spent per round · wins vs losses") {
            LineChart(listOfNotNull(Series("All", rs.avgManaSpentByTurn, Hud.Ice),
                split?.let { Series("Wins", it.wins, Hud.Positive) }, split?.let { Series("Losses", it.losses, Hud.Negative) }))
        }
    }
    item {
        HudPanel(title = "Mana rocks & ramp · earliest cast") {
            CardTable(
                d.cards.filter { (it.type == CardType.ARTIFACT || it.type == CardType.SORCERY || it.type == CardType.INSTANT) &&
                    (it.manaValue ?: 9.0) <= 3 && it.castRate > 0 }.sortedBy { it.avgCastTurn ?: 99.0 }.take(10),
                listOf("ROUND" to { c: CardStat -> num(c.avgCastTurn, 1) }, "IMPACT" to { c: CardStat -> signedPct(c.impact) }),
                nav.card,
            )
        }
    }
    item {
        HudPanel(title = "Mana value vs. round cast") {
            val groups = d.cards.filter { it.type != CardType.LAND && it.manaValue != null && it.avgCastTurn != null }
                .groupBy { minOf(it.manaValue!!.toInt(), 7) }
            ColumnChart(List(8) { mv -> groups[mv]?.map { it.avgCastTurn!! }?.average() ?: 0.0 }, List(8) { if (it == 7) "7+" else "$it" },
                valueFormat = { num(it, 1) }, height = 130.dp)
            Text("Average round each mana value gets cast. Big gaps mean the deck can't reach its curve.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
        }
    }
}

// ───────────────────────────── Commander ─────────────────────────────

private fun LazyListScope.commanderDetail(d: DeckReport, nav: MetricNav) {
    val c = d.commanderStats
    item {
        TileRow(
            Tile("First cast", num(c.avgFirstCastTurn, 1), "avg round") { nav.metric(Metric.COMMANDER_CAST) },
            Tile("Casts", num(c.castsPerGame, 2), "per game") { nav.metric(Metric.COMMANDER_CASTS) },
            Tile("Tax", num(c.avgTaxPaid, 1), "mana/game") { nav.metric(Metric.COMMANDER_TAX) },
        )
    }
    item {
        TileRow(
            Tile("Removed", num(c.removedPerGame, 2), "per game") { nav.metric(Metric.COMMANDER_REMOVED) },
            Tile("WR cast ≤R5", pct(c.winRateWhenCastByT5, 0), null),
            Tile("WR never cast", pct(c.winRateWhenNeverCast, 0), null),
            accent = Hud.Cyan,
        )
    }
    val names = d.commander.split(" + ")
    d.cards.filter { it.name in names }.forEach { cs ->
        item {
            HudPanel(title = cs.name) {
                DetailLinkRow { nav.card(cs.name) }
                val last = (cs.castTurnHistogram.indexOfLast { it > 0 } + 1).coerceAtLeast(6)
                Text("ROUND FIRST CAST", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                ColumnChart(cs.castTurnHistogram.take(last).map { it.toDouble() }, List(last) { "${it + 1}" }, height = 110.dp)
                Spacer(Modifier.height(8.dp))
                Text("WIN RATE BY ROUND FIRST CAST", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                ColumnChart(cs.winRateByCastTurn.take(last).map { it ?: 0.0 }, List(last) { "${it + 1}" },
                    valueFormat = { pct(it, 0) }, baseline = d.outcome.winRate, height = 110.dp)
                Spacer(Modifier.height(8.dp))
                TileRow(
                    Triple("Damage", num(cs.damagePerGame, 1), "per game"),
                    Triple("Kills", "${cs.killShots}", "total"),
                    Triple("Removed", pct(cs.removedRate, 0), "per cast"),
                )
                if (cs.removedBy.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("REMOVED BY", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                    cs.removedBy.take(5).forEach {
                        Text("${it.name.substringBeforeLast("|")} · ${num(it.value, 0)}", style = MaterialTheme.typography.bodySmall, color = Hud.Negative)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLinkRow(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Spacer(Modifier.weight(1f))
        com.eddiew.commandersim.ui.components.DetailLink(onClick, "Card detail")
    }
}

// ───────────────────────────── Combat & board ─────────────────────────────

private fun LazyListScope.combatDetail(d: DeckReport, nav: MetricNav) {
    val c = d.combat
    item {
        TileRow(
            Tile("Attacks", num(c.attacksPerGame, 1), "per game") { nav.metric(Metric.ATTACKS) },
            Tile("Attackers", num(c.avgAttackers, 1), "per attack") { nav.metric(Metric.ATTACKERS) },
            Tile("Blocks", num(c.blocksPerGame, 1), "per game") { nav.metric(Metric.BLOCKS) },
        )
    }
    item { TileRow(Tile("Lost in combat", num(c.creaturesLostPerGame, 1), "creatures") { nav.metric(Metric.COMBAT_LOSSES) }, accent = Hud.Negative) }
    item {
        HudPanel(title = "Creatures by combat damage · per game") {
            CardTable(
                d.cards.filter { it.type == CardType.CREATURE && it.combatDamagePerGame > 0 }.sortedByDescending { it.combatDamagePerGame },
                listOf("COMBAT" to { s: CardStat -> num(s.combatDamagePerGame, 1) }, "KILLS" to { s: CardStat -> "${s.killShots}" },
                    "PLAYED" to { s: CardStat -> pct(s.castRate, 0) }),
                nav.card, bar = { it.combatDamagePerGame },
            )
        }
    }
    item {
        HudPanel(title = "Creatures on board by round") {
            LineChart(listOf(Series("Creatures", d.board.avgCreaturesByTurn, Hud.Cyan)))
        }
    }
}

private fun LazyListScope.boardDetail(d: DeckReport, nav: MetricNav) {
    val b = d.board
    item {
        TileRow(
            Tile("Board R6", num(b.avgPermanentsByTurn.getOrElse(5) { 0.0 }, 1), "permanents") { nav.metric(Metric.BOARD) },
            Tile("Tokens", num(b.tokensPerGame, 1), "per game") { nav.metric(Metric.TOKENS) },
            Tile("Counters", num(b.countersPerGame, 1), "placed") { nav.metric(Metric.COUNTERS_PLACED) },
        )
    }
    item {
        val split = d.winLoss.series.firstOrNull { it.name == "Permanents on board" }
        HudPanel(title = "Permanents on board · wins vs losses") {
            LineChart(listOfNotNull(Series("All", b.avgPermanentsByTurn, Hud.Electric),
                split?.let { Series("Wins", it.wins, Hud.Positive) }, split?.let { Series("Losses", it.losses, Hud.Negative) }))
        }
    }
    item {
        HudPanel(title = "Permanents vs creatures by round") {
            LineChart(listOf(Series("Permanents", b.avgPermanentsByTurn, Hud.Electric), Series("Creatures", b.avgCreaturesByTurn, Hud.Cyan)))
        }
    }
    item {
        HudPanel(title = "Permanents most played") {
            CardTable(
                d.cards.filter { it.type in setOf(CardType.ARTIFACT, CardType.ENCHANTMENT, CardType.CREATURE, CardType.PLANESWALKER) }
                    .sortedByDescending { it.castRate }.take(12),
                listOf("PLAYED" to { c: CardStat -> pct(c.castRate, 0) }, "IMPACT" to { c: CardStat -> signedPct(c.impact) }),
                nav.card, bar = { it.castRate },
            )
        }
    }
}

// ───────────────────────────── Run-wide: games ─────────────────────────────

private fun LazyListScope.gamesDetail(r: SimulationReport, nav: MetricNav) {
    val meta = r.meta
    item {
        TileRow(
            Triple("Games", "%,d".format(meta.games), null),
            Triple("Avg length", num(meta.avgTurns, 1), "rounds"),
            Triple("Timeouts", "${meta.timeouts}", pct(meta.timeouts.toDouble() / meta.games.coerceAtLeast(1), 1)),
        )
    }
    item { TileRow(Triple("Sim speed", num(meta.avgGameMs, 2), "ms per game"), Triple("Decks", "${r.decks.size}", null), accent = Hud.Cyan) }
    if (meta.roundHistogram.isNotEmpty()) {
        item {
            HudPanel(title = "Game length · rounds") {
                val last = (meta.roundHistogram.indexOfLast { it > 0 } + 1).coerceAtLeast(5)
                ColumnChart(meta.roundHistogram.take(last).map { it.toDouble() }, List(last) { "${it + 1}" }, height = 140.dp)
                Text("The last bar includes games stopped by the round limit (timeouts).",
                    style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            }
        }
    }
    item {
        HudPanel(title = "Games per deck") {
            r.decks.sortedByDescending { it.outcome.games }.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { nav.deck(d.deckId) }.padding(vertical = 4.dp)) {
                    Text(d.name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.outcome.games} games · ${d.outcome.draws} draws", style = MaterialTheme.typography.labelMedium, color = Hud.Ice)
                }
            }
        }
    }
    item {
        HudPanel(title = "Average winning round by deck") {
            BarList(r.decks.mapNotNull { d ->
                val h = d.outcome.winTurnHistogram
                val n = h.sum()
                if (n == 0) null else d.name to h.withIndex().sumOf { (i, c) -> (i + 1.0) * c } / n
            }.sortedBy { it.second }, valueFormat = { num(it, 1) })
            Text("Lower = the deck closes out games faster.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
        }
    }
}
