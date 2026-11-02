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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddiew.commandersim.data.model.AbComparison
import com.eddiew.commandersim.data.model.CardStat
import com.eddiew.commandersim.data.model.MetricDelta
import com.eddiew.commandersim.data.model.SimulationReport
import com.eddiew.commandersim.ui.components.HudOutlineButton
import com.eddiew.commandersim.ui.components.HudPanel
import com.eddiew.commandersim.ui.components.NeonBar
import com.eddiew.commandersim.ui.components.num
import com.eddiew.commandersim.ui.components.pct
import com.eddiew.commandersim.ui.components.signedPct
import com.eddiew.commandersim.ui.components.cardPress
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

internal fun LazyListScope.abSection(r: SimulationReport, ab: AbComparison, onOpenDeck: (String) -> Unit) {
    val a = r.deck(ab.aId) ?: return
    val b = r.deck(ab.bId) ?: return

    item {
        HudPanel(title = "A/B verdict", accent = if (!ab.significant) Hud.Warn else if (ab.diff > 0) Hud.Positive else Hud.Negative) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SideHead("A", a.name, ab.aRate, Hud.Electric, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                    Text(signedPct(ab.diff), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = if (!ab.significant) Hud.Warn else if (ab.diff > 0) Hud.Positive else Hud.Negative)
                    Text("B VS A", style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
                }
                SideHead("B", b.name, ab.bRate, Hud.Cyan, Modifier.weight(1f), alignEnd = true)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    ab.significant && ab.diff > 0 -> "The swap helps: B wins ${"%.1f".format(ab.diff * 100)} points more often " +
                        "(95% CI ${signedPct(ab.diffLow)} to ${signedPct(ab.diffHigh)})."
                    ab.significant -> "The swap hurts: B wins ${"%.1f".format(-ab.diff * 100)} points less often " +
                        "(95% CI ${signedPct(ab.diffLow)} to ${signedPct(ab.diffHigh)})."
                    else -> "No clear difference yet (95% CI ${signedPct(ab.diffLow)} to ${signedPct(ab.diffHigh)}). " +
                        "Run more games, or the swap really doesn't matter much."
                },
                style = MaterialTheme.typography.bodySmall, color = Hud.Ice,
            )
        }
    }
    item {
        HudPanel(title = "Games the swap changed · same seed, different result") {
            val changed = (ab.onlyA + ab.onlyB).coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A only ${ab.onlyA}", style = MaterialTheme.typography.labelMedium, color = Hud.Electric, modifier = Modifier.width(80.dp))
                Row(Modifier.weight(1f).height(10.dp)) {
                    Box(Modifier.weight(ab.onlyA.coerceAtLeast(1).toFloat()).height(10.dp).background(Hud.Electric))
                    Box(Modifier.weight(ab.onlyB.coerceAtLeast(1).toFloat()).height(10.dp).background(Hud.Cyan))
                }
                Text("${ab.onlyB} B only", style = MaterialTheme.typography.labelMedium, color = Hud.Cyan,
                    modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Out of ${ab.games} paired games, ${ab.games - changed.coerceAtMost(ab.games)} ended the same way for both versions. " +
                    "These ${ab.onlyA + ab.onlyB} are the games the swap decided.",
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
            )
        }
    }
    item {
        HudPanel(title = "Swapped cards") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("OUT · IN A", style = MaterialTheme.typography.labelSmall, color = Hud.Negative)
                    Spacer(Modifier.height(4.dp))
                    ab.cardsOut.forEach { SwapCard(it, a.card(it.substringAfter(" ").takeIf { n -> a.card(n) != null } ?: it)) }
                    if (ab.cardsOut.isEmpty()) Text("—", color = Hud.Dim)
                }
                Column(Modifier.weight(1f)) {
                    Text("IN · IN B", style = MaterialTheme.typography.labelSmall, color = Hud.Positive)
                    Spacer(Modifier.height(4.dp))
                    ab.cardsIn.forEach { SwapCard(it, b.card(it.substringAfter(" ").takeIf { n -> b.card(n) != null } ?: it)) }
                    if (ab.cardsIn.isEmpty()) Text("—", color = Hud.Dim)
                }
            }
        }
    }
    ab.metrics.groupBy { it.group }.forEach { (group, rows) ->
        item {
            HudPanel(title = group) {
                Row {
                    Spacer(Modifier.weight(1f))
                    Text("A", style = MaterialTheme.typography.labelSmall, color = Hud.Electric, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                    Text("B", style = MaterialTheme.typography.labelSmall, color = Hud.Cyan, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                    Text("Δ", style = MaterialTheme.typography.labelSmall, color = Hud.Muted, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
                }
                rows.forEach { MetricRow(it) }
            }
        }
    }
    if (ab.perOpponent.isNotEmpty()) {
        item {
            HudPanel(title = "Win rate by opponent at the table") {
                ab.perOpponent.forEach { o ->
                    Text("${r.name(o.deckId)} · ${o.games} games", style = MaterialTheme.typography.bodySmall, color = Hud.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A", style = MaterialTheme.typography.labelSmall, color = Hud.Electric, modifier = Modifier.width(16.dp))
                        NeonBar((o.aRate / 0.6).toFloat(), Modifier.weight(1f), Hud.Electric, 5.dp)
                        Text(pct(o.aRate, 0), style = MaterialTheme.typography.labelSmall, color = Hud.Ice, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("B", style = MaterialTheme.typography.labelSmall, color = Hud.Cyan, modifier = Modifier.width(16.dp))
                        NeonBar((o.bRate / 0.6).toFloat(), Modifier.weight(1f), Hud.Cyan, 5.dp)
                        Text(pct(o.bRate, 0), style = MaterialTheme.typography.labelSmall, color = Hud.Ice, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudOutlineButton("Full report A", { onOpenDeck(a.deckId) }, Modifier.weight(1f))
            HudOutlineButton("Full report B", { onOpenDeck(b.deckId) }, Modifier.weight(1f), color = Hud.Cyan)
        }
    }
}

@Composable
private fun SideHead(side: String, name: String, rate: Double, color: Color, modifier: Modifier, alignEnd: Boolean = false) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(side, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color)
        Text(name, style = MaterialTheme.typography.bodySmall, color = Hud.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start)
        Text(pct(rate), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = color)
    }
}

@Composable
private fun SwapCard(label: String, stat: CardStat?) {
    Column(Modifier.cardPress(stat?.name ?: label.replace(Regex("^\\d+ "), "")).padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Hud.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (stat != null) {
            Text(
                "played ${pct(stat.castRate, 0)} · impact ${signedPct(stat.impact)}" +
                    (if (stat.damagePerGame > 0.05) " · ${num(stat.damagePerGame, 1)} dmg" else "") +
                    (if (stat.removalsPerGame > 0.01) " · ${num(stat.removalsPerGame, 2)} rem" else ""),
                style = MaterialTheme.typography.labelSmall, color = Hud.Dim,
            )
        }
    }
}

@Composable
private fun MetricRow(m: MetricDelta) {
    val d = m.b - m.a
    val good = if (m.higherIsBetter) d > 0 else d < 0
    val scale = maxOf(kotlin.math.abs(m.a), kotlin.math.abs(m.b), 1e-9)
    val notable = kotlin.math.abs(d) / scale > 0.02
    val color = when {
        !notable -> Hud.Muted
        good -> Hud.Positive
        else -> Hud.Negative
    }
    fun f(x: Double) = if (m.percent) pct(x, 1) else num(x, if (kotlin.math.abs(x) >= 10) 1 else 2)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f), maxLines = 2)
        Text(f(m.a), style = MaterialTheme.typography.labelMedium, color = Hud.Ice, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Text(f(m.b), style = MaterialTheme.typography.labelMedium, color = Hud.Ice, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Text(
            (if (d >= 0) "+" else "") + if (m.percent) "%.1f".format(d * 100) else num(d, if (kotlin.math.abs(d) >= 10) 1 else 2),
            style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.width(64.dp), textAlign = TextAlign.End,
        )
    }
}
