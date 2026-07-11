package com.zytrm.mommymods.core

import net.minecraft.client.Minecraft

object GameContext {
    fun isOnHypixel(): Boolean {
        val address = Minecraft.getInstance().currentServer?.ip ?: return false
        return address.substringBefore(':').lowercase().let {
            it == "hypixel.net" || it.endsWith(".hypixel.net")
        }
    }
}
