package io.zenandroid.onlinego.ui.screens.game

import androidx.compose.runtime.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.AndroidUiDispatcher
import app.cash.molecule.launchMolecule
import io.zenandroid.onlinego.data.model.Cell
import io.zenandroid.onlinego.data.model.Position
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.data.model.local.Message
import io.zenandroid.onlinego.data.model.local.Player
import io.zenandroid.onlinego.data.model.ogs.Phase
import io.zenandroid.onlinego.data.ogs.GameConnection
import io.zenandroid.onlinego.data.ogs.OGSWebSocketService
import io.zenandroid.onlinego.data.repositories.ActiveGamesRepository
import io.zenandroid.onlinego.data.repositories.ChatRepository
import io.zenandroid.onlinego.data.repositories.ClockDriftRepository
import io.zenandroid.onlinego.data.repositories.UserSessionRepository
import io.zenandroid.onlinego.gamelogic.RulesManager
import io.zenandroid.onlinego.ui.screens.game.Button.*
import io.zenandroid.onlinego.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val MAX_ATTEMPTS = 3
private const val DELAY_BETWEEN_ATTEMPTS = 5000L

class GameViewModel(
    private val activeGamesRepository: ActiveGamesRepository,
    userSessionRepository: UserSessionRepository,
    private val clockDriftRepository: ClockDriftRepository,
    private val socketService: OGSWebSocketService,
    private val chatRepository: ChatRepository,
): ViewModel() {

    // Need to add a MonotonicFrameClock
    // See: https://github.com/cashapp/molecule/#frame-clock
    private val moleculeScope = CoroutineScope(viewModelScope.coroutineContext + AndroidUiDispatcher.Main)

    private var loading by mutableStateOf(true)
    private lateinit var position: MutableState<Position>
    private val userId = userSessionRepository.userId
    private var candidateMove by mutableStateOf<Cell?>(null)
    private lateinit var gameConnection: GameConnection
    private var gameState by mutableStateOf<Game?>(null)
    private var timer by mutableStateOf(TimerDetails("", "", "", "", 0, 0, false, false))
    private var pendingMove by mutableStateOf<PendingMove?>(null)
    private var retrySendMoveDialogShowing by mutableStateOf(false)
    private var analyzeMode by mutableStateOf(false)
    private var analysisShownMoveNumber by mutableStateOf(0)
    private var passDialogShowing by mutableStateOf(false)
    private var resignDialogShowing by mutableStateOf(false)
    private var gameFinished by mutableStateOf<Boolean?>(null)
    private var gameOverDialog by mutableStateOf<GameOverDialogDetails?>(null)
    private var lastMoveList by mutableStateOf<List<Cell>?>(null)
    private var chatDialogShowing by mutableStateOf(false)

    private var timerJob: Job? = null

    lateinit var state: StateFlow<GameState>
    var pendingNavigation by mutableStateOf<PendingNavigation?>(null)
        private set
    private val _events = MutableSharedFlow<Event?>()
    val events = _events.asSharedFlow()

    fun initialize(gameId: Long, gameWidth: Int, gameHeight: Int) {
        val gameFlow = activeGamesRepository.monitorGameFlow(gameId).distinctUntilChanged()
        position = mutableStateOf(Position(gameWidth, gameHeight))
        gameConnection = socketService.connectToGame(gameId, true)

        val messagesFlow = chatRepository.monitorGameChat(gameId)
            .map { messages ->
                messages.map {
                    ChatMessage(
                        fromUser = it.playerId == userId,
                        message = it,
                    )
                }.filter { it.fromUser || it.message.type == Message.Type.MAIN || gameFinished == true }
                    .groupBy { it.message.moveNumber ?: 0 }
            }

        viewModelScope.launch {
            gameFlow.collect { game ->
                withContext(Dispatchers.IO) {
                    position.value = RulesManager.replay(game = game, computeTerritory = game.phase == Phase.STONE_REMOVAL)
                    if(loading) {
                        analysisShownMoveNumber = game.moves?.size ?: 0
                    }
                    gameState = game
                    checkPendingMove(game)
                    timerJob?.cancel()
                    timerJob = viewModelScope.launch {
                        timerRefresher()
                    }
                    if (game.phase == Phase.FINISHED && gameFinished == false && game.blackLost != game.whiteLost) { // Game just finished
                        gameOverDialog = calculateGameOverDetails(game)
                        analysisShownMoveNumber = gameState?.moves?.size ?: 0
                    }
                    gameFinished = game.phase == Phase.FINISHED
                    if(lastMoveList != null && lastMoveList != game.moves) {
                        _events.emit(Event.PlayStoneSound)
                    }
                    lastMoveList = game.moves
                    loading = false
                }
            }
        }

        state = moleculeScope.launchMolecule {
            val messages by messagesFlow.collectAsState(emptyMap())
            val analysisPosition = remember(analysisShownMoveNumber, gameState != null) {
                gameState?.let {
                    RulesManager.replay(it, analysisShownMoveNumber, false)
                }
            }

            val isMyTurn =
                gameState?.phase == Phase.PLAY && (position.value.nextToMove == StoneType.WHITE && gameState?.whitePlayer?.id == userId) || (position.value.nextToMove == StoneType.BLACK && gameState?.blackPlayer?.id == userId)

            val nextGame = remember(activeGamesRepository.myTurnGames) { getNextGame() }
            val visibleButtons =
                when {
                    gameState?.phase == Phase.STONE_REMOVAL -> listOf(ACCEPT_STONE_REMOVAL, REJECT_STONE_REMOVAL)
                    gameFinished == true -> listOf(CHAT, ESTIMATE, PREVIOUS, NEXT)
                    analyzeMode -> listOf(EXIT_ANALYSIS, ESTIMATE, PREVIOUS, NEXT)
                    pendingMove != null -> emptyList()
                    isMyTurn && candidateMove == null -> listOf(ANALYZE, PASS, RESIGN, CHAT, if(nextGame != null) NEXT_GAME else NEXT_GAME_DISABLED)
                    isMyTurn && candidateMove != null -> listOf(CONFIRM_MOVE, DISCARD_MOVE)
                    !isMyTurn && gameState?.phase == Phase.PLAY -> listOf(ANALYZE, UNDO, RESIGN, CHAT, if(nextGame != null) NEXT_GAME else NEXT_GAME_DISABLED)
                    else -> emptyList()
                }

            val whiteToMove = gameState?.playerToMoveId == gameState?.whitePlayer?.id
            val bottomText = when {
                pendingMove != null && pendingMove?.attempt == 1 -> "Submitting move"
                pendingMove != null -> "Submitting move (attempt #${pendingMove?.attempt})"
                else -> null
            }
            val shownPosition =
                if (analyzeMode || gameFinished == true) analysisPosition else position.value
            val score = if (shownPosition != null && gameState != null) RulesManager.scorePosition(shownPosition, gameState!!) else (0f to 0f)
            GameState(
                position = shownPosition,
                loading = loading,
                gameWidth = gameWidth,
                gameHeight = gameHeight,
                candidateMove = candidateMove,
                boardInteractive = (isMyTurn && pendingMove == null) || gameState?.phase == Phase.STONE_REMOVAL,
                drawTerritory = gameState?.phase == Phase.STONE_REMOVAL || gameFinished == true,
                fadeOutRemovedStones = gameState?.phase == Phase.STONE_REMOVAL || gameFinished == true,
                buttons = visibleButtons,
                title = if (loading) "Loading..." else "Move ${gameState?.moves?.size} · ${gameState?.rules?.capitalize()} · ${if (whiteToMove) "White" else "Black"}",
                whitePlayer = gameState?.whitePlayer?.data(StoneType.WHITE, score.first),
                blackPlayer = gameState?.blackPlayer?.data(StoneType.BLACK, score.second),
                timerDetails = timer,
                bottomText = bottomText,
                retryMoveDialogShown = retrySendMoveDialogShowing,
                showAnalysisPanel = analyzeMode || gameFinished == true,
                showPlayers = !(analyzeMode || gameFinished == true),
                passDialogShowing = passDialogShowing,
                resignDialogShowing = resignDialogShowing,
                gameOverDialogShowing = gameOverDialog,
                messages = messages,
                chatDialogShowing = chatDialogShowing,
            )
        }
    }

    private fun calculateGameOverDetails(game: Game): GameOverDialogDetails {
        val playerWon = (game.blackLost == true && game.whitePlayer.id == userId) || (game.whiteLost == true && game.blackPlayer.id == userId)
        val winner = if(game.blackLost == true) game.whitePlayer else game.blackPlayer
        val loser = if(game.blackLost == true) game.blackPlayer else game.whitePlayer
        val you = if(game.whitePlayer.id == userId) game.whitePlayer else game.blackPlayer

        var details = when {
            game.outcome == "Resignation" -> buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(loser.username)
                pop()
                append(" resigned on move ${game.moves?.size}")
            }
            game.outcome?.endsWith("points") == true -> buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(winner.username)
                pop()
                append(" has ${game.outcome?.substringBefore(' ')} more points")
            }
            game.outcome == "Timeout" -> buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(loser.username)
                pop()
                append(" timed out.")
            }
            game.outcome == "Cancellation" -> buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(loser.username)
                pop()
                append(" cancelled the game.")
            }
            else -> AnnotatedString("Result unknown (${game.outcome})")
        }

        if(game.ranked == true) {
            details += buildAnnotatedString {
                append("\nYour rating is now ")
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(formatRank(egfToRank(you.rating)))
                pop()
                append(" - ${String.format("%.0f", you.rating)}")
            }
        }

        return GameOverDialogDetails(
            playerWon = playerWon,
            detailsText = details
        )
    }

    private fun getNextGame(): Game? {
        val ourIndex = activeGamesRepository.myTurnGames.indexOfFirst { it.id == gameState?.id }
        return when {
            ourIndex == -1 -> activeGamesRepository.myTurnGames.firstOrNull()
            activeGamesRepository.myTurnGames.size == 1 -> null
            else -> activeGamesRepository.myTurnGames[(ourIndex + 1) % activeGamesRepository.myTurnGames.size]
        }
    }

    fun onRetryDialogDismissed() {
        viewModelScope.launch {
            retrySendMoveDialogShowing = false
            pendingMove = null
        }
    }

    fun onRetryDialogRetry() {
        viewModelScope.launch {
            retrySendMoveDialogShowing = false
            pendingMove?.let {
                submitMove(it.cell, it.moveNo)
            }
        }
    }

    fun onPassDialogDismissed() {
        passDialogShowing = false
    }

    fun onPassDialogConfirm() {
        passDialogShowing = false
        submitMove(Cell(-1, -1), gameState?.moves?.size ?: 0)
    }

    fun onResignDialogDismissed() {
        resignDialogShowing = false
    }

    fun onResignDialogConfirm() {
        resignDialogShowing = false
        gameConnection.resign()
    }

    fun onGameOverDialogAnalyze() {
        gameOverDialog = null
    }

    fun onGameOverDialogDismissed() {
        gameOverDialog = null
    }

    fun onGameOverDialogNextGame() {
        getNextGame()?.let { pendingNavigation = PendingNavigation.NavigateToGame(it) }
    }

    fun onChatDialogDismissed() {
        chatDialogShowing = false
    }

    private suspend fun timerRefresher() {
        while (true) {
            var delayUntilNextUpdate = 1000L
            gameState?.let { game ->
                val maxTime = game.timeControl?.let { timeControl ->
                    when(timeControl.system) {
                        "fischer" -> timeControl.initial_time?.times(1000L)
                        else -> null
                    }
                } ?: 1
                game.clock?.let { clock ->
                    val whiteToMove = game.playerToMoveId == game.whitePlayer.id
                    val blackToMove = game.playerToMoveId == game.blackPlayer.id

                    val whiteTimer = computeTimeLeft(
                        clock,
                        clock.whiteTimeSimple,
                        clock.whiteTime,
                        whiteToMove,
                        game.pausedSince,
                        game.timeControl,
                    )
                    val blackTimer = computeTimeLeft(
                        clock,
                        clock.blackTimeSimple,
                        clock.blackTime,
                        blackToMove,
                        game.pausedSince,
                        game.timeControl,
                        )

                    var timeLeft = null as Long?

                    if (clock.startMode == true) {
                        clock.expiration?.let { expiration ->
                            timeLeft = expiration - clockDriftRepository.serverTime
                            timer =
                                if (whiteToMove)
                                    TimerDetails(
                                        whiteFirstLine = formatMillis(timeLeft!!),
                                        whiteSecondLine = "(start)",
                                        whitePercentage = (timeLeft!! / 300000.0 * 100).toInt(),
                                        whiteFaded = false,
                                        blackFirstLine = blackTimer.firstLine ?: "",
                                        blackSecondLine = blackTimer.secondLine ?: "",
                                        blackPercentage = 100,
                                        blackFaded = true,
                                    )
                                else
                                    TimerDetails(
                                        whiteFirstLine = whiteTimer.firstLine ?: "",
                                        whiteSecondLine = whiteTimer.secondLine ?: "",
                                        whitePercentage = 100,
                                        whiteFaded = true,
                                        blackFirstLine = formatMillis(timeLeft!!),
                                        blackSecondLine = "(start)",
                                        blackPercentage = (timeLeft!! / 300000.0 * 100).toInt(),
                                        blackFaded = false,
                                    )
                        }
                    } else {
                        if ((game.phase == Phase.PLAY || game.phase == Phase.STONE_REMOVAL) && !loading) {

                            timer =
                                TimerDetails(
                                    whiteFirstLine = whiteTimer.firstLine ?: "",
                                    whiteSecondLine = whiteTimer.secondLine ?: "",
                                    whitePercentage = (whiteTimer.timeLeft / maxTime.toDouble() * 100).toInt(),
                                    whiteFaded = blackToMove,
                                    blackFirstLine = blackTimer.firstLine ?: "",
                                    blackSecondLine = blackTimer.secondLine ?: "",
                                    blackPercentage = (blackTimer.timeLeft / maxTime.toDouble() * 100).toInt(),
                                    blackFaded = whiteToMove,
                                )

                            timeLeft = if (whiteToMove) whiteTimer.timeLeft else blackTimer.timeLeft

                        }
                    }
                    delayUntilNextUpdate = timeLeft?.let {
                        when (it) {
                            in 0 until 10_000 -> 100
                            in 10_000 until 3_600_000 -> 1_000
                            in 3_600_000 until 24 * 3_600_000 -> 60_000
                            else -> 12 * 60_000
                        }
                    } ?: 1000
                }
            }

            delay(delayUntilNextUpdate)
        }
    }

    private fun Player.data(color: StoneType, score: Float): PlayerData {
        return PlayerData(
            name = username,
            details = if(score != 0f) "${if(score > 0) "+ " else ""}$score points" else "",
            rank = formatRank(egfToRank(rating)),
            flagCode = convertCountryCodeToEmojiFlag(country),
            iconURL = icon,
            color = color,
        )
    }

    override fun onCleared() {
        gameConnection.close()
        super.onCleared()
    }

    fun onCellTracked(cell: Cell) {
        if(gameState?.phase == Phase.PLAY && !position.value.blackStones.contains(cell) && !position.value.whiteStones.contains(cell)) {
            candidateMove = cell
        }
    }

    fun onCellTapUp(cell: Cell) {
        if(gameState?.phase == Phase.PLAY) {
            viewModelScope.launch {
                val newPosition =
                    RulesManager.makeMove(position.value, position.value.nextToMove, cell)
                if (newPosition == null) {
                    candidateMove = null
                }
            }
        } else if(gameState?.phase == Phase.STONE_REMOVAL) {
            val (removing, delta) = RulesManager.toggleRemoved(position.value, cell)
            if(delta.isNotEmpty()) {
                gameConnection.submitRemovedStones(delta, removing)
            }
        }
    }

    fun onButtonPressed(button: Button) {
        when(button) {
            CONFIRM_MOVE -> candidateMove?.let { submitMove(it,gameState?.moves?.size ?: 0) }
            DISCARD_MOVE -> candidateMove = null
            ANALYZE -> viewModelScope.launch {
                analysisShownMoveNumber = gameState?.moves?.size ?: 0
                analyzeMode = true
            }
            PASS -> passDialogShowing = true
            RESIGN -> resignDialogShowing = true
            CHAT -> chatDialogShowing = true
            NEXT_GAME -> getNextGame()?.let { pendingNavigation = PendingNavigation.NavigateToGame(it) }
            UNDO -> TODO()
            EXIT_ANALYSIS -> analyzeMode = false
            ESTIMATE -> TODO()
            PREVIOUS -> analysisShownMoveNumber = (analysisShownMoveNumber - 1).coerceIn(0 .. (gameState?.moves?.size ?: 0))
            NEXT -> analysisShownMoveNumber = (analysisShownMoveNumber + 1).coerceIn(0 .. (gameState?.moves?.size ?: 0))
            NEXT_GAME_DISABLED -> {}
            ACCEPT_STONE_REMOVAL -> gameConnection.acceptRemovedStones(position.value.removedSpots)
            REJECT_STONE_REMOVAL -> gameConnection.rejectRemovedStones()
        }
    }

    private fun submitMove(move: Cell, moveNo: Int, attempt: Int = 1) {
        viewModelScope.launch {
            val newMove = PendingMove(
                cell = move,
                moveNo = moveNo,
                attempt = attempt
            )
            pendingMove = newMove
            gameConnection.submitMove(move)
            delay(DELAY_BETWEEN_ATTEMPTS)
            if(pendingMove == newMove) {
                if(attempt >= MAX_ATTEMPTS) {
                    onSubmitMoveFailed()
                } else {
                    submitMove(move, moveNo, attempt + 1)
                }
            }
        }
    }

    private fun onSubmitMoveFailed() {
        retrySendMoveDialogShowing = true
    }

    private fun checkPendingMove(game: Game) {
        val expectedMove = pendingMove ?: return
        if(game?.moves?.getOrNull(expectedMove.moveNo) == expectedMove.cell) {
            pendingMove = null
            candidateMove = null
            retrySendMoveDialogShowing = false
        }
    }
}

@Immutable
data class GameState(
    val position: Position?,
    val loading: Boolean,
    val gameWidth: Int,
    val gameHeight: Int,
    val candidateMove: Cell?,
    val boardInteractive: Boolean,
    val drawTerritory: Boolean,
    val fadeOutRemovedStones: Boolean,
    val buttons: List<Button>,
    val title: String,
    val whitePlayer: PlayerData?,
    val blackPlayer: PlayerData?,
    val timerDetails: TimerDetails?,
    val bottomText: String?,
    val retryMoveDialogShown: Boolean,
    val showPlayers: Boolean,
    val showAnalysisPanel: Boolean,
    val passDialogShowing: Boolean,
    val resignDialogShowing: Boolean,
    val messages: Map<Long, List<ChatMessage>>,
    val chatDialogShowing: Boolean,
    val gameOverDialogShowing: GameOverDialogDetails?,
) {
    companion object {
        val DEFAULT = GameState(
            position = null,
            loading = true,
            gameWidth = 19,
            gameHeight = 19,
            candidateMove = null,
            boardInteractive = false,
            drawTerritory = false,
            fadeOutRemovedStones = false,
            buttons = emptyList(),
            title = "Loading...",
            whitePlayer = null,
            blackPlayer = null,
            timerDetails = null,
            bottomText = null,
            retryMoveDialogShown = false,
            showAnalysisPanel = false,
            showPlayers = true,
            passDialogShowing = false,
            resignDialogShowing = false,
            gameOverDialogShowing = null,
            messages = emptyMap(),
            chatDialogShowing = false,
        )
    }
}


data class ChatMessage(
    val fromUser: Boolean,
    val message: Message,
)

data class GameOverDialogDetails(
    val playerWon: Boolean,
    val detailsText: AnnotatedString,
)

data class PlayerData(
    val name: String,
    val details: String,
    val rank: String,
    val flagCode: String,
    val iconURL: String?,
    val color: StoneType,
)

enum class Button(
    val repeatable: Boolean = false,
    val enabled: Boolean = true,
) {
    CONFIRM_MOVE,
    DISCARD_MOVE,
    ACCEPT_STONE_REMOVAL,
    REJECT_STONE_REMOVAL,
    ANALYZE,
    PASS,
    RESIGN,
    CHAT,
    NEXT_GAME,
    NEXT_GAME_DISABLED (enabled = false),
    UNDO,
    EXIT_ANALYSIS,
    ESTIMATE,
    PREVIOUS (repeatable = true),
    NEXT (repeatable = true)
}

data class TimerDetails(
    val whiteFirstLine: String,
    val blackFirstLine: String,
    val whiteSecondLine: String,
    val blackSecondLine: String,
    val whitePercentage: Int,
    val blackPercentage: Int,
    val whiteFaded: Boolean,
    val blackFaded: Boolean,
)

data class PendingMove(
    val cell: Cell,
    val moveNo: Int,
    val attempt: Int,
)

sealed class PendingNavigation {
    class NavigateToGame(val game: Game) : PendingNavigation()
}

sealed class Event {
    object PlayStoneSound: Event()
}