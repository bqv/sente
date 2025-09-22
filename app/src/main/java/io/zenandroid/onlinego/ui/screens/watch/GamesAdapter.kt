package io.zenandroid.onlinego.ui.screens.watch

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.zenandroid.onlinego.data.model.ogs.OGSGame
import io.zenandroid.onlinego.data.model.ogs.OGSPlayer
import io.zenandroid.onlinego.databinding.ItemGameBinding
import io.zenandroid.onlinego.gamelogic.Util

class GamesAdapter(var games: List<OGSGame>, private val clickListener: (OGSGame) -> Unit) : RecyclerView.Adapter<GamesAdapter.GameViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        holder.bind(games[position], clickListener)
    }

    override fun getItemCount() = games.size

    class GameViewHolder(private val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(game: OGSGame, clickListener: (OGSGame) -> Unit) {
            binding.gameName.text = game.name ?: "Unnamed Game"
            val blackPlayer = game.black?.username ?: "Unknown"
            val whitePlayer = game.white?.username ?: "Unknown"
            val blackRank = Util.formatRank(Util.egfToRank(game.black?.rank?.toDouble()), 0.0)
            val whiteRank = Util.formatRank(Util.egfToRank(game.white?.rank?.toDouble()), 0.0)
            binding.gamePlayers.text = "$blackPlayer [$blackRank] vs $whitePlayer [$whiteRank]"
            binding.root.setOnClickListener { clickListener(game) }
        }
    }
}