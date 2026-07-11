package com.zytrm.mommymods.feature

import net.minecraft.client.Minecraft
import java.util.concurrent.ConcurrentHashMap

object PartyState {
    private val members = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var leader: String? = null

    private val joinedOther = Regex("^(?:\\[[^]]+] )?(\\w{1,16}) joined the party\\.$")
    private val joinedSelf = Regex("^You have joined (?:\\[[^]]+] )?(\\w{1,16})'?s? party!$")
    private val left = Regex("^(?:\\[[^]]+] )?(\\w{1,16}) (?:has left|has been removed from) the party\\.$")
    private val kickedOffline = Regex("^Kicked (?:\\[[^]]+] )?(\\w{1,16}) because they were offline\\.$")
    private val invitation = Regex("^(?:\\[[^]]+] )?(\\w{1,16}) invited (?:\\[[^]]+] )?(\\w{1,16}) to the party!.*$")
    private val transferred = Regex("^The party was transferred to (?:\\[[^]]+] )?(\\w{1,16})(?: by .+| because .+)?$")
    private val leaderLine = Regex("^Party Leader: (?:\\[[^]]+] )?(\\w{1,16})")
    private val disbanded = listOf(
        Regex("^You left the party\\.$"),
        Regex("^You are not currently in a party\\.?$"),
        Regex("^The party was disbanded.*$"),
        Regex("^You have been kicked from the party.*$"),
    )

    fun onMessage(message: String) {
        joinedOther.matchEntire(message)?.groupValues?.get(1)?.let { members.add(it.lowercase()) }
        joinedSelf.matchEntire(message)?.groupValues?.get(1)?.let {
            leader = it
            members.add(it.lowercase())
            Minecraft.getInstance().user.name.let { self -> members.add(self.lowercase()) }
        }
        left.matchEntire(message)?.groupValues?.get(1)?.let { members.remove(it.lowercase()) }
        kickedOffline.matchEntire(message)?.groupValues?.get(1)?.let { members.remove(it.lowercase()) }
        invitation.matchEntire(message)?.let {
            val inviter = it.groupValues[1]
            members.add(inviter.lowercase())
            if (leader == null) leader = inviter
        }
        transferred.matchEntire(message)?.groupValues?.get(1)?.let { leader = it }
        leaderLine.find(message)?.groupValues?.get(1)?.let {
            leader = it
            members.add(it.lowercase())
        }
        if (disbanded.any { it.matches(message) }) {
            members.clear()
            leader = null
        }
    }

    fun isMember(name: String): Boolean = name.lowercase() in members

    fun isLocalLeader(): Boolean = leader?.equals(Minecraft.getInstance().user.name, ignoreCase = true) == true
}
