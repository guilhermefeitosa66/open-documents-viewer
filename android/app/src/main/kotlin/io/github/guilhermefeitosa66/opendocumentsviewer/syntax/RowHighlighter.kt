package io.github.guilhermefeitosa66.opendocumentsviewer.syntax

import io.github.guilhermefeitosa66.opendocumentsviewer.document.LineIndex
import java.util.concurrent.atomic.AtomicInteger

/**
 * Highlights the rows of a document on demand.
 *
 * [computeStates] walks the whole text once (in the background) to learn the lexer state at the
 * start of every row; after that any row can be tokenized alone, so only visible rows cost
 * anything. Until the walk reaches a row, the row is tokenized from state 0, which is right for
 * everything except the inside of a block comment or multi-line string.
 */
class RowHighlighter(
    private val text: String,
    private val index: LineIndex,
    val lexer: Lexer,
) {
    private val states = IntArray(index.rowCount)
    private val ready = AtomicInteger(0)

    /** Rows whose start state is known. */
    val readyRows: Int get() = ready.get()

    /** Walks the document; [isActive] lets a cancelled coroutine stop early. */
    fun computeStates(isActive: () -> Boolean = { true }) {
        val sink = Tokens()
        var state = 0
        for (row in 0 until index.rowCount) {
            states[row] = state
            if (row and 1023 == 0) {
                ready.set(row)
                if (!isActive()) return
            }
            val line = index.text(text, row)
            state = try {
                lexer.tokenize(line, state, sink.also { it.reset() })
            } catch (_: RuntimeException) {
                0
            }
        }
        ready.set(index.rowCount)
    }

    fun tokenize(row: Int, line: String = index.text(text, row)): Tokens {
        val out = Tokens()
        val state = if (row < ready.get()) states[row] else 0
        try {
            lexer.tokenize(line, state, out)
        } catch (_: RuntimeException) {
            // A lexer bug must never take the document down: show the row plain.
            return Tokens()
        }
        return out
    }
}
