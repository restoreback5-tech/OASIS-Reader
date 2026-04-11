package com.oasis.reader

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class ReaderActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var scrollText: ScrollView
    private lateinit var tvBookContent: TextView
    private lateinit var btnOpenDrawer: ImageButton
    private lateinit var sound: SoundModule
    private lateinit var tts: TTSModule
    private lateinit var prefs: SharedPreferences
    private val REQUEST_CODE_OPEN_DOCUMENT = 1000

    // Sliders
    private lateinit var seekSpeed: SeekBar
    private lateinit var seekPitch: SeekBar
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekTextSize: SeekBar
    private lateinit var speedValueText: TextView
    private lateinit var pitchValueText: TextView

    // Temas
    private lateinit var themeSol: View
    private lateinit var themeLuna: View
    private lateinit var themeNubes: View
    private lateinit var indicatorSol: ImageView
    private lateinit var indicatorLuna: ImageView
    private lateinit var indicatorNubes: ImageView

    // Datos del libro
    private var chapterTitles = mutableListOf<String>()
    private var chapterContents = mutableListOf<List<String>>()
    private var currentChapterIndex = 0
    private var currentParagraphIndex = 0
    private var isPlaying = false
    private var currentUri: Uri? = null

    // Parser EPUB
    private lateinit var epubParser: EpubParser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        prefs = getSharedPreferences("oasis_settings", MODE_PRIVATE)
        sound = SoundModule(this)
        tts = TTSModule(this)
        epubParser = EpubParser(contentResolver)

        drawerLayout = findViewById(R.id.drawer_layout)
        scrollText = findViewById(R.id.scroll_text)
        tvBookContent = findViewById(R.id.tv_book_content)
        btnOpenDrawer = findViewById(R.id.btn_open_drawer)

        seekSpeed = findViewById(R.id.seekbar_tts_speed)
        seekPitch = findViewById(R.id.seekbar_tts_pitch)
        seekBrightness = findViewById(R.id.seekbar_brightness)
        seekTextSize = findViewById(R.id.seekbar_text_size)
        speedValueText = findViewById(R.id.text_tts_speed_value)
        pitchValueText = findViewById(R.id.text_tts_pitch_value)

        themeSol = findViewById(R.id.theme_sol)
        themeLuna = findViewById(R.id.theme_luna)
        themeNubes = findViewById(R.id.theme_nubes)
        indicatorSol = findViewById(R.id.indicator_sol)
        indicatorLuna = findViewById(R.id.indicator_luna)
        indicatorNubes = findViewById(R.id.indicator_nubes)

        btnOpenDrawer.setOnClickListener {
            sound.play(R.raw.touch)
            drawerLayout.openDrawer(GravityCompat.START)
        }

        setupSliders()
        setupThemes()

        findViewById<ImageButton>(R.id.btn_book).setOnClickListener {
            sound.play(R.raw.touch)
            openFileSelector()
        }

        findViewById<ImageButton>(R.id.btn_play).setOnClickListener {
            sound.play(R.raw.touch)
            if (chapterContents.isEmpty()) {
                Toast.makeText(this, "Primero selecciona un libro EPUB", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isPlaying) pauseReading()
            else startReading()
        }

        findViewById<ImageButton>(R.id.btn_chapters).setOnClickListener {
            sound.play(R.raw.touch)
            showChapterListDialog()
        }

        // Restaurar último libro si existe
        val lastBookUri = prefs.getString("last_book_uri", null)
        if (!lastBookUri.isNullOrEmpty()) {
            currentUri = Uri.parse(lastBookUri)
            loadBookFromUri(currentUri!!)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                sound.play(R.raw.confirmar)
                currentUri = uri
                prefs.edit().putString("last_book_uri", uri.toString()).apply()
                loadBookFromUri(uri)
            }
        }
    }

    private fun openFileSelector() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT)
    }

    private fun loadBookFromUri(uri: Uri) {
        try {
            val parsed = epubParser.parse(uri)
            if (parsed.chapterContents.isEmpty()) {
                Toast.makeText(this, "No se encontraron capítulos en el EPUB", Toast.LENGTH_LONG).show()
                return
            }

            chapterTitles = parsed.chapterTitles
            chapterContents = parsed.chapterContents

            currentChapterIndex = prefs.getInt("last_chapter_index", 0).coerceIn(0, chapterContents.size - 1)
            currentParagraphIndex = prefs.getInt("last_paragraph_index", 0)
            if (currentParagraphIndex >= chapterContents[currentChapterIndex].size) currentParagraphIndex = 0

            showCurrentContent()
            Toast.makeText(this, "Libro cargado: ${chapterTitles.size} capítulos", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al leer el libro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCurrentContent() {
        if (currentChapterIndex < chapterContents.size) {
            val title = chapterTitles[currentChapterIndex]
            val paragraphs = chapterContents[currentChapterIndex]
            val paragraphText = if (currentParagraphIndex < paragraphs.size) paragraphs[currentParagraphIndex] else paragraphs.lastOrNull() ?: ""

            val spannable = SpannableString("$title\n\n$paragraphText")
            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            tvBookContent.text = spannable
            scrollText.scrollTo(0, 0)
        }
    }

    private fun showChapterListDialog() {
        if (chapterTitles.isEmpty()) {
            Toast.makeText(this, "No hay capítulos cargados", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(chapterTitles.toTypedArray()) { _, which ->
                if (which != currentChapterIndex) {
                    sound.play(R.raw.page_flip)
                    currentChapterIndex = which
                    currentParagraphIndex = 0
                    if (isPlaying) pauseReading()
                    showCurrentContent()
                    saveProgress()
                }
            }
            .show()
    }

    private fun startReading() {
        if (chapterContents.isEmpty()) return
        isPlaying = true
        readCurrentParagraph()
    }

    private fun pauseReading() {
        isPlaying = false
        tts.stop()
        saveProgress()
    }

    private fun readCurrentParagraph() {
        if (!isPlaying) return
        if (currentChapterIndex >= chapterContents.size) {
            pauseReading()
            Toast.makeText(this, "Fin del libro", Toast.LENGTH_SHORT).show()
            return
        }
        val paragraphs = chapterContents[currentChapterIndex]
        if (currentParagraphIndex >= paragraphs.size) {
            sound.play(R.raw.page_flip)
            currentParagraphIndex = 0
            currentChapterIndex++
            if (currentChapterIndex < chapterContents.size) {
                showCurrentContent()
                saveProgress()
                readCurrentParagraph()
            } else {
                pauseReading()
                Toast.makeText(this, "Has terminado el libro", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val text = paragraphs[currentParagraphIndex]
        tts.speak(text) {
            runOnUiThread {
                currentParagraphIndex++
                saveProgress()
                readCurrentParagraph()
                showCurrentContent()
            }
        }
    }

    private fun saveProgress() {
        prefs.edit().putInt("last_chapter_index", currentChapterIndex).apply()
        prefs.edit().putInt("last_paragraph_index", currentParagraphIndex).apply()
    }

    private fun setupSliders() {
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sound.play(R.raw.deslizar)
                    val speed = 0.5f + (progress / 100f) * 1.5f
                    speedValueText.text = String.format("%.1fx", speed)
                    prefs.edit().putFloat("voice_speed", speed).apply()
                    tts.updateSpeechSettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sound.play(R.raw.deslizar)
                    val pitch = 0.5f + (progress / 100f) * 1.0f
                    pitchValueText.text = String.format("%.1fx", pitch)
                    prefs.edit().putFloat("voice_pitch", pitch).apply()
                    tts.updateSpeechSettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sound.play(R.raw.deslizar)
                val alpha = progress / 100f
                findViewById<View>(R.id.scroll_text).alpha = alpha
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sound.play(R.raw.deslizar)
                tvBookContent.textSize = progress.toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val savedSpeed = prefs.getFloat("voice_speed", 1.0f)
        val savedPitch = prefs.getFloat("voice_pitch", 1.0f)
        seekSpeed.progress = ((savedSpeed - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
        seekPitch.progress = ((savedPitch - 0.5f) / 1.0f * 100).toInt().coerceIn(0, 100)
        speedValueText.text = String.format("%.1fx", savedSpeed)
        pitchValueText.text = String.format("%.1fx", savedPitch)
    }

    private fun setupThemes() {
        val themes = mapOf("amanecer" to themeSol, "caribe" to themeNubes, "noche" to themeLuna)
        val indicators = mapOf("amanecer" to indicatorSol, "caribe" to indicatorNubes, "noche" to indicatorLuna)
        themeSol.setOnClickListener { changeTheme("amanecer", themes, indicators) }
        themeLuna.setOnClickListener { changeTheme("noche", themes, indicators) }
        themeNubes.setOnClickListener { changeTheme("caribe", themes, indicators) }
        val currentTheme = prefs.getString("selected_theme", "amanecer") ?: "amanecer"
        updateThemeUI(currentTheme, indicators)
        applyTheme(currentTheme)
    }

    private fun changeTheme(themeKey: String, themes: Map<String, View>, indicators: Map<String, ImageView>) {
        sound.play(R.raw.check_on)
        prefs.edit().putString("selected_theme", themeKey).apply()
        updateThemeUI(themeKey, indicators)
        applyTheme(themeKey)
    }

    private fun updateThemeUI(themeKey: String, indicators: Map<String, ImageView>) {
        indicators.values.forEach { it.visibility = View.GONE }
        indicators[themeKey]?.visibility = View.VISIBLE
    }

    private fun applyTheme(themeKey: String) {
        val bgRes = when (themeKey) {
            "caribe" -> R.color.caribe_background
            "noche" -> R.color.oscuro_background
            else -> R.color.amanecer_background
        }
        window.decorView.setBackgroundColor(ContextCompat.getColor(this, bgRes))

        val textColorRes = when (themeKey) {
            "amanecer" -> R.color.amanecer_text
            "caribe" -> R.color.caribe_text
            "noche" -> R.color.oscuro_text
            else -> R.color.amanecer_text
        }
        tvBookContent.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
        tts.shutdown()
        saveProgress()
    }
}
