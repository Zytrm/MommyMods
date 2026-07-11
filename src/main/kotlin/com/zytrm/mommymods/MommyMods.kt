package com.zytrm.mommymods

import com.mojang.brigadier.arguments.StringArgumentType
import com.zytrm.mommymods.config.ModConfig
import com.zytrm.mommymods.core.Chat
import com.zytrm.mommymods.feature.FishingPartyHelper
import com.zytrm.mommymods.feature.JawbusFinder
import com.zytrm.mommymods.feature.LouderCatch
import com.zytrm.mommymods.feature.LootingVMessage
import com.zytrm.mommymods.feature.PartyState
import com.zytrm.mommymods.ui.MommyConfigScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object MommyMods : ClientModInitializer {
    const val MOD_ID = "mommymods"
    val logger = LoggerFactory.getLogger("MommyMods")
    @Volatile private var openMenuNextTick = false

    @JvmStatic
    fun requestMenuOpen() {
        openMenuNextTick = true
    }

    override fun onInitializeClient() {
        ModConfig.load()

        ClientReceiveMessageEvents.GAME.register { component, overlay ->
            if (!overlay) {
                val message = component.string
                PartyState.onMessage(message)
                FishingPartyHelper.onMessage(message)
                JawbusFinder.onMessage(message)
                LootingVMessage.onMessage(message)
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val openScreen = {
                requestMenuOpen()
                1
            }
            dispatcher.register(ClientCommands.literal("mommymods").executes { openScreen() })
            dispatcher.register(ClientCommands.literal("mm").executes { openScreen() })
            dispatcher.register(ClientCommands.literal("mmcatchdebug").executes {
                val enabled = LouderCatch.toggleDiagnostics()
                Chat.info("LouderCatch diagnostics ${if (enabled) "enabled" else "disabled"}.")
                1
            })
            dispatcher.register(
                ClientCommands.literal("mmpartydebug")
                    .executes {
                        Chat.info("Use /mmpartydebug self, profile <player>, status, or message.")
                        1
                    }
                    .then(ClientCommands.literal("self").executes {
                        FishingPartyHelper.debugSelf()
                        1
                    })
                    .then(
                        ClientCommands.literal("profile")
                            .then(
                                ClientCommands.argument("player", StringArgumentType.word())
                                    .executes { context ->
                                        FishingPartyHelper.debugInspect(StringArgumentType.getString(context, "player"))
                                        1
                                    },
                            ),
                    )
                    .then(ClientCommands.literal("status").executes {
                        FishingPartyHelper.debugStatus()
                        1
                    })
                    .then(ClientCommands.literal("message").executes {
                        LootingVMessage.debugPreview()
                        1
                    }),
            )
            dispatcher.register(
                ClientCommands.literal("mommy")
                    .then(ClientCommands.literal("mods").executes { openScreen() })
            )
        }

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            LouderCatch.onTick(minecraft)
            FishingPartyHelper.onTick(minecraft)
            LootingVMessage.onTick(minecraft)
            if (openMenuNextTick) {
                openMenuNextTick = false
                if (minecraft.screen !is MommyConfigScreen) {
                    minecraft.setScreen(MommyConfigScreen(minecraft.screen))
                }
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { ModConfig.save() }
        logger.info("MommyMods initialized")
    }
}
