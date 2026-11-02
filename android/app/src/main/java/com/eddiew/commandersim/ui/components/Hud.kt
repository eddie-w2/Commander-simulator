package com.eddiew.commandersim.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono

/** Faint blueprint grid with a soft electric glow at the top of the screen. */
fun Modifier.gridBackground(cell: Dp = 24.dp): Modifier = this.drawBehind {
    drawRect(Hud.Bg)
    drawRect(
        Brush.radialGradient(
            colors = listOf(Hud.Electric.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.5f, 0f),
            radius = size.width * 0.9f,
        ),
    )
    val step = cell.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(Hud.Grid.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(Hud.Grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

/** Corner brackets like a targeting reticle. */
fun DrawScope.cornerBrackets(color: Color, len: Float, stroke: Float) {
    val w = size.width
    val h = size.height
    val s = stroke / 2
    // top-left
    drawLine(color, Offset(s, s), Offset(len, s), stroke, StrokeCap.Square)
    drawLine(color, Offset(s, s), Offset(s, len), stroke, StrokeCap.Square)
    // top-right
    drawLine(color, Offset(w - s, s), Offset(w - len, s), stroke, StrokeCap.Square)
    drawLine(color, Offset(w - s, s), Offset(w - s, len), stroke, StrokeCap.Square)
    // bottom-left
    drawLine(color, Offset(s, h - s), Offset(len, h - s), stroke, StrokeCap.Square)
    drawLine(color, Offset(s, h - s), Offset(s, h - len), stroke, StrokeCap.Square)
    // bottom-right
    drawLine(color, Offset(w - s, h - s), Offset(w - len, h - s), stroke, StrokeCap.Square)
    drawLine(color, Offset(w - s, h - s), Offset(w - s, h - len), stroke, StrokeCap.Square)
}

/** The basic container: dark panel, thin outline, glowing corner brackets, mono title. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = Hud.Electric,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Hud.Surface.copy(alpha = 0.92f))
            .border(1.dp, Hud.Outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { cornerBrackets(accent, 10.dp.toPx(), 2.dp.toPx()) }
            .padding(14.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(width = 3.dp, height = 12.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Hud.Muted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                trailing()
            }
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** Turns off looping decorative animations (used by screenshot tests, which wait for the UI to go idle). */
val LocalHudMotion = staticCompositionLocalOf { true }

@Composable
private fun scanSweep(): Float {
    if (!LocalHudMotion.current) return 0.55f
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    return sweep
}

/** Screen header with an animated scan line sweeping under the title. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val sweep = scanSweep()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "// COMMANDER SIM",
                    style = MaterialTheme.typography.labelSmall,
                    color = Hud.Electric,
                )
                Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Hud.Text)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Hud.Muted)
            }
            trailing()
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(2.dp).drawBehind {
                drawRect(Hud.Outline)
                val w = size.width * 0.25f
                val x = (size.width + w) * sweep - w
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Hud.Cyan, Hud.Electric, Color.Transparent),
                        startX = x, endX = x + w,
                    ),
                    topLeft = Offset(x, 0f), size = Size(w, size.height),
                )
            },
        )
    }
}

/** Big mono number with a small label. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    accent: Color = Hud.Electric,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier
            .clip(shape)
            .background(Hud.SurfaceHi)
            .border(1.dp, if (onClick != null) accent.copy(alpha = 0.45f) else Hud.Outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { drawRect(accent, size = Size(size.width, 2.dp.toPx())) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onClick != null) Text("›", color = accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Hud.Text, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
    }
}

/** One stat tile; [onClick] makes it open a detail screen. */
data class Tile(val label: String, val value: String, val sub: String? = null, val onClick: (() -> Unit)? = null)

/** A row of stat tiles that share the width evenly. */
@Composable
fun TileRow(vararg tiles: Triple<String, String, String?>, accent: Color = Hud.Electric) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (label, value, sub) -> StatTile(label, value, Modifier.weight(1f), sub, accent) }
    }
}

/** A row of tappable stat tiles. */
@Composable
fun TileRow(vararg tiles: Tile, accent: Color = Hud.Electric) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { t -> StatTile(t.label, t.value, Modifier.weight(1f), t.sub, accent, t.onClick) }
    }
}

/** "VIEW DETAIL ›" link for a panel's trailing slot. */
@Composable
fun DetailLink(onClick: () -> Unit, text: String = "Detail") {
    Text(
        "${text.uppercase()} ›",
        style = MaterialTheme.typography.labelSmall,
        color = Hud.Electric,
        modifier = Modifier.clip(RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Proportional segments in one bar, e.g. a colour breakdown. */
@Composable
fun StackedBar(segments: List<Pair<Color, Double>>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val total = segments.sumOf { it.second }
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(2.dp)).background(Hud.Grid)) {
        if (total > 0) segments.filter { it.second > 0 }.forEach { (c, v) ->
            Box(Modifier.weight((v / total).toFloat()).height(height).background(c))
        }
    }
}

/** Thin glowing progress/value bar. */
@Composable
fun NeonBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Hud.Electric, height: Dp = 6.dp) {
    val anim by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "bar")
    Box(
        modifier.fillMaxWidth().height(height).drawBehind {
            drawRect(Hud.Grid)
            val w = size.width * anim
            drawRect(color.copy(alpha = 0.25f), topLeft = Offset(0f, -2f), size = Size(w, size.height + 4f))
            drawRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color)), size = Size(w, size.height))
        },
    )
}

/** Selectable mono chip. */
@Composable
fun HudChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Hud.Electric) {
    val shape = RoundedCornerShape(3.dp)
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Hud.Bg else Hud.Muted,
        modifier = modifier
            .clip(shape)
            .background(if (selected) color else Hud.SurfaceHi)
            .border(1.dp, if (selected) color else Hud.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** Primary action button with a glow. */
@Composable
fun HudButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Hud.Electric) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .drawBehind {
                if (enabled) drawRect(color.copy(alpha = 0.18f), topLeft = Offset(-4f, -4f), size = Size(size.width + 8f, size.height + 8f))
            }
            .clip(shape)
            .background(if (enabled) color else Hud.SurfaceHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (enabled) Hud.Bg else Hud.Dim)
    }
}

@Composable
fun HudOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Hud.Electric) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.clip(shape).border(1.dp, color, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Mana colour identity as small glowing dots. */
@Composable
fun ColorPips(identity: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        identity.forEach { c ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(Hud.manaColor(c)).border(1.dp, Hud.Bg, CircleShape))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Hud.Electric,
        modifier = modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

/** Small amber banner for caveats (sample data, warnings). */
@Composable
fun Notice(text: String, color: Color = Hud.Warn, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.5f), shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▲", color = color, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Hud.Text)
    }
}

fun pct(x: Double?, digits: Int = 1): String = if (x == null) "—" else "%.${digits}f%%".format(x * 100)
fun num(x: Double?, digits: Int = 1): String = if (x == null) "—" else "%.${digits}f".format(x)
fun signedPct(x: Double?): String = if (x == null) "—" else (if (x >= 0) "+" else "") + "%.1f".format(x * 100) + "pp"
