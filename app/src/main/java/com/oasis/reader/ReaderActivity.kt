package com.oasis.reader

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.*
import java.util.*
import java.util.zip.ZipInputStream

class ReaderActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager
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

    private val imageList = listOf(
        R.drawable.pexels_ahmed,
        R.drawable.pexels_ian_panelo,
        R.drawable.pexels_mutceevvil,
        R.drawable.pexels_houwang_nguyen,
        R.drawable.pexels_crissy
    )
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var imagePagerAdapter: ImagePagerAdapter

    // Datos del libro
    private var chapterTitles = mutableListOf<String>()
    private var chapterContents = mutableListOf<List<String>>() // cada capítulo: lista de párrafos
    private var currentChapterIndex = 0
    private var currentParagraphIndex = 0
    private var isPlaying = false
    private var currentUri: Uri? = null
    private var lastBookUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        prefs = getSharedPreferences("oasis_settings", MODE_PRIVATE)
        sound = SoundModule(this)
        tts = TTSModule(this)

        drawerLayout = findViewById(R.id.drawer_layout)
        viewPager = findViewById(R.id.viewpager_images)
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

        imagePagerAdapter = ImagePagerAdapter(imageList)
        viewPager.adapter = imagePagerAdapter
        startImageRotation()

        setupSliders()
        setupThemes()

        // Botón para abrir selector de archivo EPUB
        findViewById<ImageButton>(R.id.btn_book).setOnClickListener {
            sound.play(R.raw.touch)
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/epub+zip"
            }
            startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT)
        }

        // Botón Play/Pausa
        findViewById<ImageButton>(R.id.btn_play).setOnClickListener {
            sound.play(R.raw.touch)
            if (chapterContents.isEmpty()) {
                Toast.makeText(this, "Primero selecciona un libro EPUB", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isPlaying) pauseReading()
            else startReading()
        }

        // Botón para mostrar lista de capítulos (índice)
        findViewById<ImageButton>(R.id.btn_chapters).setOnClickListener {
            sound.play(R.raw.touch)
            showChapterListDialog()
        }

        // Restaurar último libro si existe
        lastBookUri = prefs.getString("last_book_uri", null)
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

    private fun loadBookFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val zip = ZipInputStream(inputStream)

            // 1. Encontrar container.xml
            var containerXml = ""
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.equals("META-INF/container.xml", ignoreCase = true)) {
                    containerXml = readEntryContent(zip)
                    break
                }
                entry = zip.nextEntry
            }
            zip.close()

            if (containerXml.isEmpty()) {
                Toast.makeText(this, "No se encontró container.xml en el EPUB", Toast.LENGTH_LONG).show()
                return
            }

            // 2. Parsear container.xml para obtener la ruta del OPF
            val opfPath = parseContainerXml(containerXml)
            if (opfPath.isNullOrEmpty()) {
                Toast.makeText(this, "No se pudo localizar el archivo OPF", Toast.LENGTH_LONG).show()
                return
            }

            // 3. Volver a abrir el ZIP para leer el OPF
            val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
            var opfContent = ""
            var opfDir = ""
            entry = zip2.nextEntry
            while (entry != null) {
                if (entry.name.equals(opfPath, ignoreCase = true)) {
                    opfContent = readEntryContent(zip2)
                    opfDir = File(opfPath).parent ?: ""
                    break
                }
                entry = zip2.nextEntry
            }
            zip2.close()
            if (opfContent.isEmpty()) {
                Toast.makeText(this, "No se pudo leer el archivo OPF", Toast.LENGTH_LONG).show()
                return
            }

            // 4. Parsear OPF para obtener la lista de capítulos y la tabla de contenidos
            val (items, spine) = parseOpf(opfContent, opfDir)
            if (spine.isEmpty()) {
                Toast.makeText(this, "No se encontraron capítulos en el EPUB", Toast.LENGTH_LONG).show()
                return
            }

            // 5. Leer los archivos de contenido (XHTML/HTML) y extraer texto plano
            chapterTitles.clear()
            chapterContents.clear()
            val zip3 = ZipInputStream(contentResolver.openInputStream(uri))
            val filesMap = mutableMapOf<String, String>()
            entry = zip3.nextEntry
            while (entry != null) {
                val name = entry.name
                if (items.containsKey(name)) {
                    val content = readEntryContent(zip3)
                    filesMap[name] = content
                }
                entry = zip3.nextEntry
            }
            zip3.close()

            for (href in spine) {
                val rawHtml = filesMap[href] ?: continue
                val plainText = htmlToPlainText(rawHtml)
                val paragraphs = plainText.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                if (paragraphs.isNotEmpty()) {
                    val title = items[href]?.second ?: "Capítulo ${chapterTitles.size + 1}"
                    chapterTitles.add(title)
                    chapterContents.add(paragraphs)
                }
            }

            if (chapterContents.isEmpty()) {
                Toast.makeText(this, "No se pudo extraer texto del libro", Toast.LENGTH_LONG).show()
                return
            }

            // Restaurar progreso guardado
            currentChapterIndex = prefs.getInt("last_chapter_index", 0).coerceIn(0, chapterContents.size - 1)
            currentParagraphIndex = prefs.getInt("last_paragraph_index", 0)
            if (currentParagraphIndex >= chapterContents[currentChapterIndex].size) {
                currentParagraphIndex = 0
            }

            showCurrentContent()
            Toast.makeText(this, "Libro cargado: ${chapterTitles.size} capítulos", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al leer el libro: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readEntryContent(zip: ZipInputStream): String {
        val bytes = zip.readBytes()
        return String(bytes, Charsets.UTF_8)
    }

    private fun parseContainerXml(xml: String): String? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    return fullPath
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseOpf(opfXml: String, opfDir: String): Pair<MutableMap<String, Pair<String, String>>, MutableList<String>> {
        val items = mutableMapOf<String, Pair<String, String>>() // href -> (mime, title)
        val spine = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(opfXml.reader())
            var eventType = parser.eventType
            var insideSpine = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val mediaType = parser.getAttributeValue(null, "media-type")
                                if (href != null && (mediaType?.contains("xhtml") == true || mediaType?.contains("html") == true)) {
                                    val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                                    items[fullHref] = Pair(mediaType ?: "", "")
                                }
                            }
                            "itemref" -> {
                                if (insideSpine) {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    val href = items.entries.find { it.value.first == idref }?.key
                                    if (href != null) spine.add(href)
                                }
                            }
                            "spine" -> insideSpine = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "spine") insideSpine = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(items, spine)
    }

    private fun htmlToPlainText(html: String): String {
        return html.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun showCurrentContent() {
        if (currentChapterIndex < chapterContents.size) {
            val paragraphs = chapterContents[currentChapterIndex]
            if (currentParagraphIndex < paragraphs.size) {
                tvBookContent.text = paragraphs[currentParagraphIndex]
            } else {
                tvBookContent.text = paragraphs.lastOrNull() ?: ""
            }
            scrollText.scrollTo(0, 0)
        }
    }

    private fun showChapterListDialog() {
        if (chapterTitles.isEmpty()) {
            Toast.makeText(this, "No hay capítulos cargados", Toast.LENGTH_SHORT).show()
            return
        }
        val items = chapterTitles.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Índice")
            .setItems(items) { _, which ->
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
                if (currentParagraphIndex < paragraphs.size) {
                    showCurrentContent()
                }
                readCurrentParagraph()
            }
        }
    }

    private fun saveProgress() {
        prefs.edit().putInt("last_chapter_index", currentChapterIndex).apply()
        prefs.edit().putInt("last_paragraph_index", currentParagraphIndex).apply()
    }

    private fun startImageRotation() {
        val runnable = object : Runnable {
            override fun run() {
                val currentItem = viewPager.currentItem
                val nextItem = if (currentItem + 1 < imageList.size) currentItem + 1 else 0
                viewPager.setCurrentItem(nextItem, true)
                handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(runnable, 5000)
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
                findViewById<View>(R.id.viewpager_images).alpha = alpha
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
        val themes = mapOf("amanecer" to themeSol, "caribe" to themeLuna, "oscuro" to themeNubes)
        val indicators = mapOf("amanecer" to indicatorSol, "caribe" to indicatorLuna, "oscuro" to indicatorNubes)
        themeSol.setOnClickListener { setTheme("amanecer", themes, indicators) }
        themeLuna.setOnClickListener { setTheme("caribe", themes, indicators) }
        themeNubes.setOnClickL

    private fun setupThemes() {
        val themes = mapOf("amanecer" to themeSol, "caribe" to themeLuna, "oscuro" to themeNubes)
        val indicators = mapOf("amanecer" to indicatorSol, "caribe" to indicatorLuna, "oscuro" to indicatorNubes)
        themeSol.setOnClickListener { setTheme("amanecer", themes, indicators) }
        themeLuna.setOnClickListener { setTheme("caribe", themes, indicators) }
        themeNubes.setOnClickListener { setTheme("oscuro", themes, indicators) }
        val currentTheme = prefs.getString("selected_theme", "amanecer") ?: "amanecer"
        updateThemeUI(currentTheme, indicators)
    }

    private fun setTheme(themeKey: String, themes: Map<String, View>, indicators: Map<String, ImageView>) {
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
            "oscuro" -> R.color.oscuro_background
            else -> R.color.amanecer_background
        }
        window.decorView.setBackgroundColor(ContextCompat.getColor(this, bgRes))
    }

    inner class ImagePagerAdapter(private val images: List<Int>) : PagerAdapter() {
        override fun getCount(): Int = images.size
        override fun isViewFromObject(view: View, obj: Any): Boolean = view == obj
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val imageView = ImageView(this@ReaderActivity)
            imageView.setImageResource(images[position])
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            container.addView(imageView)
            return imageView
        }
        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            container.removeView(obj as View)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sound.release()
        tts.shutdown()
        saveProgress()
    }
}
