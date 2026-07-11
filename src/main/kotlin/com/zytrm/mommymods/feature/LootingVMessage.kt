package com.zytrm.mommymods.feature

import com.zytrm.mommymods.MommyMods
import com.zytrm.mommymods.config.ModConfig
import com.zytrm.mommymods.core.Chat
import com.zytrm.mommymods.core.GameContext
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component

object LootingVMessage {
    private val localJawbusSpawn = Regex(
        "^You have angered a legendary creature\\.\\.\\. (?:Lord )?Jawbus has arrived\\.?$",
        RegexOption.IGNORE_CASE,
    )
    private const val SPAWN_DEBOUNCE_MILLIS = 30_000L
    private const val MAX_CHAT_LENGTH = 256

    private var trackedLevel: ClientLevel? = null
    private var lastSentAt = 0L

    fun onMessage(message: String) {
        val settings = ModConfig.values
        if (!settings.lootingVMessageEnabled || !GameContext.isOnHypixel()) return
        if (!localJawbusSpawn.matches(message)) return

        val now = System.currentTimeMillis()
        if (now - lastSentAt < SPAWN_DEBOUNCE_MILLIS) return
        val outgoing = configuredMessage() ?: return
        val connection = Minecraft.getInstance().connection ?: return

        runCatching { connection.sendChat(outgoing) }
            .onSuccess { lastSentAt = now }
            .onFailure { MommyMods.logger.warn("Could not send the configured Jawbus message", it) }
    }

    fun onTick(minecraft: Minecraft) {
        val level = minecraft.level
        if (level !== trackedLevel) {
            trackedLevel = level
            lastSentAt = 0L
        }
    }

    fun debugPreview() {
        val outgoing = configuredMessage()
        Chat.info(
            "Looting V Message: enabled=${ModConfig.values.lootingVMessageEnabled}, " +
                "spawnPattern=valid, debounce=${SPAWN_DEBOUNCE_MILLIS / 1000}s.",
        )
        if (outgoing == null) {
            Chat.info("Preview unavailable: configure a non-empty message that does not begin with '/'.")
            return
        }
        Chat.component(
            Component.literal("Preview only - no chat was sent: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(outgoing).withStyle(ChatFormatting.WHITE)),
        )
    }

    private fun configuredMessage(): String? {
        val message = ModConfig.values.lootingVMessage
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(MAX_CHAT_LENGTH)
        if (message.isBlank() || message.startsWith('/')) return null
        return message
    }
}
