package com.example.core.analysis

import com.example.domain.model.PieceColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

data class ParsedPgnResult(
    val root: MoveNode,
    val metadata: GameMetadata,
    val initialFen: String,
    val pgn: String
)

data class MasterSampleGame(
    val title: String,
    val subtitle: String,
    val pgn: String
)

object PgnService {

    private val KNOWN_TITLED_PLAYERS: Map<String, String> = mapOf(
        "paul morphy" to "GM",
        "magnuscarlsen" to "GM",
        "magnus carlsen" to "GM",
        "hikaru" to "GM",
        "hikaru nakamura" to "GM",
        "garry kasparov" to "GM",
        "veselin topalov" to "GM",
        "danielnaroditsky" to "GM",
        "daniel naroditsky" to "GM",
        "gothamchess" to "IM",
        "levy rozman" to "IM",
        "fabianocaruana" to "GM",
        "fabiano caruana" to "GM",
        "alirezafirouzja" to "GM",
        "alireza firouzja" to "GM",
        "lachesisq" to "GM",
        "ian nepomniachtchi" to "GM",
        "anishgiri" to "GM",
        "anish giri" to "GM",
        "levonaronian" to "GM",
        "bobbyfischer" to "GM",
        "robert james fischer" to "GM",
        "mikhail tal" to "GM",
        "anatoly karpov" to "GM",
        "vladimir kramnik" to "GM",
        "viswanathan anand" to "GM",
        "ding liren" to "GM",
        "gukesh d" to "GM",
        "praggnanandhaa" to "GM",
        "botezlive" to "WFM",
        "alexandra botez" to "WFM",
        "annacramling" to "WFM",
        "anna cramling" to "WFM",
        "nemo" to "WGM",
        "akanemsko" to "WGM"
    )

    private val VALID_TITLES = setOf("GM", "IM", "FM", "CM", "NM", "WGM", "WIM", "WFM", "WCM")
    private val URL_GAME_ID_REGEX = Regex("/\\d{5,}|lichess\\.org/[a-z0-9]{8}", RegexOption.IGNORE_CASE)

    private fun todayDateStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun extractTitleAndName(rawName: String, explicitHeaderTitle: String? = null): Pair<String, String?> {
        val trimmed = rawName.trim()
        val expUpper = explicitHeaderTitle?.trim()?.uppercase(Locale.ROOT)
        if (expUpper != null && expUpper in VALID_TITLES) {
            return trimmed to expUpper
        }
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size >= 2 && parts[0].uppercase(Locale.ROOT) in VALID_TITLES) {
            return parts.drop(1).joinToString(" ") to parts[0].uppercase(Locale.ROOT)
        }
        val lookup = KNOWN_TITLED_PLAYERS[trimmed.lowercase(Locale.ROOT)]
        if (lookup != null) {
            return trimmed to lookup
        }
        return trimmed to null
    }

    fun calculateEloDelta(
        playerRating: Int?,
        opponentRating: Int?,
        result: String?,
        color: PieceColor = PieceColor.WHITE
    ): Int? {
        if (playerRating == null || opponentRating == null || result.isNullOrBlank() || result == "*") {
            return null
        }
        val actualScore = when (result) {
            "1-0" -> if (color == PieceColor.WHITE) 1.0 else 0.0
            "0-1" -> if (color == PieceColor.BLACK) 1.0 else 0.0
            "1/2-1/2", "½-½" -> 0.5
            else -> return null
        }
        val expectedScore = 1.0 / (1.0 + 10.0.pow((opponentRating - playerRating) / 400.0))
        var delta = (16.0 * (actualScore - expectedScore)).roundToInt()
        if (actualScore == 1.0 && delta < 1) delta = 1
        if (actualScore == 0.0 && delta > -1) delta = -1
        return delta
    }

    fun computeStableHash(input: String): String {
        var h1 = (0xdeadbeef.toInt() xor input.length)
        var h2 = (0x41c6ce57 xor input.length)
        for (i in input.indices) {
            val ch = input[i].code
            h1 = (h1 xor ch) * 2654435761L.toInt()
            h2 = (h2 xor ch) * 1597334677
        }
        h1 = ((h1 xor (h1 ushr 16)) * 2246822507L.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909L.toInt())
        h2 = ((h2 xor (h2 ushr 16)) * 2246822507L.toInt()) xor ((h1 xor (h1 ushr 13)) * 3266489909L.toInt())
        val combined = 4294967296L * (2097151L and h2.toLong()) + (h1.toLong() and 0xffffffffL)
        return combined.toString(36)
    }

    private fun getMainlineSanSignature(root: MoveNode): String {
        val sans = mutableListOf<String>()
        var cursor = root
        while (cursor.children.isNotEmpty()) {
            val next = cursor.children[0]
            sans.add(next.san)
            cursor = next
        }
        return sans.joinToString(" ")
    }

    fun computeCanonicalGameId(
        headers: Map<String, String>,
        root: MoveNode,
        initialFen: String,
        whiteName: String,
        blackName: String
    ): String {
        val link = (headers["Link"] ?: "").trim().lowercase(Locale.ROOT)
        val site = (headers["Site"] ?: "").trim().lowercase(Locale.ROOT)
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return "game_${computeStableHash(link)}"
        }
        if ((site.startsWith("http://") || site.startsWith("https://")) && URL_GAME_ID_REGEX.containsMatchIn(site)) {
            return "game_${computeStableHash(site)}"
        }

        val mainlineSig = getMainlineSanSignature(root)
        if (mainlineSig.isEmpty() && initialFen == VariationTree.DEFAULT_START_FEN) {
            return "game_${System.currentTimeMillis()}_${(10000..99999).random()}"
        }

        val rawKey = listOf(
            whiteName.trim().lowercase(Locale.ROOT),
            blackName.trim().lowercase(Locale.ROOT),
            (headers["Date"] ?: "").trim(),
            (headers["UTCDate"] ?: "").trim(),
            (headers["UTCTime"] ?: "").trim(),
            (headers["TimeControl"] ?: "").trim(),
            initialFen.trim(),
            mainlineSig
        ).joinToString("|")

        return "game_${computeStableHash(rawKey)}"
    }

    fun parsePgnToTree(
        pgnText: String,
        platformOverride: GamePlatform? = null,
        extraTitles: Pair<String?, String?>? = null
    ): ParsedPgnResult {
        val cleanPgn = pgnText.trim()
        val headers = OpeningBookService.parsePgnHeaders(cleanPgn)
        val initialFen = headers["FEN"]?.takeIf { it.isNotBlank() } ?: VariationTree.DEFAULT_START_FEN

        // Validate initial FEN
        ChesslibHelper.parseBoard(initialFen)
        val root = VariationTree.parsePgnMovetextToTree(cleanPgn, initialFen)

        val whiteRating = headers["WhiteElo"]?.toIntOrNull()
        val blackRating = headers["BlackElo"]?.toIntOrNull()
        val result = headers["Result"]?.takeIf { it.isNotBlank() } ?: "*"

        val whiteRatingDiff = headers["WhiteRatingDiff"]?.toIntOrNull()
            ?: calculateEloDelta(whiteRating, blackRating, result, PieceColor.WHITE)
        val blackRatingDiff = headers["BlackRatingDiff"]?.toIntOrNull()
            ?: calculateEloDelta(blackRating, whiteRating, result, PieceColor.BLACK)

        val (whiteName, whiteTitle) = extractTitleAndName(
            headers["White"]?.takeIf { it.isNotBlank() } ?: "Белые",
            extraTitles?.first ?: headers["WhiteTitle"]
        )
        val (blackName, blackTitle) = extractTitleAndName(
            headers["Black"]?.takeIf { it.isNotBlank() } ?: "Чёрные",
            extraTitles?.second ?: headers["BlackTitle"]
        )

        val siteLower = (headers["Site"] ?: "").lowercase(Locale.ROOT)
        val linkLower = (headers["Link"] ?: "").lowercase(Locale.ROOT)
        val detectedPlatform = platformOverride ?: when {
            "chess.com" in siteLower || "chess.com" in linkLower -> GamePlatform.CHESS_COM
            "lichess" in siteLower || "lichess" in linkLower -> GamePlatform.LICHESS
            else -> GamePlatform.PGN
        }

        val deterministicId = computeCanonicalGameId(headers, root, initialFen, whiteName, blackName)
        val metadata = GameMetadata(
            id = deterministicId,
            whiteName = whiteName,
            blackName = blackName,
            whiteTitle = whiteTitle,
            blackTitle = blackTitle,
            whiteRating = whiteRating,
            blackRating = blackRating,
            whiteRatingDiff = whiteRatingDiff,
            blackRatingDiff = blackRatingDiff,
            timeControl = headers["TimeControl"]?.takeIf { it.isNotBlank() } ?: "Классика",
            platform = detectedPlatform,
            date = headers["Date"]?.takeIf { it.isNotBlank() } ?: todayDateStr(),
            result = result,
            event = headers["Event"]?.takeIf { it.isNotBlank() } ?: "Шахматная партия",
            site = headers["Link"]?.takeIf { it.isNotBlank() } ?: headers["Site"] ?: "",
            eco = headers["ECO"]?.takeIf { it.isNotBlank() },
            openingName = headers["Opening"]?.takeIf { it.isNotBlank() }
        )

        return ParsedPgnResult(
            root = root,
            metadata = metadata,
            initialFen = initialFen,
            pgn = cleanPgn
        )
    }

    fun createTreeFromFen(fen: String): ParsedPgnResult {
        val board = ChesslibHelper.parseBoard(fen.trim())
        val validFen = board.fen
        val root = VariationTree.createRootNode(validFen)
        val metadata = GameMetadata(
            id = "fen_${computeStableHash(validFen)}_${System.currentTimeMillis().toString(36)}",
            whiteName = "Белые",
            blackName = "Чёрные",
            platform = GamePlatform.ANALYSIS,
            date = todayDateStr(),
            result = "*",
            event = "Позиция из FEN",
            timeControl = "-"
        )
        return ParsedPgnResult(
            root = root,
            metadata = metadata,
            initialFen = validFen,
            pgn = "[FEN \"$validFen\"]\n[SetUp \"1\"]\n\n*"
        )
    }

    fun createNewEmptyGame(): ParsedPgnResult {
        val root = VariationTree.createRootNode(VariationTree.DEFAULT_START_FEN)
        val today = todayDateStr()
        val metadata = GameMetadata(
            id = "local_${System.currentTimeMillis()}_${(1000..9999).random()}",
            whiteName = "Белые",
            blackName = "Чёрные",
            platform = GamePlatform.ANALYSIS,
            date = today,
            result = "*",
            event = "Свободная партия",
            timeControl = "Без часов"
        )
        return ParsedPgnResult(
            root = root,
            metadata = metadata,
            initialFen = VariationTree.DEFAULT_START_FEN,
            pgn = "[Event \"Свободная партия\"]\n[Date \"$today\"]\n[White \"Белые\"]\n[Black \"Чёрные\"]\n[Result \"*\"]\n\n*"
        )
    }

    val SAMPLE_MASTER_GAMES: List<MasterSampleGame> = listOf(
        MasterSampleGame(
            title = "Морфи — Герцог Брауншвейгский (Опера в Париже, 1858)",
            subtitle = "Защита Филидора · Блестящие жертвы и форсированный мат в 17 ходов",
            pgn = """
                [Event "Opera Game"]
                [Site "Paris FRA"]
                [Date "1858.11.02"]
                [White "Paul Morphy"]
                [Black "Duke Karl / Count Isouard"]
                [WhiteTitle "GM"]
                [WhiteElo "2690"]
                [BlackElo "2150"]
                [WhiteRatingDiff "+4"]
                [BlackRatingDiff "-4"]
                [TimeControl "Classical"]
                [Result "1-0"]
                [ECO "C41"]

                1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8# 1-0
            """.trimIndent()
        ),
        MasterSampleGame(
            title = "Магнус Карлсен — Хикару Накамура (Titled Tuesday, 2024)",
            subtitle = "Испанская партия · Острая тактическая борьба с перехватом инициативы",
            pgn = """
                [Event "Titled Tuesday Blitz"]
                [Site "Chess.com"]
                [Date "2024.05.14"]
                [White "MagnusCarlsen"]
                [Black "Hikaru"]
                [WhiteTitle "GM"]
                [BlackTitle "GM"]
                [WhiteElo "3265"]
                [BlackElo "3248"]
                [WhiteRatingDiff "+8"]
                [BlackRatingDiff "-8"]
                [TimeControl "180+1"]
                [Result "1-0"]
                [ECO "C65"]

                1. e4 e5 2. Nf3 Nc6 3. Bb5 Nf6 4. O-O Nxe4 5. Re1 Nd6 6. Nxe5 Be7 7. Bf1 Nxe5 8. Rxe5 O-O 9. d4 Bf6 10. Re1 Re8 11. c3 Rxe1 12. Qxe1 Ne8 13. Bf4 d5 14. Bd3 g6 15. Nd2 Ng7 16. Qe2 c6 17. Re1 Bf5 18. Bxf5 Nxf5 19. Nf3 Ng7 20. Be5 Ne6 21. Bxf6 Qxf6 22. Ne5 Re8 23. Ng4 Qd8 24. Qe5 Ng7 25. Nf6+ Kf8 26. Nxe8 Qxe8 27. Qd6+ 1-0
            """.trimIndent()
        ),
        MasterSampleGame(
            title = "Гарри Каспаров — Веселин Топалов (Вейк-ан-Зее, 1999)",
            subtitle = "Защита Пирца-Уфимцева · «Бессмертная партия Каспарова» с жертвой ладьи",
            pgn = """
                [Event "Hoogovens Wijk aan Zee"]
                [Site "Wijk aan Zee NED"]
                [Date "1999.01.20"]
                [White "Garry Kasparov"]
                [Black "Veselin Topalov"]
                [WhiteTitle "GM"]
                [BlackTitle "GM"]
                [WhiteElo "2812"]
                [BlackElo "2700"]
                [WhiteRatingDiff "+6"]
                [BlackRatingDiff "-6"]
                [TimeControl "40/7200"]
                [Result "1-0"]
                [ECO "B07"]

                1. e4 d6 2. d4 Nf6 3. Nc3 g6 4. Be3 Bg7 5. Qd2 c6 6. f3 b5 7. Nge2 Nbd7 8. Bh6 Bxh6 9. Qxh6 Bb7 10. a3 e5 11. O-O-O Qe7 12. Kb1 a6 13. Nc1 O-O-O 14. Nb3 exd4 15. Rxd4 c5 16. Rd1 Nb6 17. g3 Kb8 18. Na5 Ba8 19. Bh3 d5 20. Qf4+ Ka7 21. Rhe1 d4 22. Nd5 Nbxd5 23. exd5 Qd6 24. Rxd4 cxd4 25. Re7+ Kb6 26. Qxd4+ Kxa5 27. b4+ Ka4 28. Qc3 Qxd5 29. Ra7 Bb7 30. Rxb7 Qc4 31. Qxf6 Kxa3 32. Qxa6+ Kxb4 33. c3+ Kxc3 34. Qa1+ Kd2 35. Qb2+ Kd1 36. Bf1 Rd2 37. Rd7 Rxd7 38. Bxc4 bxc4 39. Qxh8 Rd3 40. Qa8 c3 41. Qa4+ Ke1 42. f4 f5 43. Kc1 Rd2 44. Qa7 1-0
            """.trimIndent()
        )
    )
}
