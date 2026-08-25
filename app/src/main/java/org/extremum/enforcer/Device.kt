package org.extremum.enforcer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import java.util.Locale

/**
 * Один прибор Enforcer: соединение, разбор потока, своя калибровка и свои
 * отсчёты.
 *
 * Приборов может быть до [Link.MAX_DEVICES]. Всё, что у них общее — поиск,
 * журнал, запись как процесс, очередь команд GATT, — живёт в [Link].
 *
 * Очередь команд намеренно **одна на всё приложение**, а не своя у каждого
 * прибора: Android разрешает одну незавершённую операцию GATT на процесс,
 * а не на соединение. Подключения при этом можно начинать одновременно,
 * последовательными будут только опросы при настройке. Они короткие, так что
 * четыре прибора выходят в поток за десяток секунд.
 *
 * Два места, где отступление от протокола молча портит данные:
 *   * отдельный буфер для кадров дампа — разбор потока вырезает из общего
 *     буфера всё, что лежало перед найденным значением;
 *   * пробел после буквы канала не обязателен: "l 493", но "k214".
 */
class Device(val address: String) {

    /** Порядковый номер в парке — задаёт цвет кривой. Не меняется до отключения. */
    var idx = 0

    @Volatile var state: Link.State = Link.State.CONNECTING
    @Volatile var serial: String? = null
    @Volatile var perKn: Double? = null
    @Volatile var zero: Double? = null
    /** true, если калибровка не прочитана из прибора, а взята из памяти телефона. */
    @Volatile var calFromCache = false
    @Volatile var temp: Double? = null
    @Volatile var latest: Int? = null
    @Volatile var tare = 0.0
    @Volatile var peak: Double? = null
    @Volatile var lastDump: Map<String, String> = emptyMap()

    val lock = Any()
    val samples = ArrayList<Link.Sample>()

    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var closing: BluetoothGatt? = null
    private var connectToken = 0

    private val streamBuf = StringBuilder()
    private val calBuf = StringBuilder()
    private var started = 0L
    private var sawStream = false
    private var calAttempt = 0
    private var calDeadline = 0L
    private var keyDeadline = 0L
    private var reconnects = 0
    private var reconnectTask: Runnable? = null
    private var armed = true

    private val h get() = Link.h

    // ------------------------------------------------------------------ имя
    /**
     * Имя прибора — по хвосту серийного номера: enf-0466, enf-0053.
     *
     * Ключ j для имени непригоден и номером экземпляра НЕ является. Прежде имя
     * выводилось из трёх последних цифр серийника в шестнадцатеричном виде
     * (20030466 → 466 → 1D2), потому что 1D2 совпадало с тем, что прибор
     * показывает на заставке («id2 0233»). Второй экземпляр это опроверг:
     * у прибора 14060053 ключ j отдал то же самое 1D2, а заставка у обоих
     * показывает одинаковое «id2» — то есть 1D2 константа модели, а совпадение
     * с хвостом серийника первого прибора было случайным.
     *
     * Хвост серийника выбран ещё и ради общего CSV: настольные интерфейсы
     * пишут в колонку label ровно такие имена, и записи сшиваются по ним.
     */
    val label: String
        get() {
            serial?.let { if (it.length >= 4) return "enf-" + it.takeLast(4) }
            return "enf-" + address.replace(":", "").takeLast(4)
        }

    val color: Int get() = Link.COLORS[idx % Link.COLORS.size]

    val calibrated: Boolean get() = zero != null && perKn != null

    val live: Boolean get() = state == Link.State.LIVE

    fun toKn(counts: Int): Double? {
        val z = zero ?: return null
        val a = perKn ?: return null
        if (a == 0.0) return null
        return (counts - z - tare) / a
    }

    /** Текущее показание в выбранных единицах, null — пока нечего показывать. */
    fun value(kgf: Boolean): Double? {
        val c = latest ?: return null
        val kn = toKn(c) ?: return null
        return if (kgf) kn * 1000.0 / Link.G else kn
    }

    fun peakValue(kgf: Boolean): Double? {
        val p = peak ?: return null
        return if (kgf) p * 1000.0 / Link.G else p
    }

    // ------------------------------------------------------------ соединение
    @SuppressLint("MissingPermission")
    fun connect(dev: BluetoothDevice, auto: Boolean, timeoutMs: Long, onFail: (() -> Unit)?) {
        h.post {
            cancelReconnect()
            closeGatt()
            reset()
            state = Link.State.CONNECTING
            Link.log(if (auto) "$label: пробую напрямую, без поиска …"
                     else "подключаюсь к $address …")
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                dev.connectGatt(Link.app, auto, cb, BluetoothDevice.TRANSPORT_LE)
            else
                dev.connectGatt(Link.app, auto, cb)
            if (timeoutMs > 0) {
                val token = ++connectToken
                h.postDelayed({
                    if (token == connectToken && state == Link.State.CONNECTING) {
                        Link.log("$label: не отозвался за ${timeoutMs / 1000} с")
                        closeGatt()
                        Link.drop(this)
                        onFail?.let { f -> Link.ui.post { f() } }
                    }
                }, timeoutMs)
            }
        }
    }

    private fun reset() {
        serial = null; perKn = null; zero = null
        calFromCache = false; temp = null; latest = null
        tare = 0.0; peak = null
        started = 0L; sawStream = false; calAttempt = 0; reconnects = 0
        streamBuf.setLength(0); calBuf.setLength(0)
        Link.dropSteps(this)
    }

    /**
     * Разрыв связи.
     *
     * Гасить поток перед разрывом пробовали — не работает: после команды «l»
     * отсчёты продолжали идти вплоть до самого разрыва, а ключ l в дампе всё
     * это время показывал 0. Значит «l» не переключатель, а разовый запуск,
     * и выключения потока по радио у прибора, похоже, нет вовсе.
     *
     * Сам разрыв проходит чисто: стек подтверждает его за десятки миллисекунд
     * со статусом 0. В эфир прибор после этого не возвращается по своей
     * причине, не по нашей, и лечится это только удержанием SAMPLE.
     */
    fun disconnect(quiet: Boolean = false) {
        h.post {
            Link.rawResume()
            Link.raw("==", "$label tearDown state=$state")
            cancelReconnect()
            reconnects = Int.MAX_VALUE     // ручное отключение не переподключаем
            Link.dropSteps(this)
            closeGatt()
            latest = null
            state = Link.State.IDLE
            if (!quiet) Link.log("$label отключён. Если потом не найдётся — " +
                    "переподключите на нём Bluetooth удержанием SAMPLE")
            Link.noteReleased()
            if (!quiet) Link.drop(this)
        }
    }

    /**
     * close() НЕЛЬЗЯ вызывать сразу за disconnect(): он снимает регистрацию
     * клиента раньше, чем разрыв дойдёт до прибора по радио. Тот замечает
     * потерю связи только по тайм-ауту супервизии — до двадцати секунд вне
     * эфира. Поэтому закрываем по подтверждению стека, а таймер — страховка.
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        ch = null
        closing = g
        Link.raw("==", "$label gatt.disconnect()")
        try { g.disconnect() } catch (_: Exception) {}
        h.postDelayed({ reallyClose(g) }, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun reallyClose(g: BluetoothGatt) {
        if (closing !== g) return
        closing = null
        Link.raw("==", "$label gatt.close()")
        try { g.close() } catch (_: Exception) {}
    }

    private fun cancelReconnect() {
        reconnectTask?.let { h.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun scheduleReconnect() {
        if (reconnects >= 3) { Link.log("$label: переподключиться не вышло"); Link.drop(this); return }
        reconnects++
        Link.log("$label: переподключаюсь ($reconnects из 3) …")
        cancelReconnect()
        val t = Runnable { Link.reconnect(this) }
        reconnectTask = t
        h.postDelayed(t, 2500)
    }

    // ------------------------------------------------------------ колбэк GATT
    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Link.raw("==", "$label connectionState status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Link.log("$address: соединение есть, ищу сервисы")
                // Пауза перед discoverServices: на старых стеках немедленный
                // вызов регулярно возвращает пустой список сервисов.
                h.postDelayed({ discover(g) }, 400)
            } else {
                val was = state
                h.post {
                    closing?.let { reallyClose(it) }   // стек подтвердил разрыв
                    closeGatt()
                    Link.dropSteps(this@Device)
                    state = Link.State.IDLE
                    Link.log(if (status == 0) "$label отключился"
                             else "$label: связь разорвана, код $status")
                    if (was == Link.State.LIVE || was == Link.State.PREPARING) scheduleReconnect()
                    else Link.drop(this@Device)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Link.raw("==", "$label servicesDiscovered status=$status")
            h.post { afterDiscover(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Link.raw("==", "$label descriptorWrite status=$status")
            h.post {
                if (status != BluetoothGatt.GATT_SUCCESS)
                    Link.log("$label: подписка не включилась, код $status")
                beginSequence()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            Link.raw("==", "$label write status=$status")
            h.post { Link.pump() }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            val s = String(v, Charsets.ISO_8859_1)
            h.post { ingest(s) }
        }

        // API 33+: рамка та же, но байты приходят отдельным аргументом.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val s = String(value, Charsets.ISO_8859_1)
            h.post { ingest(s) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover(g: BluetoothGatt) {
        try { g.discoverServices() }
        catch (e: Exception) { Link.log("$label: поиск сервисов не пошёл: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun afterDiscover(g: BluetoothGatt) {
        val svc = g.getService(Link.SVC)
        if (svc == null) {
            Link.log("$address: сервис прибора не найден — это не Enforcer?")
            closeGatt(); state = Link.State.IDLE; Link.drop(this)
            return
        }
        val c = svc.getCharacteristic(Link.CHAR)
        if (c == null) {
            Link.log("$address: характеристика не найдена")
            closeGatt(); state = Link.State.IDLE; Link.drop(this)
            return
        }
        ch = c
        state = Link.State.PREPARING
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Link.CCCD)
        if (d == null) {
            Link.log("$label: дескриптор подписки не найден, пробую без него")
            beginSequence()
            return
        }
        // Характеристика объявлена как indicate, а не notify — значение CCCD
        // должно быть соответствующим, иначе прибор молчит.
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        writeDescriptor(g, d, v)
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(d, v)
            else writeDescriptorLegacy(g, d, v)
        } catch (e: Exception) {
            Link.log("$label: подписка не включилась: ${e.message}")
            beginSequence()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(
        g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        d.value = v
        g.writeDescriptor(d)
    }

    @SuppressLint("MissingPermission")
    fun writeChar(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = ch ?: return false
        Link.raw("->", "$label " + String(data, Charsets.ISO_8859_1))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            else
                writeCharLegacy(g, c, data)
        } catch (e: Exception) {
            Link.log("$label: запись не удалась: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharLegacy(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = data
        return g.writeCharacteristic(c)
    }

    // ------------------------------------------- последовательность настройки
    /**
     * Калибровку читаем при каждом подключении: ноль z сдвигается всякий раз,
     * когда на приборе жмут POWER+FORCE, и запомненное значение молча даёт
     * неверные килоньютоны.
     */
    private fun beginSequence() {
        Link.rawStart()
        Link.dropSteps(this)
        calAttempt = 0
        sawStream = false
        streamBuf.setLength(0)
        calBuf.setLength(0)
        started = 0L
        Link.add(Link.Step.Wait(400))
        queueCalRead()
        Link.pump()
    }

    private fun parseFrames(text: String): LinkedHashMap<String, String> {
        // В словарь, а не в первое совпадение: первый дамп после подключения
        // может нести исторические кадры, поздние значения должны затирать ранние.
        val got = LinkedHashMap<String, String>()
        for (m in Link.FRAME.findAll(text)) got[m.groupValues[1]] = m.groupValues[2].trim()
        return got
    }

    /**
     * Дамп собирается не за фиксированное время. Когда поток усилия уже идёт,
     * кадры ответа приходят вперемешку с отсчётами и растягиваются — обрубив
     * ожидание на трёх секундах, легко потерять хвост дампа. Ждём до полноты,
     * но не дольше девяти секунд.
     *
     * Девять, а не семь: замеры на двух приборах дали 3,8 и 5,6 с, а когда
     * рядом шло сканирование эфира — 6,5 с. Прежний потолок оказался впритык,
     * а лишним он не бывает: ожидание всё равно кончается на полноте дампа.
     */
    private fun queueCalRead() {
        Link.add(Link.Step.Run { calBuf.setLength(0) })
        Link.add(Link.Step.Write(this, Link.cmd("d\"T\";")))
        Link.add(Link.Step.Run { calDeadline = System.currentTimeMillis() + 9000 })
        queueCalPoll()
    }

    private fun queueCalPoll() {
        Link.add(Link.Step.Wait(600))
        Link.add(Link.Step.Run {
            val got = parseFrames(calBuf.toString())
            // Ключа j в наборе нет: он непригоден и приходит пустым, ждать его
            // непустым — досиживать до тайм-аута впустую. Зато обязателен l:
            // по нему решается, слать ли команду запуска. Выйди мы из ожидания
            // до прихода l — послали бы l поверх уже работающего потока.
            val full = got.containsKey("a") && got.containsKey("z") &&
                    got.containsKey("i") && got.containsKey("l")
            if (full || System.currentTimeMillis() > calDeadline) afterCal()
            else queueCalPoll()
        })
    }

    private fun afterCal() {
        val text = calBuf.toString()
        calBuf.setLength(0)
        val got = parseFrames(text)
        if (got.isNotEmpty()) lastDump = got
        Link.log("$address: дамп ${text.length} Б, ключей ${got.size}")

        got["i"]?.let { if (it.isNotEmpty()) serial = it }
        // Ключ l в дампе — флаг трансляции. Надёжнее наблюдения за эфиром:
        // отсчёты идут раз в полсекунды и в короткое окно могут не попасть.
        if (got["l"] == "1") sawStream = true
        val a = got["a"]?.toIntOrNull()
        val z = got["z"]?.toIntOrNull()
        if (a != null && z != null && a != 0) {
            perKn = a.toDouble(); zero = z.toDouble(); calFromCache = false
            Link.saveCal(this)
            Link.log("$label: калибровка a=$a z=$z, серийник ${serial ?: "?"}")
            stageH()
        } else if (calAttempt < 2) {
            calAttempt++
            Link.log("$address: дамп не дочитал, попытка ${calAttempt + 1}")
            queueCalRead()
        } else {
            Link.loadCachedCal(this)
            stageH()
        }
    }

    private fun stageH() {
        queueKey("h", { v -> v.toIntOrNull()?.let { temp = it / 10.0 } }, { stageStream() })
    }

    private fun stageStream() {
        Link.add(Link.Step.Wait(500))
        Link.add(Link.Step.Run { started = System.currentTimeMillis() })
        Link.add(Link.Step.Run {
            if (sawStream) {
                // Поток уже шёл: подписка живёт в приборе и переживает разрыв
                // связи. Лишняя команда поверх работающего потока ни к чему.
                goLive("поток уже шёл, включать не надо")
            } else {
                Link.add(Link.Step.Write(this, Link.cmd("l")))
                Link.add(Link.Step.Run { goLive("поток включён, 2 Гц") })
            }
        })
    }

    /**
     * Чтение одного ключа формой ?"X";.
     *
     * Ответ приходит не сразу: прибор в ответ на запрос выдаёт кусок дампа
     * и сыплет его кадр за кадром по сотне миллисекунд. Ответ на ?"h";
     * наблюдался через 1,76 с — фиксированные полторы секунды промахивались,
     * и температура терялась. Ждём до появления ключа, потолок пять секунд.
     *
     * Пять, а не четыре: на двух приборах ответ шёл 1,3 и 3,5 с, то есть
     * разброс велик и прежний потолок стоял слишком близко к измеренному.
     *
     * Продолжение передаётся параметром [then], а не дописывается вызывающим
     * после queueKey: опрос достраивает очередь сам, и шаги, добавленные
     * снаружи заранее, оказались бы впереди ответа.
     */
    private fun queueKey(key: String, apply: (String) -> Unit, then: () -> Unit) {
        Link.add(Link.Step.Run { calBuf.setLength(0) })
        Link.add(Link.Step.Write(this, Link.cmd("?\"$key\";")))
        Link.add(Link.Step.Run { keyDeadline = System.currentTimeMillis() + 5000 })
        queueKeyPoll(key, apply, then)
    }

    private fun queueKeyPoll(key: String, apply: (String) -> Unit, then: () -> Unit) {
        Link.add(Link.Step.Wait(400))
        Link.add(Link.Step.Run {
            val text = calBuf.toString()
            val v = Regex("\"" + key + "\"=\"([^\"]*)\"").find(text)
                ?.groupValues?.get(1)?.trim()
            when {
                !v.isNullOrEmpty() -> { calBuf.setLength(0); apply(v); then() }
                System.currentTimeMillis() > keyDeadline -> {
                    calBuf.setLength(0)
                    Link.log("$label: на ?\"$key\"; ответа нет")
                    then()
                }
                else -> queueKeyPoll(key, apply, then)
            }
        })
    }

    private fun goLive(msg: String) {
        state = Link.State.LIVE
        reconnects = 0
        Link.remember(this)          // имя к этому моменту окончательное
        val t = temp
        Link.log("$label: " + msg +
                (if (t != null) ", ${"%.1f".format(Locale.US, t)} °C" else "") +
                (if (calFromCache) "  ⚠ калибровка не из прибора" else ""))
        Link.onDeviceLive(this)
    }

    // ------------------------------------------------------------ разбор потока
    private fun ingest(chunk: String) {
        Link.raw("<-", "$label $chunk")
        calBuf.append(chunk)
        if (calBuf.length > 8192) calBuf.delete(0, calBuf.length - 4096)
        streamBuf.append(chunk)
        while (true) {
            val m = Link.VALUE.find(streamBuf) ?: break
            val channel = m.groupValues[1]
            val v = m.groupValues[2].toIntOrNull()
            streamBuf.delete(0, m.range.last + 1)
            if (v == null) continue
            if (channel == "k") { temp = v / 10.0; continue }
            sawStream = true
            // Пока телефон не подключён, вывод копится в приборе и вываливается
            // разом: первые 0,6 с — хвост прошлой сессии, его выбрасываем.
            if (started == 0L || System.currentTimeMillis() - started < Link.WARMUP_MS) continue
            latest = v
            onValue(v)
        }
        if (streamBuf.length > 4096) streamBuf.delete(0, streamBuf.length - 2048)
    }

    private fun onValue(c: Int) {
        val now = System.currentTimeMillis()
        val k = toKn(c)
        if (k != null) {
            // Пик держим всегда, а не только во время записи: на обрыве
            // смотрят на табло, а «Старт» жмут не каждый раз.
            val p = peak
            if (p == null || k > p) peak = k
            checkAlarm(k)
        }
        if (!Link.running || Link.paused) return
        val kn = k ?: Double.NaN
        synchronized(lock) { samples.add(Link.Sample(now, Link.since(now), c, kn)) }
    }

    private fun checkAlarm(k: Double) {
        if (!Link.alarmEnabled) { armed = true; return }
        if (armed && k >= Link.alarmKn) { armed = false; Link.alarmPending = true }
        else if (!armed && k < Link.alarmKn * 0.85) armed = true
    }

    // ---------------------------------------------------------------- команды
    /**
     * Ноль программный: вычитаем текущее показание, сам прибор не трогаем.
     * Аппаратное обнуление (POWER+FORCE) сдвигает z внутри прибора, и тогда
     * прочитанная при подключении калибровка перестаёт быть верной.
     */
    fun tareNow(): Boolean {
        val l = latest ?: return false
        val z = zero ?: return false
        tare = l - z
        peak = null
        Link.log("$label: ноль взят")
        return true
    }

    fun resetPeak() { peak = null }

    fun clearSamples() {
        synchronized(lock) { samples.clear() }
        peak = null
    }

    fun sampleCount(): Int = synchronized(lock) { samples.size }

    fun snapshot(): List<Link.Sample> = synchronized(lock) { ArrayList(samples) }
}
