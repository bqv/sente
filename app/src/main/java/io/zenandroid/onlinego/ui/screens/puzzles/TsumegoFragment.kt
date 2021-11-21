package io.zenandroid.onlinego.ui.screens.puzzle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.GridCells
import androidx.compose.foundation.lazy.LazyVerticalGrid
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.accompanist.pager.*
import com.jakewharton.rxbinding2.view.RxView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.movement.MovementMethodPlugin
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.utils.showIf
import io.zenandroid.onlinego.ui.composables.Board
import io.zenandroid.onlinego.ui.composables.RatingBar
import io.zenandroid.onlinego.ui.screens.main.MainActivity
import io.zenandroid.onlinego.ui.screens.puzzle.TsumegoAction.*
import io.zenandroid.onlinego.ui.theme.OnlineGoTheme
import io.zenandroid.onlinego.data.model.StoneType
import io.zenandroid.onlinego.data.model.ogs.Puzzle
import io.zenandroid.onlinego.data.model.ogs.PuzzleCollection
import io.zenandroid.onlinego.mvi.MviView
import io.zenandroid.onlinego.data.repositories.SettingsRepository
import io.zenandroid.onlinego.utils.PersistenceManager
import io.zenandroid.onlinego.utils.convertCountryCodeToEmojiFlag
import io.zenandroid.onlinego.utils.nullIfEmpty
import org.commonmark.node.*
import org.koin.android.ext.android.inject
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.threeten.bp.Instant.now
import org.threeten.bp.temporal.ChronoUnit.*
import org.koin.core.parameter.parametersOf
import androidx.compose.runtime.getValue

const val PUZZLE_ID = "PUZZLE_ID"

private const val TAG = "TsumegoFragment"

class TsumegoFragment : Fragment(), MviView<TsumegoState, TsumegoAction> {
    private val settingsRepository: SettingsRepository by inject()
    private val viewModel: TsumegoViewModel by viewModel {
        parametersOf(arguments!!.getLong(PUZZLE_ID))
    }

    private val internalActions = PublishSubject.create<TsumegoAction>()
    private var currentState: TsumegoState? = null

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            internalActions.onNext(UserPressedBack)
            findNavController().navigateUp()
        }
    }

    @Composable
    private fun Header(text: String) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colors.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
        )
    }

    @ExperimentalAnimationApi
    @ExperimentalMaterialApi
    @ExperimentalFoundationApi
    @ExperimentalPagerApi
    @ExperimentalComposeUiApi
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                DisposeOnLifecycleDestroyed(viewLifecycleOwner)
            )
            setContent {
                OnlineGoTheme {
                    val state by viewModel.state.observeAsState()

                    Column (
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        val titleState = remember {
                            val base = "Tsumego"
                            derivedStateOf {
                                state?.puzzle?.name?.let {
                                    "${base}: ${it}"
                                } ?: base
                            }
                        }
                        TopAppBar(
                            title = {
                                Text(
                                    text = titleState.value,
                                    fontSize = 18.sp
                                )
                            },
                            elevation = 1.dp,
                            navigationIcon = {
                                IconButton(onClick = { findNavController().navigateUp() }) {
                                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                                }
                            },
                            backgroundColor = MaterialTheme.colors.surface
                        )

                        state?.puzzle?.let {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val listener = { action: TsumegoAction ->
                                    Toast.makeText(requireContext(), action.toString(), Toast.LENGTH_SHORT).show()
                                    internalActions.onNext(action)
                                }
                                Column(modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 10.dp)) {
                                    it.puzzle.let {
                                        Board(
                                            boardWidth = it.width,
                                            boardHeight = it.height,
                                            position = state!!.boardPosition,
                                            drawCoordinates = settingsRepository.showCoordinates,
                                            interactive = true,
                                            drawShadow = false,
                                            fadeInLastMove = false,
                                            fadeOutRemovedStones = false,
                                            removedStones = state!!.removedStones,
                                            candidateMove = state!!.hoveredCell,
                                            candidateMoveType = StoneType.BLACK,
                                            onTapMove = { if (state!!.boardInteractive) listener(BoardCellHovered(it)) },
                                            onTapUp = { if (state!!.boardInteractive) viewModel.makeMove(it) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp)
                                                .shadow(6.dp, MaterialTheme.shapes.large)
                                                .clip(MaterialTheme.shapes.small)
                                        )
                                        Row {
                                            val hasNextState = remember {
                                                viewModel.hasNextPuzzle
                                            }
                                            val hasPreviousState = remember {
                                                viewModel.hasPreviousPuzzle
                                            }
                                            if(hasPreviousState.value == true) {
                                                Row(modifier = Modifier.weight(1f)) {
                                                    Image(painter = painterResource(R.drawable.ic_navigate_previous),
                                                        modifier = Modifier
                                                            .align(Alignment.CenterVertically)
                                                            .padding(start = 18.dp),
                                                        contentDescription = null
                                                    )
                                                    TextButton(onClick = { viewModel.previousPuzzle() },
                                                            modifier = Modifier
                                                                .align(Alignment.CenterVertically)
                                                                .padding(all = 4.dp)) {
                                                        Text("PREVIOUS", color = MaterialTheme.colors.secondary, fontWeight = FontWeight.Bold)
                                                    }
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }

                                            if(hasNextState.value == true) {
                                                Row(modifier = Modifier.weight(1f)) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                    TextButton(onClick = { viewModel.nextPuzzle() },
                                                            modifier = Modifier
                                                                .align(Alignment.CenterVertically)
                                                                .padding(all = 4.dp)) {
                                                        Text("NEXT", color = MaterialTheme.colors.secondary, fontWeight = FontWeight.Bold)
                                                    }
                                                    Image(painter = painterResource(R.drawable.ic_navigate_next),
                                                        modifier = Modifier
                                                            .align(Alignment.CenterVertically)
                                                            .padding(start = 18.dp),
                                                        contentDescription = null
                                                    )
                                                }
                                            }
                                        }
                                        var boxState = rememberScrollState()
                                        Box(modifier = Modifier.verticalScroll(state = boxState)
                                                .weight(1f)) {
                                            Text(
                                                text = state!!.nodeStack.let { stack ->
                                                    stack.lastOrNull()?.text
                                                        ?: stack.dropLast(1).lastOrNull()?.text
                                                } ?: it.puzzle_description,
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.body2,
                                                fontSize = 16.sp,
                                                color = MaterialTheme.colors.onSurface,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        Box {
                                            Row(horizontalArrangement = Arrangement.SpaceAround,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 16.dp)) {
                                                if (state!!.retryButtonVisible) {
                                                    OutlinedButton(
                                                            onClick = { viewModel.resetPuzzle() },
                                                            modifier = Modifier.weight(1f)) {
                                                        Icon(imageVector = Icons.Filled.Refresh,
                                                            tint = MaterialTheme.colors.onSurface,
                                                            modifier = Modifier.size(16.dp),
                                                            contentDescription = null)
                                                        Text(text = "RETRY",
                                                            color = MaterialTheme.colors.onSurface,
                                                            modifier = Modifier.padding(start = 8.dp))
                                                    }
                                                }
                                                if (state!!.continueButtonVisible) {
                                                    Button(onClick = { viewModel.nextPuzzle() },
                                                            modifier = Modifier.weight(1f)) {
                                                        Text(text = "CONTINUE")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } ?: run {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "Loading...",
                                    color = MaterialTheme.colors.onBackground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override val actions: Observable<TsumegoAction>
        get() =
            Observable.merge(
                listOf(
                        internalActions
                )
            ).startWith(ViewReady)

    override fun render(state: TsumegoState) {
        currentState = state
    }

    private fun navigateToNextTsumegoScreen(puzzle: Puzzle) {
        Toast.makeText(requireContext(), "${puzzle.id}", Toast.LENGTH_LONG).show()
        findNavController().navigate(
            R.id.tsumegoFragment,
            bundleOf(
                PUZZLE_ID to puzzle.id,
            ),
            NavOptions.Builder()
                .setLaunchSingleTop(true)
                .build()
        )
    }

    override fun onPause() {
        viewModel.unbind()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
      //analytics.setCurrentScreen(requireActivity(), javaClass.simpleName, null)
        viewModel.bind(this)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        requireActivity().onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }
}
