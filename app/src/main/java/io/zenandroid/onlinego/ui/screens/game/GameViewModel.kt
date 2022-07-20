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
import io.zenandroid.onlinego.data.model.Mark
import io.zenandroid.onlinego.data.model.Position
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.data.model.local.Message
import io.zenandroid.onlinego.data.model.local.Player
import io.zenandroid.onlinego.data.model.local.isPaused
import io.zenandroid.onlinego.data.model.ogs.Phase
import io.zenandroid.onlinego.data.ogs.GameConnection
import io.zenandroid.onlinego.data.ogs.OGSWebSocketService
import io.zenandroid.onlinego.data.repositories.ActiveGamesRepository
import io.zenandroid.onlinego.data.repositories.ChatRepository
import io.zenandroid.onlinego.data.repositories.ClockDriftRepository
import io.zenandroid.onlinego.data.repositories.UserSessionRepository
import io.zenandroid.onlinego.gamelogic.RulesManager
import io.zenandroid.onlinego.gamelogic.RulesManager.isPass
import io.zenandroid.onlinego.ui.screens.game.Button.*
import io.zenandroid.onlinego.ui.screens.game.PendingNavigation.*
import io.zenandroid.onlinego.ui.screens.game.UserAction.*
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
    private lateinit var currentGamePosition: MutableState<Position>
    private var estimatePosition by mutableStateOf<Position?>(null)
    private var analysisPosition by mutableStateOf<Position?>(null)
    private val userId = userSessionRepository.userId
    private var candidateMove by mutableStateOf<Cell?>(null)
    private lateinit var gameConnection: GameConnection
    private var gameState by mutableStateOf<Game?>(null)
    private var timer by mutableStateOf(TimerDetails("", "", "", "", 0, 0, false, false, null, null))
    private var pendingMove by mutableStateOf<PendingMove?>(null)
    private var retrySendMoveDialogShowing by mutableStateOf(false)
    private var koMoveDialogShowing by mutableStateOf(false)
    private var analyzeMode by mutableStateOf(false)
    private var estimateMode by mutableStateOf(false)
    private var analysisShownMoveNumber by mutableStateOf(0)
    private var passDialogShowing by mutableStateOf(false)
    private var resignDialogShowing by mutableStateOf(false)
    private var cancelDialogShowing by mutableStateOf(false)
    private var gameFinished by mutableStateOf<Boolean?>(null)
    private var gameOverDetails by mutableStateOf<GameOverDialogDetails?>(null)
    private var gameOverDialogShowing by mutableStateOf(false)
    private var chatDialogShowing by mutableStateOf(false)
    private var currentVariation by mutableStateOf<Variation?>(null)
    private var unreadMessagesCount by mutableStateOf(0)
    private var gameInfoDialogShowing by mutableStateOf(false)

    lateinit var state: StateFlow<GameState>
    var pendingNavigation by mutableStateOf<PendingNavigation?>(null)
        private set
    private val _events = MutableSharedFlow<Event?>()
    val events = _events.asSharedFlow()

    fun initialize(gameId: Long, gameWidth: Int, gameHeight: Int) {
        val gameFlow = activeGamesRepository.monitorGameFlow(gameId).distinctUntilChanged()
        currentGamePosition = mutableStateOf(Position(gameWidth, gameHeight))

        socketService.resendAuth()
        gameConnection = socketService.connectToGame(gameId, true)

        val messagesFlow = chatRepository.monitorGameChat(gameId)
            .map { messages ->
                unreadMessagesCount = messages.count { !it.seen }
                messages.map {
                    ChatMessage(
                        fromUser = it.playerId == userId,
                        message = it,
                    )
                }.filter { it.fromUser || it.message.type == Message.Type.MAIN || gameFinished == true }
                    .groupBy { it.message.moveNumber ?: 0 }
            }


        state = moleculeScope.launchMolecule {
            val game by gameFlow.collectAsState(initial = null)
            val messages by messagesFlow.collectAsState(emptyMap())

            LaunchedEffect(game?.moves) {
                if(!loading && !game?.moves.isNullOrEmpty()) {
                    _events.emit(Event.PlayStoneSound)
                }
            }
            LaunchedEffect(game) {
                game?.let { onGameChanged(it) }
            }
            if(analyzeMode && game != null) {
                LaunchedEffect(analysisShownMoveNumber) {
                    game?.let { calculateAnalysisPosition(it) }
                }
            }
            if(estimateMode && game != null) {
                LaunchedEffect(analysisPosition, currentGamePosition) {
                    withContext(Dispatchers.Default) {
                        game?.let {
                            val basePosition = if(analyzeMode && analysisPosition != null) analysisPosition!! else currentGamePosition.value
                            estimatePosition = RulesManager.determineTerritory(basePosition, it.scoreStones == true)
                        }
                    }
                }
            }

            //
            // Note to future self: be careful of backward writes. Try no to write to state variables below this comment
            //

            val shownPosition = when {
                estimateMode && estimatePosition != null -> estimatePosition!!
                (analyzeMode || gameFinished == true) && analysisPosition != null -> analysisPosition!!
                else -> currentGamePosition.value
            }

            val isMyTurn =
                game?.phase == Phase.PLAY &&
                    (currentGamePosition.value.nextToMove == StoneType.WHITE && game?.whitePlayer?.id == userId) ||
                    (currentGamePosition.value.nextToMove == StoneType.BLACK && game?.blackPlayer?.id == userId)

            val nextGame = remember(activeGamesRepository.myTurnGames) { getNextGame() }

            val nextGameButton = NextGame(nextGame != null)
            val endGameButton = if(game.canBeCancelled()) CancelGame else Resign
            val maxAnalysisMoveNumber = currentVariation?.let {
                it.rootMoveNo + it.moves.size
            } ?: game?.moves?.size ?: 0
            val nextButton = Next(analysisShownMoveNumber < maxAnalysisMoveNumber)
            val chatButton = Chat(if(unreadMessagesCount > 0) unreadMessagesCount.toString() else null)

            val visibleButtons =
                when {
                    estimateMode -> listOf(ExitEstimate)
                    game?.phase == Phase.STONE_REMOVAL -> listOf(AcceptStoneRemoval, RejectStoneRemoval)
                    gameFinished == true -> listOf(chatButton, Estimate, Previous, nextButton)
                    analyzeMode -> listOf(ExitAnalysis, Estimate, Previous, nextButton)
                    pendingMove != null -> emptyList()
                    isMyTurn && candidateMove == null -> listOf(Analyze, Pass, endGameButton, chatButton, nextGameButton)
                    isMyTurn && candidateMove != null -> listOf(ConfirmMove, DiscardMove)
                    !isMyTurn && game?.phase == Phase.PLAY -> listOf(Analyze, Undo, endGameButton, chatButton, nextGameButton)
                    else -> emptyList()
                }

            val whiteToMove = game?.playerToMoveId == game?.whitePlayer?.id
            val bottomText = when {
                pendingMove != null && pendingMove?.attempt == 1 -> "Submitting move"
                pendingMove != null -> "Submitting move (attempt #${pendingMove?.attempt})"
                estimateMode && estimatePosition == null -> "Estimating"
                else -> null
            }
            val score = if (game != null) RulesManager.scorePosition(shownPosition, game!!) else (0f to 0f)

            val whiteExtraStatus = calculateExtraStatus(game, whiteToMove, game?.whitePlayer?.acceptedStones, game?.whiteLost, timer.whiteStartTimer)
            val blackExtraStatus = calculateExtraStatus(game, !whiteToMove, game?.blackPlayer?.acceptedStones, game?.blackLost, timer.blackStartTimer)

            GameState(
                position = shownPosition,
                loading = loading,
                gameWidth = gameWidth,
                gameHeight = gameHeight,
                candidateMove = candidateMove,
                boardInteractive = (isMyTurn && pendingMove == null && !analyzeMode && !estimateMode) || game?.phase == Phase.STONE_REMOVAL || (analyzeMode && !estimateMode),
                drawTerritory = game?.phase == Phase.STONE_REMOVAL || (gameFinished == true && analysisShownMoveNumber == game?.moves?.size) || (estimateMode && estimatePosition != null),
                fadeOutRemovedStones = game?.phase == Phase.STONE_REMOVAL || (gameFinished == true && analysisShownMoveNumber == game?.moves?.size) || (estimateMode && estimatePosition != null),
                buttons = visibleButtons,
                title = if (loading) "Loading..." else "Move ${game?.moves?.size} · ${game?.rules?.capitalize()} · ${if (whiteToMove) "White" else "Black"}",
                whitePlayer = game?.whitePlayer?.data(StoneType.WHITE, score.first),
                blackPlayer = game?.blackPlayer?.data(StoneType.BLACK, score.second),
                timerDetails = timer,
                bottomText = bottomText,
                retryMoveDialogShowing = retrySendMoveDialogShowing,
                koMoveDialogShowing = koMoveDialogShowing,
//                showAnalysisPanel = analyzeMode,
                showAnalysisPanel = false,
//                showPlayers = !analyzeMode,
                showPlayers = true,
                showTimers = gameFinished != true,
                passDialogShowing = passDialogShowing,
                resignDialogShowing = resignDialogShowing,
                cancelDialogShowing = cancelDialogShowing,
                gameOverDialogShowing = if(gameOverDialogShowing) gameOverDetails else null,
                messages = messages,
                chatDialogShowing = chatDialogShowing,
                whiteExtraStatus = whiteExtraStatus,
                blackExtraStatus = blackExtraStatus,
                showLastMove = !(analyzeMode && currentVariation != null && currentVariation?.rootMoveNo!! < analysisShownMoveNumber),
                gameInfoDialogShowing = gameInfoDialogShowing,
            )
        }
    }

    private fun calculateExtraStatus(game: Game?, playerToMove: Boolean, playerAcceptedStones: String?, playerLost: Boolean?, playerStartTimer: String?): String? =
        when {
            game?.phase == Phase.PLAY && !playerToMove && game.moves?.lastOrNull()?.isPass() == true -> "Player passed!"
            game?.phase == Phase.STONE_REMOVAL && game.removedStones != null && game.removedStones == playerAcceptedStones -> "Accepted"
            game?.phase == Phase.FINISHED && playerLost == true && game.outcome == "Resignation" -> "Resigned"
            game?.phase == Phase.FINISHED && playerLost == true && game.outcome == "Timeout" -> "Timed out"
            game?.phase == Phase.FINISHED && playerLost == true && game.outcome == "Cancellation" -> "Cancelled the game"
            playerStartTimer != null -> "$playerStartTimer to make first move"
            else -> null
        }

    private suspend fun calculateAnalysisPosition(it: Game) {
        withContext(Dispatchers.Default) {
            val moves = it.moves ?: emptyList()
            val moveNo = it.moves?.size ?: 0
            val variation = currentVariation

            val nextMoveInMainline =
                if ((variation == null || analysisShownMoveNumber <= variation.rootMoveNo) && analysisShownMoveNumber < moveNo)
                    listOf(moves[analysisShownMoveNumber])
                else emptyList()

            val nextMoveVariation =
                if (variation != null && analysisShownMoveNumber >= variation.rootMoveNo && analysisShownMoveNumber < variation.rootMoveNo + variation.moves.size)
                    listOf(variation.moves[analysisShownMoveNumber - variation.rootMoveNo])
                else emptyList()

            analysisPosition = RulesManager.replay(it, analysisShownMoveNumber, false, currentVariation).copy(
                    customMarks = (nextMoveInMainline + nextMoveVariation).mapIndexed { index, cell ->
                        Mark(cell, "XABCDEFG"[index % 8].toString(), null)
                    }.toSet()
                )
        }
    }

    private suspend fun onGameChanged(game: Game) {
        withContext(Dispatchers.Default) {
            currentGamePosition.value = RulesManager.replay(game = game, computeTerritory = game.phase == Phase.STONE_REMOVAL)
            if (loading) {
                analysisShownMoveNumber = game.moves?.size ?: 0
            }
            gameState = game
            checkPendingMove(game)
            if (game.phase == Phase.FINISHED && gameFinished == false && game.blackLost != game.whiteLost) { // Game just finished
                if (game.ranked == true) {
                    activeGamesRepository.refreshGameData(game.id) // just to get the latest ratings...
                }
                gameOverDialogShowing = true
                analysisShownMoveNumber = game.moves?.size ?: 0
            }
            gameOverDetails = calculateGameOverDetails(game)
            gameFinished = game.phase == Phase.FINISHED
            loading = false
            timerRefresher()
        }
    }

    private fun Game?.canBeCancelled(): Boolean {
        val maxMoveNumber = 5 + if (this?.freeHandicapPlacement == true) this.handicap ?: 1 else 1
        return (this?.moves?.size ?: 0) < maxMoveNumber
    }

    private fun calculateGameOverDetails(game: Game): GameOverDialogDetails? {
        if(game.phase != Phase.FINISHED || game.whiteLost == game.blackLost) {
            return null
        }
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
                append(" has ${game.outcome.substringBefore(' ')} more points")
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
            game.outcome == "Disconnection" -> buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(loser.username)
                pop()
                append(" disconnected.")
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
            gameCancelled = game.outcome == "Cancellation",
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

    private fun onGameOverDialogQuickReplay() {
        gameOverDialogShowing = false
        viewModelScope.launch {
            for(i in 0 until (gameState?.moves?.size ?: 0)) {
                analysisShownMoveNumber = i
                delay(700)
            }
        }
    }

    private suspend fun timerRefresher() {
        while (true) {
            var delayUntilNextUpdate = 1000L
            gameState?.let { game ->
                val maxTime = (game.timeControl?.initial_time ?: game.timeControl?.per_move ?: game.timeControl?.main_time ?: game.timeControl?.total_time ?: 1) * 1000
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

                    var timeLeft: Long? = null

                    if (clock.startMode == true) {
                        clock.expiration?.let { expiration ->
                            timeLeft = expiration - clockDriftRepository.serverTime
                            timer =
                                if (whiteToMove)
                                    TimerDetails(
                                        whiteFirstLine = blackTimer.firstLine ?: "", // opposing color is intended!
                                        whiteSecondLine = blackTimer.secondLine ?: "", // opposing color is intended!
                                        whitePercentage = 100,
                                        whiteFaded = true,
                                        blackFirstLine = blackTimer.firstLine ?: "",
                                        blackSecondLine = blackTimer.secondLine ?: "",
                                        blackPercentage = 100,
                                        blackFaded = true,
                                        whiteStartTimer = formatMillis(timeLeft!!),
                                        blackStartTimer = null,
                                    )
                                else
                                    TimerDetails(
                                        whiteFirstLine = whiteTimer.firstLine ?: "",
                                        whiteSecondLine = whiteTimer.secondLine ?: "",
                                        whitePercentage = 100,
                                        whiteFaded = true,
                                        blackFirstLine = whiteTimer.firstLine ?: "", // opposing color is intended!
                                        blackSecondLine = whiteTimer.secondLine ?: "", // opposing color is intended!
                                        blackPercentage = 100,
                                        blackFaded = true,
                                        whiteStartTimer = null,
                                        blackStartTimer = formatMillis(timeLeft!!),
                                    )
                        }
                    } else {
                        if ((game.phase == Phase.PLAY || game.phase == Phase.STONE_REMOVAL)) {

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
                                    whiteStartTimer = null,
                                    blackStartTimer = null,
                                )

                            timeLeft = if (whiteToMove) whiteTimer.timeLeft else blackTimer.timeLeft
                        }
                    }
                    if(game.pauseControl.isPaused()) {
                        return
                    }
                    delayUntilNextUpdate = timeLeft?.let {
                        when (it) {
                            in 0 until 2_000 -> it % 101
                            in 2_000 until 3_600_000 -> it % 1_001
                            in 3_600_000 until 24 * 3_600_000 -> it % 60_001
                            else -> it % (12 * 60_000 + 1)
                        }
                    } ?: 1000
                }
            }

            delay(delayUntilNextUpdate.coerceAtLeast(50))
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

    private fun onCellTracked(cell: Cell) {
        if(gameState?.phase == Phase.PLAY && !currentGamePosition.value.blackStones.contains(cell) && !currentGamePosition.value.whiteStones.contains(cell)) {
            candidateMove = cell
        }
    }

    private fun onCellTapUp(cell: Cell) {
        when {
            gameState?.phase == Phase.PLAY && !analyzeMode && !estimateMode -> {
                viewModelScope.launch {
                    val pos = currentGamePosition.value
                    val historySize = gameState?.moves?.size ?: 0
                    val newPosition = RulesManager.makeMove(pos, pos.nextToMove, cell)
                    if (newPosition == null) {
                        candidateMove = null
                    } else if(historySize > 1 && RulesManager.replay(gameState!!, historySize - 1, false).hasTheSameStonesAs(newPosition)) {
                        candidateMove = null
                        koMoveDialogShowing = true
                    }
                }
            }
            gameState?.phase == Phase.STONE_REMOVAL -> {
                val (removing, delta) = RulesManager.toggleRemoved(currentGamePosition.value, cell)
                if(delta.isNotEmpty()) {
                    gameConnection.submitRemovedStones(delta, removing)
                }
            }
            analyzeMode && analysisPosition != null && !estimateMode -> {
                viewModelScope.launch {
                    analysisPosition?.let { pos ->
                        val newPosition = RulesManager.makeMove(pos, pos.nextToMove, cell)
                        if (newPosition != null) {
                            currentVariation = currentVariation?.let { variation ->
                                when {
                                    analysisShownMoveNumber == variation.rootMoveNo + variation.moves.size -> variation.copy(moves = variation.moves + cell)
                                    analysisShownMoveNumber < variation.rootMoveNo -> Variation(analysisShownMoveNumber, listOf(cell))
                                    variation.moves[analysisShownMoveNumber - variation.rootMoveNo] == cell -> variation
                                    else -> variation.copy(moves = variation.moves.take(analysisShownMoveNumber - variation.rootMoveNo) + cell)
                                }
                            } ?: Variation(analysisShownMoveNumber, listOf(cell))
                            analysisShownMoveNumber++
                            candidateMove = null
                        }
                    }
                }
            }
        }
    }

    fun onUserAction(action: UserAction) {
        when(action) {
            is BoardCellDragged -> onCellTracked(action.cell)
            is BoardCellTapUp -> onCellTapUp(action.cell)
            is BottomButtonPressed -> onButtonPressed(action.button)
            CancelDialogConfirm -> {
                cancelDialogShowing = false
                gameConnection.abortGame()
            }
            CancelDialogDismiss -> cancelDialogShowing = false
            ChatDialogDismiss -> chatDialogShowing = false
            KOMoveDialogDismiss -> koMoveDialogShowing = false
            is ChatSend -> gameConnection.sendMessage(action.message, gameState?.moves?.size ?: 0)
            GameInfoClick -> gameInfoDialogShowing = true
            GameInfoDismiss -> gameInfoDialogShowing = false
            GameOverDialogDismiss -> gameOverDialogShowing = false
            GameOverDialogAnalyze -> {
                gameOverDialogShowing = false
                analyzeMode = true
            }
            GameOverDialogNextGame -> getNextGame()?.let { pendingNavigation = NavigateToGame(it) }
            GameOverDialogQuickReplay -> onGameOverDialogQuickReplay()
            PassDialogConfirm -> {
                passDialogShowing = false
                submitMove(Cell(-1, -1), gameState?.moves?.size ?: 0)
            }
            PassDialogDismiss -> passDialogShowing = false
            ResignDialogDismiss -> resignDialogShowing = false
            ResignDialogConfirm -> {
                resignDialogShowing = false
                gameConnection.resign()
            }
            RetryDialogDismiss -> {
                retrySendMoveDialogShowing = false
                pendingMove = null
            }
            RetryDialogRetry -> {
                retrySendMoveDialogShowing = false
                pendingMove?.let {
                    submitMove(it.cell, it.moveNo)
                }
            }
            OpenInBrowser -> pendingNavigation = OpenURL("https://online-go.com/game/${gameState?.id}")
            DownloadSGF -> pendingNavigation = OpenURL("https://online-go.com/api/v1/games/${gameState?.id}/sgf")
        }. run {}
    }

    private fun onButtonPressed(button: Button) {
        when(button) {
            ConfirmMove -> candidateMove?.let { submitMove(it,gameState?.moves?.size ?: 0) }
            DiscardMove -> candidateMove = null
            Analyze -> {
                analysisShownMoveNumber = gameState?.moves?.size ?: 0
                analyzeMode = true
            }
            Pass -> passDialogShowing = true
            Resign -> resignDialogShowing = true
            CancelGame -> cancelDialogShowing = true
            is Chat -> {
                chatDialogShowing = true
                chatRepository.markMessagesAsRead(state.value.messages.flatMap { it.value }.map { it.message }.filter { !it.seen })
            }
            is NextGame -> getNextGame()?.let { pendingNavigation = NavigateToGame(it) }
            Undo -> {} //TODO()
            ExitAnalysis -> analyzeMode = false
            Estimate -> {
                estimatePosition = null
                estimateMode = true
            }
            ExitEstimate -> estimateMode = false
            Previous -> analysisShownMoveNumber = (analysisShownMoveNumber - 1).coerceAtLeast(0)
            is Next -> {
                val max = currentVariation?.let {
                    it.rootMoveNo + it.moves.size
                } ?: gameState?.moves?.size ?: 0
                analysisShownMoveNumber = (analysisShownMoveNumber + 1).coerceIn(0 .. max)
            }
            AcceptStoneRemoval -> gameConnection.acceptRemovedStones(currentGamePosition.value.removedSpots)
            RejectStoneRemoval -> gameConnection.rejectRemovedStones()
        }.run {} // makes the while exhaustive
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
        if(game.moves?.getOrNull(expectedMove.moveNo) == expectedMove.cell) {
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
    val showLastMove: Boolean,
    val buttons: List<Button>,
    val title: String,
    val whitePlayer: PlayerData?,
    val blackPlayer: PlayerData?,
    val whiteExtraStatus: String?,
    val blackExtraStatus: String?,
    val timerDetails: TimerDetails?,
    val bottomText: String?,
    val retryMoveDialogShowing: Boolean,
    val koMoveDialogShowing: Boolean,
    val showPlayers: Boolean,
    val showTimers: Boolean,
    val showAnalysisPanel: Boolean,
    val passDialogShowing: Boolean,
    val resignDialogShowing: Boolean,
    val cancelDialogShowing: Boolean,
    val messages: Map<Long, List<ChatMessage>>,
    val chatDialogShowing: Boolean,
    val gameOverDialogShowing: GameOverDialogDetails?,
    val gameInfoDialogShowing: Boolean,
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
            showLastMove = true,
            buttons = emptyList(),
            title = "Loading...",
            whitePlayer = null,
            blackPlayer = null,
            timerDetails = null,
            bottomText = null,
            retryMoveDialogShowing = false,
            koMoveDialogShowing = false,
            showAnalysisPanel = false,
            showPlayers = true,
            showTimers = true,
            passDialogShowing = false,
            resignDialogShowing = false,
            cancelDialogShowing = false,
            gameOverDialogShowing = null,
            messages = emptyMap(),
            chatDialogShowing = false,
            whiteExtraStatus = null,
            blackExtraStatus = null,
            gameInfoDialogShowing = false,
        )
    }
}


data class ChatMessage(
    val fromUser: Boolean,
    val message: Message,
)

data class GameOverDialogDetails(
    val gameCancelled: Boolean,
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

sealed class Button(
    val repeatable: Boolean = false,
    val enabled: Boolean = true,
    val bubbleText: String? = null,
) {
    object ConfirmMove : Button()
    object DiscardMove : Button()
    object AcceptStoneRemoval : Button()
    object RejectStoneRemoval : Button()
    object Analyze : Button()
    object Pass : Button()
    object Resign : Button()
    object CancelGame : Button()
    class Chat(bubbleText: String? = null) : Button(bubbleText = bubbleText)
    class NextGame(enabled: Boolean = true, bubbleText: String? = null) : Button(enabled = enabled, bubbleText = bubbleText)
    object Undo : Button()
    object ExitAnalysis : Button()
    object Estimate : Button()
    object ExitEstimate : Button()
    object Previous : Button(repeatable = true)
    class Next(enabled: Boolean = true) : Button(repeatable = true, enabled = enabled)
}

sealed interface UserAction {
    class BottomButtonPressed(val button: Button): UserAction
    class BoardCellDragged(val cell: Cell): UserAction
    class BoardCellTapUp(val cell: Cell): UserAction
    object GameInfoClick: UserAction
    object GameInfoDismiss: UserAction
    object RetryDialogDismiss: UserAction
    object RetryDialogRetry: UserAction
    object PassDialogDismiss: UserAction
    object PassDialogConfirm: UserAction
    object ResignDialogDismiss: UserAction
    object ResignDialogConfirm: UserAction
    object CancelDialogDismiss: UserAction
    object CancelDialogConfirm: UserAction
    object GameOverDialogDismiss: UserAction
    object GameOverDialogAnalyze: UserAction
    object GameOverDialogNextGame: UserAction
    object GameOverDialogQuickReplay: UserAction
    object ChatDialogDismiss: UserAction
    object KOMoveDialogDismiss: UserAction
    class ChatSend(val message: String): UserAction
    object OpenInBrowser: UserAction
    object DownloadSGF: UserAction
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
    val whiteStartTimer: String?,
    val blackStartTimer: String?,
)

data class PendingMove(
    val cell: Cell,
    val moveNo: Int,
    val attempt: Int,
)

sealed interface PendingNavigation {
    class NavigateToGame(val game: Game) : PendingNavigation
    class OpenURL(val url: String) : PendingNavigation
}

sealed class Event {
    object PlayStoneSound: Event()
}

data class Variation (
    val rootMoveNo: Int,
    val moves: List<Cell>,
)