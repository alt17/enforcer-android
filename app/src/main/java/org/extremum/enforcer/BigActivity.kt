package org.extremum.enforcer

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Крупное табло: число во весь экран, чтобы читалось с земли или со станции.
 *
 * С одним прибором — ровно как было: одно огромное число и пик под ним.
 * С несколькими экран делится поровну между приборами, каждому своя строка
 * своим цветом. Соединения живут в [Link], поэтому переход сюда и обратно
 * ничего не рвёт.
 */
class BigActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var vLabel: TextView
    private lateinit var vValue: TextView
    private lateinit var vPeak: TextView
    private lateinit var multi: LinearLayout

    private class Row(val name: TextView, val value: TextView, val peak: TextView)

    private val rows = LinkedHashMap<String, Row>()

    private var unit = "kN"
    private var sized = false
    private var sizedFor = -1

    private companion object {
        /** Шрифт строки «имя … пик» — в долях от шрифта самого числа. */
        const val HEAD_PART = 0.30f
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_big)
        Link.init(this)
        unit = Link.getPref("unit", "kN")

        root = findViewById(R.id.bigRoot)
        vLabel = findViewById(R.id.bigLabel)
        vValue = findViewById(R.id.bigValue)
        vPeak = findViewById(R.id.bigPeak)
        multi = findViewById(R.id.bigMulti)

        findViewById<Button>(R.id.bigZero).setOnClickListener { Link.tareAll() }
        findViewById<Button>(R.id.bigPeakReset).setOnClickListener { Link.resetPeaks() }
        findViewById<Button>(R.id.bigBack).setOnClickListener { finish() }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        unit = Link.getPref("unit", "kN")
        sized = false
        sizedFor = -1
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 200)
        }
    }

    private fun refresh() {
        val devs = Link.devices
        if (devs.size > 1) {
            vLabel.visibility = View.GONE
            vValue.visibility = View.GONE
            vPeak.visibility = View.GONE
            multi.visibility = View.VISIBLE
            showMulti(devs)
        } else {
            vLabel.visibility = View.VISIBLE
            vValue.visibility = View.VISIBLE
            vPeak.visibility = View.VISIBLE
            multi.visibility = View.GONE
            if (rows.isNotEmpty()) { multi.removeAllViews(); rows.clear() }
            showSingle(devs.firstOrNull())
        }
    }

    // ------------------------------------------------------------ один прибор
    /**
     * autoSizeTextType появился только в API 26, поэтому размер шрифта считаем
     * сами — по ширине поля и длине самой длинной строки, которую число вообще
     * может принять.
     */
    private fun fitText() {
        if (sized) return
        val w = vValue.width
        val h = vValue.height
        if (w <= 0 || h <= 0) return
        vValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitSize(w, h))
        vPeak.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitSize(w, h) * 0.42f)
        sized = true
    }

    private fun fitSize(w: Int, h: Int): Float {
        val sample = if (unit == "kN") "-88.888" else "-88888"
        val p = Paint()
        p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        var size = 12f
        while (size < 400f) {
            p.textSize = size + 4f
            if (p.measureText(sample) > w * 0.92f || (size + 4f) > h * 0.85f) break
            size += 4f
        }
        return size
    }

    private fun showSingle(d: Device?) {
        fitText()
        val c = d?.latest
        val kn = if (d != null && c != null) d.toKn(c) else null
        vValue.text = when {
            c == null -> getString(R.string.dash)
            kn == null -> "$c"
            else -> fmt(kn)
        }
        if (d != null) vValue.setTextColor(d.color)
        vLabel.text = buildString {
            append(d?.label ?: "прибор не подключён")
            append("   ")
            append(if (c != null && kn == null) "отсчёты" else unit)
            if (Link.running) append(if (Link.paused) "   пауза" else "   запись")
            if (d?.calFromCache == true) append("   ⚠ калибровка не из прибора")
        }
        vPeak.text = d?.peak?.let { "пик " + fmt(it) } ?: ""
        if (d != null) vPeak.setTextColor(d.color)
    }

    // --------------------------------------------------------- несколько
    private fun showMulti(devs: List<Device>) {
        if (devs.map { it.address } != rows.keys.toList()) {
            multi.removeAllViews()
            rows.clear()
            for (d in devs) rows[d.address] = buildRow(d)
            sizedFor = -1
        }
        if (sizedFor != devs.size && multi.height > 0 && multi.width > 0) {
            // Экран делится поровну: чем больше приборов, тем мельче цифры,
            // но читать всё равно надо издалека.
            //
            // Доля прибора делится на две части: узкая строка с именем и пиком
            // сверху и число под ней. Размер числа считается по ОСТАВШЕЙСЯ
            // высоте и по полной ширине — раньше он брался по полной ширине,
            // а рисовался в остатке справа от имени, и «-12.345» переносилось
            // на вторую строку.
            // Шапка меряется от размера ЧИСЛА, а не от высоты доли прибора:
            // доля большая, и шапка в её долях выходила вдвое крупнее нужного,
            // отжимая значение пика за край. Два прохода: прикинули число без
            // шапки, вычли её высоту, пересчитали. Больше не нужно — когда
            // размер упирается в ширину, а он упирается почти всегда, второй
            // проход уже ничего не меняет.
            val tile = multi.height / devs.size
            var size = fitSize(multi.width, tile)
            repeat(2) {
                val head = size * HEAD_PART * 1.35f      // 1.35 — интерлиньяж
                size = fitSize(multi.width, (tile - head).toInt())
            }
            for (r in rows.values) {
                r.value.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                r.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * HEAD_PART)
                r.peak.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * HEAD_PART)
            }
            sizedFor = devs.size
        }
        for (d in devs) {
            val r = rows[d.address] ?: continue
            val c = d.latest
            val kn = if (c != null) d.toKn(c) else null
            r.value.text = when {
                c == null -> getString(R.string.dash)
                kn == null -> "$c"
                else -> fmt(kn)
            }
            r.name.text = d.label + "   " + (if (c != null && kn == null) "отсчёты" else unit)
            r.peak.text = d.peak?.let { "пик " + fmt(it) } ?: ""
        }
    }

    /**
     * Строка прибора: сверху узкая шапка «имя … пик», под ней число во всю
     * ширину. Число стояло справа от шапки и не помещалось: «-12.345» — это
     * семь знаков вместе с минусом и точкой.
     *
     * Выравнивание числа по правому краю оставлено намеренно: у моноширинного
     * шрифта запятые разных приборов встают друг под другом, и столбик
     * читается одним взглядом.
     */
    private fun buildRow(d: Device): Row {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.BOTTOM
        val name = TextView(this)
        name.setTextColor(d.color)
        name.maxLines = 1
        val peak = TextView(this)
        peak.setTextColor(d.color)
        peak.typeface = Typeface.MONOSPACE
        peak.maxLines = 1
        peak.gravity = Gravity.END
        // Растягивается ИМЯ, а пик занимает столько, сколько ему нужно:
        // если шапка не влезает, обрезать надо хвост имени, а не число.
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        head.addView(name, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(peak, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val value = TextView(this)
        value.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        value.setTextColor(d.color)
        value.gravity = Gravity.END
        value.includeFontPadding = false
        // на всякий случай: перенос числа лучше обрезать, чем городить
        // вторую строку и ломать разбивку экрана поровну
        value.maxLines = 1

        box.addView(head, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(value, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        multi.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return Row(name, value, peak)
    }

    private fun fmt(kn: Double): String =
        if (unit == "kN") String.format(Locale.US, "%.3f", kn)
        else String.format(Locale.US, "%.0f", kn * 1000.0 / Link.G)
}
