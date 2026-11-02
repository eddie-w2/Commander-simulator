package com.eddiew.commandersim.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eddiew.commandersim.data.model.Deck
import com.eddiew.commandersim.sim.Coverage
import com.eddiew.commandersim.sim.ManaColor
import com.eddiew.commandersim.sim.SimCard
import com.eddiew.commandersim.ui.components.HudChip
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.components.cardPress
import com.eddiew.commandersim.ui.theme.Hud

/** What the quick model reads from each card's text, and what it ignores. */
@Composable
fun QuickModelCoverage(deck: Deck) {
    val cards = remember(deck) {
        val colors = ManaColor.mask(deck.colorIdentity)
        (deck.commanders.map { SimCard.from(it, colors, commander = true) } + deck.cards.map { SimCard.from(it, colors) })
            .filter { !it.isLand }
    }
    val hasText = deck.cards.count { it.oracle != null } >= deck.cards.size / 2
    var filter by remember { mutableStateOf<Coverage.Status?>(Coverage.Status.PARTIAL) }
    var open by remember { mutableStateOf<String?>(null) }
    val count = Coverage.Status.entries.associateWith { s -> cards.count { it.coverage.status == s } }

    HudPanel(title = "Quick model · card coverage") {
        if (!hasText) {
            Text("Rules text is still downloading from Scryfall; coverage shows once it's in.", style = MaterialTheme.typography.bodySmall, color = Hud.Warn)
            return@HudPanel
        }
        TileRow(
            Triple("Understood", "${count[Coverage.Status.FULL]}", "fully"),
            Triple("Partly", "${count[Coverage.Status.PARTIAL]}", "some text ignored"),
            Triple("Ignored", "${count[Coverage.Status.NONE]}", "text not read"),
        )
        Spacer(Modifier.height(6.dp))
        NeonBar((count[Coverage.Status.FULL] ?: 0).toFloat() / cards.size.coerceAtLeast(1), color = Hud.Positive, height = 4.dp)
        Spacer(Modifier.height(8.dp))
        Text("The quick model reads each card's rules text into triggers, abilities and effects. Text it can't read is " +
            "left out of its games (Forge plays every card fully). Tap a card to see what was read.",
            style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip("Partly", filter == Coverage.Status.PARTIAL, { filter = Coverage.Status.PARTIAL }, color = Hud.Warn)
            HudChip("Ignored", filter == Coverage.Status.NONE, { filter = Coverage.Status.NONE }, color = Hud.Negative)
            HudChip("All", filter == null, { filter = null }, color = Hud.Muted)
        }
        Spacer(Modifier.height(6.dp))
        val shown = cards.filter { filter == null || it.coverage.status == filter }.sortedBy { it.name }
        if (shown.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        shown.forEach { c ->
            val expanded = open == c.name
            Column(Modifier.fillMaxWidth().cardPress(c.name) { open = if (expanded) null else c.name }.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        c.coverage.status.label.uppercase(), style = MaterialTheme.typography.labelSmall,
                        color = when (c.coverage.status) { Coverage.Status.FULL -> Hud.Positive; Coverage.Status.PARTIAL -> Hud.Warn; else -> Hud.Negative },
                    )
                }
                if (expanded) {
                    c.describe().forEach { Text("✓ $it", style = MaterialTheme.typography.labelSmall, color = Hud.Ice, modifier = Modifier.padding(top = 3.dp)) }
                    c.coverage.missed.forEach { Text("✗ $it", style = MaterialTheme.typography.labelSmall, color = Hud.Dim, modifier = Modifier.padding(top = 3.dp)) }
                }
            }
        }
    }
}
