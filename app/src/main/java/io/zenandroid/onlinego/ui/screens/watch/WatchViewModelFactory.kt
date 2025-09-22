package io.zenandroid.onlinego.ui.screens.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.zenandroid.onlinego.data.ogs.OGSWebSocketService

class WatchViewModelFactory(private val ogsWebSocketService: OGSWebSocketService) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WatchViewModel::class.java)) {
            return WatchViewModel(ogsWebSocketService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}