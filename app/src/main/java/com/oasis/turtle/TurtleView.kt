package com.oasis.turtle
import com.oasis.reader.R

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.random.Random

class TurtleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var turtleImage: ImageView? = null
    private var leafImage: ImageView? = null
    private var branchImage: ImageView? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private val random = Random
    private val prefsName = "turtle_prefs"
    private val keyAccumulated = "accumulated_leaves"

    // Array con las variantes de hojas para aleatoriedad
    private val leafDrawables = intArrayOf(
        R.drawable.leaf_single,
        R.drawable.leaf_diagonal,
        R.drawable.leaf_horizontal
    )

    private var isNightMode = false
    private var accumulatedLeaves = 0

    init {
        LayoutInflater.from(context).inflate(R.layout.view_turtle, this, true)
        turtleImage = findViewById(R.id.turtle_image)
        leafImage = findViewById(R.id.leaf_image)
        branchImage = findViewById(R.id.branch_image)
        
        // Cargar acumulados guardados
        loadAccumulatedLeaves()
        
        // Estado inicial: Día, tortuga idle
        setNightMode(false)
        turtleImage?.setImageResource(R.drawable.totu_idle)
    }
    fun setNightMode(isNight: Boolean) {
        isNightMode = isNight
        if (isNight) {
            hideTurtle()
        } else {
            showTurtle()
            // Al volver al día, aseguramos estado idle
            turtleImage?.setImageResource(R.drawable.totu_idle)
        }
    }

    fun onPageAdvanced(currentPage: Int, totalPages: Int) {
        // Siempre animamos la caída de la hoja
        animateLeafDrop()
    }

    private fun animateLeafDrop() {
        // 1. Seleccionar hoja aleatoria
        val randomLeaf = leafDrawables.random()
        leafImage?.setImageResource(randomLeaf)
        leafImage?.visibility = View.VISIBLE

        // 2. Sonido de caída (siempre activo)
        playSound(R.raw.leaves_fall_single)

        // 3. Iniciar animación
        val animation = AnimationUtils.loadAnimation(context, R.anim.fall_leaf)
        leafImage?.startAnimation(animation)

        animation.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                leafImage?.visibility = View.GONE

                if (isNightMode) {
                    // Modo Noche: Acumular hoja
                    accumulatedLeaves++
                    saveAccumulatedLeaves()
                } else {
                    // Modo Día: Probabilidad de sonido de comer (30%)
                    if (random.nextFloat() < 0.3f) {
                        playSound(R.raw.turtle_eat_crunch)
                        // Opcional: Pequeño movimiento o cambio de imagen sutil si se desea
                        // Por ahora, solo sonido ambiental como solicitado
                    }
                }
            }
        })    }

    private fun showTurtle() {
        turtleImage?.visibility = View.VISIBLE
    }

    private fun hideTurtle() {
        turtleImage?.visibility = View.GONE
    }

    private fun playSound(resId: Int) {
        // Evitar solapamiento excesivo de sonidos cortos
        if (mediaPlayer?.isPlaying == true) {
            return
        }
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(context, resId)
        mediaPlayer?.setVolume(0.5f, 0.5f)
        mediaPlayer?.start()
    }

    // --- Persistencia ---

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private fun loadAccumulatedLeaves() {
        accumulatedLeaves = getPrefs().getInt(keyAccumulated, 0)
    }

    private fun saveAccumulatedLeaves() {
        getPrefs().edit().putInt(keyAccumulated, accumulatedLeaves).apply()
    }

    fun getAccumulatedLeaves(): Int = accumulatedLeaves
    
    fun consumeAccumulatedLeaves(amount: Int) {
        if (accumulatedLeaves >= amount) {
            accumulatedLeaves -= amount
            saveAccumulatedLeaves()
        }
    }

    fun pauseAnimations() {
        mediaPlayer?.pause()
    }

    fun resumeAnimations() {
        mediaPlayer?.start()    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
