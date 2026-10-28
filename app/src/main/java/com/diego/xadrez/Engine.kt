package com.diego.xadrez

import kotlin.math.abs
import kotlin.random.Random

/** Lance: casa de origem, casa de destino e peça de promoção (0 = nenhuma). */
data class Move(val from: Int, val to: Int, val promo: Int = 0) {
    fun uci(): String {
        val p = when (promo) { Engine.Q -> "q"; Engine.R -> "r"; Engine.B -> "b"; Engine.N -> "n"; else -> "" }
        return Engine.sqName(from) + Engine.sqName(to) + p
    }
}

class Undo(
    val move: Move, val piece: Int, val captured: Int, val capSq: Int,
    val castling: Int, val ep: Int, val halfmove: Int
)

/**
 * Motor de xadrez. Casas 0..63, índice = linha*8 + coluna, linha 0 = 8ª fileira (lado das pretas).
 * Peças: positivas = brancas, negativas = pretas.
 */
class Engine {

    companion object {
        const val P = 1; const val N = 2; const val B = 3; const val R = 4; const val Q = 5; const val K = 6
        const val WHITE = 1; const val BLACK = -1
        const val MATE = 100_000
        private const val INF = 1_000_000

        private val KNIGHT = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1)
        )
        private val DIAG = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        private val ORTH = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))
        private val ALL8 = DIAG + ORTH

        // Direitos de roque: 1 = brancas lado do rei, 2 = brancas lado da dama, 4 = pretas rei, 8 = pretas dama
        private val RIGHTS_MASK = IntArray(64) { 15 }.also {
            it[60] = 12; it[63] = 14; it[56] = 13
            it[4] = 3; it[7] = 11; it[0] = 7
        }

        val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)

        fun sqName(sq: Int) = "${'a' + sq % 8}${8 - sq / 8}"

        // Tabelas de posição (ponto de vista das brancas, 8ª fileira primeiro)
        private val PST = arrayOf(
            IntArray(64),
            intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0,
                50, 50, 50, 50, 50, 50, 50, 50,
                10, 10, 20, 30, 30, 20, 10, 10,
                5, 5, 10, 25, 25, 10, 5, 5,
                0, 0, 0, 20, 20, 0, 0, 0,
                5, -5, -10, 0, 0, -10, -5, 5,
                5, 10, 10, -20, -20, 10, 10, 5,
                0, 0, 0, 0, 0, 0, 0, 0
            ),
            intArrayOf(
                -50, -40, -30, -30, -30, -30, -40, -50,
                -40, -20, 0, 0, 0, 0, -20, -40,
                -30, 0, 10, 15, 15, 10, 0, -30,
                -30, 5, 15, 20, 20, 15, 5, -30,
                -30, 0, 15, 20, 20, 15, 0, -30,
                -30, 5, 10, 15, 15, 10, 5, -30,
                -40, -20, 0, 5, 5, 0, -20, -40,
                -50, -40, -30, -30, -30, -30, -40, -50
            ),
            intArrayOf(
                -20, -10, -10, -10, -10, -10, -10, -20,
                -10, 0, 0, 0, 0, 0, 0, -10,
                -10, 0, 5, 10, 10, 5, 0, -10,
                -10, 5, 5, 10, 10, 5, 5, -10,
                -10, 0, 10, 10, 10, 10, 0, -10,
                -10, 10, 10, 10, 10, 10, 10, -10,
                -10, 5, 0, 0, 0, 0, 5, -10,
                -20, -10, -10, -10, -10, -10, -10, -20
            ),
            intArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0,
                5, 10, 10, 10, 10, 10, 10, 5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                -5, 0, 0, 0, 0, 0, 0, -5,
                0, 0, 0, 5, 5, 0, 0, 0
            ),
            intArrayOf(
                -20, -10, -10, -5, -5, -10, -10, -20,
                -10, 0, 0, 0, 0, 0, 0, -10,
                -10, 0, 5, 5, 5, 5, 0, -10,
                -5, 0, 5, 5, 5, 5, 0, -5,
                0, 0, 5, 5, 5, 5, 0, -5,
                -10, 5, 5, 5, 5, 5, 0, -10,
                -10, 0, 5, 0, 0, 0, 0, -10,
                -20, -10, -10, -5, -5, -10, -10, -20
            ),
            intArrayOf(
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -30, -40, -40, -50, -50, -40, -40, -30,
                -20, -30, -30, -40, -40, -30, -30, -20,
                -10, -20, -20, -20, -20, -20, -20, -10,
                20, 20, 0, 0, 0, 0, 20, 20,
                20, 30, 10, 0, 0, 10, 30, 20
            )
        )
    }

    enum class Status { PLAYING, CHECKMATE, STALEMATE, FIFTY, REPETITION, MATERIAL }

    val b = IntArray(64)
    var side = WHITE
    var castling = 15
    var ep = -1
    var halfmove = 0
    val undos = ArrayList<Undo>()

    /** Chaves de posição da partida real (para empate por repetição). Não usadas na busca. */
    val keys = ArrayList<String>()

    init { reset() }

    fun reset() {
        val back = intArrayOf(R, N, B, Q, K, B, N, R)
        b.fill(0)
        for (c in 0..7) {
            b[c] = -back[c]; b[8 + c] = -P
            b[48 + c] = P; b[56 + c] = back[c]
        }
        side = WHITE; castling = 15; ep = -1; halfmove = 0
        undos.clear(); keys.clear(); keys.add(key())
    }

    fun copy(): Engine {
        val e = Engine()
        b.copyInto(e.b)
        e.side = side; e.castling = castling; e.ep = ep; e.halfmove = halfmove
        e.keys.clear(); e.keys.addAll(keys)
        return e
    }

    fun key(): String = b.joinToString(",") + "|$side|$castling|$ep"

    private fun sign(p: Int) = if (p > 0) 1 else if (p < 0) -1 else 0

    fun kingSq(color: Int): Int {
        val k = color * K
        for (i in 0..63) if (b[i] == k) return i
        return -1
    }

    fun isAttacked(sq: Int, by: Int): Boolean {
        val r = sq / 8; val c = sq % 8
        // Peões: o peão branco atacante fica uma linha abaixo (r+1); o preto, uma acima (r-1)
        val pr = r + by
        if (pr in 0..7) {
            if (c > 0 && b[pr * 8 + c - 1] == by * P) return true
            if (c < 7 && b[pr * 8 + c + 1] == by * P) return true
        }
        for (d in KNIGHT) {
            val rr = r + d[0]; val cc = c + d[1]
            if (rr in 0..7 && cc in 0..7 && b[rr * 8 + cc] == by * N) return true
        }
        for (d in ALL8) {
            val rr = r + d[0]; val cc = c + d[1]
            if (rr in 0..7 && cc in 0..7 && b[rr * 8 + cc] == by * K) return true
        }
        for (d in ORTH) {
            var rr = r + d[0]; var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val p = b[rr * 8 + cc]
                if (p != 0) { if (p == by * R || p == by * Q) return true; break }
                rr += d[0]; cc += d[1]
            }
        }
        for (d in DIAG) {
            var rr = r + d[0]; var cc = c + d[1]
            while (rr in 0..7 && cc in 0..7) {
                val p = b[rr * 8 + cc]
                if (p != 0) { if (p == by * B || p == by * Q) return true; break }
                rr += d[0]; cc += d[1]
            }
        }
        return false
    }

    fun inCheck(): Boolean {
        val k = kingSq(side)
        return k >= 0 && isAttacked(k, -side)
    }

    private fun addPawn(list: ArrayList<Move>, from: Int, to: Int) {
        val row = to / 8
        if (row == 0 || row == 7) {
            list.add(Move(from, to, Q)); list.add(Move(from, to, N))
            list.add(Move(from, to, R)); list.add(Move(from, to, B))
        } else list.add(Move(from, to))
    }

    fun pseudoMoves(capturesOnly: Boolean = false): ArrayList<Move> {
        val list = ArrayList<Move>(48)
        for (sq in 0..63) {
            val p = b[sq]
            if (p == 0 || sign(p) != side) continue
            val r = sq / 8; val c = sq % 8
            when (abs(p)) {
                P -> {
                    val dir = -side
                    val r1 = r + dir
                    if (r1 in 0..7) {
                        val one = r1 * 8 + c
                        if (b[one] == 0 && (!capturesOnly || r1 == 0 || r1 == 7)) {
                            addPawn(list, sq, one)
                            val start = if (side == WHITE) 6 else 1
                            val two = (r + 2 * dir) * 8 + c
                            if (!capturesOnly && r == start && b[two] == 0) list.add(Move(sq, two))
                        }
                        for (dc in intArrayOf(-1, 1)) {
                            val cc = c + dc
                            if (cc !in 0..7) continue
                            val t = r1 * 8 + cc
                            if (b[t] != 0 && sign(b[t]) == -side) addPawn(list, sq, t)
                            else if (t == ep) list.add(Move(sq, t))
                        }
                    }
                }
                N -> for (d in KNIGHT) {
                    val rr = r + d[0]; val cc = c + d[1]
                    if (rr !in 0..7 || cc !in 0..7) continue
                    val t = rr * 8 + cc
                    if (b[t] == 0) { if (!capturesOnly) list.add(Move(sq, t)) }
                    else if (sign(b[t]) == -side) list.add(Move(sq, t))
                }
                K -> {
                    for (d in ALL8) {
                        val rr = r + d[0]; val cc = c + d[1]
                        if (rr !in 0..7 || cc !in 0..7) continue
                        val t = rr * 8 + cc
                        if (b[t] == 0) { if (!capturesOnly) list.add(Move(sq, t)) }
                        else if (sign(b[t]) == -side) list.add(Move(sq, t))
                    }
                    if (!capturesOnly) addCastling(list)
                }
                else -> {
                    val dirs = when (abs(p)) { B -> DIAG; R -> ORTH; else -> ALL8 }
                    for (d in dirs) {
                        var rr = r + d[0]; var cc = c + d[1]
                        while (rr in 0..7 && cc in 0..7) {
                            val t = rr * 8 + cc
                            if (b[t] == 0) { if (!capturesOnly) list.add(Move(sq, t)) }
                            else { if (sign(b[t]) == -side) list.add(Move(sq, t)); break }
                            rr += d[0]; cc += d[1]
                        }
                    }
                }
            }
        }
        return list
    }

    private fun addCastling(list: ArrayList<Move>) {
        val enemy = -side
        if (side == WHITE) {
            if (b[60] != K) return
            if (castling and 1 != 0 && b[61] == 0 && b[62] == 0 && b[63] == R &&
                !isAttacked(60, enemy) && !isAttacked(61, enemy) && !isAttacked(62, enemy)
            ) list.add(Move(60, 62))
            if (castling and 2 != 0 && b[59] == 0 && b[58] == 0 && b[57] == 0 && b[56] == R &&
                !isAttacked(60, enemy) && !isAttacked(59, enemy) && !isAttacked(58, enemy)
            ) list.add(Move(60, 58))
        } else {
            if (b[4] != -K) return
            if (castling and 4 != 0 && b[5] == 0 && b[6] == 0 && b[7] == -R &&
                !isAttacked(4, enemy) && !isAttacked(5, enemy) && !isAttacked(6, enemy)
            ) list.add(Move(4, 6))
            if (castling and 8 != 0 && b[3] == 0 && b[2] == 0 && b[1] == 0 && b[0] == -R &&
                !isAttacked(4, enemy) && !isAttacked(3, enemy) && !isAttacked(2, enemy)
            ) list.add(Move(4, 2))
        }
    }

    fun make(m: Move) {
        val p = b[m.from]
        var captured = b[m.to]
        var capSq = m.to
        if (abs(p) == P && m.to == ep && captured == 0) {
            capSq = m.to + side * 8
            captured = b[capSq]
            b[capSq] = 0
        }
        undos.add(Undo(m, p, captured, capSq, castling, ep, halfmove))
        b[m.to] = if (m.promo != 0) side * m.promo else p
        b[m.from] = 0
        if (abs(p) == K && abs(m.to - m.from) == 2) {
            when (m.to) {
                62 -> { b[61] = b[63]; b[63] = 0 }
                58 -> { b[59] = b[56]; b[56] = 0 }
                6 -> { b[5] = b[7]; b[7] = 0 }
                2 -> { b[3] = b[0]; b[0] = 0 }
            }
        }
        castling = castling and RIGHTS_MASK[m.from] and RIGHTS_MASK[m.to]
        ep = if (abs(p) == P && abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        halfmove = if (abs(p) == P || captured != 0) 0 else halfmove + 1
        side = -side
    }

    fun unmake() {
        val u = undos.removeAt(undos.size - 1)
        val m = u.move
        side = -side
        b[m.from] = u.piece
        b[m.to] = 0
        if (u.captured != 0) b[u.capSq] = u.captured
        if (abs(u.piece) == K && abs(m.to - m.from) == 2) {
            when (m.to) {
                62 -> { b[63] = b[61]; b[61] = 0 }
                58 -> { b[56] = b[59]; b[59] = 0 }
                6 -> { b[7] = b[5]; b[5] = 0 }
                2 -> { b[0] = b[3]; b[3] = 0 }
            }
        }
        castling = u.castling; ep = u.ep; halfmove = u.halfmove
    }

    /** O lado que acabou de jogar deixou o próprio rei em xeque? */
    private fun leftKingInCheck(): Boolean {
        val k = kingSq(-side)
        return k < 0 || isAttacked(k, side)
    }

    fun legalMoves(): List<Move> {
        val out = ArrayList<Move>()
        for (m in pseudoMoves()) {
            make(m)
            if (!leftKingInCheck()) out.add(m)
            unmake()
        }
        return out
    }

    // ---------- Partida real (com histórico de posições) ----------

    fun play(m: Move) { make(m); keys.add(key()) }

    fun takeBack(): Boolean {
        if (undos.isEmpty()) return false
        unmake()
        if (keys.size > 1) keys.removeAt(keys.size - 1)
        return true
    }

    fun status(): Status {
        if (legalMoves().isEmpty()) return if (inCheck()) Status.CHECKMATE else Status.STALEMATE
        if (halfmove >= 100) return Status.FIFTY
        val last = keys.last()
        if (keys.count { it == last } >= 3) return Status.REPETITION
        if (insufficientMaterial()) return Status.MATERIAL
        return Status.PLAYING
    }

    private fun insufficientMaterial(): Boolean {
        var minors = 0
        for (p in b) {
            when (abs(p)) {
                0, K -> {}
                N, B -> minors++
                else -> return false
            }
        }
        return minors <= 1
    }

    // ---------- Inteligência ----------

    fun evaluate(): Int {
        var s = 0
        for (sq in 0..63) {
            val p = b[sq]
            if (p == 0) continue
            val t = abs(p)
            s += if (p > 0) VALUE[t] + PST[t][sq] else -(VALUE[t] + PST[t][sq xor 56])
        }
        return s * side
    }

    private fun ordered(list: ArrayList<Move>): List<Move> =
        list.sortedByDescending { m ->
            val victim = abs(b[m.to])
            val mvv = if (victim != 0) VALUE[victim] * 10 - VALUE[abs(b[m.from])] / 10 else 0
            mvv + if (m.promo == Q) 8000 else 0
        }

    private var nodes = 0L

    private fun quiesce(alphaIn: Int, beta: Int, qd: Int): Int {
        nodes++
        val stand = evaluate()
        if (stand >= beta) return beta
        var alpha = maxOf(alphaIn, stand)
        if (qd >= 8) return alpha
        for (m in ordered(pseudoMoves(capturesOnly = true))) {
            make(m)
            if (leftKingInCheck()) { unmake(); continue }
            val s = -quiesce(-beta, -alpha, qd + 1)
            unmake()
            if (s >= beta) return beta
            if (s > alpha) alpha = s
        }
        return alpha
    }

    private fun negamax(depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        if (depth <= 0) return quiesce(alphaIn, beta, 0)
        nodes++
        var alpha = alphaIn
        var any = false
        for (m in ordered(pseudoMoves())) {
            make(m)
            if (leftKingInCheck()) { unmake(); continue }
            any = true
            val s = -negamax(depth - 1, -beta, -alpha, ply + 1)
            unmake()
            if (s >= beta) return beta
            if (s > alpha) alpha = s
        }
        if (!any) return if (inCheck()) -MATE + ply else 0
        return alpha
    }

    /** level: 1 = fácil, 2 = médio, 3 = difícil */
    fun bestMove(level: Int): Move? {
        val legal = legalMoves()
        if (legal.isEmpty()) return null
        val pieces = b.count { it != 0 }
        val depth = when (level) { 1 -> 1; 2 -> 2; else -> if (pieces <= 10) 5 else 4 }
        val noise = when (level) { 1 -> 120; 2 -> 25; else -> 0 }
        var best: Move? = null
        var bestScore = -INF
        var alpha = -INF
        for (m in ordered(ArrayList(legal))) {
            make(m)
            val s = -negamax(depth - 1, -INF, if (noise == 0) -alpha else INF, 1)
            unmake()
            val score = s + if (noise > 0) Random.nextInt(-noise, noise + 1) else 0
            if (score > bestScore) { bestScore = score; best = m }
            if (noise == 0 && s > alpha) alpha = s
        }
        return best
    }

    // Para testes
    fun perft(depth: Int): Long {
        if (depth == 0) return 1
        var n = 0L
        for (m in pseudoMoves()) {
            make(m)
            if (!leftKingInCheck()) n += perft(depth - 1)
            unmake()
        }
        return n
    }

    fun loadFen(fen: String) {
        val parts = fen.trim().split(" ")
        b.fill(0)
        var sq = 0
        for (ch in parts[0]) {
            when {
                ch == '/' -> {}
                ch.isDigit() -> sq += ch - '0'
                else -> {
                    val t = when (ch.lowercaseChar()) { 'p' -> P; 'n' -> N; 'b' -> B; 'r' -> R; 'q' -> Q; else -> K }
                    b[sq++] = if (ch.isUpperCase()) t else -t
                }
            }
        }
        side = if (parts[1] == "w") WHITE else BLACK
        castling = 0
        if ('K' in parts[2]) castling = castling or 1
        if ('Q' in parts[2]) castling = castling or 2
        if ('k' in parts[2]) castling = castling or 4
        if ('q' in parts[2]) castling = castling or 8
        ep = if (parts[3] == "-") -1 else (parts[3][0] - 'a') + (8 - (parts[3][1] - '0')) * 8
        halfmove = parts.getOrNull(4)?.toIntOrNull() ?: 0
        undos.clear(); keys.clear(); keys.add(key())
    }
}
