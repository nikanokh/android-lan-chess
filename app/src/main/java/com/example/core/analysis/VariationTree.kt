package com.example.core.analysis

import com.example.core.ui.board.BoardSquare
import com.example.core.ui.board.FenBoardParser
import com.example.core.ui.board.PieceType
import com.example.domain.model.PieceColor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

data class AddMoveResult(
    val updatedRoot: MoveNode,
    val newNode: MoveNode?,
    val error: String? = null
)

object VariationTree {
    const val DEFAULT_START_FEN: String = FenBoardParser.INITIAL_FEN

    private val idCounter = AtomicLong(1L)

    fun generateNodeId(): String = "node_${System.currentTimeMillis()}_${idCounter.getAndIncrement()}"

    fun createRootNode(initialFen: String = DEFAULT_START_FEN): MoveNode = MoveNode(
        id = "root",
        parentId = null,
        ply = 0,
        moveNumber = 0,
        color = PieceColor.BLACK,
        san = "",
        uci = "",
        fen = initialFen,
        children = mutableListOf(),
        isMainline = true
    )

    fun getPrimaryChild(node: MoveNode): MoveNode? {
        if (node.children.isEmpty()) return null
        return node.children.firstOrNull { it.isMainline } ?: node.children.first()
    }

    /**
     * Enforces strict mainline invariants across the entire tree:
     * 1. `root.isMainline = true`.
     * 2. If a parent has `isMainline == true` and `children.isNotEmpty()`,
     *    exactly one child has `isMainline == true` (positioned at `children[0]`),
     *    while all other sibling children have `isMainline == false`.
     * 3. If a parent has `isMainline == false`, all descendants have `isMainline == false`.
     */
    fun normalizeMainlineFlags(root: MoveNode, promoteChainFromNode: MoveNode? = null) {
        root.isMainline = true

        if (promoteChainFromNode != null) {
            var curr: MoveNode? = promoteChainFromNode
            while (curr != null) {
                curr.isMainline = true
                curr = curr.children.firstOrNull { it.isMainline } ?: curr.children.firstOrNull()
            }
        }

        fun enforce(node: MoveNode) {
            if (!node.isMainline) {
                for (child in node.children) {
                    child.isMainline = false
                    enforce(child)
                }
                return
            }

            if (node.children.isEmpty()) return

            var mainIdx = node.children.indexOfFirst { it.isMainline }
            if (mainIdx == -1) {
                mainIdx = 0
            }

            if (mainIdx > 0) {
                val mainChild = node.children.removeAt(mainIdx)
                node.children.add(0, mainChild)
            }

            node.children[0].isMainline = true
            for (i in 1 until node.children.size) {
                node.children[i].isMainline = false
            }

            var chain = node.children[0]
            while (chain.children.isNotEmpty() && chain.children.none { it.isMainline }) {
                chain.children[0].isMainline = true
                chain = chain.children[0]
            }

            for (child in node.children) {
                enforce(child)
            }
        }

        enforce(root)
    }

    fun findNodeById(root: MoveNode, id: String): MoveNode? {
        if (root.id == id) return root
        for (child in root.children) {
            val found = findNodeById(child, id)
            if (found != null) return found
        }
        return null
    }

    fun getMainlineNodes(root: MoveNode): List<MoveNode> {
        val result = mutableListOf(root)
        var current = root
        while (current.children.isNotEmpty()) {
            val nextMain = getPrimaryChild(current) ?: break
            result.add(nextMain)
            current = nextMain
        }
        return result
    }

    fun getPathToNode(root: MoveNode, targetId: String): List<MoveNode> {
        val path = mutableListOf<MoveNode>()
        fun dfs(current: MoveNode): Boolean {
            path.add(current)
            if (current.id == targetId) return true
            for (child in current.children) {
                if (dfs(child)) return true
            }
            path.removeAt(path.lastIndex)
            return false
        }
        dfs(root)
        return path
    }

    fun getAllTreeNodes(root: MoveNode): List<MoveNode> {
        val result = mutableListOf<MoveNode>()
        fun visit(node: MoveNode) {
            result.add(node)
            for (child in node.children) {
                visit(child)
            }
        }
        visit(root)
        return result
    }

    fun addMoveToTree(
        root: MoveNode,
        parentNodeId: String,
        from: BoardSquare,
        to: BoardSquare,
        promotion: PieceType? = null
    ): AddMoveResult {
        val clonedRoot = root.deepCopy()
        val parentNode = findNodeById(clonedRoot, parentNodeId)
            ?: return AddMoveResult(root, null, "Исходная позиция не найдена")

        val board = try {
            ChesslibHelper.parseBoard(parentNode.fen)
        } catch (ex: Exception) {
            return AddMoveResult(root, null, ex.message ?: "Некорректный FEN")
        }

        val moveResult = ChesslibHelper.applyMove(board, from, to, promotion)
            ?: return AddMoveResult(root, null, "Недопустимый ход по правилам шахмат")

        return insertAppliedMove(clonedRoot, parentNode, moveResult)
    }

    fun addSanOrUciMoveToTree(
        root: MoveNode,
        parentNodeId: String,
        sanOrUci: String
    ): AddMoveResult {
        val clonedRoot = root.deepCopy()
        val parentNode = findNodeById(clonedRoot, parentNodeId)
            ?: return AddMoveResult(root, null, "Исходная позиция не найдена")

        val board = try {
            ChesslibHelper.parseBoard(parentNode.fen)
        } catch (ex: Exception) {
            return AddMoveResult(root, null, ex.message ?: "Некорректный FEN")
        }

        val moveResult = ChesslibHelper.applySan(board, sanOrUci)
            ?: ChesslibHelper.applyUci(board, sanOrUci)
            ?: return AddMoveResult(root, null, "Недопустимый ход: $sanOrUci")

        return insertAppliedMove(clonedRoot, parentNode, moveResult)
    }

    private fun insertAppliedMove(
        clonedRoot: MoveNode,
        parentNode: MoveNode,
        moveResult: AppliedChessMove
    ): AddMoveResult {
        val existingChild = parentNode.children.firstOrNull {
            it.uci == moveResult.uci || it.san == moveResult.san
        }
        if (existingChild != null) {
            return AddMoveResult(clonedRoot, existingChild)
        }

        val nextPly = parentNode.ply + 1
        val nextMoveNumber = ceil(nextPly / 2.0).toInt()
        val hasMainlineSibling = parentNode.children.any { it.isMainline }
        val isMainline = parentNode.isMainline && !hasMainlineSibling && parentNode.children.isEmpty()

        val newNode = MoveNode(
            id = generateNodeId(),
            parentId = parentNode.id,
            ply = nextPly,
            moveNumber = nextMoveNumber,
            color = moveResult.color,
            san = moveResult.san,
            uci = moveResult.uci,
            from = moveResult.from,
            to = moveResult.to,
            fen = moveResult.fenAfter,
            children = mutableListOf(),
            isMainline = isMainline
        )

        parentNode.children.add(newNode)
        normalizeMainlineFlags(clonedRoot)
        return AddMoveResult(clonedRoot, newNode)
    }

    fun deleteVariationNode(root: MoveNode, nodeId: String): MoveNode {
        if (nodeId == "root") return root
        val clonedRoot = root.deepCopy()

        fun removeChild(current: MoveNode): Boolean {
            val idx = current.children.indexOfFirst { it.id == nodeId }
            if (idx != -1) {
                val removed = current.children.removeAt(idx)
                if (removed.isMainline && current.isMainline && current.children.isNotEmpty()) {
                    var promoted: MoveNode? = current.children[0]
                    while (promoted != null) {
                        promoted.isMainline = true
                        promoted = promoted.children.firstOrNull()
                    }
                }
                return true
            }
            for (child in current.children) {
                if (removeChild(child)) return true
            }
            return false
        }

        removeChild(clonedRoot)
        normalizeMainlineFlags(clonedRoot)
        return clonedRoot
    }

    fun exportTreeToPgn(
        root: MoveNode,
        headers: Map<String, String> = emptyMap()
    ): String {
        val headerLines = headers.entries
            .filter { it.value.isNotBlank() }
            .map { (k, v) -> "[$k \"${v.replace("\"", "\\\"")}\"]" }

        fun serializeSubtree(node: MoveNode, forceMoveNumber: Boolean): String {
            if (node.children.isEmpty()) return ""
            val mainChild = getPrimaryChild(node) ?: return ""
            val variations = node.children.filter { it.id != mainChild.id }

            val prefix = when {
                mainChild.color == PieceColor.WHITE -> "${mainChild.moveNumber}. "
                forceMoveNumber -> "${mainChild.moveNumber}... "
                else -> ""
            }

            val sb = StringBuilder()
            sb.append(prefix).append(mainChild.san)
            if (!mainChild.comment.isNullOrBlank()) {
                sb.append(" { ").append(mainChild.comment).append(" }")
            }

            for (v in variations) {
                val vPrefix = if (v.color == PieceColor.WHITE) "${v.moveNumber}. " else "${v.moveNumber}... "
                sb.append(" (").append(vPrefix).append(v.san)
                if (!v.comment.isNullOrBlank()) {
                    sb.append(" { ").append(v.comment).append(" }")
                }
                val vCont = serializeSubtree(v, false)
                if (vCont.isNotEmpty()) {
                    sb.append(" ").append(vCont)
                }
                sb.append(")")
            }

            val nextText = serializeSubtree(mainChild, variations.isNotEmpty())
            if (nextText.isNotEmpty()) {
                sb.append(" ").append(nextText)
            }
            return sb.toString()
        }

        val movesBody = serializeSubtree(root, true)
        val resultTag = headers["Result"]?.takeIf { it.isNotBlank() } ?: "*"
        val bodyWithResult = (if (movesBody.isNotEmpty()) "$movesBody " else "") + resultTag
        return if (headerLines.isEmpty()) {
            bodyWithResult.trim()
        } else {
            "${headerLines.joinToString("\n")}\n\n${bodyWithResult.trim()}".trim()
        }
    }

    private enum class PgnTokenType { LPAREN, RPAREN, COMMENT, WORD }
    private data class PgnToken(val type: PgnTokenType, val value: String)

    private val HEADER_STRIP_REGEX = Regex("^\\s*(?:\\[[A-Za-z0-9_]+\\s+\"(?:\\\\\"|[^\"])*\"\\]\\s*)*")
    private val RESULT_TOKENS = setOf("1-0", "0-1", "1/2-1/2", "½-½", "*")
    private val MOVE_NUM_PREFIX_REGEX = Regex("^\\d+\\.+")
    private val NAG_SUFFIX_REGEX = Regex("[!?]+$")

    fun parsePgnMovetextToTree(
        pgnText: String,
        initialFen: String = DEFAULT_START_FEN
    ): MoveNode {
        val root = createRootNode(initialFen)
        val body = pgnText.replace(HEADER_STRIP_REGEX, "").trim()
        if (body.isEmpty()) return root

        val tokens = mutableListOf<PgnToken>()
        var i = 0
        val n = body.length

        while (i < n) {
            val ch = body[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            if (ch == '{') {
                val endIdx = body.indexOf('}', i + 1)
                if (endIdx == -1) {
                    tokens.add(PgnToken(PgnTokenType.COMMENT, body.substring(i + 1).trim()))
                    break
                }
                tokens.add(PgnToken(PgnTokenType.COMMENT, body.substring(i + 1, endIdx).trim()))
                i = endIdx + 1
                continue
            }
            if (ch == ';') {
                val endLine = body.indexOf('\n', i + 1)
                i = if (endLine == -1) n else endLine + 1
                continue
            }
            if (ch == '(') {
                tokens.add(PgnToken(PgnTokenType.LPAREN, "("))
                i++
                continue
            }
            if (ch == ')') {
                tokens.add(PgnToken(PgnTokenType.RPAREN, ")"))
                i++
                continue
            }

            var j = i
            while (j < n && !body[j].isWhitespace() && body[j] !in charArrayOf('{', '}', '(', ')', ';')) {
                j++
            }
            val word = body.substring(i, j)
            if (word.isNotEmpty()) {
                tokens.add(PgnToken(PgnTokenType.WORD, word))
            }
            i = j
        }

        var currentNode = root
        var lastMoveParent = root
        val branchStack = ArrayDeque<Pair<MoveNode, MoveNode>>()

        for (tok in tokens) {
            when (tok.type) {
                PgnTokenType.LPAREN -> {
                    branchStack.addLast(currentNode to lastMoveParent)
                    currentNode = lastMoveParent
                }
                PgnTokenType.RPAREN -> {
                    val popped = branchStack.removeLastOrNull()
                    if (popped != null) {
                        currentNode = popped.first
                        lastMoveParent = popped.second
                    }
                }
                PgnTokenType.COMMENT -> {
                    if (currentNode.id != "root" && tok.value.isNotBlank()) {
                        currentNode.comment = if (currentNode.comment.isNullOrBlank()) {
                            tok.value
                        } else {
                            "${currentNode.comment} ${tok.value}"
                        }
                    }
                }
                PgnTokenType.WORD -> {
                    val rawWord = tok.value
                    if (rawWord in RESULT_TOKENS || rawWord.startsWith("$")) continue

                    val cleanedSan = rawWord
                        .replace(MOVE_NUM_PREFIX_REGEX, "")
                        .replace(NAG_SUFFIX_REGEX, "")
                        .trim()

                    if (cleanedSan.isEmpty() || cleanedSan in RESULT_TOKENS) continue

                    try {
                        val board = ChesslibHelper.parseBoard(currentNode.fen)
                        val moveObj = ChesslibHelper.applySan(board, cleanedSan) ?: continue

                        var childNode = currentNode.children.firstOrNull {
                            it.uci == moveObj.uci || it.san == moveObj.san
                        }
                        if (childNode == null) {
                            val nextPly = currentNode.ply + 1
                            val nextMoveNumber = ceil(nextPly / 2.0).toInt()
                            val isOnMainline = branchStack.isEmpty() &&
                                currentNode.isMainline &&
                                currentNode.children.isEmpty()

                            childNode = MoveNode(
                                id = generateNodeId(),
                                parentId = currentNode.id,
                                ply = nextPly,
                                moveNumber = nextMoveNumber,
                                color = moveObj.color,
                                san = moveObj.san,
                                uci = moveObj.uci,
                                from = moveObj.from,
                                to = moveObj.to,
                                fen = moveObj.fenAfter,
                                children = mutableListOf(),
                                isMainline = isOnMainline
                            )
                            currentNode.children.add(childNode)
                        }
                        lastMoveParent = currentNode
                        currentNode = childNode
                    } catch (_: Exception) {
                        // ignore unparseable annotation token
                    }
                }
            }
        }

        normalizeMainlineFlags(root)
        return root
    }
}
