package io.zenandroid.onlinego.ui.screens.watch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.data.model.ogs.OGSGame
import io.zenandroid.onlinego.databinding.FragmentWatchBinding
import io.zenandroid.onlinego.ui.screens.game.GAME_ID
import io.zenandroid.onlinego.ui.screens.game.GAME_WIDTH
import io.zenandroid.onlinego.ui.screens.game.GAME_HEIGHT
import io.zenandroid.onlinego.ui.screens.main.MainActivity
import org.koin.android.ext.android.get

class WatchFragment : Fragment(), (OGSGame) -> Unit {

    private var _binding: FragmentWatchBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: WatchViewModel
    private lateinit var gamesAdapter: GamesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this, WatchViewModelFactory(get())).get(WatchViewModel::class.java)

        binding.gamesRecyclerView.layoutManager = LinearLayoutManager(context)
        gamesAdapter = GamesAdapter(emptyList(), this)
        binding.gamesRecyclerView.adapter = gamesAdapter

        viewModel.games.observe(viewLifecycleOwner) { games ->
            gamesAdapter.games = games ?: emptyList()
            gamesAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun invoke(game: OGSGame) {
        val bundle = Bundle()
        bundle.putLong(GAME_ID, game.id)
        bundle.putInt(GAME_WIDTH, game.width)
        bundle.putInt(GAME_HEIGHT, game.height)
        bundle.putBoolean("isObserveGame", true)
        findNavController().navigate(R.id.gameFragment, bundle)
    }
}