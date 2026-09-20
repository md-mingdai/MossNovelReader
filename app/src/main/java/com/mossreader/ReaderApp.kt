package com.mossreader

import android.app.Application
import com.mossreader.data.BookRepository
import com.mossreader.data.SettingsStore
import com.mossreader.playback.ReaderController
import com.mossreader.tts.ModelStore
import com.mossreader.tts.VoiceStore

class ReaderApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var models: ModelStore
    lateinit var voices: VoiceStore
    lateinit var books: BookRepository
    lateinit var controller: ReaderController

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        models = ModelStore(this)
        voices = VoiceStore(this)
        books = BookRepository(this)
        controller = ReaderController(this, models, books, voices, settings)
    }

    companion object {
        lateinit var instance: ReaderApp
            private set
    }
}
