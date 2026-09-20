package com.mossreader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Prefs(
    val hfBase: String = "https://huggingface.co",
    val threads: Int = 4,
    val maxTokens: Int = 60,
    val pauseScale: Float = 1.0f,
    val speed: Float = 1.0f,
    val splitDialogue: Boolean = false,
    val narratorVoice: String = "",
    val dialogueVoice: String = "",
)

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Prefs> = _flow
    val value: Prefs get() = _flow.value

    private fun load() = Prefs(
        hfBase = sp.getString("hfBase", "https://huggingface.co") ?: "https://huggingface.co",
        threads = sp.getInt("threads", Runtime.getRuntime().availableProcessors().coerceIn(2, 4)),
        maxTokens = sp.getInt("maxTokens", 60),
        pauseScale = sp.getFloat("pauseScale", 1f),
        speed = sp.getFloat("speed", 1f),
        splitDialogue = sp.getBoolean("splitDialogue", false),
        narratorVoice = sp.getString("narratorVoice", "") ?: "",
        dialogueVoice = sp.getString("dialogueVoice", "") ?: "",
    )

    fun update(block: (Prefs) -> Prefs) {
        val n = block(_flow.value)
        _flow.value = n
        sp.edit()
            .putString("hfBase", n.hfBase).putInt("threads", n.threads).putInt("maxTokens", n.maxTokens)
            .putFloat("pauseScale", n.pauseScale).putFloat("speed", n.speed).putBoolean("splitDialogue", n.splitDialogue)
            .putString("narratorVoice", n.narratorVoice).putString("dialogueVoice", n.dialogueVoice).apply()
    }
}
