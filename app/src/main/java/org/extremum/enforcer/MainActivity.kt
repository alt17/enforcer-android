package org.extremum.enforcer

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private val UNIT_NAMES = arrayOf("kN", "кгс")
        private val WINDOW_NAMES = arrayOf("всё", "30 с", "60 с", "5 мин")
        private val WINDOW_SECONDS = intArrayOf(0, 30, 60, 300)
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var alarm: Alarm

    private lateinit var vStatus: TextView
    private lateinit var vSingle: LinearLayout
    private lateinit var vDeviceBox: LinearLayout
    private lateinit var vDevice: TextView
    private lateinit var vValue: TextView
    private lateinit var vUnit: TextView
    private lateinit var vPeak: TextView
    private lateinit var vPeakUnit: TextView
    private lateinit var vExtra: TextView
    private lateinit var plot: PlotView
    private lateinit var bFind: Button
    private lateinit var bZero: Button
    private lateinit var bPeak: Button
    private lateinit var bBig: Button
    private lateinit var bStart: Button
    private lateinit var bPause: Button
    private lateinit var bStop: Button
    private lateinit var bSave: Button

    /** Строки списка приборов, по адресу. */
    private val rows = LinkedHashMap<String, Row>()

    private class Row(
        val name: TextView, val value: TextView,
        val peak: TextView, val zero: Button)

    private var unit = "kN"
    private var windowSec = 0
    private var recName = "test"
    private var forceScan = false

    private val REQ_PERMS = 1
    private val REQ_BT = 2

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_main)
        Link.init(this)
        Link.loadSettings()
        alarm = Alarm(this)

        unit = Link.getPref("unit", "kN")
        windowSec = Link.getPref("window", "0").toIntOrNull() ?: 0
        recName = Link.getPref("name", "test")

        // Версия и хеш сборки в заголовке: без них не отличить, какой APK
        // стоит на телефоне, а это уже стоило круга разбора.
        title = "Enforcer ${Link.buildTag()}"

        vStatus = findViewById(R.id.status)
        vSingle = findViewById(R.id.single)
        vDeviceBox = findViewById(R.id.devices)
        vDevice = findViewById(R.id.device)
        vValue = findViewById(R.id.value)
        vUnit = findViewById(R.id.unit)
        vPeak = findViewById(R.id.peak)
        vPeakUnit = findViewById(R.id.peakUnit)
        vExtra = findViewById(R.id.extra)
        plot = findViewById(R.id.plot)
        bFind = findViewById(R.id.find)
        bZero = findViewById(R.id.zero)
        bPeak = findViewById(R.id.peakReset)
        bBig = findViewById(R.id.big)
        bStart = findViewById(R.id.start)
        bPause = findViewById(R.id.pause)
        bStop = findViewById(R.id.stop)
        bSave = findViewById(R.id.save)

        bFind.setOnClickListener {
            if (Link.devices.isNotEmpty()) Link.disconnectAll() else askAndScan()
        }
        bZero.setOnClickListener {
            if (Link.devices.none { it.latest != null }) toast("приборы ещё не дают значений")
            else Link.tareAll()
        }
        bPeak.setOnClickListener { Link.resetPeaks() }
        bBig.setOnClickListener { startActivity(Intent(this, BigActivity::class.java)) }
        bStart.setOnClickListener { Link.startRec() }
        bPause.setOnClickListener { Link.pauseRec() }
        bStop.setOnClickListener { Link.stopRec() }
        bSave.setOnClickListener { doSave() }

        // Тап по подписи единиц переключает kN ↔ кгс, не открывая меню:
        // это единственная настройка, которую трогают посреди испытания.
        val flip = View.OnClickListener { setUnit(if (unit == "kN") "кгс" else "kN") }
        vUnit.setOnClickListener(flip)
        vPeakUnit.setOnClickListener(flip)

        if (Link.devices.isEmpty()) Link.log("готово. «Найти» — поиск приборов")
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        alarm.release()
        // isFinishing отделяет уход из приложения от перехода на «Табло»
        // и сворачивания: там соединения надо сохранить, здесь — отпустить,
        // иначе незакрытые регистрации копятся и Bluetooth перестаёт искать.
        if (isFinishing) Link.releaseAll()
    }

    // ------------------------------------------------------------- обновление
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 200)
        }
    }

    private fun refresh() {
        if (Link.alarmPending) {
            Link.alarmPending = false
            alarm.fire()
        }

        val devs = Link.devices
        val anyLive = devs.any { it.live }
        vStatus.text = Link.lastLog()

        // Один прибор выглядит ровно как раньше; список появляется только
        // когда приборов действительно больше одного.
        if (devs.size > 1) {
            vSingle.visibility = View.GONE
            vDeviceBox.visibility = View.VISIBLE
            syncRows(devs)
        } else {
            vSingle.visibility = View.VISIBLE
            vDeviceBox.visibility = View.GONE
            if (rows.isNotEmpty()) { vDeviceBox.removeAllViews(); rows.clear() }
            showSingle(devs.firstOrNull())
        }

        val n = Link.recordedCount()
        plot.setData(devs.mapNotNull { seriesFor(it) }, unit)
        val note = when {
            Link.paused -> "ПАУЗА"
            Link.running -> "ЗАПИСЬ"
            n > 0 -> "запись остановлена"
            else -> ""
        }
        plot.setNote(note, if (Link.running && !Link.paused)
            ContextCompat.getColor(this, R.color.peak)
        else
            ContextCompat.getColor(this, R.color.muted))
        plot.setEmptyText(if (anyLive) "«Старт» — и пойдёт запись" else "приборы не подключены")

        bFind.text = if (devs.isEmpty()) getString(R.string.btn_find)
                     else getString(R.string.btn_disconnect)
        bFind.isEnabled = !Link.busy
        bZero.isEnabled = devs.any { it.latest != null }
        bPeak.isEnabled = devs.isNotEmpty()
        bStart.isEnabled = anyLive && !Link.running
        bPause.isEnabled = Link.running
        bPause.text = if (Link.paused) getString(R.string.btn_resume) else getString(R.string.btn_pause)
        bStop.isEnabled = Link.running
        bSave.isEnabled = n > 0

        // Экран не гасим, пока идёт запись: на испытании телефон лежит рядом
        // и никто его не трогает.
        if (Link.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun seriesFor(d: Device): PlotView.Series? {
        val src = d.snapshot()
        if (src.isEmpty()) return null
        val last = src[src.size - 1].t
        val from = if (windowSec > 0) last - windowSec else Float.NEGATIVE_INFINITY
        val mul = if (unit == "kN") 1.0 else 1000.0 / Link.G
        val xs = ArrayList<Float>(src.size)
        val ys = ArrayList<Float>(src.size)
        for (s in src) {
            if (s.t < from || s.kn.isNaN()) continue
            xs.add(s.t)
            ys.add((s.kn * mul).toFloat())
        }
        if (xs.isEmpty()) return null
        return PlotView.Series(xs.toFloatArray(), ys.toFloatArray(), d.color, d.label)
    }

    private fun showSingle(d: Device?) {
        vDevice.text = if (d == null || d.serial == null) "" else d.label
        val c = d?.latest
        val kn = if (d != null && c != null) d.toKn(c) else null
        vValue.text = when {
            c == null -> getString(R.string.dash)
            kn == null -> "$c"
            else -> fmt(kn)
        }
        vUnit.text = if (c != null && kn == null) "отсч." else unit
        vPeak.text = d?.peak?.let { fmt(it) } ?: getString(R.string.dash)
        vPeakUnit.text = if (d?.peak == null) "" else unit

        val bits = ArrayList<String>()
        val n = d?.sampleCount() ?: 0
        if (n > 0) bits.add("$n отсч.")
        d?.temp?.let { bits.add(String.format(Locale.US, "%.1f °C", it)) }
        if (d != null && d.calFromCache) bits.add("⚠ калибровка из памяти")
        vExtra.text = bits.joinToString("   ")
    }

    // ------------------------------------------------------- список приборов
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun syncRows(devs: List<Device>) {
        if (devs.map { it.address } != rows.keys.toList()) {
            vDeviceBox.removeAllViews()
            rows.clear()
            for (d in devs) rows[d.address] = buildRow(d)
        }
        for (d in devs) {
            val r = rows[d.address] ?: continue
            val c = d.latest
            val kn = if (c != null) d.toKn(c) else null
            // Единицы стоят при имени, а не при числе: в одноприборном виде
            // для них есть отдельное поле рядом с крупным значением, а здесь
            // столбик чисел должен оставаться ровным, чтобы читаться сразу.
            // Так же сделано на крупном табло.
            r.name.text = d.label + "   " + (if (c != null && kn == null) "отсч." else unit)
            r.name.setTextColor(d.color)
            r.value.text = when {
                c == null -> getString(R.string.dash)
                kn == null -> "$c"
                else -> fmt(kn)
            }
            r.value.setTextColor(d.color)
            r.peak.text = d.peak?.let { "пик " + fmt(it) } ?: ""
            r.zero.isEnabled = d.latest != null
        }
    }

    private fun buildRow(d: Device): Row {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL

        val name = TextView(this)
        name.textSize = 13f
        name.setTextColor(d.color)

        val value = TextView(this)
        value.typeface = Typeface.MONOSPACE
        value.textSize = 20f
        value.gravity = Gravity.END
        value.setTextColor(d.color)

        val peak = TextView(this)
        peak.textSize = 11f
        peak.setTextColor(ContextCompat.getColor(this, R.color.peak))
        peak.gravity = Gravity.END

        val zero = smallButton("ноль") { d.tareNow() }
        val reset = smallButton("пик") { d.resetPeak() }

        box.addView(name, LinearLayout.LayoutParams(dp(64f), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(peak, LinearLayout.LayoutParams(dp(72f), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(zero, LinearLayout.LayoutParams(dp(50f), dp(38f)))
        box.addView(reset, LinearLayout.LayoutParams(dp(42f), dp(38f)))
        vDeviceBox.addView(box)
        return Row(name, value, peak, zero)
    }

    private fun smallButton(text: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 11f
        b.isAllCaps = false
        b.minWidth = 0
        b.minHeight = 0
        b.setPadding(dp(2f), 0, dp(2f), 0)
        b.setOnClickListener { action() }
        return b
    }

    private fun fmt(kn: Double): String =
        if (unit == "kN") String.format(Locale.US, "%.3f", kn)
        else String.format(Locale.US, "%.0f", kn * 1000.0 / Link.G)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------ разрешения и поиск
    private fun neededPerms(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    private fun havePerms(): Boolean = neededPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun askAndScan() {
        if (!Link.bluetoothSupported()) { toast("в телефоне нет Bluetooth"); return }
        if (!havePerms()) {
            ActivityCompat.requestPermissions(this, neededPerms(), REQ_PERMS)
            return
        }
        if (!Link.bluetoothReady()) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
            return
        }
        // На Android 6…11 скан BLE считается определением местоположения:
        // без включённой геолокации он молча не находит ничего.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.R && !locationOn()) {
            AlertDialog.Builder(this)
                .setTitle("Нужна геолокация")
                .setMessage("На этой версии Андроида поиск Bluetooth-приборов работает " +
                        "только при включённой службе геопозиции. Включить её в настройках?")
                .setPositiveButton("Настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Всё равно искать") { _, _ -> startFind() }
                .show()
            return
        }
        startFind()
    }

    private fun startFind() {
        val force = forceScan
        forceScan = false
        Link.find(force) { msg -> if (Link.devices.isEmpty()) toast(msg) }
    }

    private fun locationOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != REQ_PERMS) return
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) askAndScan()
        else toast("без этих разрешений приборы не найти")
    }

    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == REQ_BT) {
            if (Link.bluetoothReady()) askAndScan() else toast("Bluetooth остался выключен")
        }
    }

    // -------------------------------------------------------------------- меню
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.m_units)?.title = "${getString(R.string.menu_units)}: $unit"
        menu.findItem(R.id.m_window)?.title =
            "${getString(R.string.menu_window)}: ${WINDOW_NAMES[windowIndex()]}"
        // Отчёт для разбора — вещь разработческая, см. Link.DIAG.
        menu.findItem(R.id.m_diag)?.isVisible = Link.DIAG
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_units -> unitsDialog()
            R.id.m_window -> windowDialog()
            R.id.m_devices -> devicesDialog()
            R.id.m_name -> nameDialog()
            R.id.m_alarm -> alarmDialog()
            R.id.m_share -> shareLast()
            R.id.m_log -> logDialog()
            R.id.m_diag -> shareReport()
            R.id.m_files -> filesDialog()
            R.id.m_repo -> openRepo()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun windowIndex(): Int = WINDOW_SECONDS.indexOf(windowSec).let { if (it < 0) 0 else it }

    private fun unitsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_units)
            .setSingleChoiceItems(UNIT_NAMES, UNIT_NAMES.indexOf(unit)) { d, i ->
                setUnit(UNIT_NAMES[i]); d.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun windowDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_window)
            .setSingleChoiceItems(WINDOW_NAMES, windowIndex()) { d, i ->
                setWindow(WINDOW_SECONDS[i]); d.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Парк приборов: подключённые и знакомые.
     *
     * Знакомые нужны не для удобства: отключённый Enforcer уходит
     * в направленную рекламу и в поиске не виден, так что подключиться
     * к нему можно только по адресу. (Это наблюдение Android: на маке тот же
     * прибор после разрыва виден сканом как обычно — разница, похоже,
     * на стороне хоста. Здесь список обязателен в любом случае.)
     */
    private fun devicesDialog() {
        val connected = Link.devices
        val busyAddrs = connected.map { it.address }.toSet()
        val known = Link.knownList().filter { it.address !in busyAddrs }
        val items = ArrayList<String>()
        for (d in connected) items.add("${d.label}\n${d.address}   на связи")
        for (k in known) items.add("${k.label}\n${k.address}   не подключён")

        val b = AlertDialog.Builder(this)
            .setTitle(R.string.menu_devices)
            .setNeutralButton("Искать новые") { _, _ -> forceScan = true; askAndScan() }
            .setNegativeButton("Закрыть", null)
        if (items.isEmpty()) {
            b.setMessage("Знакомых приборов пока нет. «Искать новые» — поиск в эфире.")
        } else {
            b.setItems(items.toTypedArray()) { _, i ->
                if (i < connected.size) deviceActions(connected[i], null)
                else deviceActions(null, known[i - connected.size])
            }
        }
        b.show()
    }

    private fun deviceActions(dev: Device?, known: Link.Known?) {
        val label = dev?.label ?: known?.label ?: "прибор"
        val addr = dev?.address ?: known?.address ?: return
        val actions = if (dev != null) arrayOf("Отключить", "Забыть")
                      else arrayOf("Подключить", "Забыть")
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(actions) { _, i ->
                when {
                    i == 0 && dev != null -> dev.disconnect()
                    i == 0 -> Link.connectDirect(addr, 20000) {
                        toast("$label не отозвался — переподключите на нём Bluetooth")
                    }
                    else -> {
                        dev?.disconnect()
                        Link.forgetDevice(addr)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openRepo() {
        val url = getString(R.string.repo_url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("repo", url))
            toast("браузер не нашёлся, ссылка скопирована")
        }
    }

    private fun setUnit(u: String) {
        unit = u
        Link.putPref("unit", u)
        invalidateOptionsMenu()
        Link.log("единицы: $u")
    }

    private fun setWindow(s: Int) {
        windowSec = s
        Link.putPref("window", s.toString())
        invalidateOptionsMenu()
        Link.log("окно графика: ${WINDOW_NAMES[windowIndex()]}")
    }

    private fun nameDialog() {
        val e = EditText(this)
        e.setText(recName)
        e.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_name)
            .setView(pad(e))
            .setPositiveButton("OK") { _, _ ->
                recName = e.text.toString().trim().ifEmpty { "test" }
                Link.putPref("name", recName)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun alarmDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val cb = CheckBox(this)
        cb.text = "подавать сигнал"
        cb.isChecked = Link.alarmEnabled
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.setSingleLine()
        val shown = if (unit == "kN") Link.alarmKn else Link.alarmKn * 1000.0 / Link.G
        e.setText(String.format(Locale.US, if (unit == "kN") "%.2f" else "%.0f", shown))
        val lab = TextView(this)
        lab.text = "порог, $unit — сигнал по любому прибору"
        box.addView(cb); box.addView(lab); box.addView(e)

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_alarm)
            .setView(pad(box))
            .setPositiveButton("OK") { _, _ ->
                val v = e.text.toString().replace(',', '.').toDoubleOrNull()
                if (v == null || v <= 0.0) { toast("порог — число больше нуля"); return@setPositiveButton }
                val kn = if (unit == "kN") v else v * Link.G / 1000.0
                Link.saveAlarm(cb.isChecked, kn)
                Link.log("сигнал: ${if (cb.isChecked) "вкл" else "выкл"}, " +
                        String.format(Locale.US, "%.2f kN", kn))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun logDialog() {
        val t = TextView(this)
        t.setTextIsSelectable(true)
        t.typeface = Typeface.MONOSPACE
        t.textSize = 11f
        t.text = Link.logText()
        val sv = ScrollView(this)
        sv.addView(pad(t))
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_log)
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun filesDialog() {
        val dir = Link.logsDir()
        val list = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val text = "Записи лежат здесь:\n\n${dir.absolutePath}\n\n" +
                (if (list.isEmpty()) "пока пусто"
                else "файлов: ${list.size}\nпоследний: ${list[0].name}") +
                "\n\nПапка видна из файлового менеджера " +
                "(Android/data/${packageName}/files/logs). Проще всего забрать файл " +
                "кнопкой «Поделиться»."
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_files)
            .setMessage(text)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Скопировать путь") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("path", dir.absolutePath))
                toast("путь скопирован")
            }
            .show()
    }

    private fun pad(v: View): View {
        val box = LinearLayout(this)
        val p = dp(16f)
        box.setPadding(p, p / 2, p, 0)
        box.addView(v)
        return box
    }

    // ------------------------------------------------------------ сохранение
    private fun doSave() {
        val f = Link.saveCsv(recName)
        if (f == null) { toast("сохранять нечего"); return }
        AlertDialog.Builder(this)
            .setTitle("Сохранено")
            .setMessage("${f.name}\n\n${Link.recordedCount()} строк\n\n${f.parent}")
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Поделиться") { _, _ -> share(f, "text/csv") }
            .show()
    }

    private fun shareLast() {
        val f = Link.lastCsv()
        if (f == null) { toast("сохранённых записей ещё нет"); return }
        share(f, "text/csv")
    }

    /** Журнал и сырой обмен с прибором — одним файлом, чтобы отправить. */
    private fun shareReport() {
        val f = Link.saveReport()
        if (f == null) { toast("отчёт не сохранился"); return }
        share(f, "text/plain")
    }

    private fun share(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = mime
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.putExtra(Intent.EXTRA_SUBJECT, f.name)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Отправить ${f.name}"))
        } catch (e: Exception) {
            toast("не вышло отправить: ${e.message}")
        }
    }
}
