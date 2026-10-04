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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.oasis.turtle.TurtleView
import kotlin.math.abs

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
    private lateinit var turtleWidget: TurtleView
    private lateinit var tvTtsEngine: TextView

    // Swipe
    private lateinit var gestureDetector: GestureDetector
    private var touchStartX = 0f
    private var touchEndX = 0f

    // Datos del libro
    private var chapterTitles = mutableListOf<String>()
    private var chapterContents = mutableListOf<List<String>>()
    private var currentChapterIndex = 0
    private var currentParagraphIndex = 0
    private var isPlaying = false
    private var currentUri: Uri? = null

    private lateinit var epubParser: EpubParser
    private var parsedBook: EpubParser.ParsedEpub? = null

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
        turtleWidget = findViewById(R.id.turtle_widget)

        setupSliders()
        setupThemes()

        btnOpenDrawer.setOnClickListener {
            sound.play(R.raw.touch)
            drawerLayout.openDrawer(GravityCompat.START)
        }

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

        tvTtsEngine = findViewById(R.id.btn_tts_engine)
        updateTtsEngineLabel()
        tvTtsEngine.setOnClickListener {
            sound.play(R.raw.touch)
            showTtsEngineDialog()
        }

        // Restaurar último libro si existe
        val lastBookUri = prefs.getString("last_book_uri", null)
        if (!lastBookUri.isNullOrEmpty()) {
            currentUri = Uri.parse(lastBookUri)
            loadBookFromUri(currentUri!!)
        }

        // Configurar Swipe
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    touchStartX = e1.x
                    touchEndX = e2.x
                    handleSwipe()
                }
                return true
            }
        })

        scrollText.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun handleSwipe() {
        val swipeThreshold = 100
        val diff = touchStartX - touchEndX
        if (abs(diff) > swipeThreshold) {
            if (diff > 0) {
                nextParagraph()
            } else {
                previousParagraph()
            }
        }
    }

    private fun nextParagraph() {
        sound.play(R.raw.page_flip)
        if (isPlaying) pauseReading()
        val paragraphs = chapterContents.getOrNull(currentChapterIndex) ?: return
        val totalParagraphs = paragraphs.size
        if (currentParagraphIndex < totalParagraphs - 1) {
            currentParagraphIndex++
            showCurrentContent()
            saveProgress()
            turtleWidget.onPageAdvanced(currentParagraphIndex, totalParagraphs)
        } else if (currentChapterIndex < chapterContents.size - 1) {
            currentChapterIndex++
            currentParagraphIndex = 0
            showCurrentContent()
            saveProgress()
            turtleWidget.onPageAdvanced(currentParagraphIndex, chapterContents[currentChapterIndex].size)
        }
    }

    private fun previousParagraph() {
        sound.play(R.raw.page_flip)
        if (isPlaying) pauseReading()
        if (currentParagraphIndex > 0) {
            currentParagraphIndex--
            showCurrentContent()
            saveProgress()
        } else if (currentChapterIndex > 0) {
            currentChapterIndex--
            currentParagraphIndex = chapterContents[currentChapterIndex].size - 1
            showCurrentContent()
            saveProgress()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
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
            parsedBook = parsed
            rebuildVisibleSections()
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

    // ---- Secciones ocultas (notas, abreviaturas, apéndices cortos...) ----

    private fun manualHiddenKey(): String = "manual_hidden_" + (currentUri?.toString() ?: "").hashCode()

    private fun manualHiddenSet(): MutableSet<String> =
        (prefs.getStringSet(manualHiddenKey(), emptySet()) ?: emptySet()).toMutableSet()

    private fun isSectionHidden(book: EpubParser.ParsedEpub, i: Int, manual: Set<String>): Boolean =
        book.chapterHidden.getOrElse(i) { false } || book.chapterTitles[i] in manual

    // Arma la lista de capítulos visibles. Si "mostrar ocultas" está activo, entran todas.
    private fun rebuildVisibleSections() {
        val book = parsedBook ?: return
        val showAll = prefs.getBoolean("show_hidden_sections", false)
        val manual = manualHiddenSet()
        val titles = mutableListOf<String>()
        val contents = mutableListOf<List<String>>()
        for (i in book.chapterTitles.indices) {
            if (showAll || !isSectionHidden(book, i, manual)) {
                titles.add(book.chapterTitles[i])
                contents.add(book.chapterContents[i])
            }
        }
        if (titles.isEmpty()) { // por si todo quedó oculto
            titles.addAll(book.chapterTitles)
            contents.addAll(book.chapterContents)
        }
        chapterTitles = titles
        chapterContents = contents
    }

    private fun countHiddenSections(): Int {
        val book = parsedBook ?: return 0
        val manual = manualHiddenSet()
        return book.chapterTitles.indices.count { isSectionHidden(book, it, manual) }
    }

    // Reconstruye la lista y se queda en el mismo capítulo si sigue visible.
    private fun refreshSections() {
        val currentTitle = chapterTitles.getOrNull(currentChapterIndex)
        if (isPlaying) pauseReading()
        rebuildVisibleSections()
        val idx = chapterTitles.indexOf(currentTitle)
        if (idx >= 0) {
            currentChapterIndex = idx
        } else {
            currentChapterIndex = currentChapterIndex.coerceIn(0, chapterTitles.size - 1)
            currentParagraphIndex = 0
        }
        showCurrentContent()
        saveProgress()
    }

    private fun toggleShowHiddenSections() {
        val showAll = prefs.getBoolean("show_hidden_sections", false)
        prefs.edit().putBoolean("show_hidden_sections", !showAll).apply()
        refreshSections()
    }

    private fun toggleManualHidden(title: String) {
        val manual = manualHiddenSet()
        if (!manual.remove(title)) manual.add(title)
        prefs.edit().putStringSet(manualHiddenKey(), manual).apply()
        refreshSections()
    }

    private fun showChapterListDialog() {
        if (chapterTitles.isEmpty()) {
            Toast.makeText(this, "No hay capítulos cargados", Toast.LENGTH_SHORT).show()
            return
        }
        val showAll = prefs.getBoolean("show_hidden_sections", false)
        val hiddenCount = countHiddenSections()
        val items = chapterTitles.toMutableList()
        if (hiddenCount > 0) {
            items.add(
                if (showAll) "Ocultar secciones auxiliares ($hiddenCount)"
                else "Mostrar secciones ocultas ($hiddenCount)"
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(items.toTypedArray()) { _, which ->
                if (which >= chapterTitles.size) {
                    toggleShowHiddenSections()
                } else if (which != currentChapterIndex) {
                    sound.play(R.raw.page_flip)
                    currentChapterIndex = which
                    currentParagraphIndex = 0
                    if (isPlaying) pauseReading()
                    showCurrentContent()
                    saveProgress()
                }
            }
            .create()
        dialog.show()
        // Mantener presionado un título para ocultarlo (o dejar de ocultarlo).
        dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position >= chapterTitles.size) return@setOnItemLongClickListener false
            val title = chapterTitles[position]
            val alreadyHidden = title in manualHiddenSet()
            AlertDialog.Builder(this)
                .setMessage(if (alreadyHidden) "¿Dejar de ocultar \"$title\"?" else "¿Ocultar \"$title\"?")
                .setPositiveButton(if (alreadyHidden) "Dejar de ocultar" else "Ocultar") { _, _ ->
                    dialog.dismiss()
                    toggleManualHidden(title)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
    }

    private fun updateTtsEngineLabel() {
        val saved = tts.getSavedEngine()
        val label = if (saved == null) null else tts.getEngines().firstOrNull { it.name == saved }?.label
        tvTtsEngine.text = label ?: "Predeterminado del sistema"
    }

    private fun showTtsEngineDialog() {
        val engines = tts.getEngines()
        if (engines.isEmpty()) {
            Toast.makeText(this, "No se encontraron motores de voz instalados", Toast.LENGTH_LONG).show()
            return
        }
        val names = arrayOf("Predeterminado del sistema") + engines.map { it.label }
        val saved = tts.getSavedEngine()
        val checked = if (saved == null) 0 else {
            val idx = engines.indexOfFirst { it.name == saved }
            if (idx < 0) 0 else idx + 1
        }
        AlertDialog.Builder(this)
            .setTitle("Motor de voz")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val pkg = if (which == 0) null else engines[which - 1].name
                if (isPlaying) pauseReading()
                tts.setEngine(pkg) {
                    updateTtsEngineLabel()
                    Toast.makeText(this, "Motor de voz: ${tvTtsEngine.text}", Toast.LENGTH_SHORT).show()
                }
                updateTtsEngineLabel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
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

    private fun splitTextForTts(text: String, maxLength: Int = 800): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        for (sentence in sentences) {
            if (currentChunk.length + sentence.length + 1 > maxLength) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    currentChunk.clear()
                }
                if (sentence.length > maxLength) {
                    var remaining = sentence
                    while (remaining.length > maxLength) {
                        val splitPos = remaining.take(maxLength).lastIndexOf(' ')
                        val chunk = if (splitPos > 0) remaining.substring(0, splitPos) else remaining.take(maxLength)
                        chunks.add(chunk.trim())
                        remaining = remaining.substring(chunk.length).trimStart()
                    }
                    if (remaining.isNotEmpty()) currentChunk.append(remaining)
                } else {
                    currentChunk.append(sentence)
                }
            } else {
                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                currentChunk.append(sentence)
            }
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk.toString().trim())
        return chunks.ifEmpty { listOf(text) }
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
        val chunks = splitTextForTts(text)
        var chunkIndex = 0

        fun speakNextChunk() {
            if (!isPlaying) return
            if (chunkIndex < chunks.size) {
                tts.speak(chunks[chunkIndex]) {
                    runOnUiThread {
                        chunkIndex++
                        speakNextChunk()
                    }
                }
            } else {
                runOnUiThread {
                    currentParagraphIndex++
                    saveProgress()
                    val totalParagraphs = chapterContents[currentChapterIndex].size
                    turtleWidget.onPageAdvanced(currentParagraphIndex, totalParagraphs)
                    readCurrentParagraph()
                    showCurrentContent()
                }
            }
        }
        speakNextChunk()
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
        val isNight = themeKey == "noche"
        turtleWidget.setNightMode(isNight)
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
        tts.shutdown()
        saveProgress()
    }
}

