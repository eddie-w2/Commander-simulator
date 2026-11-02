package com.eddiew.commandersim.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddiew.commandersim.ui.theme.Hud
import com.eddiew.commandersim.ui.theme.Mono
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Working" animation for running simulations: a scrolling hex data stream on the left, an oscilloscope trace with
 * a sweeping scan beam, and packet blips riding the trace. [progress] (0..1) tints the trace as it fills.
 */
@Composable
fun ScanAnimation(progress: Float, modifier: Modifier = Modifier, height: Dp = 84.dp) {
    val t = rememberInfiniteTransition(label = "scan")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val tick by t.animateFloat(0f, 1000f, infiniteRepeatable(tween(100_000, easing = LinearEasing), RepeatMode.Restart), label = "tick")
    val tm = rememberTextMeasurer()
    val hexStyle = TextStyle(fontFamily = Mono, fontSize = 8.sp, color = Hud.Electric.copy(alpha = 0.55f))
    val hot = TextStyle(fontFamily = Mono, fontSize = 8.sp, color = Hud.Cyan)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        drawRect(Hud.Bg)

        // Grid
        val cell = 12.dp.toPx()
        var x = 0f
        while (x < w) { drawLine(Hud.Grid, Offset(x, 0f), Offset(x, h), 1f); x += cell }
        var y = 0f
        while (y < h) { drawLine(Hud.Grid, Offset(0f, y), Offset(w, y), 1f); y += cell }

        // Hex data stream (left column), changing ~12 times a second.
        val frame = (tick * 12).toInt()
        val colW = 46.dp.toPx()
        val lineH = 10.dp.toPx()
        val rows = (h / lineH).toInt()
        for (r in 0 until rows) {
            val seed = (frame + r * 7919) * 2654435761L
            val word = "%04X %02X".format((seed ushr 8 and 0xFFFF), (seed ushr 24 and 0xFF))
            val style = if ((frame + r) % 9 == 0) hot else hexStyle
            drawText(tm.measure(word, style), topLeft = Offset(4.dp.toPx(), r * lineH + 2f))
        }
        drawLine(Hud.Outline, Offset(colW + 4.dp.toPx(), 0f), Offset(colW + 4.dp.toPx(), h), 1f)

        // Oscilloscope trace
        val left = colW + 10.dp.toPx()
        val span = w - left
        val mid = h / 2
        val trace = Path()
        val steps = 90
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = 2 * PI * (f * 3 + phase)
            val v = (sin(a) * 0.55 + sin(a * 2.7 + 1.3) * 0.25 + sin(a * 7.1) * 0.1).toFloat()
            val px = left + span * f
            val py = mid - v * h * 0.32f
            if (i == 0) trace.moveTo(px, py) else trace.lineTo(px, py)
        }
        val color = androidx.compose.ui.graphics.lerp(Hud.Electric, Hud.Cyan, progress.coerceIn(0f, 1f))
        drawPath(trace, color.copy(alpha = 0.12f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
        drawPath(trace, color.copy(alpha = 0.35f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(trace, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))

        // Packet blips moving along the baseline
        for (k in 0 until 4) {
            val f = ((phase * 1.7f + k / 4f) % 1f)
            val bx = left + span * f
            drawCircle(Hud.Cyan.copy(alpha = 0.25f), 5.dp.toPx(), Offset(bx, mid))
            drawCircle(Hud.Cyan, 1.8.dp.toPx(), Offset(bx, mid))
        }

        // Sweeping scan beam with a fading trail
        val sx = left + span * sweep
        val trail = 60.dp.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Hud.Cyan.copy(alpha = 0.18f)), startX = sx - trail, endX = sx),
            topLeft = Offset(maxOf(left, sx - trail), 0f), size = Size(sx - maxOf(left, sx - trail), h),
        )
        drawLine(Hud.Cyan, Offset(sx, 0f), Offset(sx, h), 1.5.dp.toPx())

        // Corner brackets
        val b = 8.dp.toPx()
        val s = 1.5.dp.toPx()
        listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(w, 0f) to Offset(-1f, 1f), Offset(0f, h) to Offset(1f, -1f), Offset(w, h) to Offset(-1f, -1f))
            .forEach { (o, d) ->
                drawLine(Hud.Electric, o, Offset(o.x + b * d.x, o.y), s)
                drawLine(Hud.Electric, o, Offset(o.x, o.y + b * d.y), s)
            }
    }
}
