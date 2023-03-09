package io.zenandroid.onlinego.ui.screens.face2face

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import app.cash.molecule.AndroidUiDispatcher
import app.cash.molecule.RecompositionClock.ContextClock
import app.cash.molecule.launchMolecule
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.data.model.BoardTheme
import io.zenandroid.onlinego.data.model.BoardTheme.WOOD
import io.zenandroid.onlinego.data.model.Cell
import io.zenandroid.onlinego.data.model.Position
import io.zenandroid.onlinego.data.model.StoneType.BLACK
import io.zenandroid.onlinego.data.model.StoneType.WHITE
import io.zenandroid.onlinego.data.repositories.SettingsRepository
import io.zenandroid.onlinego.gamelogic.RulesManager
import io.zenandroid.onlinego.ui.composables.BottomBarButton
import io.zenandroid.onlinego.ui.screens.face2face.Action.BoardCellDragged
import io.zenandroid.onlinego.ui.screens.face2face.Action.BoardCellTapUp
import io.zenandroid.onlinego.ui.screens.face2face.Action.BottomButtonPressed
import io.zenandroid.onlinego.ui.screens.face2face.Action.KOMoveDialogDismiss
import io.zenandroid.onlinego.ui.screens.face2face.Action.NewGameDialogDismiss
import io.zenandroid.onlinego.ui.screens.face2face.Button.CloseEstimate
import io.zenandroid.onlinego.ui.screens.face2face.Button.Estimate
import io.zenandroid.onlinego.ui.screens.face2face.Button.GameSettings
import io.zenandroid.onlinego.ui.screens.face2face.Button.Next
import io.zenandroid.onlinego.ui.screens.face2face.Button.Previous
import io.zenandroid.onlinego.ui.screens.face2face.EstimateStatus.Idle
import io.zenandroid.onlinego.ui.screens.face2face.EstimateStatus.Success
import io.zenandroid.onlinego.ui.screens.face2face.EstimateStatus.Working
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.annotation.concurrent.Immutable

private const val STATE_KEY = "FACE_TO_FACE_STATE_KEY"

class FaceToFaceViewModel(
  private val settingsRepository: SettingsRepository,
  testing: Boolean = false
) : ViewModel() {

  private val moleculeScope =
    if (testing) viewModelScope else CoroutineScope(viewModelScope.coroutineContext + AndroidUiDispatcher.Main)

  private var loading by mutableStateOf(true)
  private var currentPosition by mutableStateOf(Position(19, 19))
  private var candidateMove by mutableStateOf<Cell?>(null)
  private var history by mutableStateOf<List<Cell>>(emptyList())
  private var historyIndex by mutableStateOf<Int?>(null)
  private var koMoveDialogShowing by mutableStateOf(false)
  private var gameFinished by mutableStateOf<Boolean?>(null)
  private var estimateStatus by mutableStateOf<EstimateStatus>(Idle)
  private var newGameDialogShowing by mutableStateOf(false)

  private val prefs = PreferenceManager.getDefaultSharedPreferences(OnlineGoApplication.instance.baseContext)

  val state: StateFlow<FaceToFaceState> =
    if (testing) MutableStateFlow(FaceToFaceState.INITIAL)
    else moleculeScope.launchMolecule(clock = ContextClock) {
      Molecule()
    }

  @Composable
  fun Molecule(): FaceToFaceState {
    LaunchedEffect(null) {
      loadSavedData()
    }

    val historyIndex = historyIndex
    val title = when {
      loading -> "Face to face · Loading"
      gameFinished == true -> "Face to face · Game Over"
      currentPosition.nextToMove == WHITE -> "Face to face · White's turn"
      currentPosition.nextToMove == BLACK -> "Face to face · Black's turn"
      else -> "Face to face"
    }

    val estimateStatus = estimateStatus
    val position = when {
      estimateStatus is Success -> estimateStatus.result
      else -> currentPosition
    }

    val previousButtonEnabled = history.isNotEmpty() && (historyIndex == null || historyIndex >= 0)
    val nextButtonEnabled = history.isNotEmpty() && historyIndex != null && historyIndex < history.size

    val (buttons, bottomText) = when {
      estimateStatus is Working -> emptyList<Button>() to "Estimating"
      estimateStatus is Success -> listOf(CloseEstimate) to null
      else -> listOf(
        GameSettings, Estimate, Previous(previousButtonEnabled), Next(nextButtonEnabled)
      ) to null
    }

    return FaceToFaceState(
      loading = loading,
      position = position,
      title = title,
      gameWidth = 19,
      gameHeight = 19,
      handicap = 0,
      gameFinished = false,
      history = history,
      boardInteractive = true,
      candidateMove = candidateMove,
      boardTheme = settingsRepository.boardTheme,
      showCoordinates = settingsRepository.showCoordinates,
      drawTerritory = estimateStatus is Success,
      fadeOutRemovedStones = false,
      showLastMove = true,
      koMoveDialogShowing = koMoveDialogShowing,
      buttons = buttons,
      bottomText = bottomText,
      newGameDialogShowing = newGameDialogShowing,
    )
  }

  private suspend fun loadSavedData() {
    if(prefs.contains(STATE_KEY)) {
      val historyString = withContext(Dispatchers.IO) {
        prefs.getString(STATE_KEY, "")
      }
      historyString?.let {
        history = it.split(" ")
          .filter { it.isNotEmpty() }
          .map {
            val parts = it.split(",")
            Cell(parts[0].toInt(), parts[1].toInt())
          }
        currentPosition = historyPosition(history.lastIndex)
      }
    }
    loading = false
  }

  override fun onCleared() {
    prefs.edit().putString(STATE_KEY, history.joinToString(separator = " ") { "${it.x},${it.y}" }).apply()
    super.onCleared()
  }

  fun onAction(action: Action) {
    when (action) {
      is BoardCellDragged -> candidateMove = action.cell
      is BoardCellTapUp -> onCellTapUp(action.cell)
      KOMoveDialogDismiss -> koMoveDialogShowing = false
      is BottomButtonPressed -> onButtonPressed(action.button)
      NewGameDialogDismiss -> newGameDialogShowing = false
    }
  }

  private fun onButtonPressed(button: Button) {
    when(button) {
      is Estimate -> onEstimatePressed()
      is GameSettings -> newGameDialogShowing = true
      is Next -> onNextPressed()
      is Previous -> onPreviousPressed()
      is CloseEstimate -> estimateStatus = Idle
    }
  }

  private fun onEstimatePressed() {
    estimateStatus = Working
    viewModelScope.launch(Dispatchers.IO) {
      val estimate = RulesManager.determineTerritory(currentPosition, false)
      withContext(Dispatchers.Main) {
        estimateStatus = Success(estimate)
      }
    }
  }

  private fun onPreviousPressed() {
    val newIndex = historyIndex?.minus(1) ?: (history.lastIndex - 1)
    val newPos = historyPosition(newIndex)
    historyIndex = newIndex
    currentPosition = newPos
  }

  private fun onNextPressed() {
    val newIndex = historyIndex?.plus(1) ?: history.lastIndex
    val newPos = historyPosition(newIndex)
    historyIndex = if(newIndex < history.lastIndex) newIndex else null
    currentPosition = newPos
  }

  private fun historyPosition(index: Int) =
    RulesManager.buildPos(
      moves = history.subList(0, index + 1),
      boardWidth = currentPosition.boardWidth,
      boardHeight = currentPosition.boardHeight,
      handicap = currentPosition.handicap
    )!!

  private fun onCellTapUp(cell: Cell) {
    viewModelScope.launch {
      val pos = currentPosition
      val newPosition = RulesManager.makeMove(pos, pos.nextToMove, cell)
      if(newPosition != null) {
        val index = historyIndex ?: history.lastIndex
        val potentialKOPosition = if(index > 0) {
          historyPosition(index - 1)
        } else null
        if(potentialKOPosition?.hasTheSameStonesAs(newPosition) == true) {
          koMoveDialogShowing = true
        } else {
          currentPosition = newPosition
          history = history.subList(0, index + 1) + cell
          historyIndex = null
        }
      }
      candidateMove = null
    }
  }
}

@Immutable
data class FaceToFaceState(
  val position: Position?,
  val loading: Boolean,
  val title: String,
  val buttons: List<Button>,
  val bottomText: String?,
  val gameWidth: Int,
  val gameHeight: Int,
  val handicap: Int,
  val gameFinished: Boolean,
  val history: List<Cell>,
  val candidateMove: Cell?,
  val boardInteractive: Boolean,
  val boardTheme: BoardTheme,
  val showCoordinates: Boolean,
  val drawTerritory: Boolean,
  val fadeOutRemovedStones: Boolean,
  val showLastMove: Boolean,
  val koMoveDialogShowing: Boolean,
  val newGameDialogShowing: Boolean,
) {
  companion object {
    val INITIAL = FaceToFaceState(
      loading = true,
      title = "Face to face · Loading",
      position = Position(19, 19),
      gameWidth = 19,
      gameHeight = 19,
      handicap = 0,
      gameFinished = false,
      history = emptyList(),
      boardInteractive = true,
      candidateMove = null,
      boardTheme = WOOD,
      showCoordinates = true,
      drawTerritory = false,
      fadeOutRemovedStones = false,
      showLastMove = true,
      koMoveDialogShowing = false,
      buttons = emptyList(),
      bottomText = null,
      newGameDialogShowing = false,
    )
  }
}

sealed class Button(
  override val icon: ImageVector,
  override val label: String,
  override val repeatable: Boolean = false,
  override val enabled: Boolean = true,
  override val bubbleText: String? = null,
  override val highlighted: Boolean = false,
) : BottomBarButton {
  object GameSettings : Button(Icons.Rounded.Tune, "Game Settings")
  object Estimate : Button(Icons.Rounded.Functions, "Auto-score")
  class Previous(enabled: Boolean = true) : Button(repeatable = true, enabled = enabled, icon = Icons.Rounded.SkipPrevious, label = "Previous")
  class Next(enabled: Boolean = true) : Button(repeatable = true, enabled = enabled, icon = Icons.Rounded.SkipNext, label = "Next")
  object CloseEstimate : Button(Icons.Rounded.HighlightOff, "Return")
}

sealed interface Action {
  class BoardCellDragged(val cell: Cell) : Action
  class BoardCellTapUp(val cell: Cell) : Action
  class BottomButtonPressed(val button: Button) : Action
  object KOMoveDialogDismiss: Action
  object NewGameDialogDismiss: Action
}

sealed interface EstimateStatus {
  object Idle: EstimateStatus
  object Working: EstimateStatus
  data class Success(val result: Position): EstimateStatus
}