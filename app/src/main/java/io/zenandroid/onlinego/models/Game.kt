package io.zenandroid.onlinego.models

data class Game(
    val id: String,
    val name: String,
    val players: List<String>,
    val ranked: Boolean,
    val handicap: Int
)