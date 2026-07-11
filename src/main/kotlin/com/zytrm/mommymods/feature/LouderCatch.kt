package com.zytrm.mommymods.feature

import com.zytrm.mommymods.MommyMods
import com.zytrm.mommymods.config.ModConfig
import com.zytrm.mommymods.core.GameContext
import com.zytrm.mommymods.mixin.FishingHookAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

object LouderCatch {
    data class Choice(
        val label: String,
        val identifier: Identifier,
        val fallback: SoundEvent,
    )

    private enum class CastPhase {
        IDLE,
        CASTING,
        LANDED,
        WAITING,
        ALERTED,
    }

    private enum class DetectionSource {
        HYPIXEL_TIMER_MARKER,
        HOOK_METADATA,
        CORROBORATED_FALLBACK,
        HYPIXEL_BITE_SOUND,
        VANILLA_SOUND,
    }

    private fun alertId(path: String) = Identifier.fromNamespaceAndPath(MommyMods.MOD_ID, "catch.$path")

    val choices = listOf(
        Choice("Experience", alertId("experience"), SoundEvents.EXPERIENCE_ORB_PICKUP),
        Choice("Pling", alertId("pling"), SoundEvents.NOTE_BLOCK_PLING.value()),
        Choice("Level Up", alertId("level_up"), SoundEvents.PLAYER_LEVELUP),
        Choice("Amethyst", alertId("amethyst"), SoundEvents.AMETHYST_BLOCK_CHIME),
        Choice("Allay", alertId("allay"), SoundEvents.ALLAY_ITEM_GIVEN),
        Choice("Thunder", alertId("thunder"), SoundEvents.TRIDENT_THUNDER.value()),
        Choice("Button", alertId("button"), SoundEvents.UI_BUTTON_CLICK.value()),
    )

    private val hypixelCatchId = SoundEvents.EXPERIENCE_ORB_PICKUP.location
    private val vanillaBiteId = SoundEvents.FISHING_BOBBER_SPLASH.location
    private const val HYPIXEL_BITE_VOLUME = 3.0f
    private const val HYPIXEL_BITE_ASSIST_VOLUME = 1.0f
    private const val HYPIXEL_CATCH_RESULT_VOLUME = 0.5f
    private const val MIN_HOOK_AGE_TICKS = 5
    private const val WAITING_DELAY_TICKS = 15
    private const val REQUIRED_STABLE_TICKS = 4
    private const val MISSING_HOOK_GRACE_TICKS = 5
    private const val DUPLICATE_WINDOW_NANOS = 750_000_000L
    private const val ALERT_PHASE_NANOS = 2_500_000_000L
    private const val FALLBACK_CORROBORATION_NANOS = 500_000_000L
    private const val HOOK_SOUND_RADIUS_SQUARED = 6.25
    private const val PARTICLE_RADIUS_SQUARED = 5.0625
    private const val TIMER_MARKER_RADIUS_SQUARED = 25.0

    @Volatile private var diagnosticsEnabled = false
    private var phase = CastPhase.IDLE
    private var trackedLevel: ClientLevel? = null
    private var trackedHook: FishingHook? = null
    private var trackedHookId = -1
    private var trackedHookUuid: UUID? = null
    private var missingHookTicks = 0
    private var ticksAfterLiquid = 0
    private var stableHookTicks = 0
    private var hookTouchedLiquid = false
    private var previousBiting = false
    private var lastHookY = Double.NaN
    private var lastTriggerNanos = 0L
    private var pendingMotionNanos = 0L
    private var pendingParticleNanos = 0L
    private var timerMarkerUuid: UUID? = null
    private var readyMarkerUuid: UUID? = null

    fun onTick(minecraft: Minecraft) {
        val level = minecraft.level
        if (level !== trackedLevel) {
            reset("world changed")
            trackedLevel = level
        }

        if (!ModConfig.values.louderCatch) {
            reset("feature disabled")
            return
        }
        if (!GameContext.isOnHypixel()) {
            reset("left Hypixel")
            return
        }

        val player = minecraft.player
        if (level == null || player == null || !player.isAlive) {
            reset("player unavailable")
            return
        }

        val hook = resolveLocalHook(level, player)
        if (hook == null) {
            if (phase != CastPhase.IDLE) {
                missingHookTicks++
                if (missingHookTicks == 1) {
                    trace("state", "failed_to_reacquire_hook phase=$phase hook=$trackedHookId")
                }
                if (missingHookTicks > MISSING_HOOK_GRACE_TICKS) reset("hook lost")
            }
            return
        }

        refreshTrackedHook(hook)
        updateCast(hook)
    }

    @JvmStatic
    fun onSoundPacket(identifier: Identifier, volume: Float, x: Double, y: Double, z: Double): Boolean {
        val isExperienceSound = identifier == hypixelCatchId
        val isHypixelBite = isExperienceSound && abs(volume - HYPIXEL_BITE_VOLUME) <= 0.05f
        val isBiteAssist = isExperienceSound && abs(volume - HYPIXEL_BITE_ASSIST_VOLUME) <= 0.05f
        val isCatchResult = isExperienceSound && abs(volume - HYPIXEL_CATCH_RESULT_VOLUME) <= 0.02f

        if (isCatchResult) {
            trace("ignored", "event=ignored_post_reel_sound volume=$volume packet=($x,$y,$z) phase=$phase")
            return false
        }
        if (isExperienceSound && !isHypixelBite && !isBiteAssist) {
            trace(
                "ignored",
                "event=ignored_unclassified_experience_sound volume=$volume packet=($x,$y,$z) phase=$phase",
            )
            return false
        }
        if (!isHypixelBite && !isBiteAssist && identifier != vanillaBiteId) return false
        val hook = signalHook() ?: return false

        if (!isSignalEligible(hook)) {
            val event = if (isHypixelBite || isBiteAssist) "ignored_cast_sound" else "ignored_early_splash"
            trace(
                "ignored",
                "event=$event phase=$phase hook=${hook.id} age=${hook.tickCount} ticksAfterLiquid=$ticksAfterLiquid",
            )
            return false
        }

        if (isBiteAssist) {
            val marker = findReadyMarker(hook)
            if (marker == null) {
                trace(
                    "ignored",
                    "event=ignored_unconfirmed_bite_sound volume=$volume packet=($x,$y,$z) " +
                        "phase=$phase hook=${hook.id} distanceSquared=${hook.distanceToSqr(x, y, z)}",
                )
                return false
            }
            trace(
                "candidate_matched",
                "sources=hypixel_timer_and_sound hook=${hook.id} marker=${marker.uuid} volume=$volume",
            )
            trigger(DetectionSource.HYPIXEL_TIMER_MARKER, hook)
            return true
        }

        if (isHypixelBite) {
            trace(
                "candidate",
                "source=hypixel_bite_sound hook=${hook.id} volume=$volume packet=($x,$y,$z)",
            )
            trigger(DetectionSource.HYPIXEL_BITE_SOUND, hook)
            return true
        }

        val distance = hook.distanceToSqr(x, y, z)
        if (distance > HOOK_SOUND_RADIUS_SQUARED) {
            trace("rejected", "source=vanilla_sound reason=distance hook=${hook.id} distanceSquared=$distance")
            return false
        }

        trace("candidate", "source=vanilla_sound hook=${hook.id} distanceSquared=$distance")
        trigger(DetectionSource.VANILLA_SOUND, hook)
        return true
    }

    @JvmStatic
    fun onSoundEntityPacket(identifier: Identifier, volume: Float, entityId: Int): Boolean {
        if (identifier == hypixelCatchId) {
            return onSoundPacket(identifier, volume, 0.0, 0.0, 0.0)
        }
        val minecraft = Minecraft.getInstance()
        val entity = minecraft.level?.getEntity(entityId)
        if (entity == null) {
            trace("rejected", "source=entity_sound reason=missing_entity entity=$entityId")
            return false
        }
        return onSoundPacket(identifier, volume, entity.x, entity.y, entity.z)
    }

    @JvmStatic
    fun onParticlePacket(packet: ClientboundLevelParticlesPacket) {
        val particleType = packet.particle.type
        if (particleType != ParticleTypes.BUBBLE && particleType != ParticleTypes.FISHING) return

        val hook = signalHook() ?: return
        val distance = hook.distanceToSqr(packet.x, packet.y, packet.z)
        if (packet.count < 3 || distance > PARTICLE_RADIUS_SQUARED) {
            trace(
                "rejected",
                "source=particle reason=shape hook=${hook.id} type=$particleType count=${packet.count} distanceSquared=$distance",
            )
            return
        }

        if (!isSignalEligible(hook)) {
            trace(
                "ignored",
                "event=ignored_landing_particles phase=$phase hook=${hook.id} count=${packet.count} distanceSquared=$distance",
            )
            return
        }

        trace(
            "candidate",
            "source=particle hook=${hook.id} type=$particleType count=${packet.count} distanceSquared=$distance",
        )
        pendingParticleNanos = System.nanoTime()
        confirmFallbackIfCorroborated(hook)
    }

    fun playConfigured() {
        Minecraft.getInstance().execute(::playConfiguredOnClientThread)
    }

    fun toggleDiagnostics(): Boolean {
        diagnosticsEnabled = !diagnosticsEnabled
        MommyMods.logger.info("LouderCatch diagnostics {}", if (diagnosticsEnabled) "enabled" else "disabled")
        return diagnosticsEnabled
    }

    @JvmStatic
    fun onFishingRodUse(stack: ItemStack) {
        if (!stack.`is`(Items.FISHING_ROD)) return
        val activeHook = Minecraft.getInstance().player?.fishing?.takeUnless { it.isRemoved }
        if (activeHook != null) {
            trace("state", "reel_input hook=${activeHook.id} phase=$phase")
            reset("reel input")
        } else {
            if (phase != CastPhase.IDLE) reset("new cast input replaced stale state")
            trace("state", "cast_initiated")
        }
    }

    private fun updateCast(hook: FishingHook) {
        val inLiquid = hook.isInWater || hook.isInLava
        if (!hookTouchedLiquid && inLiquid) {
            markHookLanded(hook, "tick")
        } else if (hookTouchedLiquid) {
            ticksAfterLiquid++
        }

        val biting = (hook as FishingHookAccessor).`mommymods$getBiting`()
        val now = System.nanoTime()
        if (phase == CastPhase.ALERTED && now - lastTriggerNanos >= ALERT_PHASE_NANOS && !biting) {
            phase = CastPhase.WAITING
            clearFallbackCandidates()
            trace("state", "bite_window_expired hook=${hook.id}")
        }
        if (previousBiting && !biting && phase == CastPhase.ALERTED) {
            phase = CastPhase.WAITING
            clearFallbackCandidates()
            trace("state", "synced_bite_ended hook=${hook.id}")
        }
        if (!previousBiting && biting && isSignalEligible(hook)) {
            trace("candidate", "source=hook_metadata hook=${hook.id}")
            trigger(DetectionSource.HOOK_METADATA, hook)
        }

        val yDrop = if (lastHookY.isNaN()) 0.0 else hook.y - lastHookY
        val motionCandidate = hook.deltaMovement.y <= -0.22 || yDrop <= -0.15
        val motionStable = abs(hook.deltaMovement.y) < 0.15 && abs(yDrop) < 0.12
        stableHookTicks = if (hookTouchedLiquid && motionStable) stableHookTicks + 1 else 0

        if (
            phase == CastPhase.LANDED &&
            ticksAfterLiquid >= WAITING_DELAY_TICKS &&
            stableHookTicks >= REQUIRED_STABLE_TICKS
        ) {
            phase = CastPhase.WAITING
            clearFallbackCandidates()
            trace(
                "state",
                "entered_waiting hook=${hook.id} age=${hook.tickCount} ticksAfterLiquid=$ticksAfterLiquid stableTicks=$stableHookTicks",
            )
        }

        if (motionCandidate) {
            when (phase) {
                CastPhase.CASTING, CastPhase.LANDED -> trace(
                    "ignored",
                    "event=ignored_landing_motion phase=$phase hook=${hook.id} velocityY=${hook.deltaMovement.y} yDrop=$yDrop",
                )
                CastPhase.WAITING -> {
                    trace(
                        "candidate",
                        "source=hook_motion hook=${hook.id} velocityY=${hook.deltaMovement.y} yDrop=$yDrop",
                    )
                    pendingMotionNanos = now
                    confirmFallbackIfCorroborated(hook)
                }
                else -> Unit
            }
        }

        detectHypixelReadyMarker(hook)

        previousBiting = biting
        lastHookY = hook.y
    }

    private fun refreshTrackedHook(hook: FishingHook) {
        if (trackedHookUuid != hook.uuid) {
            beginCast(hook)
            return
        }

        if (trackedHook !== hook || trackedHookId != hook.id) {
            trace("state", "hook_reference_refreshed old=$trackedHookId new=${hook.id} uuid=${hook.uuid}")
        }
        trackedHook = hook
        trackedHookId = hook.id
        missingHookTicks = 0
    }

    private fun beginCast(hook: FishingHook) {
        phase = CastPhase.CASTING
        trackedHook = hook
        trackedHookId = hook.id
        trackedHookUuid = hook.uuid
        missingHookTicks = 0
        ticksAfterLiquid = 0
        stableHookTicks = 0
        hookTouchedLiquid = false
        previousBiting = false
        lastHookY = hook.y
        timerMarkerUuid = null
        readyMarkerUuid = null
        clearFallbackCandidates()
        trace("state", "cast_started hook=${hook.id} uuid=${hook.uuid}")
        trace("state", "hook_spawned hook=${hook.id} age=${hook.tickCount}")
    }

    private fun reset(reason: String) {
        if (phase != CastPhase.IDLE || trackedHook != null) trace("state", "reset reason=$reason phase=$phase hook=$trackedHookId")
        phase = CastPhase.IDLE
        trackedHook = null
        trackedHookId = -1
        trackedHookUuid = null
        missingHookTicks = 0
        ticksAfterLiquid = 0
        stableHookTicks = 0
        hookTouchedLiquid = false
        previousBiting = false
        lastHookY = Double.NaN
        timerMarkerUuid = null
        readyMarkerUuid = null
        clearFallbackCandidates()
    }

    private fun signalHook(): FishingHook? {
        if (!ModConfig.values.louderCatch || !GameContext.isOnHypixel()) return null
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val player = minecraft.player ?: return null
        if (!player.isAlive) return null

        if (level !== trackedLevel) {
            reset("world changed during signal")
            trackedLevel = level
        }

        val hook = resolveLocalHook(level, player) ?: run {
            trace("ignored", "event=candidate_without_local_hook phase=$phase trackedHook=$trackedHookId")
            return null
        }
        refreshTrackedHook(hook)
        if (!hookTouchedLiquid && (hook.isInWater || hook.isInLava)) {
            markHookLanded(hook, "packet")
        }
        return hook
    }

    private fun resolveLocalHook(level: ClientLevel, player: LocalPlayer): FishingHook? {
        fun FishingHook.isLocalAndActive(): Boolean {
            return !isRemoved && playerOwner === player && this.level() === level
        }

        player.fishing?.takeIf(FishingHook::isLocalAndActive)?.let { return it }
        trackedHook?.takeIf(FishingHook::isLocalAndActive)?.let { return it }
        (level.getEntity(trackedHookId) as? FishingHook)?.takeIf(FishingHook::isLocalAndActive)?.let { return it }
        return level.entitiesForRendering()
            .asSequence()
            .filterIsInstance<FishingHook>()
            .firstOrNull(FishingHook::isLocalAndActive)
    }

    private fun isSignalEligible(hook: FishingHook): Boolean {
        return (phase == CastPhase.WAITING || phase == CastPhase.ALERTED) &&
            hook.uuid == trackedHookUuid &&
            hook.tickCount >= MIN_HOOK_AGE_TICKS &&
            hookTouchedLiquid
    }

    private fun markHookLanded(hook: FishingHook, source: String) {
        hookTouchedLiquid = true
        ticksAfterLiquid = 0
        stableHookTicks = 0
        phase = CastPhase.LANDED
        clearFallbackCandidates()
        trace("state", "hook_landed hook=${hook.id} age=${hook.tickCount} source=$source")
        trace("state", "hook_entered_liquid hook=${hook.id}")
    }

    private fun detectHypixelReadyMarker(hook: FishingHook) {
        val marker = findTimerMarker(hook) ?: return
        val markerText = marker.customName?.string?.trim() ?: return

        if (markerText != "!!!") {
            if (timerMarkerUuid != marker.uuid) {
                timerMarkerUuid = marker.uuid
                trace(
                    "state",
                    "timer_marker_acquired hook=${hook.id} marker=${marker.uuid} text=$markerText " +
                        "distanceSquared=${hook.distanceToSqr(marker)}",
                )
            }
            readyMarkerUuid = null
            return
        }
        if (timerMarkerUuid != null && timerMarkerUuid != marker.uuid) {
            trace(
                "rejected",
                "source=hypixel_timer_marker reason=foreign_marker hook=${hook.id} " +
                    "expected=$timerMarkerUuid actual=${marker.uuid}",
            )
            return
        }
        if (phase == CastPhase.LANDED && hookTouchedLiquid && ticksAfterLiquid >= WAITING_DELAY_TICKS) {
            phase = CastPhase.WAITING
            clearFallbackCandidates()
            trace(
                "state",
                "entered_waiting hook=${hook.id} age=${hook.tickCount} source=timer_marker " +
                    "ticksAfterLiquid=$ticksAfterLiquid",
            )
        }
        if (!isSignalEligible(hook)) {
            trace(
                "ignored",
                "event=ignored_early_timer_marker phase=$phase hook=${hook.id} age=${hook.tickCount} " +
                    "ticksAfterLiquid=$ticksAfterLiquid",
            )
            return
        }
        if (marker.uuid == readyMarkerUuid) return

        timerMarkerUuid = marker.uuid
        readyMarkerUuid = marker.uuid
        trace(
            "candidate",
            "source=hypixel_timer_marker hook=${hook.id} marker=${marker.uuid} " +
                "distanceSquared=${hook.distanceToSqr(marker)}",
        )
        trigger(DetectionSource.HYPIXEL_TIMER_MARKER, hook)
    }

    private fun findReadyMarker(hook: FishingHook): ArmorStand? {
        return findTimerMarker(hook)?.takeIf { marker ->
            marker.customName?.string?.trim() == "!!!" &&
                (timerMarkerUuid == null || timerMarkerUuid == marker.uuid)
        }
    }

    private fun findTimerMarker(hook: FishingHook): ArmorStand? {
        val level = trackedLevel ?: return null
        return level.entitiesForRendering()
            .asSequence()
            .filterIsInstance<ArmorStand>()
            .filter { marker ->
                val text = marker.customName?.string?.trim() ?: return@filter false
                (text == "!!!" || text.toDoubleOrNull() != null) &&
                    hook.distanceToSqr(marker) <= TIMER_MARKER_RADIUS_SQUARED
            }
            .minByOrNull(hook::distanceToSqr)
    }

    private fun confirmFallbackIfCorroborated(hook: FishingHook) {
        if (phase != CastPhase.WAITING || pendingMotionNanos == 0L || pendingParticleNanos == 0L) return
        val separation = abs(pendingMotionNanos - pendingParticleNanos)
        if (separation > FALLBACK_CORROBORATION_NANOS) return

        trace("candidate_matched", "sources=motion_and_particle hook=${hook.id} separationNanos=$separation")
        clearFallbackCandidates()
        trigger(DetectionSource.CORROBORATED_FALLBACK, hook)
    }

    private fun clearFallbackCandidates() {
        pendingMotionNanos = 0L
        pendingParticleNanos = 0L
    }

    private fun trigger(source: DetectionSource, hook: FishingHook): Boolean {
        val now = System.nanoTime()
        if (phase == CastPhase.ALERTED) {
            trace("suppressed", "reason=already_alerted source=$source hook=${hook.id}")
            return false
        }
        if (now - lastTriggerNanos < DUPLICATE_WINDOW_NANOS) {
            trace("suppressed", "reason=debounce source=$source hook=${hook.id}")
            return false
        }

        phase = CastPhase.ALERTED
        lastTriggerNanos = now
        trace("bite_confirmed", "source=$source hook=${hook.id}")
        trace("alert", "play_requested source=$source hook=${hook.id}")
        playConfigured()
        return true
    }

    private fun playConfiguredOnClientThread() {
        val minecraft = Minecraft.getInstance()
        val settings = ModConfig.values
        val configured = choices.firstOrNull { it.label == settings.catchSound }
        val choice = configured ?: choices.first()
        if (configured == null) trace("playback", "invalid_selection=${settings.catchSound} fallback=${choice.label}")

        val soundManager = minecraft.soundManager
        val aliasAvailable = soundManager.getSoundEvent(choice.identifier) != null
        val identifier = if (aliasAvailable) choice.identifier else choice.fallback.location
        val volume = settings.catchVolume.coerceIn(0.1f, 20.0f)
        val pitch = settings.catchPitch.coerceIn(0.5f, 2.0f)

        var playedIdentifier = identifier
        var results = playVoices(playedIdentifier, volume, pitch)
        if (results.all { it == SoundEngine.PlayResult.NOT_STARTED } && identifier != choice.fallback.location) {
            trace("playback", "alias_failed=$identifier fallback=${choice.fallback.location}")
            playedIdentifier = choice.fallback.location
            results = playVoices(playedIdentifier, volume, pitch)
        }
        val started = results.any { it != SoundEngine.PlayResult.NOT_STARTED }
        trace(
            if (started) "alert_played" else "playback_failed",
            "sound=$playedIdentifier aliasAvailable=$aliasAvailable volume=$volume pitch=$pitch results=$results",
        )
    }

    private fun playVoices(identifier: Identifier, volume: Float, pitch: Float): Set<SoundEngine.PlayResult> {
        val soundManager = Minecraft.getInstance().soundManager
        val voices = ceil(volume).toInt().coerceIn(1, 20)
        val fullVoices = volume.toInt().coerceAtMost(voices)
        val fractionalVoice = volume - fullVoices
        val results = linkedSetOf<SoundEngine.PlayResult>()

        repeat(voices) { index ->
            val voiceVolume = when {
                index < fullVoices -> 1.0f
                fractionalVoice > 0f -> fractionalVoice
                else -> min(1.0f, volume)
            }.coerceIn(0.05f, 1.0f)
            results += soundManager.play(
                SimpleSoundInstance(
                    identifier,
                    SoundSource.MASTER,
                    voiceVolume,
                    pitch,
                    SoundInstance.createUnseededRandom(),
                    false,
                    0,
                    SoundInstance.Attenuation.NONE,
                    0.0,
                    0.0,
                    0.0,
                    true,
                ),
            )
        }
        return results
    }

    private fun trace(event: String, details: String) {
        if (diagnosticsEnabled) {
            MommyMods.logger.info("LouderCatch [{}] {}", event, details)
        } else {
            MommyMods.logger.debug("LouderCatch [{}] {}", event, details)
        }
    }
}
