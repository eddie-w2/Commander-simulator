package com.eddiew.commandersim.ui.decks

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.ui.components.ColorPips
import com.eddiew.commandersim.ui.components.ColumnChart
import com.eddiew.commandersim.ui.components.DonutChart
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.theme.Hud

@Composable
fun DeckDetailScreen(vm: AppViewModel, deckId: String, onBack: () -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val deck = decks.firstOrNull { it.id == deckId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (deck == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Deck not found")
            HudOutlineButton("Back", onBack, Modifier.padding(16.dp))
        }
        return
    }

    val all = deck.commanders + deck.cards
    val byType = all.groupBy { it.type }.mapValues { (_, v) -> v.sumOf { it.quantity } }
    val nonLand = all.filter { it.type != CardType.LAND && it.manaValue != null }
    val curve = List(8) { mv -> nonLand.filter { minOf(it.manaValue!!.toInt(), 7) == mv }.sumOf { it.quantity }.toDouble() }
    val avgMv = nonLand.sumOf { it.manaValue!! * it.quantity } / nonLand.sumOf { it.quantity }.coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
    ScreenHeader(deck.name, deck.commanderLabel, trailing = { ColorPips(deck.colorIdentity) })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudOutlineButton("← Back", onBack, color = Hud.Muted)
                HudOutlineButton(if (deck.id in syncing) "Syncing…" else "Re-sync Scryfall", { vm.resync(deck.id) })
                HudOutlineButton("Delete", { confirmDelete = true }, color = Hud.Negative)
            }
        }
        if (!deck.enriched) {
            item { Notice("Card types and mana values aren't loaded yet. They come from Scryfall and need an internet connection.") }
        }
        item {
            TileRow(
                Triple("Cards", "${deck.cardCount}", null),
                Triple("Lands", "${byType[CardType.LAND] ?: 0}", null),
                Triple("Avg MV", if (nonLand.isEmpty()) "—" else num(avgMv, 2), "non-land"),
            )
        }
        item {
            HudPanel(title = "Type breakdown") {
                DonutChart(
                    byType.entries.sortedByDescending { it.value }.map { it.key.label to it.value.toDouble() },
                    centerTop = "${deck.cardCount}", centerBottom = "cards",
                )
            }
        }
        if (nonLand.isNotEmpty()) {
            item {
                HudPanel(title = "Mana curve") {
                    ColumnChart(curve, List(8) { if (it == 7) "7+" else "$it" })
                }
            }
        }
        CardType.entries.filter { byType.containsKey(it) }.forEach { type ->
            item {
                HudPanel(title = "${type.label} · ${byType[type]}") {
                    all.filter { it.type == type }.sortedWith(compareBy({ it.manaValue ?: 99.0 }, { it.name })).forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.quantity}×", style = MaterialTheme.typography.labelMedium, color = Hud.Dim, modifier = Modifier.width(30.dp))
                            Text(
                                c.name, style = MaterialTheme.typography.bodyMedium,
                                color = if (deck.commanders.contains(c)) Hud.Cyan else Hud.Text,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            c.manaValue?.let { Text(num(it, 0), style = MaterialTheme.typography.labelMedium, color = Hud.Electric) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Hud.SurfaceHi,
            title = { Text("Delete ${deck.name}?", color = Hud.Text) },
            text = { Text("This removes the deck from the app. Past results that include it are kept.", color = Hud.Muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteDeck(deck.id); onBack() }) { Text("DELETE", color = Hud.Negative) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = Hud.Muted) } },
        )
    }
}
