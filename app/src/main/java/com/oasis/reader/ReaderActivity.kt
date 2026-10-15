package com.oasis.reader

import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
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
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan


class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val MAX_CHARS_POR_PARTE = 30_000
        private val TTS_HIGHLIGHT = Color.parseColor("#332196F3")
    }

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

    // Gestos
    private lateinit var gestureDetector: GestureDetector
    private var touchStartX = 0f
    private var touchStartY = 0f

    // Datos del libro
    private var chapterTitles = mutableListOf<String>()
    private var chapterContents = mutableListOf<List<String>>()
    private var currentChapterIndex = 0
    private var currentParagraphIndex = 0
    private var isPlaying = false
    private var currentUri: Uri? = null

    // Offsets de párrafos dentro del capítulo actual
    private var parrafoOffsets = IntArray(0)

    // Guardado con retardo
    private val scrollHandler = Handler(Looper.getMainLooper())
    private var scrollSaveRunnable: Runnable? = null

    private lateinit var epubParser: EpubParser
    private val pdfParser by lazy { PdfParser(this, contentResolver) }
    private var hiloCarga: Thread? = null
    private var dialogoCarga: AlertDialog? = null
    private var parsedBook: EpubParser.ParsedEpub? = null
    private var buscador: BuscadorLibro? = null
    private var consultaResaltada: String? = null
    private var resultadosBusqueda: List<BuscadorLibro.Resultado> = emptyList()
    private var posResultado = -1
    private var barraBusqueda: PopupWindow? = null
    private var contadorBusqueda: TextView? = null
    
    private val pedirNotificacion = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
    ) { granted ->
    if (!granted) {
        Toast.makeText(
            this@ReaderActivity,
            "La notificación no se verá, pero la lectura seguirá.",
            Toast.LENGTH_SHORT
        ).show()
    }
  }

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
                Toast.makeText(this, "Primero selecciona un libro (EPUB o PDF)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isPlaying) pauseReading()
            else startReading()
        }

        findViewById<ImageButton>(R.id.btn_chapters).setOnClickListener {
            sound.play(R.raw.touch)
            showChapterListDialog()
        }

        findViewById<ImageButton>(R.id.btn_chapters).setOnLongClickListener {
            showSearchDialog(); true
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
            loadBookFromUri(Uri.parse(lastBookUri))
        }

        // Gestos: swipe horizontal solo cambia de capítulo. El scroll vertical es nativo.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val minDx = 120 * resources.displayMetrics.density
                if (abs(dx) >= minDx && abs(dx) >= 2 * abs(dy)) {
                    if (dx < 0) irACapitulo(1) else irACapitulo(-1)
                    return true
                }
                return false
            }
        })

        scrollText.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        scrollText.setOnScrollChangeListener { _, _, _, _, _ ->
            scrollSaveRunnable?.let { scrollHandler.removeCallbacks(it) }
            scrollSaveRunnable = Runnable {
                if (chapterContents.isNotEmpty() && !isPlaying) {
                    val idx = parrafoVisibleArriba()
                    prefs.edit().putInt("last_chapter_index", currentChapterIndex).apply()
                    prefs.edit().putInt("last_paragraph_index", idx).apply()
                }
            }
            scrollHandler.postDelayed(scrollSaveRunnable!!, 500)
        }
    }

    private fun irACapitulo(delta: Int) {
        val nuevo = currentChapterIndex + delta
        if (nuevo < 0) {
            Toast.makeText(this, "Primer capítulo", Toast.LENGTH_SHORT).show()
            return
        }
        if (nuevo >= chapterContents.size) {
            Toast.makeText(this, "Último capítulo", Toast.LENGTH_SHORT).show()
            return
        }
        if (isPlaying) pauseReading()
        sound.play(R.raw.page_flip)
        currentChapterIndex = nuevo
        currentParagraphIndex = 0
        showCurrentContent()
        saveProgress()
        scrollText.scrollTo(0, 0)
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
                loadBookFromUri(uri)
            }
        }
    }

    private fun openFileSelector() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/pdf"))
        }
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT)
    }

    // Detecta PDF por su cabecera "%PDF-", sin fiarse del tipo MIME ni de la extensión.
    private fun esPdf(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(5)
            s.read(b) == 5 && String(b, Charsets.ISO_8859_1) == "%PDF-"
        } ?: false
    } catch (e: Exception) {
        false
    }

    // Lee el libro (EPUB o PDF) en segundo plano y lo aplica al terminar.
    private fun loadBookFromUri(uri: Uri) {
        if (hiloCarga?.isAlive == true) {
            Toast.makeText(this, "Ya se está cargando un libro", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogo = AlertDialog.Builder(this)
            .setMessage("Abriendo libro…")
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> hiloCarga?.interrupt() }
            .create()
        dialogo.show()
        dialogoCarga = dialogo

        hiloCarga = Thread {
            var libro: EpubParser.ParsedEpub? = null
            var error: Throwable? = null
            var aviso: String? = null
            try {
                if (esPdf(uri)) {
                    val p = pdfParser
                    libro = p.parse(uri) { hecho, total ->
                        if (hecho % 10 == 0 || hecho == total) {
                            runOnUiThread { dialogo.setMessage("Leyendo PDF… $hecho de $total páginas") }
                        }
                    }
                    aviso = p.aviso
                } else {
                    libro = epubParser.parse(uri)
                }
            } catch (e: Throwable) {
                error = e
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (dialogo.isShowing) dialogo.dismiss()
                val fallo = error
                val parsed = libro
                if (fallo != null || parsed == null) {
                    if (fallo !is InterruptedException) {
                        fallo?.printStackTrace()
                        AlertDialog.Builder(this)
                            .setTitle("No se pudo abrir el libro")
                            .setMessage(fallo?.message ?: "Error desconocido")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } else {
                    aplicarLibro(parsed, uri, aviso)
                }
            }
        }.also { it.start() }
    }

    private fun aplicarLibro(parsed: EpubParser.ParsedEpub, uri: Uri, aviso: String?) {
        if (parsed.chapterContents.isEmpty()) {
            Toast.makeText(this, "No se encontraron capítulos en el libro", Toast.LENGTH_LONG).show()
            return
        }
        if (isPlaying) pauseReading()
        // Se retoma el avance solo si es el mismo libro que se leía; si es otro, empieza desde el inicio.
        val mismoLibro = uri.toString() == prefs.getString("last_book_uri", null)
        currentUri = uri
        prefs.edit().putString("last_book_uri", uri.toString()).apply()
        parsedBook = parsed
        rebuildVisibleSections()
        if (mismoLibro) {
            currentChapterIndex = prefs.getInt("last_chapter_index", 0).coerceIn(0, chapterContents.size - 1)
            currentParagraphIndex = prefs.getInt("last_paragraph_index", 0)
        } else {
            currentChapterIndex = 0
            currentParagraphIndex = 0
        }
        if (currentParagraphIndex >= chapterContents[currentChapterIndex].size) currentParagraphIndex = 0
        showCurrentContent()
        scrollAParrafo(currentParagraphIndex)
        if (!mismoLibro) saveProgress()
        Toast.makeText(this, "Libro cargado: ${chapterTitles.size} capítulos", Toast.LENGTH_SHORT).show()
        if (aviso != null) {
            AlertDialog.Builder(this)
                .setTitle("Aviso")
                .setMessage(aviso)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showCurrentContent() {
        if (currentChapterIndex >= chapterContents.size) return
        val title = chapterTitles[currentChapterIndex]
        val paragraphs = chapterContents[currentChapterIndex]

        val sb = SpannableStringBuilder()
        sb.append(title)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n\n")

        val offsets = IntArray(paragraphs.size)
        for (i in paragraphs.indices) {
            offsets[i] = sb.length
            sb.append(paragraphs[i])
            if (i < paragraphs.size - 1) sb.append("\n\n")
        }
        parrafoOffsets = offsets
        
             // Resaltado de búsqueda
        val consulta = consultaResaltada
        val motor = buscador
        if (consulta != null && motor != null) {
            val actual = resultadosBusqueda.getOrNull(posResultado)
            for (i in paragraphs.indices) {
                for (r in motor.coincidencias(paragraphs[i], consulta)) {
                    val start = offsets[i] + r.first
                    val end = offsets[i] + r.last + 1
                    val esActual = actual != null && actual.capitulo == currentChapterIndex &&
                        actual.parrafo == i && actual.inicio == r.first
                    sb.setSpan(
                        BackgroundColorSpan(if (esActual) Color.parseColor("#FF9800") else Color.YELLOW),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        // Resaltado del párrafo que está sonando
        if (isPlaying && currentParagraphIndex in paragraphs.indices) {
            val start = offsets[currentParagraphIndex]
            val end = start + paragraphs[currentParagraphIndex].length
            sb.setSpan(BackgroundColorSpan(TTS_HIGHLIGHT), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        tvBookContent.text = sb
    }

    // Hace scroll hasta el párrafo indicado dentro del capítulo actual.
    private fun scrollAParrafo(index: Int) {
        if (index < 0 || index >= parrafoOffsets.size) return
        val offset = parrafoOffsets[index]
        tvBookContent.post {
            val layout = tvBookContent.layout ?: return@post
            val line = layout.getLineForOffset(offset)
            val top = layout.getLineTop(line) + tvBookContent.top
            scrollText.smoothScrollTo(0, top)
        }
    }

    // Devuelve el índice del primer párrafo visible en pantalla.
    private fun parrafoVisibleArriba(): Int {
        if (parrafoOffsets.isEmpty()) return 0
        val layout = tvBookContent.layout ?: return 0
        val scrollY = scrollText.scrollY
        val line = layout.getLineForVertical(scrollY)
        val offset = layout.getLineStart(line)
        var lo = 0
        var hi = parrafoOffsets.size - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (parrafoOffsets[mid] <= offset) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    // ---- Secciones ocultas (notas, abreviaturas, apéndices cortos...) ----

    private fun manualHiddenKey(): String = "manual_hidden_" + (currentUri?.toString() ?: "").hashCode()

    private fun manualHiddenSet(): MutableSet<String> =
        (prefs.getStringSet(manualHiddenKey(), emptySet()) ?: emptySet()).toMutableSet()

    private fun isSectionHidden(book: EpubParser.ParsedEpub, i: Int, manual: Set<String>): Boolean =
        book.chapterHidden.getOrElse(i) { false } || book.chapterTitles[i] in manual

    // Parte un capítulo si supera el límite de caracteres. Devuelve títulos y contenidos.
    private fun partirCapitulo(titulo: String, parrafos: List<String>): Pair<List<String>, List<List<String>>> {
        var total = 0
        for (p in parrafos) total += p.length
        if (total <= MAX_CHARS_POR_PARTE) {
            return Pair(listOf(titulo), listOf(parrafos))
        }

        val partes = mutableListOf<List<String>>()
        var actual = mutableListOf<String>()
        var actualChars = 0
        for (p in parrafos) {
            if (actualChars + p.length > MAX_CHARS_POR_PARTE && actual.isNotEmpty()) {
                partes.add(actual)
                actual = mutableListOf()
                actualChars = 0
            }
            actual.add(p)
            actualChars += p.length
        }
        if (actual.isNotEmpty()) partes.add(actual)

        val titulos = mutableListOf<String>()
        val contenidos = mutableListOf<List<String>>()
        val totalPartes = partes.size
        for (i in partes.indices) {
            titulos.add("$titulo (${i + 1}/$totalPartes)")
            contenidos.add(partes[i])
        }
        return Pair(titulos, contenidos)
    }

    // Arma la lista de capítulos visibles. Si "mostrar ocultas" está activo, entran todas.
    private fun rebuildVisibleSections() {
        val book = parsedBook ?: return
        val showAll = prefs.getBoolean("show_hidden_sections", false)
        val manual = manualHiddenSet()
        val titles = mutableListOf<String>()
        val contents = mutableListOf<List<String>>()
        for (i in book.chapterTitles.indices) {
            if (showAll || !isSectionHidden(book, i, manual)) {
                val (t, c) = partirCapitulo(book.chapterTitles[i], book.chapterContents[i])
                titles.addAll(t)
                contents.addAll(c)
            }
        }
        if (titles.isEmpty()) { // por si todo quedó oculto
            titles.addAll(book.chapterTitles)
            contents.addAll(book.chapterContents)
        }
        chapterTitles = titles
        chapterContents = contents
        buscador = null
        cerrarBarraBusqueda()
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
                    if (isPlaying) pauseReading()
                    currentChapterIndex = which
                    currentParagraphIndex = 0
                    showCurrentContent()
                    saveProgress()
                    scrollText.scrollTo(0, 0)
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

    private fun leerHistorial(): List<String> =
        (prefs.getString("historial_busquedas", "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun guardarEnHistorial(q: String) {
        val limpio = q.trim()
        if (limpio.length < 2) return
        val nuevo = (listOf(limpio) + leerHistorial().filter { !it.equals(limpio, true) }).take(8)
        prefs.edit().putString("historial_busquedas", nuevo.joinToString("\n")).apply()
    }

    private fun showSearchDialog() {
        if (chapterContents.isEmpty()) {
            Toast.makeText(this, "Primero selecciona un libro (EPUB o PDF)", Toast.LENGTH_SHORT).show()
            return
        }
        val motor = buscador ?: BuscadorLibro(chapterContents).also { buscador = it }
        val resultados = mutableListOf<BuscadorLibro.Resultado>()
        var modoHistorial = true
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val input = EditText(this).apply { hint = "Buscar en el libro"; setSingleLine() }
        val lista = ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.5).toInt())
        }
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(input); addView(lista)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Buscar")
            .setView(caja).setNegativeButton("Cerrar", null).create()

        fun mostrarHistorial() {
            modoHistorial = true
            resultados.clear()
            adapter.clear()
            adapter.addAll(leerHistorial().map { "🕘 $it" })
        }

        val handler = Handler(Looper.getMainLooper())
        var tarea: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tarea?.let { handler.removeCallbacks(it) }
                tarea = Runnable {
                    val texto = s.toString()
                    if (texto.trim().length < 2) {
                        mostrarHistorial()
                    } else {
                        modoHistorial = false
                        resultados.clear()
                        resultados.addAll(motor.buscar(texto))
                        adapter.clear()
                        adapter.addAll(resultados.map { "${chapterTitles[it.capitulo]}: …${it.fragmento}…" })
                    }
                }
                handler.postDelayed(tarea!!, 300)
            }
        })
        lista.setOnItemClickListener { _, _, pos, _ ->
            if (modoHistorial) {
                input.setText(leerHistorial().getOrNull(pos) ?: "")
                input.setSelection(input.text.length)
                return@setOnItemClickListener
            }
            guardarEnHistorial(input.text.toString())
            resultadosBusqueda = resultados.toList()
            consultaResaltada = input.text.toString()
            dialog.dismiss()
            mostrarBarraBusqueda()
            irAResultado(pos)
        }
        mostrarHistorial()
        dialog.show()
    }

    private fun irAResultado(pos: Int) {
        val r = resultadosBusqueda.getOrNull(pos) ?: return
        posResultado = pos
        if (isPlaying) pauseReading()
        currentChapterIndex = r.capitulo
        currentParagraphIndex = r.parrafo
        showCurrentContent()
        scrollAParrafo(r.parrafo)
        saveProgress()
        contadorBusqueda?.text = "${pos + 1} / ${resultadosBusqueda.size}"
    }

    private fun moverResultado(delta: Int) {
        val total = resultadosBusqueda.size
        if (total == 0) return
        irAResultado((posResultado + delta + total) % total)
    }

    private fun mostrarBarraBusqueda() {
        barraBusqueda?.dismiss()
        val dp = resources.displayMetrics.density
        fun boton(texto: String, accion: () -> Unit) = Button(this).apply {
            text = texto
            setOnClickListener { accion() }
        }
        val contador = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        contadorBusqueda = contador
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            addView(boton("◀") { moverResultado(-1) })
            addView(contador)
            addView(boton("▶") { moverResultado(1) })
            addView(boton("✕") { cerrarBarraBusqueda(true) })
        }
        val popup = PopupWindow(
            fila, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        popup.showAtLocation(
            scrollText, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
            0, (24 * dp).toInt())
        barraBusqueda = popup
    }

    private fun cerrarBarraBusqueda(refrescar: Boolean = false) {
        barraBusqueda?.dismiss()
        barraBusqueda = null
        contadorBusqueda = null
        consultaResaltada = null
        resultadosBusqueda = emptyList()
        posResultado = -1
        if (refrescar && chapterContents.isNotEmpty()) showCurrentContent()
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
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
        pedirNotificacion.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LecturaService.start(this)
    isPlaying = true
    currentParagraphIndex = parrafoVisibleArriba()
    readCurrentParagraph()
  }

    private fun pauseReading() {
    isPlaying = false
    tts.stop()
    saveProgress()
    LecturaService.stop(this)
    if (chapterContents.isNotEmpty()) showCurrentContent()
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
                scrollText.scrollTo(0, 0)
                readCurrentParagraph()
            } else {
                pauseReading()
                Toast.makeText(this, "Has terminado el libro", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
            // Actualiza resaltado y scroll al párrafo que empieza a sonar
        showCurrentContent()
        scrollAParrafo(currentParagraphIndex)

        val text = paragraphs[currentParagraphIndex]
        val chunks = splitTextForTts(text)
        var chunkIndex = 0

        fun speakNextChunk() {
            if (!isPlaying) return
            if (chunkIndex < chunks.size) {
                tts.speak(
                    text = chunks[chunkIndex],
                    onError = {
                        runOnUiThread {
                            pauseReading()
                            Toast.makeText(
                                this@ReaderActivity,
                                "La voz falló. Toca reproducir para continuar.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onDone = {
                        runOnUiThread {
                            chunkIndex++
                            speakNextChunk()
                        }
                    }
                )
            } else {
                runOnUiThread {
                    currentParagraphIndex++
                    saveProgress()
                    readCurrentParagraph()
                }
            }
        }
        speakNextChunk()
    }

    private fun saveProgress() {
        prefs.edit().putInt("last_chapter_index", currentChapterIndex).apply()
        prefs.edit().putInt("last_paragraph_index", currentParagraphIndex).apply()
    }

    override fun onPause() {
        super.onPause()
        if (chapterContents.isNotEmpty() && !isPlaying) {
            val idx = parrafoVisibleArriba()
            prefs.edit().putInt("last_chapter_index", currentChapterIndex).apply()
            prefs.edit().putInt("last_paragraph_index", idx).apply()
        }
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
    scrollSaveRunnable?.let { scrollHandler.removeCallbacks(it) }
    barraBusqueda?.dismiss()
    hiloCarga?.interrupt()
    dialogoCarga?.dismiss()
    sound.release()
    tts.shutdown()
    saveProgress()
    LecturaService.stop(this)
 }
}
