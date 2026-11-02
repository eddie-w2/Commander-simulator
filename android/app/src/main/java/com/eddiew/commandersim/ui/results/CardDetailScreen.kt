package com.eddiew.commandersim.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.ui.components.BarList
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.components.signedPct
import com.eddiew.commandersim.ui.theme.Hud

@Composable
fun CardDetailScreen(vm: AppViewModel, deckId: String, cardName: String, onBack: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val r = report
    val d = r?.deck(deckId)
    val c = d?.card(cardName)
    Column(Modifier.fillMaxSize()) {
        if (r == null || d == null || c == null) {
            ScreenHeader("No data")
            HudOutlineButton("← Back", onBack, Modifier.padding(16.dp), color = Hud.Muted)
            return@Column
        }
        ScreenHeader(c.name, "${c.type.label}${c.manaValue?.let { " · MV ${num(it, 0)}" } ?: ""} · in ${d.name}")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                val show = com.eddiew.commandersim.ui.components.LocalCardInfo.current
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudChip("← Back", false, onBack, color = Hud.Muted)
                    HudChip("Card text & info", false, { show(c.name) }, color = Hud.Cyan)
                }
            }
            item {
                val iv = c.impactInterval
                HudPanel(title = "Verdict · ${c.verdict.label}", accent = impactColor(c.impact)) {
                    TileRow(
                        Triple("Impact", signedPct(c.impact), iv?.let { "${signedPct(it.first)} to ${signedPct(it.second)}" }),
                        Triple("WR cast", pct(c.winRateWhenCast, 0), "${c.gamesCast} games"),
                        Triple("WR not cast", pct(c.winRateWhenNotCast, 0), "${c.games - c.gamesCast} games"),
                        accent = impactColor(c.impact),
                    )
                    Spacer(Modifier.height(8.dp))
                    TileRow(
                        Triple("WR drawn", pct(c.winRateWhenSeen, 0), "${c.gamesSeen} games"),
                        Triple("WR not drawn", pct(c.winRateWhenNotSeen, 0), null),
                        Triple("WR opening hand", pct(c.winRateInOpeningHand, 0), null),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Deck average: ${pct(d.outcome.winRate)}. The impact interval is 95%: if it doesn't cross zero, " +
                            "the effect is unlikely to be luck.",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
                    )
                }
            }
            item {
                TileRow(
                    Triple("Played", pct(c.castRate, 0), "of games"),
                    Triple("Drawn", pct(c.seenRate, 0), "of games"),
                    Triple("Dead", pct(c.deadRate, 0), "drawn, not cast"),
                )
            }
            item {
                TileRow(
                    Triple("Avg cast round", num(c.avgCastTurn, 1), null),
                    Triple("Held", num(c.avgTurnsInHand, 1), "rounds in hand"),
                    Triple("Casts/game", num(c.castsPerGame, 2), null),
                    accent = Hud.Cyan,
                )
            }
            item {
                val last = (c.castTurnHistogram.indexOfLast { it > 0 } + 1).coerceAtLeast(6)
                HudPanel(title = "Round first cast") {
                    ColumnChart(c.castTurnHistogram.take(last).map { it.toDouble() }, List(last) { "${it + 1}" }, height = 120.dp)
                }
            }
            item {
                val last = (c.castTurnHistogram.indexOfLast { it > 0 } + 1).coerceAtLeast(6)
                HudPanel(title = "Win rate by round first cast") {
                    ColumnChart(c.winRateByCastTurn.take(last).map { it ?: 0.0 }, List(last) { "${it + 1}" },
                        valueFormat = { pct(it, 0) }, baseline = d.outcome.winRate, height = 120.dp, highlightMax = true)
                    Text("Dashed line = deck average. Empty bars had fewer than 5 games.",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }
            if (c.damagePerGame > 0) {
                item {
                    TileRow(
                        Triple("Damage", num(c.damagePerGame, 1), "per game"),
                        Triple("Combat", num(c.combatDamagePerGame, 1), null),
                        Triple("Non-combat", num(c.nonCombatDamagePerGame, 1), null),
                    )
                }
                item { TileRow(Triple("Kill shots", "${c.killShots}", "final blows"), accent = Hud.Cyan) }
                item {
                    HudPanel(title = "Damage to each opponent · per game together") {
                        BarList(c.damageToOpponents.map { r.name(it.name) to it.value })
                    }
                }
            }
            if (c.removalsPerGame > 0 || c.removedRate > 0 || c.counteredRate > 0) {
                item {
                    TileRow(
                        Triple("Removals", num(c.removalsPerGame, 2), "per game"),
                        Triple("Got removed", pct(c.removedRate, 0), "per cast"),
                        Triple("Countered", pct(c.counteredRate, 0), "per cast"),
                        accent = Hud.Negative,
                    )
                }
            }
            if (c.removedBy.isNotEmpty()) {
                item {
                    HudPanel(title = "Removed by · total times", accent = Hud.Negative) {
                        BarList(c.removedBy.map { cardFrom(r, it.name) to it.value }, color = Hud.Negative, valueFormat = { num(it, 0) }, cardLabels = true)
                    }
                }
            }
        }
    }
}
