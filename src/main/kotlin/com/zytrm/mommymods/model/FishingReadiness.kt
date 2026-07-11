package com.zytrm.mommymods.model

data class FishingReadiness(
    val name: String,
    val profileName: String,
    val fishingLevel: Int?,
    val silverTrophyHunter: Boolean?,
    val inventoryAvailable: Boolean,
    val lootingWeapon: String?,
    val lootingV: Boolean? = lootingWeapon?.let { true },
    val beltCheckAvailable: Boolean = false,
    val fishingBelt: String? = null,
    val bloodshotBelt: Boolean?,
    val observedInWorld: Boolean = false,
) {
    val canJawbus: Boolean?
        get() = if (silverTrophyHunter == null || fishingLevel == null) null
        else silverTrophyHunter && fishingLevel >= 45

    val hasLootingV: Boolean?
        get() = lootingV
}
