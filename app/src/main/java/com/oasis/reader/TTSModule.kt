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
    
    // Callbacks protegidos por 'synchronized'
    private var onDoneCallback: (() -> Unit)? = null
    private var onReadyCallback: (() -> Unit)? = null
    private var currentUtteranceId: String? = null

    init {
        createEngine()
    }

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
                finish(utteranceId, false)
            }

            override fun onError(utteranceId: String?) {
                finish(utteranceId, true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finish(utteranceId, true)
            }
        })

        onReadyCallback?.invoke()
        onReadyCallback = null
    }

    // Toma el callback, lo borra y luego lo invoca (evita condiciones de carrera).
    private fun finish(utteranceId: String?, error: Boolean) {
        synchronized(this) {
            if (utteranceId == null || utteranceId != currentUtteranceId) return
            val callback = onDoneCallback
            onDoneCallback = null
            currentUtteranceId = null
            if (error) {
                // Opcional: podrías notificar un error distinto aquí si quisieras
                callback?.invoke() // Por ahora usamos el mismo callback para simplificar
            } else {
                callback?.invoke()
            }
        }
    }

    private fun applySpeechRateAndPitch() {
        val speed = prefs.getFloat("voice_speed", 1.0f)
        val pitch = prefs.getFloat("voice_pitch", 1.0f)
        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)
    }

    fun getEngines(): List<TextToSpeech.EngineInfo> = tts?.engines ?: emptyList()
    fun getSavedEngine(): String? = prefs.getString(KEY_ENGINE, null)

    fun setEngine(pkg: String?, onReady: (() -> Unit)? = null) {
        val editor = prefs.edit()
        if (pkg.isNullOrEmpty()) editor.remove(KEY_ENGINE) else editor.putString(KEY_ENGINE, pkg)
        editor.apply()

        synchronized(this) {
            onDoneCallback = null
            currentUtteranceId = null
        }
        onReadyCallback = onReady
        tts?.stop()
        tts?.shutdown()
        tts = null
        createEngine()
    }

    fun speak(text: String, onError: (() -> Unit)? = null, onDone: (() -> Unit)? = null) {
        val id = UUID.randomUUID().toString()
        synchronized(this) {
            currentUtteranceId = id
            onDoneCallback = {
                // Envolvemos para capturar errores
                try { onDone?.invoke() } catch (e: Exception) { onError?.invoke() }
            }
        }
        
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR || tts == null) {
            synchronized(this) {
                currentUtteranceId = null
                onDoneCallback = null
            }
            onError?.invoke()
        }
    }

    fun stop() {
        tts?.stop()
        synchronized(this) {
            currentUtteranceId = null
            onDoneCallback = null
        }
    }

    fun updateSpeechSettings() {
        applySpeechRateAndPitch()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        synchronized(this) {
            currentUtteranceId = null
            onDoneCallback = null
            onReadyCallback = null
        }
    }
}
