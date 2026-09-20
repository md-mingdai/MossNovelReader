package com.mossreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class Screen { LIBRARY, READER, SETTINGS, VOICES }

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.LIBRARY) }
    var from by remember { mutableStateOf(Screen.LIBRARY) }
    val app = com.mossreader.ReaderApp.instance

    fun go(s: Screen) { from = screen; screen = s }

    BackHandler(enabled = screen != Screen.LIBRARY) {
        screen = when (screen) {
            Screen.VOICES -> if (from == Screen.READER) Screen.READER else Screen.SETTINGS
            Screen.SETTINGS -> if (from == Screen.READER) Screen.READER else Screen.LIBRARY
            else -> Screen.LIBRARY
        }
    }

    when (screen) {
        Screen.LIBRARY -> LibraryScreen(
            onOpen = { id -> app.controller.openBook(id); go(Screen.READER) },
            onResume = { go(Screen.READER) },
            onSettings = { go(Screen.SETTINGS) },
        )
        Screen.READER -> ReaderScreen(onBack = { screen = Screen.LIBRARY }, onSettings = { go(Screen.SETTINGS) }, onVoices = { go(Screen.VOICES) })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = if (from == Screen.READER) Screen.READER else Screen.LIBRARY }, onVoices = { go(Screen.VOICES) })
        Screen.VOICES -> VoiceScreen(onBack = { screen = if (from == Screen.READER) Screen.READER else Screen.SETTINGS }, onSettings = { go(Screen.SETTINGS) })
    }
}
