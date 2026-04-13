package com.oasis.reader

import android.app.AlertDialog
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.StyleSpan
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ExpandableListView
import android.widget.SimpleExpandableListAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class ReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvBookContent: TextView
    private lateinit var scrollText: View
    private lateinit var turtleView: TurtleView
    private lateinit var tts: TextToSpeech
    private lateinit var sound: SoundModule
    private lateinit var epubParser: EpubParser
    private var bookUri: String? = null

    private var chapterTitles = mutableListOf<String>()
    private var chapterContents = mutableListOf<List<String>>()
    private var currentChapterIndex = 0
    private var currentParagraphIndex = 0
    private var isPlaying = false

    // Variables para índice jerárquico
    private var hierarchicalChapters: List<ChapterNode> = emptyList()
    private var chapterNodeMap = mutableMapOf<Int, ChapterNode>() // posición plana -> nodo

    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        tvBookContent = findViewById(R.id.tvBookContent)
        scrollText = findViewById(R.id.scrollText)
        turtleView = findViewById(R.id.turtleView)
        tvBookContent.movementMethod = ScrollingMovementMethod()

        sound = SoundModule(this)
        tts = TextToSpeech(this, this)
        epubParser = EpubParser(contentResolver)

        bookUri = intent.getStringExtra("book_uri")
        if (bookUri != null) {
            loadBook(Uri.parse(bookUri))
        } else {
            Toast.makeText(this, "No se pudo cargar el libro", Toast.LENGTH_SHORT).show()
            finish()
        }

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent?,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || e2 == null) return false
                val diffX = e1.x - e2.x
                if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    if (diffX > 0) {
                        // swipe izquierda -> siguiente párrafo
                        nextParagraph()
                    } else {
                        // swipe derecha -> anterior párrafo
                        previousParagraph()
                    }
                    return true
                }
                return false
            }
        })

        findViewById<View>(R.id.btnIndex).setOnClickListener {
            showChapterListDialog()
        }
        findViewById<View>(R.id.btnPlayPause).setOnClickListener {
            if (isPlaying) pauseReading() else startReading()
        }
        findViewById<View>(R.id.btnStop).setOnClickListener {
            pauseReading()
            currentParagraphIndex = 0
            showCurrentContent()
        }
    }

    private fun loadBook(uri: Uri) {
        try {
            val parsed = epubParser.parse(uri)
            chapterTitles = parsed.chapterTitles
            chapterContents = parsed.chapterContents
            // Cargar índice jerárquico si existe
            hierarchicalChapters = epubParser.getHierarchicalChapters(uri)
            if (hierarchicalChapters.isNotEmpty()) {
                // Construir mapeo de posición plana a nodo (para navegación)
                flattenNodes(hierarchicalChapters)
            }
            if (chapterTitles.isNotEmpty()) {
                showCurrentContent()
                loadProgress()
            } else {
                Toast.makeText(this, "No se encontraron capítulos", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al cargar el libro: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun flattenNodes(nodes: List<ChapterNode>, parentPosition: Int = -1) {
        for ((index, node) in nodes.withIndex()) {
            val flatPos = chapterNodeMap.size
            chapterNodeMap[flatPos] = node
            if (node.children.isNotEmpty()) {
                flattenNodes(node.children, flatPos)
            }
        }
    }

    private fun showCurrentContent() {
        if (currentChapterIndex < chapterContents.size) {
            val title = chapterTitles[currentChapterIndex]
            val paragraphs = chapterContents[currentChapterIndex]
            val paragraphText = if (currentParagraphIndex < paragraphs.size) paragraphs[currentParagraphIndex] else paragraphs.lastOrNull() ?: ""
            val spannable = SpannableString("$title\n\n$paragraphText")
            spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvBookContent.text = spannable
            scrollText.scrollTo(0, 0)
        }
    }

    private fun showChapterListDialog() {
        if (chapterTitles.isEmpty()) {
            Toast.makeText(this, "No hay capítulos cargados", Toast.LENGTH_SHORT).show()
            return
        }

        // Si hay índice jerárquico, mostrar ExpandableListView
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
        // Preparar datos para SimpleExpandableListAdapter
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
            if (node?.isLeaf() == true) {
                jumpToChapter(node)
                dialog.dismiss()
                return@setOnGroupClickListener true
            }
            false // permite expandir/colapsar
        }
        dialog.show()
    }

    private fun buildExpandableData(
        nodes: List<ChapterNode>,
        groupList: ArrayList<Map<String, String>>,
        childList: ArrayList<ArrayList<Map<String, String>>>,
        parentNodes: List<ChapterNode>? = null
    ) {
        for (node in nodes) {
            val groupMap = HashMap<String, String>()
            groupMap["title"] = node.title
            groupList.add(groupMap)
            val childrenArray = ArrayList<Map<String, String>>()
            for (child in node.children) {
                val childMap = HashMap<String, String>()
                childMap["title"] = child.title
                childrenArray.add(childMap)
            }
            childList.add(childrenArray)
            // Si el nodo tiene hijos, se agregan como grupos adicionales? No, ya están como hijos. 
            // Pero para sub-subcapítulos, esto no los maneja (solo dos niveles). Para más profundidad, habría que usar un adaptador recursivo.
            // Por simplicidad, mostramos solo dos niveles. Se puede mejorar después.
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
        // Buscar el índice plano correspondiente al src del nodo
        val src = node.src
        for (i in 0 until chapterContents.size) {
            // Comparación simple: el href del capítulo actual (pero no tenemos guardado el src en parse())
            // Alternativa: usar el título para buscar. Esto es un workaround.
            // Idealmente, deberíamos guardar el src en parse() pero eso cambiaría mucho.
            // Por ahora, asumimos que el orden es el mismo: el nodo plano coincide con el orden jerárquico en profundidad.
            // Una solución robusta: al cargar el índice jerárquico, también llenamos una lista plana de capítulos con su src.
            // Pero para no complicar, buscamos por título (puede fallar si títulos duplicados).
            if (chapterTitles[i] == node.title) {
                if (currentChapterIndex != i) {
                    sound.play(R.raw.page_flip)
                    currentChapterIndex = i
                    currentParagraphIndex = 0
                    if (isPlaying) pauseReading()
                    showCurrentContent()
                    saveProgress()
                }
                return
            }
        }
        // Si no se encuentra, intentar con índice posicional aproximado
        val flatIndex = getFlatIndexForNode(node)
        if (flatIndex >= 0 && flatIndex < chapterTitles.size) {
            if (currentChapterIndex != flatIndex) {
                sound.play(R.raw.page_flip)
                currentChapterIndex = flatIndex
                currentParagraphIndex = 0
                if (isPlaying) pauseReading()
                showCurrentContent()
                saveProgress()
            }
        } else {
            Toast.makeText(this, "No se pudo encontrar el capítulo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFlatIndexForNode(node: ChapterNode): Int {
        // Mapeo simple por orden de aparición en flattenNodes
        for ((idx, n) in chapterNodeMap) {
            if (n === node) return idx
        }
        return -1
    }

    private fun showFlatChapterDialog() {
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

    private fun nextParagraph() {
        if (chapterContents.isEmpty()) return
        val currentParagraphs = chapterContents[currentChapterIndex]
        if (currentParagraphIndex + 1 < currentParagraphs.size) {
            currentParagraphIndex++
            sound.play(R.raw.page_flip)
            showCurrentContent()
            if (isPlaying) {
                tts.stop()
                readCurrentParagraph()
            }
            saveProgress()
        } else if (currentChapterIndex + 1 < chapterContents.size) {
            // Siguiente capítulo
            currentChapterIndex++
            currentParagraphIndex = 0
            sound.play(R.raw.page_flip)
            showCurrentContent()
            if (isPlaying) {
                tts.stop()
                readCurrentParagraph()
            }
            saveProgress()
        } else {
            Toast.makeText(this, "Fin del libro", Toast.LENGTH_SHORT).show()
        }
    }

    private fun previousParagraph() {
        if (chapterContents.isEmpty()) return
        if (currentParagraphIndex - 1 >= 0) {
            currentParagraphIndex--
            sound.play(R.raw.page_flip)
            showCurrentContent()
            if (isPlaying) {
                tts.stop()
                readCurrentParagraph()
            }
            saveProgress()
        } else if (currentChapterIndex - 1 >= 0) {
            currentChapterIndex--
            val prevParagraphs = chapterContents[currentChapterIndex]
            currentParagraphIndex = prevParagraphs.size - 1
            sound.play(R.raw.page_flip)
            showCurrentContent()
            if (isPlaying) {
                tts.stop()
                readCurrentParagraph()
            }
            saveProgress()
        } else {
            Toast.makeText(this, "Inicio del libro", Toast.LENGTH_SHORT).show()
        }
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
        val paragraphs = chapterContents[currentChapterIndex]
        if (currentParagraphIndex < paragraphs.size) {
            val text = paragraphs[currentParagraphIndex]
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            turtleView.startMoving()
            // Programar siguiente párrafo cuando termine
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (isPlaying) {
                            nextParagraph()
                        }
                        turtleView.stopMoving()
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        turtleView.stopMoving()
                    }
                }
            })
        } else {
            pauseReading()
        }
    }

    private fun saveProgress() {
        val prefs = getSharedPreferences("OASIS_Reader", Context.MODE_PRIVATE)
        prefs.edit().putInt("chapter_${bookUri}", currentChapterIndex)
            .putInt("paragraph_${bookUri}", currentParagraphIndex)
            .apply()
    }

    private fun loadProgress() {
        val prefs = getSharedPreferences("OASIS_Reader", Context.MODE_PRIVATE)
        currentChapterIndex = prefs.getInt("chapter_${bookUri}", 0)
        currentParagraphIndex = prefs.getInt("paragraph_${bookUri}", 0)
        if (currentChapterIndex >= chapterTitles.size) currentChapterIndex = 0
        if (currentParagraphIndex >= chapterContents[currentChapterIndex].size) currentParagraphIndex = 0
        showCurrentContent()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        sound.release()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}