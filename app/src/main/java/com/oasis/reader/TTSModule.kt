package com.oasis.reader

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class TTSModule(ctx: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val prefs: SharedPreferences = ctx.getSharedPreferences("oasis_settings", Context.MODE_PRIVATE)
    private var onDoneCallback: (() -> Unit)? = null

    init {
        tts = TextToSpeech(ctx, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
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
        }
    }

    private fun applySpeechRateAndPitch() {
        val speed = prefs.getFloat("voice_speed", 1.0f)
        val pitch = prefs.getFloat("voice_pitch", 1.0f)
        tts?.setSpeechRate(speed)
        tts?.setPitch(pitch)
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
