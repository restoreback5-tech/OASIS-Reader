package com.oasis.reader

import android.content.Context
import android.media.MediaPlayer
import android.util.SparseArray

class SoundModule(private val context: Context) {

    private val players = SparseArray<MediaPlayer>()

    init {
        preload(R.raw.touch)
        preload(R.raw.deslizar)
        preload(R.raw.confirmar)
        preload(R.raw.check_on)
        preload(R.raw.error)
        preload(R.raw.inicio)
        preload(R.raw.page_flip)
    }

    private fun preload(resId: Int) {
        try {
            val player = MediaPlayer.create(context, resId)
            player?.isLooping = false
            players.put(resId, player)
        } catch (e: Exception) { }
    }

    fun play(resId: Int) {
        val player = players.get(resId)
        if (player != null) {
            if (player.isPlaying) player.seekTo(0)
            player.start()
        } else {
            try {
                MediaPlayer.create(context, resId)?.apply {
                    start()
                    setOnCompletionListener { release() }
                }
            } catch (e: Exception) { }
        }
    }

    fun release() {
        for (i in 0 until players.size()) {
            players.valueAt(i)?.release()
        }
        players.clear()
    }
}
