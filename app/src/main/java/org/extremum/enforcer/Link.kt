package org.extremum.enforcer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Парк приборов и всё, что у них общее: поиск, журнал, запись как процесс,
 * очередь команд GATT, сохранение CSV.
 *
 * Состояние отдельного прибора живёт в [Device]. Объект переживает активность:
 * поворот экрана и переход на «крупное табло» не рвут соединения.
 *
 * **Одним прибором пользоваться должно быть ровно так же просто, как раньше.**
 * Поэтому «Найти» при единственном знакомом приборе не сканирует эфир вовсе —
 * идёт по адресу и подключается за секунду, а интерфейс остаётся прежним:
 * большое число, пик, график. Список приборов появляется только когда их
 * действительно больше одного.
 */
object Link {

    val SVC: UUID = UUID.fromString("0bd51666-e7cb-469b-8e4d-2742f1ba77cc")
    val CHAR: UUID = UUID.fromString("e7add780-b042-4876-aae1-112855353cc1")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val ADV_NAME = "Bluegiga CR device"
    const val SCAN_MS = 10_000L
    const val WARMUP_MS = 600L          // отбросить хвост прошлой сессии
    const val G = 9.80665
    const val MAX_DEVICES = 4
    private const val DIRECT_MS = 8000L // столько ждём знакомый прибор по адресу

    // Запасные значения на случай, если дамп прочитать не удалось.
    private const val FALLBACK_A = 233.0
    private const val FALLBACK_Z = 500.0

    /**
     * Цвет закрепляется за прибором по порядку подключения. Оттенки разнесены
     * намеренно: похожие цвета на графике неразличимы.
     */
    val COLORS = intArrayOf(
        0xFF1F77B4.toInt(), 0xFF2CA02C.toInt(), 0xFFD62728.toInt(),
        0xFF9467BD.toInt(), 0xFFFF7F0E.toInt(), 0xFF17BECF.toInt())

    /**
     * Разработческий отчёт: журнал плюс сырой обмен с прибором одним файлом.
     *
     * Выключен намеренно. Оператору он не нужен, а пункт в меню засоряет.
     * Чтобы вернуть — поставить здесь true и пересобрать: на этот флаг смотрят
     * и запись обмена, и видимость пункта «Отчёт для разбора…».
     * Подробности — в enforcer-android.md.
     */
    const val DIAG = false

    enum class State { IDLE, SCANNING, CONNECTING, PREPARING, LIVE }

    class Sample(val ts: Long, val t: Float, val counts: Int, val kn: Double)
    class Found(val device: BluetoothDevice, val address: String, val name: String, val rssi: Int)
    /** Прибор, с которым уже работали: адрес плюс имя с его заставки. */
    class Known(val address: String, val label: String)

    val VALUE = Regex("([kl])\\s*(-?\\d+)\\r")
    val FRAME = Regex("\"([^\"]+)\"=\"([^\"]*)\";")

    // -------------------------------------------------------------- служебное
    lateinit var app: Context; private set
    private val bg = HandlerThread("enforcer-ble").apply { start() }
    val h = Handler(bg.looper)
    val ui = Handler(Looper.getMainLooper())

    /** Подключённые и подключающиеся приборы. Меняется только в потоке [h]. */
    private val devicesList = ArrayList<Device>()
    val devices: List<Device> get() = synchronized(devicesList) { ArrayList(devicesList) }

    @Volatile var busy = false; private set     // идёт поиск или подключение

    private var scanCb: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private val scanTimes = ArrayList<Long>()
    private var btReceiver: BroadcastReceiver? = null
    private var readyAt = 0L        // раньше этого времени отпущенного прибора в эфире нет

    // ---------------------------------------------------------------- запись
    @Volatile var running = false; private set
    @Volatile var paused = false; private set
    private var t0 = 0L
    private var pausedAt = 0L

    // ------------------------------------------------------ сигнал по порогу
    @Volatile var alarmEnabled = false
    @Volatile var alarmKn = 5.0
    @Volatile var alarmPending = false

    private val logLines = ArrayList<String>()
    @Volatile var logSeq = 0; private set

    // Сырой обмен: от начала подключения до нескольких секунд после запуска
    // потока и при разрыве связи. Поток отсчётов целиком сюда не льётся.
    private val rawLines = ArrayList<String>()
    @Volatile private var rawOn = false
    private var rawT0 = 0L

    /** «1.0 · a1b2c3d» — версия и коммит, из которого собран этот APK. */
    fun buildTag(): String {
        val mark = BuildConfig.GIT_SHA.ifEmpty { BuildConfig.BUILD_STAMP }
        return if (mark.isEmpty()) BuildConfig.VERSION_NAME
        else "${BuildConfig.VERSION_NAME} · $mark"
    }

    fun init(ctx: Context) {
        if (this::app.isInitialized) return
        app = ctx.applicationContext
        watchBluetooth()
    }

    /**
     * Выключение Bluetooth на телефоне не приходит в GATT-колбэк: соединение
     * просто перестаёт работать, а наши поля продолжают уверять, что всё живо.
     * Дальше «Найти» упирается в это враньё и не делает ничего.
     */
    private fun watchBluetooth() {
        if (btReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) ?: -1) {
                    BluetoothAdapter.STATE_OFF -> h.post {
                        stopScan()
                        steps.clear()
                        for (d in devices) d.disconnect(quiet = true)
                        synchronized(devicesList) { devicesList.clear() }
                        busy = false
                        log("Bluetooth выключен — соединения сброшены")
                    }
                    BluetoothAdapter.STATE_ON -> log("Bluetooth включён")
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                app, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            btReceiver = r
        } catch (e: Exception) {
            log("не удалось следить за Bluetooth: ${e.message}")
        }
    }

    // ----------------------------------------------------------------- журнал
    fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logLines) {
            logLines.add("$stamp  $msg")
            while (logLines.size > 300) logLines.removeAt(0)
        }
        logSeq++
        Log.i("Enforcer", msg)
    }

    fun lastLog(): String = synchronized(logLines) { logLines.lastOrNull() ?: "" }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun rawStart() {
        if (!DIAG) return
        synchronized(rawLines) { rawLines.clear() }
        rawT0 = System.currentTimeMillis()
        rawOn = true
    }

    fun rawResume() {
        if (!DIAG) return
        if (rawT0 == 0L) rawT0 = System.currentTimeMillis()
        rawOn = true
    }

    private fun rawStop() { rawOn = false }

    /** dir: «->» послано прибору, «<-» принято, «==» событие GATT. */
    fun raw(dir: String, text: String) {
        if (!rawOn) return
        synchronized(rawLines) {
            if (rawLines.size > 800) return
            val dt = System.currentTimeMillis() - rawT0
            rawLines.add(String.format(Locale.US, "%6d %s %s", dt, dir, escape(text)))
        }
    }

    private fun escape(s: String): String {
        val b = StringBuilder()
        for (c in s) {
            when {
                c == '\r' -> b.append("\\r")
                c == '\n' -> b.append("\\n")
                c.code in 32..126 -> b.append(c)
                else -> b.append(String.format(Locale.US, "\\x%02X", c.code))
            }
        }
        return b.toString()
    }

    fun rawText(): String = synchronized(rawLines) { rawLines.joinToString("\n") }

    // ------------------------------------------------- очередь команд GATT
    /**
     * Очередь одна на всё приложение: Android разрешает одну незавершённую
     * операцию GATT на процесс, а не на соединение. Настройки приборов из-за
     * этого идут по очереди — они короткие, и четыре прибора успевают выйти
     * в поток за десяток секунд.
     */
    sealed class Step {
        class Write(val dev: Device, val data: ByteArray) : Step()
        class Wait(val ms: Long) : Step()
        class Run(val block: () -> Unit) : Step()
    }

    private val steps = ArrayDeque<Step>()
    private var pumpTick = 0

    fun cmd(s: String): ByteArray = (s + "\r\n").toByteArray(Charsets.ISO_8859_1)

    fun add(step: Step) { steps.add(step) }

    /** Шаги отключившегося прибора выбрасываем — писать больше некуда. */
    fun dropSteps(dev: Device) {
        val keep = steps.filter { !(it is Step.Write && it.dev === dev) }
        steps.clear()
        steps.addAll(keep)
    }

    fun pump() {
        val gen = ++pumpTick
        val s = steps.poll() ?: return
        when (s) {
            is Step.Run -> { s.block(); pump() }
            is Step.Wait -> h.postDelayed({ if (gen == pumpTick) pump() }, s.ms)
            is Step.Write -> {
                if (!s.dev.writeChar(s.data)) {
                    h.postDelayed({ if (gen == pumpTick) pump() }, 600)
                } else {
                    // Сторож на случай, если подтверждения записи не будет:
                    // без него вся цепочка встанет молча.
                    h.postDelayed({
                        if (gen == pumpTick) { log("подтверждения записи нет, иду дальше"); pump() }
                    }, 4000)
                }
            }
        }
    }

    // ------------------------------------------------------------------ парк
    private fun freeIdx(): Int {
        val used = synchronized(devicesList) { devicesList.map { it.idx }.toSet() }
        var i = 0
        while (i in used) i++
        return i
    }

    fun byAddress(addr: String): Device? =
        synchronized(devicesList) { devicesList.firstOrNull { it.address == addr } }

    private fun obtain(addr: String): Device {
        byAddress(addr)?.let { return it }
        val d = Device(addr)
        d.idx = freeIdx()
        synchronized(devicesList) { devicesList.add(d) }
        return d
    }

    /** Прибор больше не наш: убрать из парка, освободив цвет. */
    fun drop(dev: Device) {
        synchronized(devicesList) { devicesList.remove(dev) }
        dropSteps(dev)
        checkFindDone()
    }

    fun disconnectAll() {
        h.post {
            for (d in devices) d.disconnect()
            synchronized(devicesList) { devicesList.clear() }
        }
    }

    fun releaseAll() {
        h.post {
            stopScan()
            steps.clear()
            for (d in devices) d.disconnect(quiet = true)
            synchronized(devicesList) { devicesList.clear() }
            busy = false
            log("приложение закрыто, соединения освобождены")
        }
    }

    /** Прибор отпущен: несколько секунд его в эфире не будет. */
    fun noteReleased() {
        readyAt = System.currentTimeMillis() + 2000
        h.postDelayed({ rawStop() }, 5000)
    }

    fun reconnect(dev: Device) {
        val ad = adapter() ?: return
        try { dev.connect(ad.getRemoteDevice(dev.address), true, 20000, null) }
        catch (e: Exception) { log("адрес не годится: ${e.message}"); drop(dev) }
    }

    // ------------------------------------------------------------------ скан
    private fun adapter(): BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun bluetoothReady(): Boolean = adapter()?.isEnabled == true

    fun bluetoothSupported(): Boolean = adapter() != null

    /**
     * Скан без фильтра: 128-битный UUID сервиса часть стеков не умеет
     * сопоставлять, и прибор тогда просто не находится. Отбор делаем сами —
     * по UUID сервиса либо по имени в эфире.
     */
    @SuppressLint("MissingPermission")
    private fun startScan(onDone: (List<Found>) -> Unit) {
        // Прошлый поиск мог остаться висеть — пока он не снят, новый молча
        // не даёт результатов, и лечится это только перезапуском Bluetooth.
        stopScan()

        var delivered = false
        fun finish(list: List<Found>) {
            if (delivered) return
            delivered = true
            stopScan()
            ui.post { onDone(list) }
        }

        val hold = readyAt - System.currentTimeMillis()
        if (hold > 0) {
            log("жду ${hold / 1000 + 1} с — отпущенный прибор ещё не в эфире")
            h.postDelayed({ startScan(onDone) }, hold)
            return
        }

        val ad = adapter()
        if (ad == null) { log("Bluetooth в телефоне не найден"); finish(emptyList()); return }
        if (!ad.isEnabled) { log("Bluetooth выключен"); finish(emptyList()); return }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            log("сканер BLE недоступен — выключите и включите Bluetooth")
            finish(emptyList()); return
        }

        // Начиная с Android 7 система глушит приложение, если оно запускает
        // скан чаще пяти раз за полминуты: onScanFailed при этом не приходит,
        // поиск просто ничего не находит.
        val now = SystemClock.elapsedRealtime()
        scanTimes.add(now)
        while (scanTimes.isNotEmpty() && now - scanTimes[0] > 30_000) scanTimes.removeAt(0)
        if (scanTimes.size > 4) {
            log("поиск запускался ${scanTimes.size} раза за полминуты — Андроид " +
                    "его придержит; подождите с полминуты")
        }

        val found = LinkedHashMap<String, Found>()
        val seen = LinkedHashMap<String, String>()   // всё, что вообще в эфире
        log("сканирую ${SCAN_MS / 1000} с …")

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                val byUuid = rec?.serviceUuids?.any { it.uuid == SVC } == true
                val name = rec?.deviceName
                val byName = name == ADV_NAME
                if (seen.size < 40) seen[result.device.address] =
                    (name ?: "(без имени)") + "  uuid=" + (rec?.serviceUuids?.size ?: 0)
                if (byUuid || byName) {
                    found[result.device.address] = Found(
                        result.device, result.device.address, name ?: ADV_NAME, result.rssi)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) onScanResult(0, r)
            }

            override fun onScanFailed(errorCode: Int) {
                log("поиск не начался: " + scanError(errorCode))
                h.post { finish(emptyList()) }
            }
        }
        scanCb = cb
        try {
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            log("поиск не запустился: ${e.message}")
            finish(emptyList())
            return
        }

        val t = Runnable {
            val list = found.values.sortedByDescending { it.rssi }
            if (list.isEmpty()) {
                // Список всего эфира отвечает на главный вопрос: приборы молчат
                // или они есть, но мы их отсеяли.
                log("в эфире устройств: ${seen.size}, приборов нет")
                for ((a, n) in seen.entries.take(12)) log("   $a  $n")
                if (seen.isEmpty()) log("эфир пуст — похоже, скан заблокирован системой")
            } else {
                log("найдено приборов: ${list.size}")
            }
            finish(list)
        }
        scanTimeout = t
        h.postDelayed(t, SCAN_MS)
    }

    private fun scanError(code: Int): String = when (code) {
        1 -> "поиск уже идёт (код 1)"
        2 -> "система не дала начать поиск (код 2)"
        3 -> "внутренняя ошибка Bluetooth (код 3) — выключите и включите Bluetooth"
        4 -> "телефон не поддерживает такой режим поиска (код 4)"
        else -> "код $code"
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeout?.let { h.removeCallbacks(it) }
        scanTimeout = null
        val cb = scanCb ?: return
        scanCb = null
        try { adapter()?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    // -------------------------------------------------------------- поиск
    private var pendingDirect = 0
    private var findForce = false
    private var findScanned = false
    private var onFindDone: ((String) -> Unit)? = null

    /**
     * «Найти».
     *
     * Порядок такой, чтобы владельцу единственного прибора не приходилось
     * ждать ничего лишнего:
     *
     * 1. знакомые приборы пробуем **по адресу, одновременно** — после разрыва
     *    связи Enforcer в поиске не виден, но по адресу подключается за секунду;
     * 2. если все знакомые отозвались, эфир не сканируем вовсе;
     * 3. если кто-то из знакомых молчит или знакомых нет — идём в эфир
     *    и подключаемся ко всему найденному, до [MAX_DEVICES].
     *
     * [force] пропускает шаг 1 — это «искать новые приборы».
     */
    fun find(force: Boolean, onDone: ((String) -> Unit)? = null) {
        h.post {
            if (busy) return@post
            busy = true
            findForce = force
            findScanned = false
            onFindDone = onDone
            val connected = devices.map { it.address }.toSet()
            val todo = if (force) emptyList()
                       else knownList().filter { it.address !in connected }
                           .take(MAX_DEVICES - connected.size)
            if (todo.isEmpty()) { scanStage(); return@post }
            pendingDirect = todo.size
            log(if (todo.size == 1) "пробую знакомый прибор по адресу …"
                else "пробую ${todo.size} знакомых приборов по адресу …")
            for (k in todo) connectDirect(k.address, DIRECT_MS) { onDirectFail() }
        }
    }

    private fun onDirectFail() {
        h.post {
            pendingDirect--
            if (pendingDirect <= 0 && !findScanned) scanStage()
        }
    }

    /** Прибор вышел в поток: если ждать больше некого, поиск закончен. */
    fun onDeviceLive(dev: Device) {
        h.post {
            if (pendingDirect > 0) pendingDirect--
            // Ещё несколько секунд пишем поток — по нему видно, что отсчёты
            // пошли, — и на этом запись обмена прекращаем.
            h.postDelayed({ rawStop() }, 4000)
            checkFindDone()
        }
    }

    private fun checkFindDone() {
        if (!busy) return
        if (pendingDirect > 0) return
        if (devices.any { it.state == State.CONNECTING || it.state == State.PREPARING }) return
        if (!findScanned && devices.isEmpty()) { scanStage(); return }
        finishFind()
    }

    private fun scanStage() {
        if (findScanned) { finishFind(); return }
        findScanned = true
        startScan { list ->
            h.post {
                val connected = devices.map { it.address }.toSet()
                val all = list.filter { it.address !in connected }
                val free = MAX_DEVICES - connected.size
                val fresh = all.take(free)
                // Предел не в приложении, а в телефоне: параллельных
                // GATT-соединений стек держит конечное число, и на слабых
                // аппаратах их меньше. Молча ронять лишние нельзя.
                if (all.size > free) log("найдено новых ${all.size}, свободных мест $free")
                if (fresh.isEmpty()) {
                    finishFind()
                } else {
                    pendingDirect = fresh.size
                    for (f in fresh) {
                        val d = obtain(f.address)
                        d.connect(f.device, false, 20000) { onDirectFail() }
                    }
                }
            }
        }
    }

    private fun finishFind() {
        busy = false
        val n = devices.count { it.live }
        val msg = when {
            n > 0 -> "приборов на связи: $n"
            findForce -> "новых приборов не найдено"
            else -> "не найден. Переподключите Bluetooth на приборе: удержите " +
                    "SAMPLE — значок погаснет, удержите ещё раз — загорится"
        }
        log(msg)
        val cbk = onFindDone
        onFindDone = null
        if (cbk != null) ui.post { cbk(msg) }
    }

    /**
     * Подключение по адресу, мимо поиска.
     *
     * После разрыва связи Enforcer в обычном поиске не виден, но подключается
     * по адресу мгновенно: похоже, он переходит на направленную рекламу,
     * адресованную прежнему хозяину. autoConnect=true к тому же ставит адрес
     * в очередь фоновых подключений, так что телефон сцепится и с редкой
     * рекламой, которую скан не ловит.
     *
     * Оговорка: это наблюдение Android. На маке то же самое не повторяется —
     * оба прибора после разрыва видны сканом как обычно, и реклама содержит
     * UUID сервиса, то есть она обычная, а не направленная. Скорее всего дело
     * в стороне хоста, а не в приборе. Для этого кода ничего не меняется:
     * заход по адресу и здесь быстрее поиска, а на телефоне он к тому же
     * единственный работающий.
     */
    fun connectDirect(addr: String, timeoutMs: Long, onFail: (() -> Unit)?) {
        val ad = adapter()
        if (ad == null) { onFail?.let { f -> ui.post { f() } }; return }
        try {
            val d = obtain(addr)
            d.connect(ad.getRemoteDevice(addr), true, timeoutMs, onFail)
        } catch (e: Exception) {
            log("адрес не годится: ${e.message}")
            onFail?.let { f -> ui.post { f() } }
        }
    }

    // -------------------------------------------------------------- настройки
    private fun prefs() = app.getSharedPreferences("enforcer", Context.MODE_PRIVATE)

    fun lastAddress(): String? = prefs().getString("last", null)

    /**
     * Приборы, с которыми уже работали.
     *
     * Список обязателен, а не удобен: отключённый Enforcer уходит
     * в направленную рекламу и в поиске не виден. Без адресов свой парк
     * вообще не собрать.
     */
    fun knownList(): List<Known> =
        (prefs().getString("known", "") ?: "")
            .split(";")
            .filter { it.contains("|") }
            .map { val p = it.split("|"); Known(p[0], p.getOrNull(1)?.ifEmpty { p[0] } ?: p[0]) }

    fun remember(dev: Device) {
        val entry = Known(dev.address, dev.label)
        val out = (listOf(entry) + knownList().filter { it.address != dev.address }).take(6)
        prefs().edit()
            .putString("known", out.joinToString(";") { "${it.address}|${it.label}" })
            .putString("last", dev.address)
            .apply()
    }

    fun forgetDevice(addr: String) {
        val out = knownList().filter { it.address != addr }
        prefs().edit()
            .putString("known", out.joinToString(";") { "${it.address}|${it.label}" })
            .apply()
        log("прибор $addr забыт")
    }

    fun saveCal(dev: Device) {
        val a = dev.perKn ?: return
        val z = dev.zero ?: return
        prefs().edit()
            // Четвёртым полем раньше писался hexid; поле убрано вместе
            // с ключом j. Старые записи с ним читаются как и прежде —
            // лишние части строки просто не берутся.
            .putString("cal_${dev.address}", "$a;$z;${dev.serial ?: ""}")
            .apply()
    }

    /**
     * Запасной путь: калибровка из памяти телефона либо заводская.
     * z сдвигается каждый раз, когда на приборе жмут POWER+FORCE, поэтому
     * сохранённое значение может устареть — об этом пишем прямо в журнал.
     */
    fun loadCachedCal(dev: Device) {
        val s = prefs().getString("cal_${dev.address}", null)
        if (s != null) {
            val p = s.split(";")
            dev.perKn = p.getOrNull(0)?.toDoubleOrNull() ?: FALLBACK_A
            dev.zero = p.getOrNull(1)?.toDoubleOrNull() ?: FALLBACK_Z
            if (dev.serial.isNullOrEmpty()) dev.serial = p.getOrNull(2)?.takeIf { it.isNotEmpty() }
            dev.calFromCache = true
            log("${dev.label}: калибровка из памяти телефона a=${dev.perKn?.toInt()} " +
                    "z=${dev.zero?.toInt()} — если после неё жали POWER+FORCE, ноль устарел")
        } else {
            dev.perKn = FALLBACK_A; dev.zero = FALLBACK_Z; dev.calFromCache = true
            log("${dev.label}: калибровку прочитать не удалось, взял запасную — числа могут врать")
        }
    }

    fun loadSettings() {
        val p = prefs()
        alarmEnabled = p.getBoolean("alarmOn", false)
        alarmKn = p.getFloat("alarmKn", 5.0f).toDouble()
    }

    fun saveAlarm(on: Boolean, kn: Double) {
        alarmEnabled = on
        alarmKn = kn
        prefs().edit().putBoolean("alarmOn", on).putFloat("alarmKn", kn.toFloat()).apply()
    }

    fun getPref(key: String, def: String): String = prefs().getString(key, def) ?: def
    fun putPref(key: String, v: String) { prefs().edit().putString(key, v).apply() }

    // ---------------------------------------------------------------- запись
    fun since(now: Long): Float = (now - t0) / 1000f

    fun startRec() {
        for (d in devices) d.clearSamples()
        t0 = System.currentTimeMillis()
        running = true
        paused = false
        log("запись пошла")
    }

    fun pauseRec() {
        if (!running) return
        if (paused) {
            // Пауза как у секундомера: время записи стоит, на кривой не
            // остаётся пустого участка. В CSV метки времени настоящие.
            t0 += System.currentTimeMillis() - pausedAt
            paused = false
            log("запись продолжается")
        } else {
            paused = true
            pausedAt = System.currentTimeMillis()
            log("пауза")
        }
    }

    fun stopRec() {
        running = false
        paused = false
        log("остановлено: ${recordedCount()} отсчётов")
    }

    fun recordedCount(): Int = devices.sumOf { it.sampleCount() }

    fun tareAll() { for (d in devices) d.tareNow() }
    fun resetPeaks() { for (d in devices) d.resetPeak() }

    // ------------------------------------------------------------------- CSV
    fun logsDir(): File {
        val d = File(app.getExternalFilesDir(null), "logs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** Все приборы в одном файле, строки по общей оси времени. */
    fun saveCsv(name: String): File? {
        val rows = ArrayList<Array<String>>()
        for (d in devices) {
            val ser = d.serial ?: ""
            val lab = d.label
            for (s in d.snapshot()) {
                rows.add(arrayOf(
                    isoFmt.format(Date(s.ts)),
                    "%.3f".format(Locale.US, s.t),
                    ser, lab, s.counts.toString(),
                    if (s.kn.isNaN()) "" else "%.4f".format(Locale.US, s.kn)))
            }
        }
        if (rows.isEmpty()) return null
        rows.sortBy { it[1].toFloatOrNull() ?: 0f }

        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "test" }
        val stamp = SimpleDateFormat("dd MMM yyyy HH-mm-ss", Locale("ru")).format(Date())
        val f = File(logsDir(), "$stamp - $safe.csv")
        try {
            f.bufferedWriter().use { w ->
                w.write("timestamp,t_sec,serial,label,counts,kN\n")
                for (r in rows) w.write(r.joinToString(",") + "\n")
            }
        } catch (e: Exception) {
            log("сохранить не вышло: ${e.message}")
            return null
        }
        prefs().edit().putString("lastCsv", f.absolutePath).apply()
        log("сохранено: ${f.name} (${rows.size} строк)")
        return f
    }

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun lastCsv(): File? {
        val p = prefs().getString("lastCsv", null) ?: return null
        val f = File(p)
        return if (f.exists()) f else null
    }

    /**
     * Отчёт для разбора: состояние, дампы, журнал и сырой обмен одним файлом.
     * Виден только при [DIAG]. Зачем он нужен — в enforcer-android.md.
     */
    fun saveReport(): File? {
        val stamp = SimpleDateFormat("dd MMM yyyy HH-mm-ss", Locale("ru")).format(Date())
        val f = File(logsDir(), "диагностика $stamp.txt")
        try {
            f.bufferedWriter().use { w ->
                w.write("Enforcer ${buildTag()} — отчёт $stamp\n")
                w.write("сборка: ${BuildConfig.BUILD_STAMP} UTC\n")
                w.write("приборов в парке: ${devices.size}\n\n")
                for (d in devices) {
                    w.write("=== ${d.label}  ${d.address}\n")
                    w.write("состояние: ${d.state}\n")
                    w.write("серийник: ${d.serial ?: "—"}\n")
                    w.write("a = ${d.perKn ?: "—"}   z = ${d.zero ?: "—"}   " +
                            "калибровка из памяти: ${d.calFromCache}\n")
                    w.write("температура: ${d.temp ?: "—"}   тара: ${d.tare}   " +
                            "пик: ${d.peak ?: "—"}\n")
                    w.write("отсчётов записано: ${d.sampleCount()}\n")
                    if (d.lastDump.isEmpty()) w.write("дамп не читался\n")
                    else for ((k, v) in d.lastDump) w.write("  $k = \"$v\"\n")
                    w.write("\n")
                }
                w.write("===== журнал =====\n")
                w.write(logText())
                w.write("\n\n===== обмен с прибором =====\n")
                w.write("мс   направление  данные   (-> послано, <- принято, == событие)\n")
                w.write(rawText())
                w.write("\n")
            }
        } catch (e: Exception) {
            log("отчёт не сохранился: ${e.message}")
            return null
        }
        log("отчёт: ${f.name}")
        return f
    }
}
