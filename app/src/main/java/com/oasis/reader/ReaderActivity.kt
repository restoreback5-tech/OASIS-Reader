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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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

    // Índice jerárquico
    private var hierarchicalChapters: List<ChapterNode> = emptyList()
    // Mapa de título limpio -> índice en la lista plana
    private val titleToIndexMap = mutableMapOf<String, Int>()

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
            chapterTitles = parsed.chapterTitles
            chapterContents = parsed.chapterContents
            // Construir mapa de título limpio a índice
            titleToIndexMap.clear()
            chapterTitles.forEachIndexed { index, title ->
                titleToIndexMap[cleanHtmlTitle(title)] = index
            }
            // Cargar índice jerárquico
            hierarchicalChapters = epubParser.getHierarchicalChapters(uri)
            if (hierarchicalChapters.isNotEmpty()) {
                // No necesitamos flattenNodes si usamos el mapa
            }
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
        if (hierarchicalChapters.isNotEmpty()) {
            showHierarchicalChapterDialog()
        } else {
            showFlatChapterDialog()
        }
    }

    private fun showHierarchicalChapterDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Índice")
        val expandableListView = ExpandableListView(this)
        val groupList = ArrayList<Map<String, String>>()
        val childList = ArrayList<ArrayList<Map<String, String>>>()
        buildExpandableData(hierarchicalChapters, groupList, childList)
        val adapter = SimpleExpandableListAdapter(
            this,
            groupList,
            android.R.layout.simple_expandable_list_item_1,
            arrayOf("title"),
            intArrayOf(android.R.id.text1),
            childList,
            android.R.layout.simple_list_item_1,
            arrayOf("title"),
            intArrayOf(android.R.id.text1)
        )
        expandableListView.setAdapter(adapter)
        builder.setView(expandableListView)
        val dialog = builder.create()
        expandableListView.setOnChildClickListener { _, _, groupPosition, childPosition, _ ->
            val node = getNodeAtPosition(groupPosition, childPosition, hierarchicalChapters)
            node?.let {
                jumpToChapter(it)
                dialog.dismiss()
            }
            true
        }
        expandableListView.setOnGroupClickListener { _, _, groupPosition, _ ->
            val node = getNodeAtPosition(groupPosition, hierarchicalChapters)
            if (node != null && node.children.isEmpty()) {
                jumpToChapter(node)
                dialog.dismiss()
                return@setOnGroupClickListener true
            }
            false // permite expandir si tiene hijos
        }
        dialog.show()
    }

    private fun buildExpandableData(
        nodes: List<ChapterNode>,
        groupList: ArrayList<Map<String, String>>,
        childList: ArrayList<ArrayList<Map<String, String>>>
    ) {
        for (node in nodes) {
            val groupMap = HashMap<String, String>()
            groupMap["title"] = cleanHtmlTitle(node.title)
            groupList.add(groupMap)
            val childrenArray = ArrayList<Map<String, String>>()
            for (child in node.children) {
                val childMap = HashMap<String, String>()
                childMap["title"] = cleanHtmlTitle(child.title)
                childrenArray.add(childMap)
            }
            childList.add(childrenArray)
        }
    }

    private fun getNodeAtPosition(groupPosition: Int, childPosition: Int, nodes: List<ChapterNode>): ChapterNode? {
        var currentGroup = 0
        for (node in nodes) {
            if (currentGroup == groupPosition) {
                return if (childPosition >= 0 && childPosition < node.children.size) node.children[childPosition] else node
            }
            currentGroup++
        }
        return null
    }

    private fun getNodeAtPosition(groupPosition: Int, nodes: List<ChapterNode>): ChapterNode? {
        var currentGroup = 0
        for (node in nodes) {
            if (currentGroup == groupPosition) return node
            currentGroup++
        }
        return null
    }

    private fun jumpToChapter(node: ChapterNode) {
        // Primero intentar con el título limpio del nodo
        val cleanTitle = cleanHtmlTitle(node.title)
        var index = titleToIndexMap[cleanTitle]
        // Si no se encuentra, intentar búsqueda parcial (por si hay diferencias)
        if (index == null) {
            index = chapterTitles.indexOfFirst { cleanHtmlTitle(it).contains(cleanTitle, ignoreCase = true) }
        }
        // Si aún no se encuentra, usar el orden de aplanamiento (menos fiable)
        if (index == null || index == -1) {
            index = getFlatIndexForNode(node)
        }
        if (index != null && index in chapterTitles.indices && index != currentChapterIndex) {
            sound.play(R.raw.page_flip)
            currentChapterIndex = index
            currentParagraphIndex = 0
            tts.stop()
            showCurrentContent()
            saveProgress()
        } else {
            Toast.makeText(this, "No se pudo encontrar el capítulo: ${cleanTitle}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFlatIndexForNode(node: ChapterNode): Int? {
        // Recorrer jerárquicamente y devolver el orden de aparición en profundidad
        var counter = 0
        fun dfs(nodes: List<ChapterNode>): Int? {
            for (n in nodes) {
                if (n === node) return counter
                counter++
                if (n.children.isNotEmpty()) {
                    val found = dfs(n.children)
                    if (found != null) return found
                }
            }
            return null
        }
        return dfs(hierarchicalChapters)
    }

    private fun showFlatChapterDialog() {
        AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(chapterTitles.toTypedArray()) { _, which ->
                if (which != currentChapterIndex) {
                    sound.play(R.raw.page_flip)
                    currentChapterIndex = which
                    currentParagraphIndex = 0
                    tts.stop()
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
        val gradient = when (themeKey) {
            "noche" -> {
                // Midnight Premium: Profundo y serio
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#121212"), 
                        Color.parseColor("#1E1E1E")
                    )
                )
            }
            "caribe" -> {
                // Deep Ocean: Sofisticado y fresco
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#0F2027"), 
                        Color.parseColor("#203A43")
                    )
                )
            }
            else -> {
                // Warm Paper: Cálido y natural (Default/Amanecer)
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#FDFBF7"), 
                        Color.parseColor("#F5F0E6")
                    )
                )
            }
        }

        // Aplicar fondo general
        drawerLayout.background = gradient
        window.statusBarColor = if (themeKey == "amanecer") Color.parseColor("#F5F0E6") else Color.parseColor("#000000")
        window.decorView.setBackgroundColor(Color.TRANSPARENT) // Dejar que el gradient se vea

        // Definir paleta de textos y acentos
        val (textMain, textValues, accentColor) = when (themeKey) {
            "noche" -> Triple(
                Color.parseColor("#E0E0E0"), // Texto principal suave
                Color.parseColor("#A0A0A0"), // Valores secundarios
                Color.parseColor("#64B5F6")  // Azul Acero brillante
            )
            "caribe" -> Triple(
                Color.parseColor("#F0F8FF"), // Blanco hielo
                Color.parseColor("#B0E0E6"), // Azul pálido
                Color.parseColor("#4DD0E1")  // Cian vibrante
            )
            else -> Triple( // Amanecer / Default
                Color.parseColor("#2C2C2C"), // Gris carbón
                Color.parseColor("#5D4037"), // Marrón tierra
                Color.parseColor("#FFB74D")  // Ámbar cálido
            )
        }

        // Aplicar colores a textos
        tvBookContent.setTextColor(textMain)
        speedValueText.setTextColor(textValues)
        pitchValueText.setTextColor(textValues)

        // Aplicar acentos a SeekBars
        val accentStateList = ColorStateList.valueOf(accentColor)
        seekSpeed.progressTintList = accentStateList
        seekSpeed.thumbTintList = accentStateList
        
        seekPitch.progressTintList = accentStateList
        seekPitch.thumbTintList = accentStateList
        
        seekBrightness.progressTintList = accentStateList
        seekBrightness.thumbTintList = accentStateList
        
        seekTextSize.progressTintList = accentStateList
        seekTextSize.thumbTintList = accentStateList

        // Configu,rar widget tortuga
        turtleWidget.setNightMode(themeKey != "amanecer")
    }

        private fun decodeHtmlEntities(text: String): String {
    var result = text
    result = result.replace("&nbsp;", " ")
    result = result.replace("&amp;", "&")
    result = result.replace("&lt;", "<")
    result = result.replace("&gt;", ">")
    result = result.replace("&quot;", "\"")
    result = result.replace("&#39;", "'")
    return result
}

private fun cleanHtmlTitle(html: String): String {
    val withoutTags = html.replace(Regex("<[^>]*>"), "")
    val decoded = decodeHtmlEntities(withoutTags)
    return decoded.trim().let {
        if (it.isBlank() || it.length < 2) "Sección" else it
    }
}

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
        tts.shutdown()
        saveProgress()
    }
}
                    
