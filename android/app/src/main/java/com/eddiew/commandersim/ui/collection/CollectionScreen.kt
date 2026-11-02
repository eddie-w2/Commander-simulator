package com.eddiew.commandersim.ui.collection

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.CollectionStats
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.ui.components.BarList
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.DonutChart
import com.eddiew.commandersim.ui.components.Heatmap
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.StackedBar
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

private val colorNames = mapOf('W' to "White", 'U' to "Blue", 'B' to "Black", 'R' to "Red", 'G' to "Green", 'C' to "Colorless")

@Composable
fun CollectionScreen(vm: AppViewModel, onOpenDeck: (String) -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val owned = decks.filter { it.owned }
    var includeLands by rememberSaveable { mutableStateOf(false) }
    var uniqueOnly by rememberSaveable { mutableStateOf(false) }
    var sharedLands by rememberSaveable { mutableStateOf(false) }
    var showAllShared by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Collection", "${owned.size} owned decks")
        if (owned.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Notice("No decks are marked as owned yet. Open a deck and tap OWNED, or switch on \"I own this deck\" when importing.", color = Hud.Electric)
            }
            return@Column
        }
        val summary = remember(owned) { CollectionStats.summary(owned) }
        val shared = remember(owned, sharedLands) { CollectionStats.sharedCards(owned, includeBasics = false, includeLands = sharedLands) }
        val overall = remember(owned, includeLands, uniqueOnly) {
            if (uniqueOnly) CollectionStats.uniqueColors(owned, includeLands) else CollectionStats.cumulativeColors(owned, includeLands)
        }
        val names = owned.associate { it.id to it.name }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TileRow(
                    Triple("Owned decks", "${summary.decks}", "${summary.totalCards} cards"),
                    Triple("Unique cards", "${summary.uniqueCards}", null),
                    Triple("Shared", "${summary.sharedCards}", "in 2+ decks"),
                )
            }
            item {
                TileRow(
                    Triple("Extra copies", "${summary.copiesNeeded}", "to build all at once"),
                    Triple("Avg overlap", pct(avgOverlap(owned), 0), "per deck pair"),
                    accent = Hud.Cyan,
                )
            }

            // ── Colours, all decks
            item {
                HudPanel(title = "Colour breakdown · all owned decks") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip(if (uniqueOnly) "Unique cards" else "Every copy", true, { uniqueOnly = !uniqueOnly }, Modifier.weight(1f))
                        HudChip(if (includeLands) "Lands included" else "Lands excluded", includeLands, { includeLands = !includeLands },
                            Modifier.weight(1f), color = Hud.Cyan)
                    }
                    Spacer(Modifier.height(10.dp))
                    val order = CollectionStats.ColorCounts.ORDER.filter { overall[it] > 0 }
                    DonutChart(
                        order.map { (colorNames[it] ?: "$it") to overall[it].toDouble() },
                        centerTop = "${overall.total}", centerBottom = "cards",
                        colors = order.map { Hud.manaColor(it) },
                    )
                    Spacer(Modifier.height(10.dp))
                    TileRow(
                        Triple("Mono", "${overall.mono}", pct(overall.mono.toDouble() / overall.total.coerceAtLeast(1), 0)),
                        Triple("Multi", "${overall.multi}", pct(overall.multi.toDouble() / overall.total.coerceAtLeast(1), 0)),
                        Triple("Colorless", "${overall.colorless}", pct(overall.colorless.toDouble() / overall.total.coerceAtLeast(1), 0)),
                    )
                    if (overall.unknown > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("${overall.unknown} cards have no colour data yet (re-sync those decks with Scryfall).",
                            style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                    }
                    Text("Multicolour cards count once for each of their colours in the chart.",
                        style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }

            // ── Colours, per deck
            item {
                HudPanel(title = "Colour breakdown by deck") {
                    owned.forEach { d ->
                        val c = CollectionStats.colors(d, includeLands)
                        Column(Modifier.fillMaxWidth().clickable { onOpenDeck(d.id) }.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ColorPips(d.colorIdentity)
                            }
                            Spacer(Modifier.height(4.dp))
                            StackedBar(CollectionStats.ColorCounts.ORDER.map { Hud.manaColor(it) to c[it].toDouble() })
                            Spacer(Modifier.height(2.dp))
                            Text(
                                CollectionStats.ColorCounts.ORDER.filter { c[it] > 0 }.joinToString("  ") { "$it ${c[it]}" } +
                                    "   ·  mono ${c.mono} · multi ${c.multi} · colorless ${c.colorless}",
                                style = MaterialTheme.typography.labelSmall, color = Hud.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item {
                HudPanel(title = "Commander colour identities") {
                    BarList(CollectionStats.identityCounts(owned).entries.sortedByDescending { it.value }.map { it.key to it.value.toDouble() },
                        valueFormat = { "%.0f".format(it) })
                }
            }

            item {
                HudPanel(title = "Card types · unique cards across owned decks") {
                    val unique = owned.flatMap { it.commanders + it.cards }.distinctBy { it.name.lowercase() }
                    val byType = unique.groupBy { it.type }.mapValues { it.value.size }.entries.sortedByDescending { it.value }
                    DonutChart(byType.map { it.key.label to it.value.toDouble() }, centerTop = "${unique.size}", centerBottom = "unique")
                }
            }

            // ── Shared cards
            item {
                HudPanel(title = "Most shared cards · in how many owned decks", accent = Hud.Cyan) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip(if (sharedLands) "Nonbasic lands shown" else "Nonbasic lands hidden", sharedLands, { sharedLands = !sharedLands }, color = Hud.Cyan)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (shared.isEmpty()) Text("No cards appear in more than one owned deck.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
                    val list = if (showAllShared) shared else shared.take(25)
                    list.forEach { sc ->
                        val open = expanded == sc.name
                        Column(Modifier.fillMaxWidth().clickable { expanded = if (open) null else sc.name }.padding(vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("×${sc.count}", fontFamily = Mono, fontWeight = FontWeight.Bold, color = Hud.Cyan, modifier = Modifier.width(36.dp))
                                Text(sc.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (sc.colors != null) ColorPips(sc.colors.ifEmpty { "C" })
                                Spacer(Modifier.width(6.dp))
                                Text(sc.type.label.take(3).uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Electric)
                            }
                            Text(
                                if (open) sc.deckIds.joinToString("\n") { "· ${names[it] ?: it}" }
                                else sc.deckIds.joinToString(", ") { names[it] ?: it },
                                style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
                                maxLines = if (open) 20 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 36.dp),
                            )
                        }
                    }
                    if (!showAllShared && shared.size > 25) {
                        Spacer(Modifier.height(6.dp))
                        HudOutlineButton("Show all ${shared.size}", { showAllShared = true }, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Basic lands are never counted. Tap a card to list its decks.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }

            item {
                HudPanel(title = "Shared cards by type") {
                    BarList(shared.groupBy { it.type }.map { it.key.label to it.value.size.toDouble() }.sortedByDescending { it.second },
                        valueFormat = { "%.0f".format(it) }, color = Hud.Cyan)
                }
            }

            if (owned.size >= 2) {
                item {
                    HudPanel(title = "Deck overlap · cards in common") {
                        val m = CollectionStats.overlap(owned)
                        val max = m.flatMapIndexed { i, row -> row.filterIndexed { j, _ -> i != j } }.maxOrNull()?.toDouble() ?: 1.0
                        Heatmap(
                            rowLabels = owned.map { it.name },
                            colLabels = owned.map { it.name },
                            values = m.mapIndexed { i, row -> row.mapIndexed { j, v -> if (i == j) null else v.toDouble() } },
                            mid = 0.0, span = max.coerceAtLeast(1.0), format = { "%.0f".format(it) },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Distinct non-basic cards each pair of decks shares.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                    }
                }
                item {
                    HudPanel(title = "Most similar deck pairs") {
                        val m = CollectionStats.overlap(owned)
                        val pairs = buildList {
                            for (i in owned.indices) for (j in i + 1 until owned.size) add(Triple(owned[i], owned[j], m[i][j]))
                        }.sortedByDescending { it.third }.take(8)
                        pairs.forEach { (a, b, n) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${a.name} + ${b.name}", style = MaterialTheme.typography.bodySmall, color = Hud.Text,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$n", style = MaterialTheme.typography.labelMedium, color = Hud.Cyan, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Average share of a deck's non-basic cards that also appear in another given owned deck. */
private fun avgOverlap(decks: List<com.eddiew.commandersim.data.model.Deck>): Double {
    if (decks.size < 2) return 0.0
    val m = CollectionStats.overlap(decks)
    val sizes = m.indices.map { m[it][it].coerceAtLeast(1) }
    var sum = 0.0
    var n = 0
    for (i in decks.indices) for (j in decks.indices) if (i != j) { sum += m[i][j].toDouble() / sizes[i]; n++ }
    return sum / n
}
