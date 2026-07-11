package com.zytrm.mommymods.feature

import com.zytrm.mommymods.config.ModConfig
import com.zytrm.mommymods.core.Chat
import com.zytrm.mommymods.core.GameContext
import com.zytrm.mommymods.model.FishingReadiness
import com.zytrm.mommymods.network.HypixelProfileClient
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object FishingPartyHelper {
    private const val SCAN_TICKS = 160L
    private val validPlayerName = Regex("^[A-Za-z0-9_]{1,16}$")
    private val joinedParty = Regex("^(?:\\[[^]]+] )?(\\w{1,16}) joined the party\\.$")
    private val lootingV = Regex("\\bLooting\\s+V\\b", RegexOption.IGNORE_CASE)
    private val pendingApi = ConcurrentHashMap.newKeySet<String>()
    private val pendingScans = ConcurrentHashMap<String, PendingScan>()
    private var clientTick = 0L

    private data class PendingScan(
        val name: String,
        val startedAt: Long,
        var observedInWorld: Boolean = false,
        var lootingWeapon: String? = null,
        var hasLootingV: Boolean? = null,
    )

    fun onMessage(message: String) {
        if (!ModConfig.values.fishingPartyHelper || !GameContext.isOnHypixel()) return
        val name = joinedParty.matchEntire(message)?.groupValues?.get(1) ?: return
        if (name.equals(Minecraft.getInstance().user.name, ignoreCase = true)) return

        inspectProfile(name)
    }

    fun onTick(minecraft: Minecraft) {
        clientTick++
        if (!ModConfig.values.fishingPartyHelper || !GameContext.isOnHypixel()) {
            pendingScans.clear()
            return
        }

        val level = minecraft.level ?: return
        pendingScans.entries.toList().forEach { (key, scan) ->
            findPlayer(level, scan.name)?.let { observe(minecraft, level, it, scan) }
            if (clientTick - scan.startedAt >= SCAN_TICKS) {
                pendingScans.remove(key)
                val readiness = scan.toReadiness()
                show(readiness)
                maybeKick(readiness)
            }
        }
    }

    private fun inspectProfile(name: String) {
        val key = name.lowercase()
        if (!pendingApi.add(key)) return
        HypixelProfileClient.inspect(name).whenComplete { readiness, throwable ->
            pendingApi.remove(key)
            Minecraft.getInstance().execute {
                if (throwable != null) {
                    startInGameScan(name)
                } else {
                    show(readiness)
                    maybeKick(readiness)
                }
            }
        }
    }

    fun debugInspect(name: String) {
        if (!validPlayerName.matches(name)) {
            Chat.info("Debug lookup rejected: player names must be 1-16 letters, numbers, or underscores.")
            return
        }
        val diagnostics = CopyOnWriteArrayList<HypixelProfileClient.Diagnostic>()
        HypixelProfileClient.inspect(
            name,
            diagnostics = { diagnostics.add(it) },
            bypassCache = true,
        ).whenComplete { readiness, throwable ->
            Minecraft.getInstance().execute {
                val path = diagnostics.joinToString(" > ") { "${it.stage}:${it.status}" }
                Chat.info("Debug $name: ${path.ifBlank { "no diagnostics" }}")
                if (throwable == null) {
                    show(readiness)
                    return@execute
                }

                val cause = generateSequence(throwable) { it.cause }.last()
                val classified = cause as? HypixelProfileClient.LookupException
                val kind = classified?.kind?.name ?: "UNCLASSIFIED"
                val stage = classified?.stage ?: "unknown"
                Chat.info("Debug failed: $kind at $stage — ${cause.message ?: "unknown error"}")
                showDebugFallback(name)
            }
        }
    }

    fun debugSelf() {
        debugInspect(Minecraft.getInstance().user.name)
    }

    fun debugStatus() {
        Chat.info(
            "Party helper status: enabled=${ModConfig.values.fishingPartyHelper}, " +
                "hypixel=${GameContext.isOnHypixel()}, pendingProfiles=${pendingApi.size}, " +
                "pendingGearScans=${pendingScans.size}, ${HypixelProfileClient.statusSummary()}.",
        )
    }

    private fun showDebugFallback(name: String) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = level?.let { findPlayer(it, name) }
        if (level == null || player == null) {
            Chat.info("Fallback unavailable: player not loaded.")
            return
        }

        val scan = PendingScan(name, clientTick)
        observe(minecraft, level, player, scan)
        Chat.info("Fallback: visible held/equipped gear.")
        show(scan.toReadiness())
    }

    private fun startInGameScan(name: String) {
        val key = name.lowercase()
        pendingScans.putIfAbsent(key, PendingScan(name, clientTick))
    }

    private fun findPlayer(level: ClientLevel, name: String): AbstractClientPlayer? =
        level.players().firstOrNull { it.scoreboardName.equals(name, ignoreCase = true) }

    private fun observe(
        minecraft: Minecraft,
        level: ClientLevel,
        player: AbstractClientPlayer,
        scan: PendingScan,
    ) {
        scan.observedInWorld = true
        visibleEquipment(player).filterNot { it.isEmpty }.forEach { stack ->
            val text = itemText(minecraft, level, stack)
            val weapon = when {
                text.contains("hyperion", ignoreCase = true) -> "Hyperion"
                text.contains("flaming flay", ignoreCase = true) -> "Flaming Flay"
                else -> null
            }
            if (weapon != null) {
                val hasEnchant = lootingV.containsMatchIn(text)
                scan.lootingWeapon = weapon
                scan.hasLootingV = scan.hasLootingV == true || hasEnchant
            }
        }
    }

    private fun visibleEquipment(player: AbstractClientPlayer): List<ItemStack> = listOf(
        player.mainHandItem,
        player.offhandItem,
        player.getItemBySlot(EquipmentSlot.HEAD),
        player.getItemBySlot(EquipmentSlot.CHEST),
        player.getItemBySlot(EquipmentSlot.LEGS),
        player.getItemBySlot(EquipmentSlot.FEET),
    )

    private fun itemText(minecraft: Minecraft, level: ClientLevel, stack: ItemStack): String = buildString {
        appendLine(stack.hoverName.string)
        runCatching {
            stack.getTooltipLines(Item.TooltipContext.of(level), minecraft.player, TooltipFlag.NORMAL)
        }.getOrDefault(emptyList()).forEach { appendLine(it.string) }
    }

    private fun PendingScan.toReadiness() = FishingReadiness(
        name = name,
        profileName = "In-game",
        fishingLevel = null,
        silverTrophyHunter = null,
        inventoryAvailable = observedInWorld,
        lootingWeapon = lootingWeapon,
        lootingV = hasLootingV,
        beltCheckAvailable = false,
        fishingBelt = null,
        bloodshotBelt = null,
        observedInWorld = observedInWorld,
    )

    private data class SummaryValue(val label: String, val value: String, val state: CheckState)

    private fun show(data: FishingReadiness) {
        Chat.component(
            Component.literal(data.name).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal(" · ${data.profileName}").withStyle(ChatFormatting.DARK_GRAY)),
        )
        Chat.component(
            summaryLine(
                SummaryValue(
                    "Fish 45+",
                    data.fishingLevel?.toString() ?: "?",
                    state(data.fishingLevel?.let { it >= 45 }),
                ),
                SummaryValue("Silver", yesNo(data.silverTrophyHunter), state(data.silverTrophyHunter)),
                SummaryValue("Jawbus", yesNo(data.canJawbus), state(data.canJawbus)),
            ),
        )

        val looting = when {
            data.hasLootingV == true -> "Yes (${data.lootingWeapon ?: "weapon"})"
            data.hasLootingV == false && data.lootingWeapon != null -> "No (${data.lootingWeapon})"
            data.hasLootingV == false -> "No weapon"
            data.observedInWorld -> "Not held"
            else -> "?"
        }
        Chat.component(
            summaryLine(
                SummaryValue("Looting V", looting, state(data.hasLootingV)),
                beltSummary(data),
            ),
        )
    }

    private fun beltSummary(data: FishingReadiness): SummaryValue = when {
        !data.beltCheckAvailable -> SummaryValue("Belt", "?", CheckState.UNKNOWN)
        data.fishingBelt == null -> SummaryValue("Belt", "Not worn", CheckState.FAIL)
        data.bloodshotBelt == true -> SummaryValue("Belt", "Bloodshot ${data.fishingBelt}", CheckState.PASS)
        data.bloodshotBelt == false -> SummaryValue("Belt", "${data.fishingBelt}, no Bloodshot", CheckState.FAIL)
        else -> SummaryValue("Belt", "?", CheckState.UNKNOWN)
    }

    private fun summaryLine(vararg values: SummaryValue): Component {
        val result = Component.empty()
        values.forEachIndexed { index, entry ->
            if (index > 0) result.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
            result.append(Component.literal("${entry.label}: ").withStyle(ChatFormatting.GRAY))
            result.append(Component.literal(entry.value).withStyle(when (entry.state) {
                CheckState.PASS -> ChatFormatting.GREEN
                CheckState.FAIL -> ChatFormatting.RED
                CheckState.UNKNOWN -> ChatFormatting.YELLOW
            }))
        }
        return result
    }

    private fun yesNo(value: Boolean?) = when (value) {
        true -> "Yes"
        false -> "No"
        null -> "?"
    }

    private fun state(value: Boolean?) = when (value) {
        true -> CheckState.PASS
        false -> CheckState.FAIL
        null -> CheckState.UNKNOWN
    }

    private fun maybeKick(data: FishingReadiness) {
        val settings = ModConfig.values
        if (!settings.autoKick || !PartyState.isLocalLeader() || !PartyState.isMember(data.name)) return

        val reasons = buildList {
            if (settings.kickNoLootingV && data.hasLootingV == false) add("No Looting V")
            if (settings.kickCantJawbus && data.canJawbus == false) add("Can't Jawbus")
        }
        if (reasons.isEmpty()) return

        Chat.info("Auto-kicking ${data.name}: ${reasons.joinToString(", ")}")
        Minecraft.getInstance().connection?.sendCommand("party kick ${data.name}")
    }

    private enum class CheckState { PASS, FAIL, UNKNOWN }
}
