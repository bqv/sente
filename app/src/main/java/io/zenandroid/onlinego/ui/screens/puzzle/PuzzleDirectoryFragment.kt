package io.zenandroid.onlinego.ui.screens.puzzle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.data.model.local.PuzzleCollection
import io.zenandroid.onlinego.ui.theme.OnlineGoTheme
import io.zenandroid.onlinego.utils.PersistenceManager
import io.zenandroid.onlinego.utils.analyticsReportScreen
import io.zenandroid.onlinego.utils.rememberStateWithLifecycle
import org.koin.androidx.viewmodel.ext.android.viewModel

private const val TAG = "PuzzleDirectoryFragment"

class PuzzleDirectoryFragment : Fragment() {
    private val viewModel: PuzzleDirectoryViewModel by viewModel()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                DisposeOnLifecycleDestroyed(viewLifecycleOwner)
            )
            setContent {
                OnlineGoTheme {
                    val state by rememberStateWithLifecycle(viewModel.state)

                    PuzzleDirectoryScreen(
                        state = state,
                        onCollection = ::navigateToCollectionScreen,
                        onBack = { findNavController().navigateUp() },
                        onSortChanged = { viewModel.onSortChanged(it) },
                        onFilterChanged = { viewModel.onFilterChanged(it) },
                        onToggleOnlyOpened = { viewModel.onToggleOnlyOpened() },
                    )
                }
            }
        }
    }

    private fun navigateToCollectionScreen(collection: PuzzleCollection) {
        findNavController().navigate(
            R.id.puzzleSetFragment,
            bundleOf(
                COLLECTION_ID to collection.id,
            ),
            NavOptions.Builder()
                .setLaunchSingleTop(true)
                .build()
        )
    }

    override fun onResume() {
        super.onResume()
        analyticsReportScreen("PuzzleDirectory")
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        PersistenceManager.visitedPuzzleDirectory = true
    }

}
