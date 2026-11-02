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
import com.eddiew.commandersim.data.model.CardType
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.sim.CardCategories
import com.eddiew.commandersim.sim.Category
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.RadarChart
import com.eddiew.commandersim.ui.components.cardPress
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

/** Automatic card categories for one deck: radar, counts vs guideline, and the cards in each (tap to expand). */
@Composable
fun DeckCategoriesPanel(deck: Deck) {
    val breakdown = remember(deck) { CardCategories.breakdown(deck) }
    val counts = breakdown.mapValues { (_, cards) -> cards.sumOf { it.quantity } }
    var open by remember { mutableStateOf<Category?>(null) }
    val scale = maxOf(10, counts.values.maxOrNull() ?: 0).toDouble()
    val uncategorised = remember(deck) {
        val tagged = breakdown.values.flatten().map { it.name }.toSet()
        deck.cards.filter { it.type != CardType.LAND && it.name !in tagged }.sumOf { it.quantity }
    }

    HudPanel(title = "Card categories") {
        if (!CardCategories.hasData(deck)) {
            Text("Card data is still downloading from Scryfall; categories fill in when it's done.",
                style = MaterialTheme.typography.bodySmall, color = Hud.Warn)
            Spacer(Modifier.height(8.dp))
        }
        RadarChart(Category.entries.map { it.short to (counts[it] ?: 0) / scale }, size = 230.dp)
        Spacer(Modifier.height(8.dp))
        Category.entries.forEach { c ->
            val n = counts[c] ?: 0
            val target = CardCategories.guideline[c]
            val expanded = open == c
            Column(Modifier.fillMaxWidth().clickable(enabled = n > 0) { open = if (expanded) null else c }.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.short, fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = Hud.Electric, modifier = Modifier.width(34.dp))
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, color = if (n > 0) Hud.Text else Hud.Dim, modifier = Modifier.weight(1f))
                    target?.let {
                        Text("/ $it", style = MaterialTheme.typography.labelSmall, color = Hud.Dim, modifier = Modifier.padding(end = 6.dp))
                    }
                    Text("$n", fontFamily = Mono, fontWeight = FontWeight.Bold,
                        color = when {
                            target == null -> Hud.Cyan
                            n >= target -> Hud.Positive
                            n >= target * 0.6 -> Hud.Warn
                            else -> Hud.Negative
                        })
                    Spacer(Modifier.width(6.dp))
                    Text(if (n == 0) " " else if (expanded) "▾" else "▸", color = Hud.Electric)
                }
                Spacer(Modifier.height(3.dp))
                NeonBar((n / scale).toFloat(), color = if (expanded) Hud.Cyan else Hud.Electric, height = 4.dp)
                if (expanded) {
                    breakdown.getValue(c).sortedBy { it.name }.forEach { card ->
                        Row(Modifier.fillMaxWidth().cardPress(card.name).padding(start = 34.dp, top = 4.dp)) {
                            Text(if (card.quantity > 1) "${card.quantity}× ${card.name}" else card.name,
                                style = MaterialTheme.typography.bodySmall, color = Hud.Ice, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val others = CardCategories.of(card, com.eddiew.commandersim.sim.ManaColor.mask(deck.colorIdentity),
                                commander = card in deck.commanders) - c
                            if (others.isNotEmpty()) Text(others.joinToString(" ") { it.short }, fontFamily = Mono,
                                style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("$uncategorised non-land cards fit no category (creatures, threats, synergy pieces). " +
            "Tap a category to see its cards; long-press a card for its text. Guideline: ~10 ramp, ~10 draw, ~8 removal, ~3 wipes.",
            style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
    }
}
