package org.extremum.enforcer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * График усилия. Своя отрисовка, а не библиотека: одна кривая, никаких
 * зависимостей и одинаковое поведение от Android 5 до последней версии.
 */
class PlotView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    View(ctx, attrs) {

    /** Одна кривая: своя ось значений общая со всеми, свой цвет и имя. */
    class Series(val xs: FloatArray, val ys: FloatArray, val color: Int, val name: String)

    private var series: List<Series> = emptyList()
    private var unit = "kN"
    private var note = ""
    private var noteColor = 0
    private var emptyText = "данных нет"

    private val path = Path()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val pBg = Paint().apply { color = ContextCompat.getColor(ctx, R.color.plot_bg) }
    private val pGrid = Paint().apply {
        color = ContextCompat.getColor(ctx, R.color.plot_grid)
        strokeWidth = dp(1f)
    }
    private val pAxis = Paint().apply {
        color = ContextCompat.getColor(ctx, R.color.plot_axis)
        strokeWidth = dp(1f)
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.plot_axis)
        textSize = dp(10f)
    }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.device)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pNote = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val pPeak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.peak)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    fun setData(list: List<Series>, unitName: String) {
        series = list
        unit = unitName
        invalidate()
    }

    /** Что писать посреди пустого поля, когда записи ещё нет. */
    fun setEmptyText(t: String) {
        if (t == emptyText) return
        emptyText = t
        invalidate()
    }

    /** Подпись в углу поля: идёт запись, пауза или запись остановлена. */
    fun setNote(text: String, color: Int) {
        if (text == note && color == noteColor) return
        note = text
        noteColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, pBg)

        val left = dp(46f)
        val right = w - dp(6f)
        val top = dp(8f)
        val bottom = h - dp(18f)
        if (right <= left || bottom <= top) return

        canvas.drawLine(left, bottom, right, bottom, pAxis)
        canvas.drawLine(left, top, left, bottom, pAxis)

        if (note.isNotEmpty()) {
            pNote.color = noteColor
            canvas.drawText(note, left + dp(6f), top + dp(12f), pNote)
        }

        val live = series.filter { it.xs.isNotEmpty() }
        if (live.isEmpty()) {
            pText.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, (left + right) / 2f, (top + bottom) / 2f, pText)
            pText.textAlign = Paint.Align.LEFT
            return
        }

        // Все приборы в одних осях: сравнивать усилия имеет смысл только
        // в общей шкале, и ось времени у записи одна на всех.
        var yMin = live[0].ys[0]; var yMax = live[0].ys[0]
        for (s in live) for (v in s.ys) { if (v < yMin) yMin = v; if (v > yMax) yMax = v }
        // Ноль в кадре всегда: иначе шум нуля растягивается на весь экран
        // и выглядит как рабочая нагрузка.
        if (yMin > 0f) yMin = 0f
        if (yMax < 0f) yMax = 0f
        if (abs(yMax - yMin) < 1e-6f) { yMax += 1f; yMin -= 1f }
        val padY = (yMax - yMin) * 0.06f
        yMin -= padY; yMax += padY

        var xMin = live[0].xs[0]
        var xMax = live[0].xs[live[0].xs.size - 1]
        for (s in live) {
            if (s.xs[0] < xMin) xMin = s.xs[0]
            val last = s.xs[s.xs.size - 1]
            if (last > xMax) xMax = last
        }
        if (xMax - xMin < 1e-6f) xMax = xMin + 1f

        val sx = (right - left) / (xMax - xMin)
        val sy = (bottom - top) / (yMax - yMin)
        fun px(x: Float) = left + (x - xMin) * sx
        fun py(y: Float) = bottom - (y - yMin) * sy

        // сетка по вертикали — значения усилия
        val stepY = niceStep(yMax - yMin, 4)
        var g = floor(yMin / stepY) * stepY
        pText.textAlign = Paint.Align.RIGHT
        while (g <= yMax) {
            val y = py(g)
            if (y in top..bottom) {
                canvas.drawLine(left, y, right, y, pGrid)
                canvas.drawText(fmt(g), left - dp(4f), y + dp(3.5f), pText)
            }
            g += stepY
        }
        pText.textAlign = Paint.Align.LEFT

        // сетка по горизонтали — время
        val stepX = niceStep(xMax - xMin, 4)
        var t = floor(xMin / stepX) * stepX
        pText.textAlign = Paint.Align.CENTER
        while (t <= xMax) {
            val x = px(t)
            if (x in left..right) {
                canvas.drawLine(x, top, x, bottom, pGrid)
                canvas.drawText(fmtTime(t), x, h - dp(4f), pText)
            }
            t += stepX
        }

        // Единицы — в левом нижнем углу, у начала обеих осей. С несколькими
        // приборами это единственное место на графике, где они вообще есть:
        // подписи кривых заняты именами.
        pText.textAlign = Paint.Align.RIGHT
        canvas.drawText(unit, left - dp(4f), h - dp(4f), pText)
        pText.textAlign = Paint.Align.LEFT

        canvas.drawLine(left, py(0f), right, py(0f), pAxis)

        // кривые
        val target = max(64, ((right - left) / dp(1f)).toInt())
        for (s in live) {
            val (dx, dy) = decimate(s.xs, s.ys, target)
            path.reset()
            for (i in dx.indices) {
                val x = px(dx[i]); val y = py(dy[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            pLine.color = s.color
            canvas.drawPath(path, pLine)
        }

        // отметка пика — только когда прибор один: при нескольких линий
        // на поле и так хватает, а общий пик ни о чём не говорит
        if (live.size == 1) {
            var peak = live[0].ys[0]
            for (v in live[0].ys) if (v > peak) peak = v
            val yp = py(peak)
            pPeak.color = live[0].color
            if (yp in top..bottom) canvas.drawLine(left, yp, right, yp, pPeak)
        } else {
            // подписи кривых столбиком в правом верхнем углу
            pText.textAlign = Paint.Align.RIGHT
            var ly = top + dp(12f)
            for (s in live) {
                pText.color = s.color
                canvas.drawText(s.name, right - dp(4f), ly, pText)
                ly += dp(13f)
            }
            pText.color = ContextCompat.getColor(context, R.color.plot_axis)
            pText.textAlign = Paint.Align.LEFT
        }
    }

    private fun fmt(v: Float): String =
        if (unit == "kN") String.format(Locale.US, "%.2f", v)
        else String.format(Locale.US, "%.0f", v)

    private fun fmtTime(t: Float): String =
        if (t < 100f) String.format(Locale.US, "%.1f", t) else String.format(Locale.US, "%.0f", t)

    private fun niceStep(range: Float, want: Int): Float {
        if (range <= 0f) return 1f
        val raw = (range / want).toDouble()
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        val f = when {
            n <= 1.0 -> 1.0
            n <= 2.0 -> 2.0
            n <= 5.0 -> 5.0
            else -> 10.0
        }
        return (f * mag).toFloat()
    }

    /**
     * Прореживание с сохранением пиков: в каждом окне берём минимум и максимум,
     * в том порядке, в каком они встретились. Взятие каждого n-го отсчёта
     * срезало бы вершины рывков — а именно они и нужны.
     */
    private fun decimate(x: FloatArray, y: FloatArray, target: Int): Pair<FloatArray, FloatArray> {
        val n = minOf(x.size, y.size)
        if (n <= target * 2) return Pair(x, y)
        val step = n.toDouble() / target
        val ox = ArrayList<Float>(target * 2)
        val oy = ArrayList<Float>(target * 2)
        for (i in 0 until target) {
            val a = (i * step).toInt()
            val b = maxOf(a + 1, ((i + 1) * step).toInt()).coerceAtMost(n)
            if (a >= n) break
            var iLo = a; var iHi = a
            for (j in a until b) {
                if (y[j] < y[iLo]) iLo = j
                if (y[j] > y[iHi]) iHi = j
            }
            if (iLo <= iHi) {
                ox.add(x[iLo]); oy.add(y[iLo]); ox.add(x[iHi]); oy.add(y[iHi])
            } else {
                ox.add(x[iHi]); oy.add(y[iHi]); ox.add(x[iLo]); oy.add(y[iLo])
            }
        }
        return Pair(ox.toFloatArray(), oy.toFloatArray())
    }
}
