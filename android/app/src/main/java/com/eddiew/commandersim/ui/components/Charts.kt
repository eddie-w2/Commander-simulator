package com.eddiew.commandersim.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** 0→1 entrance animation shared by all charts. */
@Composable
private fun rememberReveal(key: Any?): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    return a.value
}

/** Draws a line three times (wide/faint → thin/bright) for a neon glow. */
private fun DrawScope.glowPath(path: Path, color: Color, width: Float) {
    drawPath(path, color.copy(alpha = 0.12f), style = Stroke(width * 4, cap = StrokeCap.Round))
    drawPath(path, color.copy(alpha = 0.30f), style = Stroke(width * 2, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

private val axisStyle = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Hud.Muted)

data class Series(val name: String, val values: List<Double>, val color: Color)

/**
 * Multi-series line chart over turns (x = 1..n). Values are clipped to the last turn with data.
 */
@Composable
fun LineChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    yFormat: (Double) -> String = { num(it, 1) },
    xLabel: String = "TURN",
    fixedMax: Double? = null,
    baseline: Double? = null,
) {
    val reveal = rememberReveal(series)
    val tm = rememberTextMeasurer()
    val points = series.maxOfOrNull { s -> s.values.indexOfLast { it > 0.0001 } + 1 }?.coerceAtLeast(2) ?: 2
    val maxV = fixedMax ?: (series.flatMap { it.values.take(points) }.maxOrNull() ?: 1.0).let { if (it <= 0) 1.0 else it * 1.1 }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val left = 34.dp.toPx()
            val bottom = 16.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom
            // grid + y labels
            for (i in 0..4) {
                val y = h - h * i / 4f
                drawLine(Hud.Grid, Offset(left, y), Offset(size.width, y), 1f)
                val label = tm.measure(yFormat(maxV * i / 4), axisStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 4.dp.toPx(), y - label.size.height / 2f))
            }
            // x labels
            val stepX = w / (points - 1)
            for (i in 0 until points) {
                if (points > 10 && i % 2 == 1) continue
                val x = left + stepX * i
                val label = tm.measure("${i + 1}", axisStyle)
                drawText(label, topLeft = Offset(x - label.size.width / 2f, h + 3.dp.toPx()))
            }
            baseline?.let {
                val y = h - (it / maxV).toFloat() * h
                drawLine(Hud.Warn.copy(alpha = 0.7f), Offset(left, y), Offset(size.width, y), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
            for (s in series) {
                val path = Path()
                val fill = Path()
                val vals = s.values.take(points)
                vals.forEachIndexed { i, v ->
                    val x = left + stepX * i
                    val y = h - (v / maxV).toFloat().coerceIn(0f, 1f) * h * reveal
                    if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
                }
                fill.lineTo(left + stepX * (vals.size - 1), h)
                fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.22f), Color.Transparent), startY = 0f, endY = h))
                glowPath(path, s.color, 2.dp.toPx())
                vals.forEachIndexed { i, v ->
                    val x = left + stepX * i
                    val y = h - (v / maxV).toFloat().coerceIn(0f, 1f) * h * reveal
                    drawCircle(Hud.Bg, 3.dp.toPx(), Offset(x, y))
                    drawCircle(s.color, 2.dp.toPx(), Offset(x, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(xLabel, style = MaterialTheme.typography.labelSmall, color = Hud.Dim)
            if (series.size > 1) series.forEach { Legend(it.name, it.color) }
        }
    }
}

@Composable
fun Legend(name: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(5.dp))
        Text(name.uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.Muted)
    }
}

/** Vertical bars (histograms, per-seat rates). */
@Composable
fun ColumnChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    color: Color = Hud.Electric,
    valueFormat: (Double) -> String = { num(it, 0) },
    highlightMax: Boolean = true,
    baseline: Double? = null,
) {
    val reveal = rememberReveal(values)
    val tm = rememberTextMeasurer()
    val maxV = max(values.maxOrNull() ?: 1.0, baseline ?: 0.0).let { if (it <= 0) 1.0 else it }
    val maxIdx = values.indices.maxByOrNull { values[it] } ?: -1
    Canvas(modifier.fillMaxWidth().height(height)) {
        val bottom = 16.dp.toPx()
        val top = 14.dp.toPx()
        val h = size.height - bottom - top
        val slot = size.width / values.size.coerceAtLeast(1)
        val barW = slot * 0.62f
        drawLine(Hud.Outline, Offset(0f, top + h), Offset(size.width, top + h), 1f)
        values.forEachIndexed { i, v ->
            val bh = (v / maxV).toFloat() * h * reveal
            val x = slot * i + (slot - barW) / 2
            val c = if (highlightMax && i == maxIdx) Hud.Cyan else color
            drawRect(c.copy(alpha = 0.18f), Offset(x - 3f, top + h - bh - 3f), Size(barW + 6f, bh + 3f))
            drawRect(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.45f)), startY = top + h - bh, endY = top + h), Offset(x, top + h - bh), Size(barW, bh))
            val lab = tm.measure(labels.getOrElse(i) { "" }, axisStyle)
            drawText(lab, topLeft = Offset(x + barW / 2 - lab.size.width / 2f, top + h + 3.dp.toPx()))
            if (v > 0 && values.size <= 16) {
                val vl = tm.measure(valueFormat(v), axisStyle.copy(color = Hud.Text))
                drawText(vl, topLeft = Offset(x + barW / 2 - vl.size.width / 2f, top + h - bh - vl.size.height - 1f))
            }
        }
        baseline?.let {
            val y = top + h - (it / maxV).toFloat() * h
            drawLine(Hud.Warn, Offset(0f, y), Offset(size.width, y), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
    }
}

/** Horizontal labelled bars; good for rankings and "by card" lists. */
@Composable
fun BarList(
    items: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    color: Color = Hud.Electric,
    valueFormat: (Double) -> String = { num(it, 1) },
    maxValue: Double? = null,
) {
    val maxV = maxValue ?: (items.maxOfOrNull { it.second } ?: 1.0).let { if (it <= 0) 1.0 else it }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { i, (label, v) ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = Hud.Text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(valueFormat(v), style = MaterialTheme.typography.labelMedium, color = if (i == 0) Hud.Cyan else Hud.Ice)
                }
                Spacer(Modifier.height(3.dp))
                NeonBar((v / maxV).toFloat(), color = if (i == 0) Hud.Cyan else color, height = 5.dp)
            }
        }
        if (items.isEmpty()) Text("No data", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
    }
}

/** Donut with centre label and legend. */
@Composable
fun DonutChart(
    slices: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    centerTop: String = "",
    centerBottom: String = "",
    size: Dp = 128.dp,
) {
    val reveal = rememberReveal(slices)
    val total = slices.sumOf { it.second }.let { if (it <= 0) 1.0 else it }
    val tm = rememberTextMeasurer()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(Hud.Grid, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            var start = -90f
            slices.forEachIndexed { i, (_, v) ->
                val sweep = (v / total * 360f).toFloat() * reveal
                val c = Hud.Series[i % Hud.Series.size]
                drawArc(c.copy(alpha = 0.25f), start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke + 6.dp.toPx()))
                drawArc(c, start + 0.8f, (sweep - 1.6f).coerceAtLeast(0.1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                start += sweep
            }
            val t1 = tm.measure(centerTop, TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Hud.Text))
            val t2 = tm.measure(centerBottom.uppercase(), axisStyle)
            drawText(t1, topLeft = Offset(center.x - t1.size.width / 2f, center.y - t1.size.height + 2f))
            drawText(t2, topLeft = Offset(center.x - t2.size.width / 2f, center.y + 2f))
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            slices.forEachIndexed { i, (name, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Hud.Series[i % Hud.Series.size]))
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, color = Hud.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pct(v / total, 0), style = MaterialTheme.typography.labelMedium, color = Hud.Ice)
                }
            }
            if (slices.isEmpty()) Text("No data", style = MaterialTheme.typography.bodySmall, color = Hud.Dim)
        }
    }
}

/** Win-rate bar with 95% CI whisker and the 25% four-player baseline. */
@Composable
fun WinRateBar(rate: Double, low: Double, high: Double, modifier: Modifier = Modifier, scaleMax: Double = 0.6, highlight: Boolean = false) {
    val reveal = rememberReveal(rate)
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val h = size.height
        val barH = h * 0.5f
        val y0 = (h - barH) / 2
        fun x(v: Double) = (v / scaleMax).toFloat().coerceIn(0f, 1f) * size.width
        drawRect(Hud.Grid, Offset(0f, y0), Size(size.width, barH))
        val c = if (highlight) Hud.Cyan else Hud.Electric
        val w = x(rate) * reveal
        drawRect(c.copy(alpha = 0.22f), Offset(0f, y0 - 3f), Size(w, barH + 6f))
        drawRect(Brush.horizontalGradient(listOf(Hud.ElectricDeep, c)), Offset(0f, y0), Size(w, barH))
        // CI whisker
        val cy = h / 2
        drawLine(Hud.Text.copy(alpha = 0.85f), Offset(x(low), cy), Offset(x(high), cy), 2f)
        drawLine(Hud.Text.copy(alpha = 0.85f), Offset(x(low), cy - 5f), Offset(x(low), cy + 5f), 2f)
        drawLine(Hud.Text.copy(alpha = 0.85f), Offset(x(high), cy - 5f), Offset(x(high), cy + 5f), 2f)
        // baseline 25%
        val bx = x(0.25)
        drawLine(Hud.Warn, Offset(bx, 0f), Offset(bx, h), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    }
}

/** Radar / spider chart for a deck's profile (values 0..1). */
@Composable
fun RadarChart(axes: List<Pair<String, Double>>, modifier: Modifier = Modifier, color: Color = Hud.Electric, size: Dp = 220.dp) {
    val reveal = rememberReveal(axes)
    val tm = rememberTextMeasurer()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2 - 30.dp.toPx()
            val c = center
            val n = axes.size
            fun pt(i: Int, f: Float): Offset {
                val a = -PI / 2 + 2 * PI * i / n
                return Offset(c.x + (cos(a) * r * f).toFloat(), c.y + (sin(a) * r * f).toFloat())
            }
            for (ring in 1..4) {
                val p = Path()
                for (i in 0 until n) { val o = pt(i, ring / 4f); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                p.close()
                drawPath(p, Hud.Grid, style = Stroke(1f))
            }
            for (i in 0 until n) drawLine(Hud.Grid, c, pt(i, 1f), 1f)
            val shape = Path()
            axes.forEachIndexed { i, (_, v) ->
                val o = pt(i, (v.toFloat().coerceIn(0.03f, 1f)) * reveal)
                if (i == 0) shape.moveTo(o.x, o.y) else shape.lineTo(o.x, o.y)
            }
            shape.close()
            drawPath(shape, color.copy(alpha = 0.22f))
            glowPath(shape, color, 2.dp.toPx())
            axes.forEachIndexed { i, (label, v) ->
                val o = pt(i, (v.toFloat().coerceIn(0.03f, 1f)) * reveal)
                drawCircle(Hud.Cyan, 3.dp.toPx(), o)
                val lp = pt(i, 1.18f)
                val t = tm.measure(label.uppercase(), axisStyle)
                drawText(t, topLeft = Offset(lp.x - t.size.width / 2f, lp.y - t.size.height / 2f))
            }
        }
    }
}

/**
 * Matrix of values (e.g. row deck's win rate with column opponent at the table).
 * Cells shade from red (below [mid]) through grey to electric blue (above).
 */
@Composable
fun Heatmap(
    rowLabels: List<String>,
    colLabels: List<String>,
    values: List<List<Double?>>,
    modifier: Modifier = Modifier,
    mid: Double = 0.25,
    span: Double = 0.2,
    format: (Double) -> String = { pct(it, 0) },
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.width(92.dp))
            colLabels.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp), color = Hud.Muted,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        rowLabels.forEachIndexed { r, label ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Hud.Text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(92.dp))
                colLabels.indices.forEach { c ->
                    val v = values.getOrNull(r)?.getOrNull(c)
                    val color = when {
                        v == null -> Hud.SurfaceHi
                        v >= mid -> lerp(Hud.SurfaceHi, Hud.Electric, ((v - mid) / span).toFloat().coerceIn(0f, 1f))
                        else -> lerp(Hud.SurfaceHi, Hud.Negative, ((mid - v) / span).toFloat().coerceIn(0f, 1f))
                    }
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(2.dp)).background(color),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (v == null) "—" else format(v), style = MaterialTheme.typography.labelMedium, color = Hud.Text)
                    }
                }
            }
        }
    }
}

/** Tiny inline trend line. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = Hud.Electric) {
    Box(
        modifier.drawBehind {
            if (values.size < 2) return@drawBehind
            val maxV = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            val step = size.width / (values.size - 1)
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = step * i
                val y = size.height - (v / maxV).toFloat() * size.height
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            glowPath(p, color, 1.5f)
        },
    )
}
