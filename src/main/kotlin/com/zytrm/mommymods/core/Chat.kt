package com.zytrm.mommymods.core

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

object Chat {
    private fun prefix(): MutableComponent = Component.literal("[MommyMods] ")
        .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)

    fun info(message: String) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().gui.chat.addClientSystemMessage(
                prefix().append(Component.literal(message).withStyle(ChatFormatting.GRAY))
            )
        }
    }

    fun component(component: Component) {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().gui.chat.addClientSystemMessage(prefix().append(component))
        }
    }
}
