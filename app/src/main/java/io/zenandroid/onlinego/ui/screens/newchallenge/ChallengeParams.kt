package io.zenandroid.onlinego.ui.screens.newchallenge

import io.zenandroid.onlinego.data.model.ogs.OGSPlayer
import io.zenandroid.onlinego.data.ogs.TimeControl

data class ChallengeParams(
        var opponent: OGSPlayer? = null,
        var color: String,
        var size: String,
        var handicap: String,
        var speed: String,
        var ranked: Boolean,
        var disable_analysis: Boolean = false,
        var private: Boolean = false,
        var timeControl: TimeControl = TimeControl()
)
