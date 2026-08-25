package org.extremum.enforcer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * Сигнал при превышении порога: зуммер плюс вибрация.
 *
 * ToneGenerator на части устройств падает уже в конструкторе (занят аудиотракт),
 * поэтому создаётся лениво, а любая ошибка гасится: сигнал — не тот случай,
 * ради которого стоит ронять приложение посреди испытания.
 *
 * Вызовы новых API вынесены в отдельные методы под @RequiresApi. Так проверяющий
 * байт-код на старых версиях Андроида просто не встречает незнакомых классов.
 */
class Alarm(ctx: Context) {

    private val app = ctx.applicationContext
    private var tone: ToneGenerator? = null

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vibratorNew() else vibratorOld()
    } catch (e: Exception) {
        null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun vibratorNew(): Vibrator? =
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    @Suppress("DEPRECATION")
    private fun vibratorOld(): Vibrator? =
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun fire() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        } catch (e: Exception) {
            tone = null
        }
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) buzzNew(v) else buzzOld(v)
        } catch (_: Exception) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buzzNew(v: Vibrator) {
        v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @Suppress("DEPRECATION")
    private fun buzzOld(v: Vibrator) {
        v.vibrate(400)
    }

    fun release() {
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }
}
