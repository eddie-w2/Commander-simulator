package com.eddiew.commandersim.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eddiew.commandersim.AppViewModel
import com.eddiew.commandersim.data.DeckParser
import com.eddiew.commandersim.data.model.DeckCard
import com.eddiew.commandersim.ui.components.HudButton
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.Notice
import com.eddiew.commandersim.ui.components.ScreenHeader
import com.eddiew.commandersim.ui.components.TileRow
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

@Composable
fun VariantEditorScreen(vm: AppViewModel, deckId: String, onCancel: () -> Unit, onSaved: (String) -> Unit) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val base = decks.firstOrNull { it.id == deckId }
    if (base == null) {
        Column(Modifier.fillMaxSize()) { ScreenHeader("Deck not found"); HudOutlineButton("Back", onCancel, Modifier.padding(16.dp)) }
        return
    }
    var name by rememberSaveable { mutableStateOf("${base.name} (B)") }
    var removed by remember { mutableStateOf(mapOf<String, Int>()) }
    var addText by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    val added: List<DeckCard> = remember(addText) {
        addText.lines().mapNotNull { DeckParser.parseCardLine(it.trim())?.first }
    }
    val outCount = removed.values.sum()
    val inCount = added.sumOf { it.quantity }
    val newTotal = base.cardCount - outCount + inCount

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        ScreenHeader("Create variant", "Swap cards in ${base.name}, then A/B test both versions")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Variant name") }, modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
            )
            TileRow(
                Triple("Out", "−$outCount", null),
                Triple("In", "+$inCount", null),
                Triple("New size", "$newTotal", if (newTotal == 100) "valid" else "expect 100"),
            )
            if (outCount != inCount) Notice("Take out as many cards as you put in to keep the deck at 100.")

            HudPanel(title = "Put in · one card per line", accent = Hud.Positive) {
                OutlinedTextField(
                    value = addText, onValueChange = { addText = it },
                    placeholder = { Text("Cyclonic Rift\n1 Rhystic Study", color = Hud.Dim) },
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Hud.Text),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), colors = hudFieldColors(),
                )
                added.forEach {
                    Text("+ ${if (it.quantity > 1) "${it.quantity} " else ""}${it.name}", style = MaterialTheme.typography.bodyMedium, color = Hud.Positive)
                }
            }

            HudPanel(title = "Take out · tap cards", accent = Hud.Negative) {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it }, singleLine = true,
                    label = { Text("Search this deck") }, modifier = Modifier.fillMaxWidth(), colors = hudFieldColors(),
                )
                Spacer(Modifier.height(6.dp))
                // Selected cards first, then search matches.
                val list = base.cards.sortedWith(compareByDescending<DeckCard> { it.name in removed }.thenBy { it.name })
                    .filter { it.name in removed || filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
                list.take(if (filter.isBlank()) 40 else 25).forEach { c ->
                    val n = removed[c.name] ?: 0
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            removed = when {
                                n < c.quantity && c.quantity > 1 && n > 0 -> removed + (c.name to n + 1)
                                n == 0 -> removed + (c.name to 1)
                                else -> removed - c.name
                            }
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (n > 0) "−$n" else "${c.quantity}×", style = MaterialTheme.typography.labelMedium,
                            color = if (n > 0) Hud.Negative else Hud.Dim, modifier = Modifier.width(34.dp))
                        Text(
                            c.name, style = MaterialTheme.typography.bodyMedium,
                            color = if (n > 0) Hud.Negative else Hud.Text,
                            textDecoration = if (n > 0 && n == c.quantity) TextDecoration.LineThrough else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(c.type.label.take(3).uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Electric)
                    }
                }
                if (filter.isBlank() && base.cards.size > 40) {
                    Text("Search to find more cards.", style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                HudOutlineButton("Cancel", onCancel, color = Hud.Muted)
                HudButton(
                    "Save variant",
                    onClick = { onSaved(vm.saveVariant(base, name, removed, added)) },
                    modifier = Modifier.weight(1f),
                    enabled = outCount > 0 || inCount > 0,
                )
            }
        }
    }
}
