package com.zytrm.mommymods.config

import com.google.gson.GsonBuilder
import com.zytrm.mommymods.MommyMods
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val DEFAULT_LOOTING_V_MESSAGE = "Please do not kill my Jawbus unless you have Looting V."

data class MommySettings(
    var hideFishingLine: Boolean = true,
    var louderCatch: Boolean = true,
    var catchSound: String = "Experience",
    var catchVolume: Float = 4.0f,
    var catchPitch: Float = 1.0f,
    var fishingPartyHelper: Boolean = true,
    var autoKick: Boolean = false,
    var kickNoLootingV: Boolean = true,
    var kickCantJawbus: Boolean = true,
    var jawbusFinder: Boolean = true,
    var deathMessageDetection: Boolean = true,
    var lootingVMessageEnabled: Boolean = true,
    var lootingVMessage: String = DEFAULT_LOOTING_V_MESSAGE,
)

object ModConfig {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path = FabricLoader.getInstance().configDir.resolve("mommymods.json")

    @Volatile
    var values = MommySettings()
        private set

    fun load() {
        values = runCatching {
            if (!Files.exists(path)) MommySettings()
            else Files.newBufferedReader(path).use { gson.fromJson(it, MommySettings::class.java) ?: MommySettings() }
        }.onFailure { MommyMods.logger.warn("Could not load configuration", it) }
            .getOrElse { MommySettings() }
        if (values.lootingVMessage.isBlank()) values.lootingVMessage = DEFAULT_LOOTING_V_MESSAGE
        save()
    }

    @Synchronized
    fun save() {
        runCatching {
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary).use { gson.toJson(values, it) }
            runCatching {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { MommyMods.logger.warn("Could not save configuration", it) }
    }
}
