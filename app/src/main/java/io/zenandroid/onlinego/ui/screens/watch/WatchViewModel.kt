package io.zenandroid.onlinego.ui.screens.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.zenandroid.onlinego.data.model.ogs.OGSGame
import io.zenandroid.onlinego.data.ogs.OGSWebSocketService
import io.zenandroid.onlinego.utils.addToDisposable
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class WatchViewModel(private val ogsWebSocketService: OGSWebSocketService) : ViewModel() {

    private val _games = MutableLiveData<List<OGSGame>>()
    val games: LiveData<List<OGSGame>> = _games
    private val subscriptions = CompositeDisposable()

    init {
        ogsWebSocketService.connectionState
            .onEach { connected ->
                if (connected) {
                    fetchGames()
                }
            }
            .launchIn(viewModelScope)

        ogsWebSocketService.ensureSocketConnected()
    }

    private fun fetchGames() {
        ogsWebSocketService.fetchGameList()
            .subscribeOn(Schedulers.io())
            .subscribe(
                { gameList ->
                    val sortedGames = gameList.results.sortedByDescending { game ->
                        val blackRank = game.black?.rank?.toDouble() ?: 0.0
                        val whiteRank = game.white?.rank?.toDouble() ?: 0.0
                        maxOf(blackRank, whiteRank)
                    }
                    _games.postValue(sortedGames)
                },
                { _ -> /* Handle error */ }
            )
            .addToDisposable(subscriptions)
    }

    override fun onCleared() {
        subscriptions.clear()
        super.onCleared()
    }
}