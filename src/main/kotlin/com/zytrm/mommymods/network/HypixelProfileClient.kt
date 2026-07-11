package com.zytrm.mommymods.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zytrm.mommymods.model.FishingReadiness
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object HypixelProfileClient {
    private const val ENDPOINT = "https://mommymods-api.zapk32.workers.dev/v2/readiness"
    private const val CACHE_MILLIS = 6 * 60 * 60 * 1000L
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val cache = ConcurrentHashMap<String, CachedReadiness>()

    enum class FailureKind {
        PLAYER_NOT_FOUND,
        DATA_BLOCKED,
        TIMEOUT,
        RATE_LIMITED,
        PARSE_FAILURE,
        SERVICE_ERROR,
        NETWORK_ERROR,
    }

    data class Diagnostic(
        val stage: String,
        val status: String,
        val detail: String,
    )

    class LookupException(
        val kind: FailureKind,
        val stage: String,
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private data class CachedReadiness(val value: FishingReadiness, val expiresAt: Long)

    fun inspect(
        playerName: String,
        diagnostics: ((Diagnostic) -> Unit)? = null,
        bypassCache: Boolean = false,
    ): CompletableFuture<FishingReadiness> {
        val emit = { stage: String, status: String, detail: String ->
            runCatching { diagnostics?.invoke(Diagnostic(stage, status, detail)) }
            Unit
        }
        val cacheKey = playerName.lowercase()
        val now = System.currentTimeMillis()
        if (!bypassCache) {
            cache[cacheKey]?.takeIf { it.expiresAt > now }?.let {
                emit("cache", "HIT", "Using a cached readiness result.")
                return CompletableFuture.completedFuture(it.value)
            }
            emit("cache", "MISS", "No current cached result.")
        } else {
            emit("cache", "BYPASS", "Forced a fresh lookup for diagnostics.")
        }

        val connectedUuid = Minecraft.getInstance().connection
            ?.getPlayerInfoIgnoreCase(playerName)?.profile?.id
        if (connectedUuid != null) emit("uuid", "TAB_LIST", "Resolved UUID from the current lobby.")

        return CompletableFuture.supplyAsync {
            val uuid = connectedUuid ?: resolveUuid(playerName, emit)
            val encodedUuid = URLEncoder.encode(uuid.toString().replace("-", ""), StandardCharsets.UTF_8)
            emit("backend", "REQUEST", "Requesting the readiness service.")
            val response = send(
                HttpRequest.newBuilder(URI("$ENDPOINT/$encodedUuid"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "MommyMods")
                    .GET()
                    .build(),
                "backend",
            )
            emit("backend", "HTTP_${response.statusCode()}", "Readiness service responded.")
            when (response.statusCode()) {
                200 -> {
                    rejectBlockedOrHtml(response, "backend")
                    parse(playerName, response.body(), emit)
                }
                404 -> throw LookupException(
                    FailureKind.PLAYER_NOT_FOUND,
                    "profile",
                    "No usable SkyBlock profile was found.",
                )
                401, 403 -> throw LookupException(
                    FailureKind.DATA_BLOCKED,
                    "backend",
                    "The readiness service blocked or rejected the request.",
                )
                429 -> throw LookupException(
                    FailureKind.RATE_LIMITED,
                    "backend",
                    "The readiness service rate limit was reached.",
                )
                504, 522, 524 -> throw LookupException(
                    FailureKind.TIMEOUT,
                    "backend",
                    "The readiness service timed out upstream.",
                )
                else -> throw LookupException(
                    FailureKind.SERVICE_ERROR,
                    "backend",
                    "The readiness service returned HTTP ${response.statusCode()}.",
                )
            }
        }.thenApply {
            cache[cacheKey] = CachedReadiness(it, System.currentTimeMillis() + CACHE_MILLIS)
            emit("complete", "SUCCESS", "Readiness evaluation completed.")
            it
        }
    }

    fun statusSummary(): String = "backend=workers.dev cacheEntries=${cache.size}"

    private fun parse(
        fallbackName: String,
        body: String,
        emit: (String, String, String) -> Unit,
    ): FishingReadiness {
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (exception: Exception) {
            throw LookupException(
                FailureKind.PARSE_FAILURE,
                "parse",
                "The readiness response was not valid JSON data.",
                exception,
            )
        }
        val readiness = FishingReadiness(
            name = fallbackName,
            profileName = root.stringOrNull("profileName") ?: "Unknown",
            fishingLevel = root.intOrNull("fishingLevel"),
            silverTrophyHunter = root.booleanOrNull("silverTrophyHunter"),
            inventoryAvailable = root.booleanOrNull("inventoryAvailable") == true,
            lootingWeapon = root.stringOrNull("lootingWeapon"),
            lootingV = root.booleanOrNull("lootingV"),
            beltCheckAvailable = root.booleanOrNull("beltCheckAvailable") == true,
            fishingBelt = root.stringOrNull("fishingBelt"),
            bloodshotBelt = root.booleanOrNull("bloodshotBelt"),
        )
        val missing = buildList {
            if (readiness.fishingLevel == null) add("fishing level")
            if (readiness.silverTrophyHunter == null) add("trophy tier")
            if (readiness.hasLootingV == null) add("Looting V")
            if (!readiness.beltCheckAvailable) add("equipped belt")
        }
        if (missing.isEmpty()) {
            emit("parse", "VALID", "All readiness fields were available.")
        } else {
            emit("parse", "PARTIAL", "Missing: ${missing.joinToString(", ")}.")
        }
        return readiness
    }

    private fun resolveUuid(name: String, emit: (String, String, String) -> Unit): UUID {
        emit("uuid", "LOOKUP", "Player was not in tab; querying Minecraft Services.")
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
        val response = send(
            HttpRequest.newBuilder(URI("https://api.minecraftservices.com/minecraft/profile/lookup/name/$encodedName"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build(),
            "uuid",
        )
        emit("uuid", "HTTP_${response.statusCode()}", "Minecraft Services responded.")
        when (response.statusCode()) {
            200 -> Unit
            204, 404 -> throw LookupException(
                FailureKind.PLAYER_NOT_FOUND,
                "uuid",
                "No Minecraft player named $name was found.",
            )
            401, 403 -> throw LookupException(
                FailureKind.DATA_BLOCKED,
                "uuid",
                "Minecraft Services blocked or rejected the UUID lookup.",
            )
            429 -> throw LookupException(
                FailureKind.RATE_LIMITED,
                "uuid",
                "Minecraft Services rate-limited the UUID lookup.",
            )
            else -> throw LookupException(
                FailureKind.SERVICE_ERROR,
                "uuid",
                "Minecraft Services returned HTTP ${response.statusCode()}.",
            )
        }
        rejectBlockedOrHtml(response, "uuid")
        val id = try {
            JsonParser.parseString(response.body()).asJsonObject.stringOrNull("id")
        } catch (exception: Exception) {
            throw LookupException(FailureKind.PARSE_FAILURE, "uuid", "Could not parse the UUID response.", exception)
        }
        if (id == null || id.length != 32) {
            throw LookupException(FailureKind.PARSE_FAILURE, "uuid", "The UUID response did not contain a valid ID.")
        }
        return runCatching {
            UUID.fromString(id.replaceFirst(
                Regex("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})"),
                "\$1-\$2-\$3-\$4-\$5",
            ))
        }.getOrElse {
            throw LookupException(FailureKind.PARSE_FAILURE, "uuid", "The returned UUID was malformed.", it)
        }.also { emit("uuid", "RESOLVED", "Minecraft Services resolved the player.") }
    }

    private fun send(request: HttpRequest, stage: String): HttpResponse<String> = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (exception: HttpTimeoutException) {
        throw LookupException(FailureKind.TIMEOUT, stage, "The $stage request timed out.", exception)
    } catch (exception: Exception) {
        throw LookupException(FailureKind.NETWORK_ERROR, stage, "The $stage request failed: ${exception.message}", exception)
    }

    private fun rejectBlockedOrHtml(response: HttpResponse<String>, stage: String) {
        val body = response.body().trimStart()
        val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
        val html = body.startsWith("<") || "text/html" in contentType
        if (!html) return
        val blocked = body.contains("cloudflare", ignoreCase = true) ||
            body.contains("challenge", ignoreCase = true) ||
            body.contains("attention required", ignoreCase = true)
        throw LookupException(
            if (blocked) FailureKind.DATA_BLOCKED else FailureKind.PARSE_FAILURE,
            stage,
            if (blocked) "The $stage response was an anti-bot or Cloudflare block page."
            else "The $stage response was HTML instead of JSON.",
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? = get(key)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.intOrNull(key: String): Int? = get(key)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

    private fun JsonObject.booleanOrNull(key: String): Boolean? = get(key)
        ?.takeUnless { it.isJsonNull }
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean
}
