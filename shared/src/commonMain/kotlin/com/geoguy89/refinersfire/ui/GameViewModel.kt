package com.geoguy89.refinersfire.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geoguy89.refinersfire.audio.AudioPlayer
import com.geoguy89.refinersfire.epochMillis
import com.geoguy89.refinersfire.net.MannaSubmitBody
import com.geoguy89.refinersfire.game.Manna
import com.geoguy89.refinersfire.game.Puzzles
import com.geoguy89.refinersfire.data.MannaResult
import com.geoguy89.refinersfire.localDay
import com.geoguy89.refinersfire.audio.Sfx
import com.geoguy89.refinersfire.data.HighScore
import com.geoguy89.refinersfire.data.Settings
import com.geoguy89.refinersfire.data.Store
import com.geoguy89.refinersfire.game.Achievement
import com.geoguy89.refinersfire.game.Achievements
import com.geoguy89.refinersfire.game.GameEngine
import com.geoguy89.refinersfire.game.LifetimeStats
import com.geoguy89.refinersfire.game.Difficulty
import com.geoguy89.refinersfire.game.GameEvent
import com.geoguy89.refinersfire.game.GameMode
import com.geoguy89.refinersfire.game.GameState
import com.geoguy89.refinersfire.game.Piece
import com.geoguy89.refinersfire.gfx.BoardFx
import com.geoguy89.refinersfire.gfx.Palette
import com.geoguy89.refinersfire.ThemeId
import com.geoguy89.refinersfire.game.COLS
import com.geoguy89.refinersfire.game.ROWS
import com.geoguy89.refinersfire.net.CHAT_MAX_CHARS
import com.geoguy89.refinersfire.net.ChatCrypto
import com.geoguy89.refinersfire.net.ChatLine
import com.geoguy89.refinersfire.net.AsyncChallenge
import com.geoguy89.refinersfire.net.AsyncSubmitBody
import com.geoguy89.refinersfire.net.Friend
import com.geoguy89.refinersfire.net.LeaderboardEntry
import com.geoguy89.refinersfire.net.Rival
import com.geoguy89.refinersfire.net.NoChatCrypto
import com.geoguy89.refinersfire.net.Sealed
import com.geoguy89.refinersfire.net.AppUpdater
import com.geoguy89.refinersfire.net.NoUpdater
import com.geoguy89.refinersfire.net.UpdateInfo
import com.geoguy89.refinersfire.net.HttpTransport
import com.geoguy89.refinersfire.net.LanProtocol
import com.geoguy89.refinersfire.net.LanTransport
import com.geoguy89.refinersfire.net.MatchGoal
import com.geoguy89.refinersfire.net.MatchPhase
import com.geoguy89.refinersfire.net.MatchSession
import com.geoguy89.refinersfire.net.NoHttp
import com.geoguy89.refinersfire.net.NoLanTransport
import com.geoguy89.refinersfire.net.OnlineService
import com.geoguy89.refinersfire.net.LeaderboardStanding
import com.geoguy89.refinersfire.net.BoardKey
import com.geoguy89.refinersfire.DebugLog
import com.geoguy89.refinersfire.net.NoPush
import com.geoguy89.refinersfire.net.PushRegistrar
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class Screen { TITLE, GAME }

enum class Haptic { TICK, CONFIRM, REJECT, HEAVY }

sealed interface Overlay {
    data object NewGame : Overlay
    data object Options : Overlay
    data object HighScores : Overlay
    data object HowToPlay : Overlay
    data object Pause : Overlay
    data object ConfirmQuit : Overlay
    data object ConfirmAbandonMatch : Overlay
    data class BoardComplete(val event: GameEvent.BoardCleared) : Overlay
    data class GameOver(val final: GameState, val qualifies: Boolean) : Overlay
    data object Friends : Overlay
    data class Challenge(val rival: Rival) : Overlay
    data class PlayerCard(val entry: LeaderboardEntry) : Overlay
    data object MatchLobby : Overlay
    /** Pick friends and settings for a gathering. */
    data object GatheringSetup : Overlay
    data object ConfirmLeaveMatch : Overlay
    data object MatchOver : Overlay
    data class Chat(val friendId: String) : Overlay
    data object Achievements : Overlay
    data object ChangeName : Overlay
    data object ReportBug : Overlay
    data object Update : Overlay
    data object Peek : Overlay
    data class AsyncSetup(val rival: Rival) : Overlay
    /** The puzzle book. */
    data object Puzzles : Overlay
    /** A puzzle ended: solved (with stars) or not, and why. */
    data class PuzzleDone(val id: Int, val solved: Boolean, val stars: Int, val reason: String) : Overlay
    /** Today's Manna: the standings, and the way in. */
    data object Manna : Overlay
    /** A Manna run just ended. */
    data class MannaDone(val result: MannaResult, val streak: Int) : Overlay
    /** A challenge run just ended: [sent] for the challenger, otherwise the outcome for the friend. */
    data class AsyncDone(val rival: String, val rivalId: String?, val score: Long, val theirScore: Long?, val sent: Boolean, val won: Boolean?) : Overlay
}

/** Local = nearby only, own scores only. Plus = Local+: pass on open scores and list yours on the global board. */
enum class ShareMode(val label: String) { PLUS("Global"), LOCAL("Local"), HIDDEN("Hide Activity") }

/** Days of Manna that unlock the Starlight theme. */
const val STARLIGHT_DAYS = 7

enum class HallTab(val label: String) { GLOBAL("Global"), FRIENDS("Friends"), NEARBY("Nearby"), MINE("Mine") }

/** One Hall of Fame line: the score, where it came from, and who to befriend (if anyone). */
data class HallRow(val score: HighScore, val tag: String?, val playerId: String?, val isMe: Boolean)

/** All game-session state and actions. Platform code owns its lifetime and calls [dispose] at the end. */
class GameViewModel(
    private val store: Store,
    val audio: AudioPlayer,
    private val lan: LanTransport = NoLanTransport,
    http: HttpTransport = NoHttp,
    private val crypto: ChatCrypto = NoChatCrypto,
    val updater: AppUpdater = NoUpdater,
    val push: PushRegistrar = NoPush,
) {
    val fx = BoardFx()

    var settings by mutableStateOf(store.loadSettings())
        private set
    var screen by mutableStateOf(Screen.TITLE)
        private set
    /** A stack, so Options can open on top of Pause and close back to it. */
    var overlays by mutableStateOf(listOf<Overlay>())
        private set
    val overlay: Overlay? get() = overlays.lastOrNull()

    private var engine: GameEngine? = null

    /** Test-only: the current piece's legal squares, without the wrong-guess forge penalty a probing tap would cost. */
    internal fun validCellsForTest(): List<Int> = engine?.validCells() ?: emptyList()
    var state by mutableStateOf<GameState?>(null)
        private set
    var savedGame by mutableStateOf(store.loadGame())
        private set
    /** An unfinished async challenge run, resumable from the title screen. */
    var savedChallenge by mutableStateOf(store.loadChallengeGame())
        private set
    /** Today's Manna in progress, and every day's result on this device. */
    var savedManna by mutableStateOf(store.loadMannaGame())
        private set
    var mannaHistory by mutableStateOf(store.loadMannaHistory())
        private set
    /** Best stars per puzzle. */
    var puzzleStars by mutableStateOf(store.loadPuzzleStars())
        private set
    private var announced = store.loadAnnounced()
    var highScores by mutableStateOf(store.loadHighScores())
        private set
    /** Scores received from other devices on the local network. */
    var peers by mutableStateOf(store.loadPeers())
        private set

    private val deviceId = store.deviceId()
    /** Datagrams arrive on a network thread; they are applied on the next frame. */
    private val inbox = Channel<ByteArray>(64, BufferOverflow.DROP_OLDEST)
    private var lanActive = false
    private var lastBroadcast = -100f

    /** Network callbacks (HTTP, match socket) queue work here; it runs on the UI thread at the next frame. */
    private val tasks = Channel<() -> Unit>(Channel.UNLIMITED)
    private val post: (() -> Unit) -> Unit = { tasks.trySend(it) }

    val online = OnlineService(http, post, store.loadAccount(), store::saveAccount).also { svc ->
        svc.friends = store.loadFriends()
    }
    private var foreground = false
    private var lastSync = -100f
    private var scoresPushed = false

    /** The 1v1 match in progress, if any. */
    var match by mutableStateOf<MatchSession?>(null)
        private set
    private val joinedMatches = HashSet<String>()
    /** Open conversation (loaded from storage when a chat is opened) and unread counts per friend. */
    var chatLines by mutableStateOf<List<ChatLine>>(emptyList())
        private set
    var unread by mutableStateOf(store.loadUnread())
        private set
    private var keyPublished = false
    private var pushRegistered = false
    private var trayAnnounced = setOf<String>()

    /** False while the desktop window is in the background; notices then go to the system tray. */
    var windowFocused = true
    /** Notices for the desktop tray (title, text) when the window isn't focused. */
    val systemNotices = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Achievements: counters, what's unlocked, and a queue of unlocks waiting for a quiet moment to be mentioned.
    var stats by mutableStateOf(store.loadStats())
        private set
    var unlocked by mutableStateOf(store.loadAchievements())
        private set
    var unseenAchievements by mutableStateOf(store.loadUnseenAchievements())
        private set
    private var pendingAnnounce = listOf<Achievement>()
    /** The one-line "achievement unlocked" note currently showing, and when it goes away. */
    var achievementNote by mutableStateOf<String?>(null)
        private set
    private var achievementNoteUntil = 0f
    private var unsavedPlayMs = 0L
    private var boardStartMs = 0L
    private var boardStartDiscards = 0
    private var boardStartScore = 0L

    /** A newer release, once found. Offered on the title screen only. */
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    /** Download progress 0..1 while an update is downloading, else null. */
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    private var updateChecked = false
    private var updateOffered = false

    /** A short message for the player ("Request sent", errors). Cleared by [dismissNotice]. */
    private var noticeState by mutableStateOf<String?>(null)
    var notice: String?
        get() = noticeState
        private set(value) {
            if (value != null) com.geoguy89.refinersfire.DebugLog.add("notice: $value")
            noticeState = value
        }
    /** Set when the notice is about a new chat message: the toast then offers to open that chat. */
    private var noticeChat: Pair<String, String>? = null
    /** The friend whose new message the current notice is about, if it is one. */
    val noticeChatFrom: String? get() = noticeChat?.takeIf { it.first == notice }?.second

    /** Hovered cell while a finger is on the board, or -1. */
    var hoverIndex by mutableIntStateOf(-1)
    /** Effect clock times for the hand slot and forge. */
    var pieceArrivedAt by mutableFloatStateOf(-10f)
        private set
    var forgeFlareAt by mutableFloatStateOf(-10f)
        private set
    var forgeCoolAt by mutableFloatStateOf(-10f)
        private set
    /** Squares revealed by the hint for the current piece. */
    var hintCells by mutableStateOf<Set<Int>>(emptySet())
        private set
    /** The hint was used on this piece (so it is not charged twice). */
    var hintShown by mutableStateOf(false)
        private set
    /** A hint found no legal square for this piece: the discard button pulses. */
    var noMoves by mutableStateOf(false)
        private set

    private val _haptics = MutableSharedFlow<Haptic>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val haptics: SharedFlow<Haptic> = _haptics

    private var lastFrameNanos = 0L
    private var clockStartNanos = 0L
    private var lastTickSecond = -1L

    init {
        if (settings.nameChosen) com.geoguy89.refinersfire.keepSavedDataSafe()
        audio.sfxVolume = settings.sfxVolume
        audio.musicVolume = settings.musicVolume
        Palette.theme = settings.theme
        Palette.pieceSet = settings.pieceSet
        audio.setTheme(settings.theme)
        online.onAccountRejected = {
            store.clearAccount()
            store.saveFriends(emptyList())
            online.friends = emptyList()
            keyPublished = false
            // Make a fresh account straight away and put our scores back up.
            online.ensureAccount(settings.playerName) { online.pushScores(store.scoresForUpload(), settings.sharePlus); online.sync() }
        }
        online.onSynced = { r ->
            store.saveFriends(r.friends)
            // Keep the server in step with the privacy switch (it may have been changed while offline).
            if (r.me.hideActivity != settings.hideActivity) online.setHideActivity(settings.hideActivity)
            if (r.friends.size > stats.friends) bump { it.copy(friends = r.friends.size) }
            publishChatKey(r.me.publicKey)
            registerPush()
            // Desktop: new challenges and friend requests while the window is in the background.
            if (!windowFocused) {
                r.invites.filter { it.incoming && it.status == "pending" && it.id !in trayAnnounced }.forEach {
                    systemNotices.tryEmit("Refiner's Fire" to "${it.name} challenges you to a 1v1")
                }
                r.incoming.filter { it.id !in trayAnnounced }.forEach { systemNotices.tryEmit("Refiner's Fire" to "${it.name} wants to connect") }
            }
            trayAnnounced = trayAnnounced + r.invites.map { it.id } + r.incoming.map { it.id }
            receiveChat(r.messages)
            announceChallenges(r.asyncChallenges)
            retryPendingResults()
            retryPendingManna()
            if (!scoresPushed) {
                scoresPushed = true
                online.pushScores(store.scoresForUpload(), settings.sharePlus)
            }
            // Our challenge was accepted: join the match.
            r.invites.firstOrNull { !it.incoming && it.status == "accepted" && it.matchId != null && it.ageMs < 5 * 60_000 && it.matchId !in joinedMatches }
                ?.let { joinMatch(it.matchId!!) }
        }
    }

    /** The board shown on screen: while the "board complete" panel is up we keep showing the finished gold board. */
    val displayState: GameState?
        get() = (overlay as? Overlay.BoardComplete)?.event?.stats ?: state

    val boardInteractive: Boolean
        get() = screen == Screen.GAME && overlay == null && state?.gameOver == false &&
            (match == null || match?.phase == MatchPhase.PLAYING)

    // ---- Navigation ---------------------------------------------------------------------------------------------

    fun push(o: Overlay) { click(); overlays = overlays + o }

    fun pop() {
        click()
        overlays = overlays.dropLast(1)
    }

    fun onBack(): Boolean = when {
        overlay is Overlay.MatchOver -> { leaveMatch(); true }
        overlay is Overlay.MatchLobby -> { leaveMatch(); true }
        overlay == null && match != null -> { push(Overlay.ConfirmLeaveMatch); true }
        overlay is Overlay.BoardComplete -> { continueAfterBoard(); true }
        overlay is Overlay.GameOver -> { finishGameOver(false); true }
        overlay != null -> { pop(); true }
        screen == Screen.GAME -> { push(Overlay.Pause); true }
        else -> false
    }

    fun startNewGame(difficulty: Difficulty, mode: GameMode) {
        DebugLog.add("new game: ${difficulty.name.lowercase()}, ${mode.name.lowercase()}")
        click()
        updateSettings(settings.copy(difficulty = difficulty, mode = mode))
        val e = GameEngine.newGame(difficulty, mode)
        adopt(e)
        countGameStart()
        overlays = emptyList()
        screen = Screen.GAME
        persist()
    }

    fun resumeSavedGame() {
        val saved = savedGame ?: return
        click()
        adopt(GameEngine(saved))
        overlays = emptyList()
        screen = Screen.GAME
    }

    /** Jump straight into a prepared position (used by screenshot tests). */
    fun loadForPreview(s: GameState, overlay: Overlay? = null) {
        adopt(GameEngine(s))
        overlays = listOfNotNull(overlay)
        screen = Screen.GAME
    }

    /** A finished (or failed) live match must not keep the board locked once another game starts. */
    private fun dropFinishedMatch() {
        val m = match ?: return
        if (m.phase == MatchPhase.ENDED || m.phase == MatchPhase.FAILED) {
            m.leave()
            match = null
        }
    }

    private fun adopt(e: GameEngine) {
        if (e.state.matchSeed == null || e.state.challengeId != null) dropFinishedMatch()
        engine = e
        state = e.state
        fx.reset()
        pieceArrivedAt = fx.now
        markBoardStart(e.state)
        hintShown = false
        hintCells = emptySet()
        noMoves = false
    }

    fun quitToTitle() {
        click()
        toTitle()
    }

    private fun toTitle() {
        persist()
        overlays = emptyList()
        screen = Screen.TITLE
        engine = null
        state = null
        savedGame = store.loadGame()
        savedChallenge = store.loadChallengeGame()
    }

    // ---- Play ---------------------------------------------------------------------------------------------------

    fun tapCell(index: Int) {
        val e = engine ?: return
        if (!boardInteractive) return
        val m = match
        if (m != null && m.isCoop) {
            if (!m.isMyTurn) { fx.banner("It's ${m.opponent?.name ?: "your partner"}'s turn", Palette.parchment, height = 0.34f, y = ROWS - 1.7f, duration = 1.2f); return }
            if (m.moveInFlight) return
            if (e.canPlay(e.state.current, index)) m.sendMove("play", index)
            else {
                fx.invalid(index)
                audio.play(Sfx.INVALID)
                _haptics.tryEmit(Haptic.REJECT)
                if (isGuess(e.state, index)) m.sendMove("miss")
            }
            return
        }
        val events = e.play(index)
        if (events.isEmpty()) {
            fx.invalid(index)
            audio.play(Sfx.INVALID)
            _haptics.tryEmit(Haptic.REJECT)
            // Probing squares by hand instead of using a hint isn't free: every second wrong try stokes the forge.
            // Tapping a square that's already taken is just a slip, so it never counts.
            if (isGuess(e.state, index) && e.registerMiss()) {
                state = e.state
                forgeFlareAt = fx.now
                if (e.state.gameOver) {
                    fx.banner("Too many wrong guesses -- the forge overflowed!", Palette.lava, height = 0.42f, y = ROWS - 1.2f, duration = 2.4f)
                    handle(listOf(GameEvent.GameOver), origin = -1)
                } else {
                    fx.banner("Wrong guesses stoke the forge", Palette.lava, height = 0.42f, y = ROWS - 1.2f, duration = 2.0f)
                }
                persist()
            }
            return
        }
        handle(events, origin = index)
    }

    /**
     * Whether a refused tap was a guess at where the stone might go: an empty square tapped with a stone in hand. A tap on
     * a square that's already filled (or with the Hammer, which only ever needs a stone to strike) is an accident.
     */
    private fun isGuess(s: GameState, index: Int): Boolean = s.current !is Piece.Hammer && s.cells.getOrNull(index) == null

    fun discard() {
        val e = engine ?: return
        if (!boardInteractive) return
        val m = match
        if (m != null && m.isCoop) {
            if (m.isMyTurn) m.sendMove("discard")
            else fx.banner("It's ${m.opponent?.name ?: "your partner"}'s turn", Palette.parchment, height = 0.34f, y = ROWS - 1.7f, duration = 1.2f)
            return
        }
        handle(e.discard(), origin = -1)
    }

    /** The hint is useless on an empty board (every square is legal) and is charged once per piece. */
    val hintAvailable: Boolean
        get() = boardInteractive && !hintShown && state?.boardEmpty == false &&
            (match == null || (match!!.isCoop && match!!.isMyTurn && !match!!.moveInFlight)) &&
            (state?.hintsThisBoard ?: 0) < GameEngine.MAX_HINTS_PER_BOARD

    fun useHint() {
        val e = engine ?: return
        if (!hintAvailable) return
        // Co-op: the hint stokes the shared forge, so it goes through the turn like any move.
        match?.takeIf { it.isCoop }?.let { it.sendMove("hint"); return }
        val cells = e.useHint()
        bump { it.copy(hintsUsed = it.hintsUsed + 1) }
        hintShown = true
        hintCells = cells.toSet()
        noMoves = cells.isEmpty()
        audio.play(Sfx.HINT)
        _haptics.tryEmit(Haptic.TICK)
        val msg = if (cells.isEmpty()) "No home for this stone" else "Hint"
        fx.banner(msg, if (cells.isEmpty()) Palette.ember else Palette.hint, height = 0.42f, y = ROWS - 0.8f)
        // Two per board: say plainly when that was the last one.
        if (e.state.hintsThisBoard >= GameEngine.MAX_HINTS_PER_BOARD) {
            fx.banner("That was your last hint for this board", Palette.ember, height = 0.36f, y = ROWS - 1.7f, duration = 2.4f)
        }
        state = e.state
        persist()
    }

    /** Hints left on this board (two per board). */
    val hintsLeft: Int get() = (GameEngine.MAX_HINTS_PER_BOARD - (state?.hintsThisBoard ?: 0)).coerceAtLeast(0)

    /** The Hint button's label: how many are left, or why there are none. */
    val hintLabel: String get() = when {
        match?.isGathering == true -> "No Hints Here"
        match != null && !match!!.isCoop -> "No Hints in 1v1"
        hintsLeft == 0 -> "No Hints Left"
        else -> "Hint · $hintsLeft left"
    }

    private fun handle(events: List<GameEvent>, origin: Int) {
        val e = engine ?: return
        val before = state
        track(events, before, e.state)
        for (ev in events) when (ev) {
            is GameEvent.Placed -> {
                fx.placed(ev.index, ev.piece, ev.points)
                audio.play(if (ev.piece is Piece.Cornerstone) Sfx.CORNERSTONE_PLACE else Sfx.place(ev.neighbors))
                _haptics.tryEmit(Haptic.TICK)
            }
            is GameEvent.HammerUsed -> {
                fx.hammer(ev.index, ev.removed)
                audio.play(Sfx.HAMMER_USE)
                _haptics.tryEmit(Haptic.HEAVY)
            }
            is GameEvent.LinesCleared -> {
                val lines = ev.rows.size + ev.cols.size
                // Stoke Duel: each line stokes the rival once; a symbol or perfect line stokes twice.
                // While your own forge is stoked, clears go to cooling it and don't stoke the rival.
                if (!ev.coolsStoke) match?.stoke(if (ev.bonuses.isNotEmpty()) 2 else 1)
                fx.linesCleared(origin, ev.removed, ev.newlyGold, ev.points, lines)
                audio.play(if (lines > 1) Sfx.MULTI_LINE else Sfx.LINE_CLEAR)
                _haptics.tryEmit(Haptic.CONFIRM)
                ev.bonuses.forEachIndexed { k, b ->
                    val sample = ev.removed.entries.firstOrNull { (idx, p) ->
                        p is Piece.Stone && (b.row == idx / COLS || b.col == idx % COLS)
                    }?.value as? Piece.Stone
                    val color = sample?.let { Palette.pieceColor(it.color) } ?: Palette.goldLight
                    fx.lineBonus(b.row, b.col, b.perfect, color, b.points, bannerY = 2.2f + k * 2f)
                    audio.play(if (b.perfect) Sfx.PERFECT_LINE else Sfx.SYMBOL_LINE)
                    _haptics.tryEmit(Haptic.HEAVY)
                }
            }
            GameEvent.ForgeEmptied -> {
                forgeCoolAt = fx.now
                audio.play(Sfx.FORGE_COOL, 0.8f)
            }
            is GameEvent.BoardCleared -> {
                fx.boardComplete()
                audio.play(Sfx.BOARD_CLEAR)
                _haptics.tryEmit(Haptic.HEAVY)
                // A challenge or Manna run ends when its boards are cleared.
                val run = ev.stats.challengeId != null || ev.stats.mannaDay != null
                if (ev.stats.puzzleId != null) Unit // A puzzle judges itself after the move.
                else if (run && ev.stats.boardsCleared >= ev.stats.challengeBoards) {
                    if (ev.stats.mannaDay != null) finishMannaRun(ev.stats) else finishChallengeRun(ev.stats)
                } else overlays = overlays + Overlay.BoardComplete(ev)
            }
            is GameEvent.Discarded -> {
                forgeFlareAt = fx.now
                audio.play(Sfx.DISCARD)
                _haptics.tryEmit(Haptic.HEAVY)
                if (ev.forgeLevel == e.state.forgeCapacity) audio.play(Sfx.FORGE_WARNING, 0.9f)
                when {
                    ev.timedOut -> fx.banner("Time's up!", Palette.ember, height = 0.42f, y = ROWS - 0.8f)
                    ev.penalty > 0 -> fx.banner("Melted  -${ev.penalty}", Palette.invalid, height = 0.42f, y = ROWS - 0.8f)
                }
            }
            is GameEvent.StreakMilestone -> {
                fx.banner("Streak ${ev.streak}!", Palette.goldLight, height = 0.5f, y = 1.2f)
                audio.play(Sfx.STREAK, 0.8f)
            }
            is GameEvent.NewPiece -> {
                pieceArrivedAt = fx.now
                hintShown = false
                hintCells = emptySet()
                noMoves = false
                lastTickSecond = -1
                when (ev.piece) {
                    Piece.Hammer -> audio.play(Sfx.HAMMER_APPEAR, 0.8f)
                    Piece.Cornerstone -> if (e.state.cells.any { it != null }) audio.play(Sfx.CORNERSTONE_APPEAR, 0.8f)
                    else -> Unit
                }
            }
            GameEvent.GameOver -> {
                audio.play(Sfx.GAME_OVER)
                // In a match you're out but the match goes on; the result comes from the server.
                if (e.state.puzzleId != null) Unit // Judged after the move.
                else if (e.state.mannaDay != null) finishMannaRun(e.state)
                else if (e.state.challengeId != null) finishChallengeRun(e.state)
                else if (match == null) overlays = overlays + Overlay.GameOver(e.state, store.qualifies(e.state.score, e.state.difficulty, e.state.mode))
            }
        }
        state = e.state
        match?.report(e.state)
        judgePuzzle()
        persist()
    }

    fun continueAfterBoard() {
        val o = overlay as? Overlay.BoardComplete ?: return
        click()
        val leftovers = o.event.stats.cells.withIndex().filter { it.value != null }.associate { it.index to it.value!! }
        fx.boardReset(leftovers)
        overlays = overlays.dropLast(1)
        pieceArrivedAt = fx.now
    }

    /** Record the score under the player's name (when [inscribe] and it qualifies) and go back to the title. */
    fun finishGameOver(inscribe: Boolean) {
        val o = overlay as? Overlay.GameOver ?: return
        val name = if (inscribe) settings.playerName else null
        if (name != null && o.qualifies) {
            val clean = name
            store.addHighScore(
                HighScore(clean, o.final.score, o.final.rank, o.final.board, o.final.difficulty, o.final.mode, epochMillis()),
            )
            highScores = store.loadHighScores()
            lastBroadcast = -100f
            online.pushScores(store.scoresForUpload(), settings.sharePlus)
        }
        store.saveGame(null)
        savedGame = null
        engine = null
        state = null
        overlays = if (name != null && o.qualifies) listOf(Overlay.HighScores) else emptyList()
        screen = Screen.TITLE
    }

    /** Called every animation frame by the UI. */
    fun onFrame(frameNanos: Long) {
        if (clockStartNanos == 0L) clockStartNanos = frameNanos
        val dtMs = if (lastFrameNanos == 0L) 0L else ((frameNanos - lastFrameNanos) / 1_000_000L).coerceIn(0L, 100L)
        lastFrameNanos = frameNanos
        fx.advance((frameNanos - clockStartNanos) / 1e9f)
        while (true) (tasks.tryReceive().getOrNull() ?: break).invoke()
        pumpLan()
        pumpOnline()
        pumpAchievementNote()
        val e = engine ?: return
        if (!boardInteractive) return
        countPlayTime(dtMs)
        val events = e.tick(dtMs)
        if (events.isNotEmpty()) {
            handle(events, -1)
        } else if (e.state.mode == GameMode.TIME_TRIAL || e.state.elapsedMillis / 1000 != (state?.elapsedMillis ?: 0) / 1000) {
            // Publish at most once a second in Strategic mode; Time Trial needs every frame for its hourglass.
            state = e.state
            if (e.state.mode == GameMode.TIME_TRIAL) {
                val sec = e.state.pieceTimeLeftMillis / 1000
                if (e.state.pieceTimeLeftMillis < 4000 && sec != lastTickSecond) {
                    lastTickSecond = sec
                    audio.play(Sfx.TICK)
                }
            }
        }
    }

    // ---- Local network score sharing ----------------------------------------------------------------------------

    private fun startLan() {
        if (lanActive) return
        lanActive = true
        lastBroadcast = -100f
        lan.start { inbox.trySend(it) }
    }

    private fun stopLan() {
        if (!lanActive) return
        lanActive = false
        lan.stop()
    }

    /** Apply received packets and re-announce our scores every few seconds. Runs on the UI thread. */
    private fun pumpLan() {
        if (!lanActive) return
        val plus = settings.sharePlus
        while (true) {
            val bytes = inbox.tryReceive().getOrNull() ?: break
            val update = LanProtocol.decode(bytes, deviceId) ?: continue
            val now = epochMillis()
            peers = store.mergePeer(update.deviceId, update.scores, now, update.playerId, direct = true, open = update.open)
            val met = peers.count { it.direct }
            if (met > stats.nearbyDevices) bump { it.copy(nearbyDevices = met) }
            // Scores a Local+ neighbour passed on are only taken in Local+ mode, and only ones their owner opened.
            if (plus) for (f in update.forwarded) {
                if (f.deviceId == deviceId || f.scores.isEmpty()) continue
                peers = store.mergePeer(f.deviceId, f.scores, now, f.playerId, direct = false, open = true)
            }
        }
        if (fx.now - lastBroadcast >= BROADCAST_SECONDS) {
            lastBroadcast = fx.now
            // Local: only this device's own scores. Local+: also any open scores we know of.
            lan.send(LanProtocol.encode(deviceId, store.loadHighScores(), online.account?.playerId, plus, if (plus) peers else emptyList()))
        }
    }

    // ---- Online: friends, global board, matches -------------------------------------------------------------------

    val shareMode: ShareMode
        get() = when {
            settings.hideActivity -> ShareMode.HIDDEN
            settings.sharePlus -> ShareMode.PLUS
            else -> ShareMode.LOCAL
        }

    fun setShareMode(mode: ShareMode) {
        click()
        val hide = mode == ShareMode.HIDDEN
        // Hide Activity keeps scores on this device and stops the server recording when you play.
        updateSettings(settings.copy(lanShare = !hide, sharePlus = mode == ShareMode.PLUS, hideActivity = hide))
        online.setHideActivity(hide)
        if (mode == ShareMode.PLUS) {
            bump { it.copy(sharedGlobally = true) }
            online.ensureAccount(settings.playerName) { online.pushScores(store.scoresForUpload(), true); online.sync() }
        } else if (online.account != null) {
            online.pushScores(store.scoresForUpload(), false)
        }
    }

    private fun pumpOnline() {
        offerUpdate()
        match?.let { m ->
            m.tick()
            if (m.phase == MatchPhase.ENDED && overlay != Overlay.MatchOver && (m.isCoop || m.isGathering)) {
                val r = m.result
                bump {
                    if (m.isCoop) it.copy(coopGames = it.coopGames + 1, bestCoopScore = maxOf(it.bestCoopScore, r?.myScore ?: 0))
                    else it.copy(gatheringsPlayed = it.gatheringsPlayed + 1, gatheringWins = it.gatheringWins + if (r?.won == true) 1 else 0)
                }
                audio.play(if (r?.won == true || m.isCoop) Sfx.BOARD_CLEAR else Sfx.GAME_OVER)
                overlays = listOf(Overlay.MatchOver)
            }
            if (m.phase == MatchPhase.ENDED && overlay != Overlay.MatchOver) {
                val r = m.result
                bump {
                    val won = r?.won == true
                    val streak = if (won) it.matchWinStreak + 1 else 0
                    it.copy(
                        matchesPlayed = it.matchesPlayed + 1,
                        matchesWon = it.matchesWon + if (won) 1 else 0,
                        matchWinStreak = streak,
                        bestMatchWinStreak = maxOf(it.bestMatchWinStreak, streak),
                        raceWins = it.raceWins + if (won && m.goal.race) 1 else 0,
                        timedWins = it.timedWins + if (won && m.goal.timed) 1 else 0,
                    )
                }
                audio.play(if (r?.won == true) Sfx.BOARD_CLEAR else Sfx.GAME_OVER)
                overlays = listOf(Overlay.MatchOver)
            }
            if (m.phase == MatchPhase.FAILED && overlay != Overlay.MatchOver) {
                notice = if (engine == null) "Couldn't join the match." else "Lost the connection to the match."
                leaveMatch()
            }
        }
        if (!foreground || online.account == null) return
        val waiting = online.invites.any { !it.incoming && it.status == "pending" } || overlay == Overlay.Friends || overlay is Overlay.Chat
        val interval = if (waiting) 3f else if (screen == Screen.GAME) 15f else 8f
        if (fx.now - lastSync >= interval) {
            lastSync = fx.now
            online.sync()
        }
    }

    fun openHallOfFame() {
        push(Overlay.HighScores)
        online.fetchLeaderboard()
    }

    /** Hand the server this device's notification token once per session (or clear it when switched off). */
    private fun registerPush() {
        if (pushRegistered || !push.supported || online.account == null) return
        pushRegistered = true
        online.setNotifyManna(settings.notifyManna)
        if (!settings.notifications) { online.setPushToken(""); return }
        push.register { token -> post { if (settings.notifications) online.setPushToken(token) } }
    }

    fun setNotifications(on: Boolean) {
        click()
        updateSettings(settings.copy(notifications = on))
        pushRegistered = false
        registerPush()
    }

    fun setHideActivity(hide: Boolean) {
        click()
        updateSettings(settings.copy(hideActivity = hide))
        online.setHideActivity(hide)
    }

    /** A leaderboard player as someone to challenge. */
    fun rivalOf(e: LeaderboardEntry) = Rival(e.playerId, e.name, e.online, isFriend(e.playerId))

    /** Strangers can only be challenged when you're in Global mode too (the server checks it). */
    fun canChallenge(e: LeaderboardEntry): Boolean = e.playerId != online.account?.playerId && (isFriend(e.playerId) || settings.sharePlus)

    fun openFriends() {
        push(Overlay.Friends)
        online.ensureAccount(settings.playerName) { online.sync() }
        lastSync = fx.now
    }

    fun addFriendByCode(code: String) {
        online.ensureAccount(settings.playerName) { online.addFriendByCode(code) { notice = it } }
    }

    fun addFriend(playerId: String) {
        click()
        online.ensureAccount(settings.playerName) { online.addFriend(playerId) { notice = it } }
    }

    fun respondToRequest(id: String, accept: Boolean) {
        click()
        online.respondToRequest(id, accept)
    }

    fun removeFriend(friend: Friend) {
        click()
        online.removeFriend(friend.playerId)
    }

    fun challenge(rival: Rival, difficulty: Difficulty, goal: MatchGoal, mode: GameMode = GameMode.STRATEGIC) {
        click()
        online.invite(rival.playerId, difficulty, goal, mode) { notice = it }
        overlays = overlays.filter { it !is Overlay.Challenge }
        lastSync = -100f
    }

    fun cancelChallenge(id: String) {
        click()
        online.cancelInvite(id)
    }

    fun respondToChallenge(id: String, accept: Boolean) {
        click()
        online.respondToInvite(id, accept, onMatch = { joinMatch(it) }) { notice = it }
    }

    // ---- Gatherings --------------------------------------------------------------------------------------------------

    /** Invite 2-7 friends; the host goes straight to the lobby. */
    fun createGathering(friendIds: List<String>, difficulty: Difficulty, mode: GameMode, minutes: Int) {
        click()
        dropFinishedMatch()
        if (match != null) { notice = "Finish your live match first."; return }
        online.createGathering(friendIds, difficulty, mode, minutes, onError = { notice = it }) { id ->
            overlays = emptyList()
            joinMatch(id, gathering = true)
        }
    }

    fun respondToGathering(id: String, accept: Boolean) {
        click()
        dropFinishedMatch()
        if (accept && match != null) { notice = "Finish your live match first."; return }
        online.respondToGathering(id, accept, onJoin = { joinMatch(it, gathering = true) }) { notice = it }
    }

    /** Host: start the gathering with whoever's in the lobby. */
    fun startGathering() {
        click()
        match?.go()
    }

    // ---- Co-op -------------------------------------------------------------------------------------------------------

    /** A co-op move relayed by the server (ours or our partner's): both games apply the same moves in the same order. */
    private fun applyCoopMove(mv: com.geoguy89.refinersfire.net.CoopMove) {
        val e = engine ?: return
        val mine = mv.by == online.account?.playerId
        when (mv.kind) {
            "play" -> {
                val events = e.play(mv.index)
                if (events.isEmpty()) DebugLog.add("co-op: move ${mv.seq} was illegal here (out of step?)") else handle(events, origin = mv.index)
            }
            "discard" -> {
                if (mv.auto) fx.banner(if (mine) "Your turn ran out: the stone was melted" else "Their turn ran out", Palette.ember, height = 0.36f, y = ROWS - 1.7f, duration = 2.2f)
                handle(e.discard(), origin = -1)
            }
            "miss" -> if (e.registerMiss()) {
                forgeFlareAt = fx.now
                fx.banner("Wrong guesses stoke the forge", Palette.lava, height = 0.42f, y = ROWS - 1.2f, duration = 2.0f)
                if (e.state.gameOver) handle(listOf(GameEvent.GameOver), origin = -1) else { state = e.state; match?.report(e.state) }
            } else state = e.state
            "hint" -> {
                val cells = e.useHint()
                if (mine) {
                    hintShown = true
                    hintCells = cells.toSet()
                    noMoves = cells.isEmpty()
                    audio.play(Sfx.HINT)
                } else fx.banner("Your partner used a hint", Palette.hint, height = 0.36f, y = ROWS - 1.7f, duration = 1.8f)
                state = e.state
                match?.report(e.state)
            }
        }
    }

    fun dismissNotice() { notice = null }

    fun openNoticeChat() {
        val friend = noticeChatFrom?.let { chatFriend(it) }
        notice = null
        if (friend != null) openChat(friend)
    }

    // ---- Async challenges ---------------------------------------------------------------------------------------------

    /** Challenges waiting for our run. */
    val ourMoves: List<AsyncChallenge> get() = online.asyncChallenges.filter { it.ourMove }

    fun startChallenge(friend: Rival, difficulty: Difficulty, boards: Int) {
        click()
        dropFinishedMatch()
        if (match != null) { notice = "Finish your live match first."; return }
        online.createAsync(friend.playerId, difficulty, boards, onError = { notice = it }) { c ->
            beginChallengeRun(c.id, c.seed, c.difficulty, c.boards, friend.name)
        }
    }

    fun playChallenge(c: AsyncChallenge) {
        click()
        dropFinishedMatch()
        if (match != null) { notice = "Finish your live match first."; return }
        // Resuming our own unfinished run keeps its progress.
        val saved = store.loadChallengeGame()
        if (saved?.challengeId == c.id) {
            resumeChallenge()
            return
        }
        // A friend's challenge comes with their run, to race as a ghost.
        beginChallengeRun(c.id, c.seed, c.difficulty, c.boards, c.name, ghost = if (c.incoming) c.ghost else null)
    }

    fun declineChallenge(c: AsyncChallenge) {
        click()
        online.declineAsync(c.id)
    }

    private fun beginChallengeRun(id: String, seed: Long, difficulty: Difficulty, boards: Int, rival: String, ghost: List<Long>? = null) {
        persist() // Keep the single-player game safe.
        val e = GameEngine.newMatch(difficulty, seed)
        adopt(GameEngine(e.state.copy(
            challengeId = id, challengeBoards = boards, challengeRival = rival,
            timeline = emptyList(), ghost = ghost, ghostName = ghost?.let { rival },
        )))
        overlays = emptyList()
        screen = Screen.GAME
        countGameStart()
        persist()
    }

    fun resumeChallenge() {
        val saved = store.loadChallengeGame() ?: return
        click()
        adopt(GameEngine(saved))
        overlays = emptyList()
        screen = Screen.GAME
    }

    /** The run is over (boards cleared or forge overflowed): send the result and show where things stand. */
    private fun finishChallengeRun(s: GameState) {
        val id = s.challengeId ?: return
        // Mark the run over, so the save at the end of this move clears its slot instead of writing it back.
        engine = GameEngine(s.copy(gameOver = true))
        val result = AsyncSubmitBody(id, s.score, s.boardsCleared.coerceAtMost(s.challengeBoards), s.elapsedMillis, s.timeline)
        store.saveChallengeGame(null)
        savedChallenge = null
        store.savePendingResults(store.loadPendingResults().filter { it.id != id } + result)
        val c = online.asyncChallenges.firstOrNull { it.id == id }
        val rival = s.challengeRival ?: c?.name ?: "your friend"
        val target = c?.theirScore
        val won = if (c?.incoming == true && target != null) s.score > target else null
        bump { it.copy(asyncPlayed = it.asyncPlayed + 1, asyncWon = it.asyncWon + if (won == true) 1 else 0) }
        overlays = listOf(Overlay.AsyncDone(rival, c?.playerId, s.score, target, sent = c?.incoming != true, won = won))
        retryPendingResults()
    }

    private fun retryPendingManna() {
        for (r in store.loadPendingManna()) online.submitManna(r) { delivered ->
            if (delivered) {
                store.savePendingManna(store.loadPendingManna().filter { it.day != r.day })
                if (r.day == today) online.fetchManna(r.day)
            }
        }
    }

    // ---- Puzzles -----------------------------------------------------------------------------------------------------

    /** Puzzles open as you go: the first three, then one more for each solved. */
    fun puzzleUnlocked(id: Int): Boolean = id <= puzzleStars.size + 3

    val totalPuzzleStars: Int get() = puzzleStars.values.sum()

    fun openPuzzles() {
        click()
        overlays = overlays.filter { it != Overlay.Puzzles } + Overlay.Puzzles
    }

    fun startPuzzle(id: Int) {
        if (id !in 1..Puzzles.COUNT || !puzzleUnlocked(id)) return
        click()
        dropFinishedMatch()
        if (match != null) { notice = "Finish your live match first."; return }
        persist() // Keep the single-player game safe.
        adopt(Puzzles.get(id).start())
        overlays = emptyList()
        screen = Screen.GAME
    }

    fun retryPuzzle() {
        val id = state?.puzzleId ?: (overlay as? Overlay.PuzzleDone)?.id ?: return
        startPuzzle(id)
    }

    fun nextPuzzle() {
        val id = (overlay as? Overlay.PuzzleDone)?.id ?: state?.puzzleId ?: return
        if (id < Puzzles.COUNT && puzzleUnlocked(id + 1)) startPuzzle(id + 1) else leavePuzzle()
    }

    /** Back to the puzzle book. */
    fun leavePuzzle() {
        click()
        engine = null
        state = null
        screen = Screen.TITLE
        savedGame = store.loadGame()
        overlays = listOf(Overlay.Puzzles)
    }

    /** After each move: solved once the lines are cleared; out of luck when the stones or the forge run out. */
    private fun judgePuzzle() {
        val s = engine?.state ?: return
        val id = s.puzzleId ?: return
        if (overlay is Overlay.PuzzleDone) return
        when {
            s.puzzleLines >= s.puzzleTarget -> {
                val stars = Puzzles.stars(s.discards, s.hintsUsed)
                val best = maxOf(puzzleStars[id] ?: 0, stars)
                val first = id !in puzzleStars
                puzzleStars = puzzleStars + (id to best)
                store.savePuzzleStars(puzzleStars)
                bump { it.copy(puzzlesSolved = puzzleStars.size, puzzleStars = puzzleStars.values.sum()) }
                audio.play(Sfx.BOARD_CLEAR)
                overlays = listOf(Overlay.PuzzleDone(id, true, stars, if (first) "Solved!" else "Solved again!"))
            }
            s.gameOver -> overlays = listOf(Overlay.PuzzleDone(id, false, 0, "The forge overflowed."))
            s.puzzleExhausted -> overlays = listOf(Overlay.PuzzleDone(id, false, 0, "Out of stones: ${s.puzzleLines} of ${s.puzzleTarget} lines."))
        }
    }

    // ---- Manna -------------------------------------------------------------------------------------------------------

    /** Today's local date, as a day number. */
    val today: Long get() = localDay(epochMillis())

    /** Today's Manna, if this device has gathered it. */
    val todaysManna: MannaResult? get() = mannaHistory.firstOrNull { it.day == today }

    /** Days in a row gathered, up to today. */
    val mannaStreak: Int get() = Manna.streak(mannaHistory.map { it.day }.toSet(), today)

    fun openManna() {
        click()
        overlays = overlays + Overlay.Manna
        online.ensureAccount(settings.playerName) { online.fetchManna(today); retryPendingManna() }
    }

    /** Begin (or carry on with) today's Manna. One run a day: once it's gathered, there's no second try. */
    fun playManna() {
        click()
        dropFinishedMatch()
        if (match != null) { notice = "Finish your live match first."; return }
        if (todaysManna != null) { notice = "You've gathered today's Manna. Come back tomorrow for more."; return }
        val saved = store.loadMannaGame()
        persist() // Keep the single-player game safe.
        if (saved != null && saved.mannaDay == today) {
            adopt(GameEngine(saved))
        } else {
            // A friend who has already played today lends their run as a ghost to race.
            val board = online.manna?.takeIf { it.day == today }
            adopt(Manna.newRun(today, board?.ghost?.timeline, board?.ghost?.name))
            countGameStart()
        }
        overlays = emptyList()
        screen = Screen.GAME
        persist()
    }

    /** The run is over (three boards or the forge overflowed): keep it, send it, and show the day's standings. */
    private fun finishMannaRun(s: GameState) {
        val day = s.mannaDay ?: return
        // Mark the run over, so the save at the end of this move clears its slot instead of writing it back.
        engine = GameEngine(s.copy(gameOver = true))
        val result = MannaResult(day, s.score, s.boardsCleared.coerceAtMost(s.challengeBoards))
        store.addMannaResult(result)
        mannaHistory = store.loadMannaHistory()
        store.saveMannaGame(null)
        savedManna = null
        val streak = Manna.streak(mannaHistory.map { it.day }.toSet(), today)
        bump { it.copy(mannaDays = mannaHistory.size, bestMannaStreak = maxOf(it.bestMannaStreak, streak)) }
        store.savePendingManna(store.loadPendingManna().filter { it.day != day } + MannaSubmitBody(day, s.score, result.boards, s.elapsedMillis, s.timeline))
        overlays = listOf(Overlay.MannaDone(result, streak))
        online.ensureAccount(settings.playerName) { retryPendingManna() }
    }

    fun setNotifyManna(on: Boolean) {
        click()
        updateSettings(settings.copy(notifyManna = on))
        online.setNotifyManna(on)
    }

    private fun retryPendingResults() {
        for (r in store.loadPendingResults()) online.submitAsync(r) { delivered ->
            if (delivered) store.savePendingResults(store.loadPendingResults().filter { it.id != r.id })
        }
    }

    /** Leave the finished run for the title screen. */
    fun closeChallengeResult() {
        click()
        engine = null
        state = null
        overlays = emptyList()
        screen = Screen.TITLE
        savedGame = store.loadGame()
        lastSync = -100f
    }

    /** One quiet mention per new challenge and per result, not a popup each sync. */
    private fun announceChallenges(list: List<AsyncChallenge>) {
        val fresh = mutableListOf<String>()
        for (c in list) {
            val key = "${c.id}:${c.status}"
            if (key in announced) continue
            when {
                c.ourMove -> fresh += "${c.name} challenged you: beat ${c.theirScore ?: 0} (${c.difficulty.displayName}, ${c.boards} ${if (c.boards == 1) "board" else "boards"})"
                !c.incoming && c.status == "done" -> fresh += when (c.winner) {
                    null -> "${c.name} tied your challenge at ${c.myScore}"
                    // Equal scores are decided on boards cleared, then time.
                    c.playerId -> if (c.myScore == c.theirScore) "${c.name} edged you out on time (${c.theirScore} vs ${c.myScore})" else "${c.name} beat your ${c.myScore} with ${c.theirScore}"
                    else -> if (c.myScore == c.theirScore) "You edged out ${c.name} on time (${c.myScore} vs ${c.theirScore})" else "You beat ${c.name}: ${c.myScore} to ${c.theirScore}"
                }
                !c.incoming && c.status == "declined" -> fresh += "${c.name} declined your challenge"
                else -> {}
            }
            announced = announced + key
        }
        store.saveAnnounced(announced)
        if (fresh.isNotEmpty()) notice = if (fresh.size == 1) fresh[0] else "${fresh.size} challenge updates. See Friends & 1v1."
        if (fresh.isNotEmpty() && !windowFocused) systemNotices.tryEmit("Refiner's Fire" to (notice ?: fresh[0]))
    }

    // ---- Achievements -----------------------------------------------------------------------------------------------

    /** Update the lifetime counters and record anything that unlocks. Unlocks are announced later, never mid-play. */
    private fun bump(change: (LifetimeStats) -> LifetimeStats) {
        val next = change(stats)
        if (next == stats) return
        stats = next
        store.saveStats(next)
        val fresh = Achievements.newlyUnlocked(next, unlocked.keys)
        if (fresh.isEmpty()) return
        val now = epochMillis()
        unlocked = unlocked + fresh.associate { it.id to now }
        store.saveAchievements(unlocked)
        unseenAchievements = unseenAchievements + fresh.map { it.id }
        store.saveUnseenAchievements(unseenAchievements)
        pendingAnnounce = pendingAnnounce + fresh
    }

    private fun countGameStart() = bump {
        it.copy(gamesStarted = it.gamesStarted + 1, themesPlayed = it.themesPlayed + settings.theme.name)
    }

    private fun markBoardStart(s: GameState) {
        boardStartMs = s.elapsedMillis
        boardStartDiscards = s.discards
        boardStartScore = s.score
    }

    private fun countPlayTime(dtMs: Long) {
        unsavedPlayMs += dtMs
        if (unsavedPlayMs >= 15_000) {
            val add = unsavedPlayMs
            unsavedPlayMs = 0
            bump { it.copy(playMillis = it.playMillis + add) }
        }
    }

    private fun track(events: List<GameEvent>, before: GameState?, after: GameState) {
        val mult = after.multiplier.coerceAtLeast(1)
        bump { st ->
            var s = st
            for (ev in events) s = when (ev) {
                is GameEvent.Placed -> s.copy(
                    stonesPlaced = s.stonesPlaced + 1,
                    cornerstonesPlaced = s.cornerstonesPlaced + if (ev.piece is Piece.Cornerstone) 1 else 0,
                    fiftyPointPlacements = s.fiftyPointPlacements + if (ev.points / mult == 50L) 1 else 0,
                )
                is GameEvent.HammerUsed -> s.copy(hammersUsed = s.hammersUsed + 1)
                is GameEvent.LinesCleared -> {
                    val perfect = ev.bonuses.count { it.perfect }
                    s.copy(
                        linesCleared = s.linesCleared + ev.rows.size + ev.cols.size,
                        doubleClears = s.doubleClears + if (ev.rows.isNotEmpty() && ev.cols.isNotEmpty()) 1 else 0,
                        symbolLines = s.symbolLines + ev.bonuses.count { !it.perfect },
                        perfectLines = s.perfectLines + perfect,
                        perfectLineInMatch = s.perfectLineInMatch || (perfect > 0 && after.matchSeed != null),
                        forgeSaves = s.forgeSaves + if (before != null && before.forge == before.forgeCapacity) 1 else 0,
                    )
                }
                is GameEvent.BoardCleared -> {
                    val g = ev.stats
                    val boardMs = g.elapsedMillis - boardStartMs
                    val points = g.score - boardStartScore
                    val clean = g.discards == boardStartDiscards
                    markBoardStart(g)
                    val d = g.difficulty.name
                    s.copy(
                        boardsCleared = s.boardsCleared + 1,
                        cleanBoards = s.cleanBoards + if (clean) 1 else 0,
                        timeTrialBoards = s.timeTrialBoards + if (g.mode == GameMode.TIME_TRIAL) 1 else 0,
                        ironBoards = s.ironBoards + if (g.mode == GameMode.IRON_FORGE) 1 else 0,
                        foresightBoards = s.foresightBoards + if (g.mode == GameMode.FORESIGHT) 1 else 0,
                        difficultiesCleared = s.difficultiesCleared + d,
                        bestBoardsInGame = s.bestBoardsInGame + (d to maxOf(s.bestBoardsInGame[d] ?: 0, g.boardsCleared)),
                        fastestBoardMs = if (boardMs > 0 && (s.fastestBoardMs == 0L || boardMs < s.fastestBoardMs)) boardMs else s.fastestBoardMs,
                        bestBoardPoints = maxOf(s.bestBoardPoints, points),
                        noHintGameBoards = if (g.hintsUsed == 0) maxOf(s.noHintGameBoards, g.boardsCleared) else s.noHintGameBoards,
                    )
                }
                is GameEvent.Discarded -> s.copy(discards = s.discards + 1)
                GameEvent.GameOver -> s.copy(
                    gamesFinished = s.gamesFinished + 1,
                    zeroScoreGameOver = s.zeroScoreGameOver || after.score == 0L,
                )
                else -> s
            }
            s.copy(
                bestScore = maxOf(s.bestScore, after.score),
                bestStreak = maxOf(s.bestStreak, after.bestStreak),
                highestBoard = maxOf(s.highestBoard, after.board),
            )
        }
    }

    /** Only at a pause (title, board complete, game or match over) and only one line, however many unlocked. */
    private fun pumpAchievementNote() {
        if (achievementNote != null && fx.now > achievementNoteUntil) achievementNote = null
        if (pendingAnnounce.isEmpty() || achievementNote != null) return
        val quiet = screen == Screen.TITLE || overlay is Overlay.BoardComplete || overlay is Overlay.GameOver || overlay == Overlay.MatchOver
        if (!quiet) return
        val list = pendingAnnounce
        pendingAnnounce = emptyList()
        achievementNote = if (list.size == 1) "Achievement unlocked: ${list[0].name}" else "${list.size} achievements unlocked"
        achievementNoteUntil = fx.now + 4.5f
        audio.play(Sfx.STREAK, 0.5f)
    }

    fun openAchievements() {
        achievementNote = null
        push(Overlay.Achievements)
    }

    /** Called when the Achievements panel closes: everything shown is now seen. */
    fun markAchievementsSeen() {
        unseenAchievements = emptySet()
        store.saveUnseenAchievements(emptySet())
    }

    // ---- Encrypted chat ---------------------------------------------------------------------------------------------

    private fun chatKey() = store.loadChatKey() ?: crypto.newKeyPair().also(store::saveChatKey)

    /** Make sure the server has our current public key (the private key never leaves the device). */
    private fun publishChatKey(onServer: String?) {
        if (keyPublished || crypto === NoChatCrypto) return
        val key = chatKey()
        keyPublished = true
        if (onServer != key.publicKey) online.publishKey(key.publicKey)
    }

    private fun receiveChat(messages: List<com.geoguy89.refinersfire.net.InboundMessage>) {
        val me = online.account ?: return
        if (messages.isEmpty()) return
        val key = chatKey()
        val open = (overlay as? Overlay.Chat)?.friendId
        val counts = unread.toMutableMap()
        for ((from, batch) in messages.groupBy { it.from }) {
            val friend = online.friends.firstOrNull { it.playerId == from } ?: continue
            val theirKey = friend.publicKey ?: continue
            val lines = batch.map { m ->
                val text = crypto.open(key.privateKey, theirKey, from, me.playerId, Sealed(m.nonce, m.ct))
                ChatLine(fromMe = false, text = text ?: "(This message couldn't be decrypted.)", t = m.t, failed = text == null)
            }
            val all = store.loadChat(from) + lines
            store.saveChat(from, all)
            if (from == open) chatLines = all.takeLast(Store.MAX_CHAT_LINES)
            else counts[from] = (counts[from] ?: 0) + lines.size
        }
        unread = counts
        store.saveUnread(counts)
        if (open == null) audio.play(Sfx.HINT, 0.6f)
        val unseen = messages.map { it.from }.distinct().filter { it != open }
            .mapNotNull { id -> online.friends.firstOrNull { it.playerId == id } }
        if (unseen.isNotEmpty()) {
            notice = if (unseen.size == 1) "New message from ${unseen[0].name}" else "New messages from ${unseen.joinToString { it.name }}"
            noticeChat = unseen.singleOrNull()?.let { notice!! to it.playerId }
        }
        if (!windowFocused) for (from in messages.map { it.from }.distinct()) {
            online.friends.firstOrNull { it.playerId == from }?.let { systemNotices.tryEmit("Refiner's Fire" to "New message from ${it.name}") }
        }
    }

    fun openChat(friend: Friend) {
        click()
        chatLines = store.loadChat(friend.playerId)
        unread = unread - friend.playerId
        store.saveUnread(unread)
        overlays = overlays + Overlay.Chat(friend.playerId)
        lastSync = -100f
    }

    var bugSending by mutableStateOf(false)
        private set

    fun reportBug(text: String) {
        click()
        bugSending = true
        val s = settings
        val info = "${com.geoguy89.refinersfire.AppVersion.label} · ${screen.name.lowercase()} · theme ${s.theme.name.lowercase()} · share ${shareMode.label}" +
            (state?.let { " · board ${it.board}, forge ${it.forge}, ${it.difficulty.name.lowercase()}" } ?: "") + (if (match != null) " · in 1v1" else "")
        DebugLog.add("bug report: $info")
        online.ensureAccount(s.playerName) {
            online.reportBug(text, info, DebugLog.dump(),
                onError = { bugSending = false; notice = it },
                onOk = { bugSending = false; pop(); notice = "Thanks! Your report was sent." })
        }
    }

    fun poke(friend: Friend) {
        click()
        online.poke(friend.playerId, onError = { notice = it }, onOk = { notice = "Poked ${friend.name}. They'll get a notification." })
    }

    fun chatFriend(id: String): Friend? = online.friends.firstOrNull { it.playerId == id }

    /** The number both friends can compare to be sure the chat is private. */
    fun safetyCode(friend: Friend): String? {
        val theirs = friend.publicKey ?: return null
        return crypto.safetyCode(chatKey().publicKey, theirs)
    }

    fun sendChat(friend: Friend, text: String) {
        val me = online.account ?: return
        val clean = text.trim().take(CHAT_MAX_CHARS)
        if (clean.isEmpty()) return
        val theirKey = friend.publicKey
        if (theirKey == null) {
            notice = "${friend.name} needs to open the new version of the game once before you can chat."
            return
        }
        val sealed = crypto.seal(chatKey().privateKey, theirKey, me.playerId, friend.playerId, clean)
        val line = ChatLine(fromMe = true, text = clean, t = epochMillis())
        bump { it.copy(chatsSent = it.chatsSent + 1) }
        val all = store.loadChat(friend.playerId) + line
        store.saveChat(friend.playerId, all)
        chatLines = all.takeLast(Store.MAX_CHAT_LINES)
        online.sendChat(friend.playerId, sealed) { err ->
            val marked = store.loadChat(friend.playerId).map { if (it == line) it.copy(failed = true) else it }
            store.saveChat(friend.playerId, marked)
            if ((overlay as? Overlay.Chat)?.friendId == friend.playerId) chatLines = marked
            notice = err
        }
    }

    val totalUnread: Int get() = unread.values.sum()

    private fun joinMatch(matchId: String, gathering: Boolean = false) {
        val me = online.account ?: return
        if (match != null || !joinedMatches.add(matchId)) return
        persist() // Keep any single-player game safe; it resumes from the title afterwards.
        match = MatchSession(
            matchId, me.playerId, online, post, { fx.now },
            initialGoal = if (gathering) MatchGoal("gathering", 5) else MatchGoal(),
            onCoopMove = ::applyCoopMove,
            onStart = { seed, difficulty, mode, _ ->
                adopt(GameEngine.newMatch(difficulty, seed, mode))
                overlays = emptyList()
                screen = Screen.GAME
                countGameStart()
            },
            onOpponentCleared = { board, score ->
                val who = match?.opponent?.name ?: "Your opponent"
                fx.banner("$who cleared Board $board ($score)", Palette.ember, height = 0.4f, y = 0.9f, duration = 2.6f)
                audio.play(Sfx.FORGE_WARNING, 0.7f)
            },
            onStoked = { levels ->
                val e = engine
                val who = match?.opponent?.name ?: "Your rival"
                val added = e?.stoke(levels) ?: 0
                if (e != null) state = e.state
                forgeFlareAt = fx.now
                audio.play(Sfx.HAMMER_APPEAR, 0.8f)
                _haptics.tryEmit(Haptic.HEAVY)
                if (e?.state?.gameOver == true) {
                    fx.banner("$who stoked your forge past the top!", Palette.lava, height = 0.42f, y = ROWS - 1.2f, duration = 2.2f)
                    handle(listOf(GameEvent.GameOver), origin = -1)
                } else {
                    fx.banner("$who stoked your forge! +$added", Palette.lava, height = 0.42f, y = ROWS - 1.2f, duration = 2.2f)
                }
                match?.report(e?.state ?: return@MatchSession)
            },
        )
        overlays = listOf(Overlay.MatchLobby)
    }

    /** Leave (or close) the match and return to the title. Leaving a live match forfeits it. */
    fun leaveMatch() {
        val m = match ?: return
        click()
        m.leave()
        match = null
        engine = null
        state = null
        overlays = emptyList()
        screen = Screen.TITLE
        savedGame = store.loadGame()
        lastSync = -100f
    }

    fun rematch() {
        val m = match ?: return
        val opponent = m.opponent ?: return
        val difficulty = m.difficulty
        val goal = m.goal
        val mode = m.mode
        leaveMatch()
        challenge(Rival(opponent.playerId, opponent.name), difficulty, goal, mode)
    }

    /**
     * The Hall of Fame list for a tab. [difficulty] and [mode] (null = all) narrow it to one table; [week] keeps only
     * this week's scores (since Monday 00:00 UTC).
     */
    fun hallRows(tab: HallTab, difficulty: Difficulty? = null, mode: GameMode? = null, week: Boolean = false): List<HallRow> =
        allHallRows(tab, difficulty, mode, week).take(if (tab == HallTab.GLOBAL) 100 else Store.MAX_SCORES)

    /** Where the player stands in that list: from the server for Global, counted here for the rest. */
    fun hallStanding(tab: HallTab, difficulty: Difficulty? = null, mode: GameMode? = null, week: Boolean = false): LeaderboardStanding? {
        if (tab == HallTab.GLOBAL) return online.boards[BoardKey(difficulty, mode, week)]?.me
        val all = allHallRows(tab, difficulty, mode, week)
        val i = all.indexOfFirst { it.isMe }
        return if (i < 0) null else LeaderboardStanding(i + 1, all.size, all[i].score.score)
    }

    /** The player's best score in each table (difficulty and mode). */
    fun bestsByTable(): Map<Pair<Difficulty, GameMode>, HighScore> =
        highScores.groupBy { it.difficulty to it.mode }.mapValues { (_, l) -> l.maxBy { it.score } }

    private fun allHallRows(tab: HallTab, difficulty: Difficulty?, mode: GameMode?, week: Boolean): List<HallRow> {
        val weekStart = Store.weekStart(epochMillis())
        fun fits(h: HighScore) = (difficulty == null || h.difficulty == difficulty) && (mode == null || h.mode == mode) &&
            (!week || h.epochMillis >= weekStart)
        val myId = online.account?.playerId
        val myScores = if (week) (highScores + store.loadWeekScores()).distinct() else highScores
        val mine = myScores.map { HallRow(it, null, null, isMe = true) }
        val rows = when (tab) {
            HallTab.MINE -> mine
            HallTab.NEARBY -> mine + peers.filter { it.direct }.flatMap { p -> p.scores.map { HallRow(it, "nearby", p.playerId, false) } }
            HallTab.FRIENDS -> mine + online.friends.flatMap { f -> f.scores.map { HallRow(it.toHighScore(), "friend", f.playerId, false) } }
            HallTab.GLOBAL -> {
                fun notMe(id: String?) = id == null || id != myId
                // The ranked board: one line per player (their best).
                // The server sends the slice already narrowed (and each player's best within it).
                val board = online.boards[BoardKey(difficulty, mode, week)]?.players
                    ?: if (difficulty == null && mode == null && !week) online.leaderboard else emptyList()
                val server = board.filter { notMe(it.playerId) }.map { HallRow(it.toHighScore(), if (it.online) "online" else null, it.playerId, false) }
                val relayed = peers.filter { it.open && notMe(it.playerId) }.flatMap { p -> p.scores.map { HallRow(it, if (p.direct) "nearby" else null, p.playerId, false) } }
                val own = if (settings.sharePlus) mine else emptyList()
                own + server + relayed
            }
        }
        val unique = rows.filter { fits(it.score) }.distinctBy { Triple(it.score.name, it.score.score, it.score.epochMillis) }
        // Global ranks players, so each appears once with their best (in the chosen table).
        val perPlayer = if (tab == HallTab.GLOBAL) unique.sortedByDescending { it.score.score }.distinctBy { it.playerId ?: (it.score.name + if (it.isMe) "#me" else "") } else unique
        return perPlayer.sortedByDescending { it.score.score }
    }

    /** Foresight: the next stones, shown beside the current one. Empty in other modes. */
    val upcoming: List<Piece>
        get() {
            val s = state ?: return emptyList()
            // Foresight shows the next three; a puzzle shows what's left of its fixed run.
            if (s.mode != GameMode.FORESIGHT && s.puzzleId == null) return emptyList()
            return engine?.upcoming() ?: emptyList()
        }

    fun isFriend(playerId: String?): Boolean = playerId != null && online.friends.any { it.playerId == playerId }

    fun canBefriend(row: HallRow): Boolean {
        val id = row.playerId ?: return false
        return !row.isMe && id != online.account?.playerId && online.friends.none { it.playerId == id } &&
            online.outgoing.none { it.playerId == id }
    }

    // ---- Settings & lifecycle -----------------------------------------------------------------------------------

    fun updateSettings(s: Settings) {
        if (s.lanShare != settings.lanShare) if (s.lanShare) startLan() else stopLan()
        if (s.theme != settings.theme) {
            Palette.theme = s.theme
            audio.setTheme(s.theme)
        }
        Palette.pieceSet = s.pieceSet
        settings = s
        audio.sfxVolume = s.sfxVolume
        audio.musicVolume = s.musicVolume
        store.saveSettings(s)
    }

    fun setTheme(theme: ThemeId) = updateSettings(settings.copy(theme = theme))

    /** Days of Manna that open the Starlight theme. */
    val starlightDays: Int get() = STARLIGHT_DAYS

    /** Starlight is earned by gathering the daily Manna on seven days; everything else is open. */
    fun themeUnlocked(theme: ThemeId): Boolean = theme != ThemeId.STARLIGHT || maxOf(stats.mannaDays, mannaHistory.size) >= STARLIGHT_DAYS

    fun chooseTheme(theme: ThemeId) {
        click()
        if (!themeUnlocked(theme)) {
            val have = maxOf(stats.mannaDays, mannaHistory.size)
            notice = "Gather the daily Manna on $STARLIGHT_DAYS days to unlock Starlight ($have of $STARLIGHT_DAYS so far)."
            return
        }
        setTheme(theme)
    }

    // ---- Updates ------------------------------------------------------------------------------------------------------

    fun checkForUpdate(manual: Boolean) {
        if (!updater.supported) return
        updateChecked = true
        updater.latest { info ->
            post {
                val newer = info != null && info.build > updater.installedBuild
                if (newer && (manual || info!!.build != settings.skippedUpdateBuild)) {
                    update = info
                    updateOffered = false
                    if (manual) {
                        if (screen == Screen.TITLE) {
                            // Asked for from Options on the title screen: show it in place of Options right away.
                            updateOffered = true
                            overlays = overlays.filter { it != Overlay.Options } + Overlay.Update
                        } else {
                            // Never over an active game; it's offered when back on the title screen.
                            notice = "Version ${info!!.version} is available. It'll be offered on the title screen."
                        }
                    }
                } else if (manual) {
                    notice = if (info == null) "Couldn't reach GitHub to check for updates." else "You have the latest version."
                }
            }
        }
    }

    /** Offer the update at a quiet moment: on the title screen with nothing else open. */
    private fun offerUpdate() {
        if (update == null || updateOffered || needsName || screen != Screen.TITLE || overlay != null) return
        updateOffered = true
        overlays = listOf(Overlay.Update)
    }

    fun installUpdate() {
        val info = update ?: return
        click()
        // The installer closes the game when it swaps in the new version, so save everything first.
        persist()
        updateProgress = 0f
        updater.install(info, progress = { p -> post { updateProgress = p } }) { error ->
            post {
                updateProgress = null
                if (error != null) notice = "Update failed: $error" else overlays = overlays.filter { it != Overlay.Update }
            }
        }
    }

    fun skipUpdate() {
        val info = update ?: return
        click()
        updateSettings(settings.copy(skippedUpdateBuild = info.build))
        update = null
        overlays = overlays.filter { it != Overlay.Update }
    }

    // ---- Player name --------------------------------------------------------------------------------------------------

    /** First launch: nothing else happens until the player has picked a name. */
    val needsName: Boolean get() = !settings.nameChosen

    /** Sets the one name used everywhere: Hall of Fame, friends, matches, chat and the global board. */
    fun choosePlayerName(raw: String): Boolean {
        val name = cleanName(raw) ?: return false
        click()
        updateSettings(settings.copy(playerName = name, nameChosen = true))
        online.setName(name)
        com.geoguy89.refinersfire.keepSavedDataSafe()
        if (overlay == Overlay.ChangeName) overlays = overlays.dropLast(1)
        return true
    }

    fun click() = audio.play(Sfx.CLICK, 0.7f)

    fun onAppForeground() {
        audio.startMusic()
        foreground = true
        if (settings.checkUpdates && !updateChecked) checkForUpdate(manual = false)
        lastSync = -100f
        if (settings.lanShare) startLan()
    }

    fun onAppBackground() {
        audio.pauseMusic()
        foreground = false
        stopLan()
        persist()
        // Closing or leaving the game saves it and goes back to the title, where Continue picks it up.
        // A live match isn't left this way: that would forfeit it.
        if (screen == Screen.GAME && match == null) toTitle()
        lastFrameNanos = 0L
    }

    private fun persist() {
        val s = engine?.state ?: return
        if (s.puzzleId != null) return // Puzzles are short: never saved over the game in progress.
        if (s.mannaDay != null) {
            store.saveMannaGame(if (s.gameOver) null else s)
            savedManna = store.loadMannaGame()
            return
        }
        if (s.challengeId != null) {
            store.saveChallengeGame(if (s.gameOver) null else s)
            savedChallenge = store.loadChallengeGame()
            return
        }
        if (s.matchSeed != null) return // Matches are never saved over the single-player game.
        store.saveGame(s)
        savedGame = if (s.gameOver) null else s
    }

    fun forgetNearbyScores() {
        click()
        store.clearPeers()
        peers = emptyList()
    }

    fun dispose() {
        match?.leave()
        stopLan()
        persist()
        audio.release()
    }

    companion object {
        private const val BROADCAST_SECONDS = 5f
        const val NAME_MAX = 18

        /** Trimmed, control characters removed, at most [NAME_MAX]; null when nothing is left. */
        fun cleanName(raw: String): String? =
            raw.filter { !it.isISOControl() }.trim().take(NAME_MAX).trim().ifEmpty { null }
    }
}
