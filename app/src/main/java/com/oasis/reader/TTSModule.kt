package com.oasis.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale
import java.util.UUID

class TTSModule(private val ctx: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val KEY_ENGINE = "tts_engine"
    }

    private var tts: TextToSpeech? = null
    private val prefs: SharedPreferences = ctx.getSharedPreferences("oasis_settings", Context.MODE_PRIVATE)
    private var onDoneCallback: (() -> Unit)? = null
    private var onReadyCallback: (() -> Unit)? = null

    init {
        createEngine()
    }

    // Crea el TextToSpeech con el motor guardado (o el predeterminado del sistema si no hay uno).
    private fun createEngine() {
        val enginePkg = prefs.getString(KEY_ENGINE, null)
        tts = if (enginePkg.isNullOrEmpty()) {
            TextToSpeech(ctx, this)
        } else {
            TextToSpeech(ctx, this, enginePkg)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            // Si el motor elegido ya no existe o falla, vuelve al predeterminado.
            if (!prefs.getString(KEY_ENGINE, null).isNullOrEmpty()) {
                prefs.edit().remove(KEY_ENGINE).apply()
                Handler(Looper.getMainLooper()).post {
                    tts?.shutdown()
                    tts = null
                    createEngine()
                    Toast.makeText(
                        ctx,
                        "No se pudo iniciar el motor de voz elegido. Se usa el predeterminado.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            return
        }

        tts?.language = Locale("es", "MX")
        applySpeechRateAndPitch()

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDoneCallback?.invoke()
                onDoneCallback = null
            }
            override fun onError(utteranceId: String?) {}
        })

        onReadyCallback?.invoke()
        onReadyCallback = null
    }

    private fun applySpeechRateAndPitch() {
        val speed = prefs.getFloat("voice_speed", 1.0f)
        val pitch = prefs.getFloat("voice_pitch", 1.0f)
        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)
    }

    // Motores de voz instalados en el teléfono.
    fun getEngines(): List<TextToSpeech.EngineInfo> = tts?.engines ?: emptyList()

    // Paquete del motor elegido por el usuario, o null si usa el predeterminado del sistema.
    fun getSavedEngine(): String? = prefs.getString(KEY_ENGINE, null)

    // Cambia de motor. pkg = null vuelve al predeterminado del sistema.
    fun setEngine(pkg: String?, onReady: (() -> Unit)? = null) {
        val editor = prefs.edit()
        if (pkg.isNullOrEmpty()) editor.remove(KEY_ENGINE) else editor.putString(KEY_ENGINE, pkg)
        editor.apply()

        onDoneCallback = null
        onReadyCallback = onReady
        tts?.stop()
        tts?.shutdown()
        tts = null
        createEngine()
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        onDoneCallback = onDone
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        tts?.stop()
        onDoneCallback = null
    }

    fun updateSpeechSettings() {
        applySpeechRateAndPitch()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
