package com.diego.xadrez

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity() {

    enum class Mode(val label: String, val level: Int) {
        FACIL("vs CPU fácil", 1),
        MEDIO("vs CPU médio", 2),
        DIFICIL("vs CPU difícil", 3),
        DOIS("2 jogadores", 0)
    }

    companion object {
        private val BG = Color.parseColor("#121417")
        private val CARD = Color.parseColor("#1E2228")
        private val TEXT = Color.parseColor("#ECEFF1")
        private val MUTED = Color.parseColor("#9AA3AD")
        private val ACCENT = Color.parseColor("#4FC3F7")
        private val LIGHT = Color.parseColor("#F0D9B5")
        private val DARK = Color.parseColor("#B58863")
        private val LAST_MOVE = Color.parseColor("#80CDD26A")
        private val SELECTED = Color.parseColor("#B0F6F669")
        private val HINT = Color.parseColor("#55000000")
        private val CHECK = Color.parseColor("#C0E53935")

        // Peças sólidas Unicode (rei, dama, torre, bispo, cavalo, peão), forçando apresentação de texto
        private val GLYPH = arrayOf("", "♟︎", "♞︎", "♝︎", "♜︎", "♛︎", "♚︎")
        private val LETTER = arrayOf("", "P", "C", "B", "T", "D", "R")
    }

    private var engine = Engine()
    private var mode = Mode.MEDIO
    private var humanColor = Engine.WHITE
    private var selected = -1
    private var targets: List<Move> = emptyList()
    private var status = Engine.Status.PLAYING
    private var thinking = false
    private var generation = 0

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var modeButton: Button
    private lateinit var colorButton: Button
    private lateinit var boardView: BoardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savedInstanceState?.let { restore(it) }
        status = engine.status()
        buildUi()
        maybeCpu()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi() // a tela girou: refaz o layout mantendo a partida
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putStringArrayList("moves", ArrayList(engine.undos.map { it.move.uci() }))
        out.putInt("mode", mode.ordinal)
        out.putInt("human", humanColor)
    }

    private fun restore(b: Bundle) {
        mode = Mode.values()[b.getInt("mode", Mode.MEDIO.ordinal)]
        humanColor = b.getInt("human", Engine.WHITE)
        engine = Engine()
        b.getStringArrayList("moves")?.forEach { uci ->
            engine.legalMoves().firstOrNull { it.uci() == uci }?.let { engine.play(it) }
        }
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- Interface ----------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bigButton(color: Int, onTap: () -> Unit) = Button(this).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        minHeight = dp(60)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(color)
        }
        setOnClickListener { onTap() }
    }

    private fun buildUi() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val title = label(28f, TEXT, bold = true).apply { text = "Xadrez" }
        statusText = label(22f, TEXT, bold = true)
        infoText = label(16f, MUTED)
        modeButton = bigButton(CARD) { nextMode() }
        colorButton = bigButton(CARD) { switchColor() }
        val undoButton = bigButton(CARD) { undo() }.apply { text = "Desfazer lance" }
        val newButton = bigButton(Color.parseColor("#2E7D5B")) { confirmNewGame() }.apply { text = "Nova partida" }

        boardView = BoardView()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        fun gap(h: Int) = panel.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
        fun add(v: View) = panel.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (landscape) {
            add(title); gap(12)
            add(statusText); gap(6)
            add(infoText); gap(20)
            add(modeButton); gap(10)
            add(colorButton); gap(10)
            add(undoButton); gap(10)
            add(newButton)
            root.addView(boardView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.5f))
            root.addView(View(this), LinearLayout.LayoutParams(dp(28), 1))
            root.addView(panel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        } else {
            add(statusText); gap(4)
            add(infoText)
            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun cell(row: LinearLayout, v: View, last: Boolean) {
                val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                if (!last) lp.marginEnd = dp(10)
                row.addView(v, lp)
            }
            cell(row1, modeButton, false); cell(row1, colorButton, true)
            cell(row2, undoButton, false); cell(row2, newButton, true)
            root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            root.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
            root.addView(boardView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            root.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
            root.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            root.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
            root.addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
            root.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        setContentView(root)
        refresh()
    }

    private fun sideName(color: Int) = if (color == Engine.WHITE) "brancas" else "pretas"

    private fun refresh() {
        val vsCpu = mode != Mode.DOIS
        statusText.text = when (status) {
            Engine.Status.CHECKMATE -> {
                val winner = -engine.side
                if (vsCpu) (if (winner == humanColor) "Xeque-mate! Você venceu 🎉" else "Xeque-mate. A CPU venceu")
                else "Xeque-mate! ${sideName(winner).replaceFirstChar { it.uppercase() }} vencem 🎉"
            }
            Engine.Status.STALEMATE -> "Empate por afogamento"
            Engine.Status.FIFTY -> "Empate (regra dos 50 lances)"
            Engine.Status.REPETITION -> "Empate por repetição"
            Engine.Status.MATERIAL -> "Empate (material insuficiente)"
            Engine.Status.PLAYING -> {
                val check = if (engine.inCheck()) " — Xeque!" else ""
                when {
                    thinking -> "CPU pensando…"
                    vsCpu -> "Sua vez$check"
                    else -> "Vez das ${sideName(engine.side)}$check"
                }
            }
        }
        statusText.setTextColor(if (status == Engine.Status.PLAYING && engine.inCheck()) Color.parseColor("#FF7961") else TEXT)

        val last = engine.undos.lastOrNull()?.move
        val material = materialBalance()
        val matText = when {
            material > 0 -> "Brancas +${material}"
            material < 0 -> "Pretas +${-material}"
            else -> "Material igual"
        }
        infoText.text = (if (last != null) "Último: ${Engine.sqName(last.from)} → ${Engine.sqName(last.to)}   ·   " else "") + matText

        modeButton.text = "Modo: ${mode.label}"
        colorButton.text = if (mode == Mode.DOIS) "Girar tabuleiro" else "Jogar de: ${sideName(humanColor)}"
        boardView.invalidate()
    }

    private fun materialBalance(): Int {
        val pts = intArrayOf(0, 1, 3, 3, 5, 9, 0)
        var s = 0
        for (p in engine.b) s += if (p > 0) pts[p] else -pts[-p]
        return s
    }

    private fun flipped() = humanColor == Engine.BLACK

    // ---------- Ações ----------

    private fun cancelCpu() {
        generation++
        thinking = false
    }

    private fun confirmNewGame() {
        if (engine.undos.isEmpty() || status != Engine.Status.PLAYING) { newGame(); return }
        AlertDialog.Builder(this)
            .setTitle("Começar nova partida?")
            .setPositiveButton("Sim") { _, _ -> newGame() }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun newGame() {
        cancelCpu()
        engine = Engine()
        selected = -1; targets = emptyList()
        status = engine.status()
        refresh()
        maybeCpu()
    }

    private fun nextMode() {
        mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
        newGame()
    }

    private fun switchColor() {
        humanColor = -humanColor
        if (mode == Mode.DOIS) { refresh(); return } // em 2 jogadores só gira o tabuleiro
        newGame()
    }

    private fun undo() {
        cancelCpu()
        if (!engine.takeBack()) { refresh(); maybeCpu(); return }
        if (mode != Mode.DOIS) {
            while (engine.side != humanColor && engine.takeBack()) { /* volta até a vez do jogador */ }
        }
        selected = -1; targets = emptyList()
        status = engine.status()
        refresh()
        maybeCpu()
    }

    private fun onSquareTapped(sq: Int) {
        if (status != Engine.Status.PLAYING || thinking) return
        if (mode != Mode.DOIS && engine.side != humanColor) return

        val choices = targets.filter { it.to == sq }
        if (selected >= 0 && choices.isNotEmpty()) {
            if (choices.size > 1) askPromotion(choices) else playMove(choices[0])
            return
        }
        val p = engine.b[sq]
        if (p != 0 && (p > 0) == (engine.side > 0)) {
            selected = if (selected == sq) -1 else sq
            targets = if (selected >= 0) engine.legalMoves().filter { it.from == sq } else emptyList()
        } else {
            selected = -1; targets = emptyList()
        }
        boardView.invalidate()
    }

    private fun askPromotion(choices: List<Move>) {
        val names = arrayOf("Dama", "Torre", "Bispo", "Cavalo")
        val pieces = intArrayOf(Engine.Q, Engine.R, Engine.B, Engine.N)
        AlertDialog.Builder(this)
            .setTitle("Promover peão para")
            .setItems(names) { _, i -> choices.firstOrNull { it.promo == pieces[i] }?.let { playMove(it) } }
            .show()
    }

    private fun playMove(m: Move) {
        engine.play(m)
        selected = -1; targets = emptyList()
        status = engine.status()
        refresh()
        maybeCpu()
    }

    private fun maybeCpu() {
        if (mode == Mode.DOIS || status != Engine.Status.PLAYING || engine.side == humanColor) return
        thinking = true
        refresh()
        val gen = ++generation
        val copy = engine.copy()
        val level = mode.level
        Thread {
            val start = SystemClock.elapsedRealtime()
            val m = copy.bestMove(level)
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed < 450) SystemClock.sleep(450 - elapsed) // pausa curta para o lance não "piscar"
            handler.post {
                if (gen != generation) return@post
                thinking = false
                if (m != null) playMove(m) else { status = engine.status(); refresh() }
            }
        }.start()
    }

    // ---------- Tabuleiro ----------

    inner class BoardView : View(this@MainActivity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private val pieceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val pieceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        }
        private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private val glyphsOk = try { pieceFill.hasGlyph("♚") && pieceFill.hasGlyph("♟") } catch (e: Throwable) { false }

        private fun area(): RectF {
            val s = min(width, height) * 0.98f
            val l = (width - s) / 2f
            val t = (height - s) / 2f
            return RectF(l, t, l + s, t + s)
        }

        /** casa do motor -> (linha, coluna) na tela */
        private fun toView(sq: Int): Pair<Int, Int> {
            val r = sq / 8; val c = sq % 8
            return if (flipped()) (7 - r) to (7 - c) else r to c
        }

        private fun toSq(vr: Int, vc: Int) = if (flipped()) (7 - vr) * 8 + (7 - vc) else vr * 8 + vc

        override fun onDraw(cv: Canvas) {
            super.onDraw(cv)
            val a = area()
            val cell = a.width() / 8f
            val last = engine.undos.lastOrNull()?.move
            val checkSq = if (status != Engine.Status.STALEMATE && engine.inCheck()) engine.kingSq(engine.side) else -1

            for (vr in 0..7) for (vc in 0..7) {
                val sq = toSq(vr, vc)
                val x = a.left + vc * cell; val y = a.top + vr * cell
                fill.color = if ((vr + vc) % 2 == 0) LIGHT else DARK
                cv.drawRect(x, y, x + cell, y + cell, fill)
                if (last != null && (sq == last.from || sq == last.to)) {
                    fill.color = LAST_MOVE; cv.drawRect(x, y, x + cell, y + cell, fill)
                }
                if (sq == selected) {
                    fill.color = SELECTED; cv.drawRect(x, y, x + cell, y + cell, fill)
                }
                if (sq == checkSq) {
                    fill.color = CHECK; cv.drawCircle(x + cell / 2, y + cell / 2, cell * 0.48f, fill)
                }
            }

            // Coordenadas
            coord.textSize = cell * 0.18f
            for (i in 0..7) {
                val fileSq = toSq(7, i)
                val rankSq = toSq(i, 0)
                coord.color = if ((7 + i) % 2 == 0) DARK else LIGHT
                cv.drawText("${'a' + fileSq % 8}", a.left + i * cell + cell * 0.80f, a.bottom - cell * 0.06f, coord)
                coord.color = if (i % 2 == 0) DARK else LIGHT
                cv.drawText("${8 - rankSq / 8}", a.left + cell * 0.05f, a.top + i * cell + cell * 0.22f, coord)
            }

            // Peças
            pieceFill.textSize = cell * 0.82f
            pieceStroke.textSize = cell * 0.82f
            pieceStroke.strokeWidth = cell * 0.045f
            val fm = pieceFill.fontMetrics
            for (sq in 0..63) {
                val p = engine.b[sq]
                if (p == 0) continue
                val (vr, vc) = toView(sq)
                val cx = a.left + vc * cell + cell / 2
                val cy = a.top + vr * cell + cell / 2
                val white = p > 0
                if (glyphsOk) {
                    val g = GLYPH[abs(p)]
                    val base = cy - (fm.ascent + fm.descent) / 2
                    pieceStroke.color = if (white) Color.parseColor("#222222") else Color.parseColor("#000000")
                    pieceFill.color = if (white) Color.WHITE else Color.parseColor("#1C1C1C")
                    cv.drawText(g, cx, base, pieceStroke)
                    cv.drawText(g, cx, base, pieceFill)
                } else {
                    // Plano B se a multimídia não tiver os símbolos de xadrez: disco com a letra da peça
                    fill.color = if (white) Color.WHITE else Color.parseColor("#1C1C1C")
                    cv.drawCircle(cx, cy, cell * 0.38f, fill)
                    letterPaint.color = if (white) Color.parseColor("#1C1C1C") else Color.WHITE
                    letterPaint.textSize = cell * 0.42f
                    val lfm = letterPaint.fontMetrics
                    cv.drawText(LETTER[abs(p)], cx, cy - (lfm.ascent + lfm.descent) / 2, letterPaint)
                }
            }

            // Destinos possíveis
            for (m in targets) {
                val (vr, vc) = toView(m.to)
                val cx = a.left + vc * cell + cell / 2
                val cy = a.top + vr * cell + cell / 2
                fill.color = HINT
                if (engine.b[m.to] != 0 || (abs(engine.b[m.from]) == Engine.P && m.to == engine.ep)) {
                    fill.style = Paint.Style.STROKE; fill.strokeWidth = cell * 0.08f
                    cv.drawCircle(cx, cy, cell * 0.44f, fill)
                    fill.style = Paint.Style.FILL
                } else {
                    cv.drawCircle(cx, cy, cell * 0.15f, fill)
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                val a = area()
                if (a.contains(e.x, e.y)) {
                    val cell = a.width() / 8f
                    val vc = ((e.x - a.left) / cell).toInt().coerceIn(0, 7)
                    val vr = ((e.y - a.top) / cell).toInt().coerceIn(0, 7)
                    onSquareTapped(toSq(vr, vc))
                }
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
