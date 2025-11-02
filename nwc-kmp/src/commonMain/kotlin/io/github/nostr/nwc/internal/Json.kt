package io.github.nostr.nwc.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}

internal fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentStringOrNull

internal fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longValueOrNull()

internal fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

internal fun JsonObject.jsonObjectOrNull(key: String): JsonObject? =
    this[key] as? JsonObject

internal fun JsonObject.jsonArrayOrNull(key: String): JsonArray? =
    this[key] as? JsonArray

internal fun JsonElement?.asJsonObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asString(): String? = this?.jsonPrimitive?.contentStringOrNull

internal fun JsonElement?.asLong(): Long? = this?.jsonPrimitive?.longValueOrNull()

internal fun JsonElement?.asJsonArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asJsonPrimitive(): JsonPrimitive? = this as? JsonPrimitive

internal val JsonPrimitive.contentStringOrNull: String?
    get() = if (isString) content else null

internal fun JsonPrimitive?.longValueOrNull(): Long? = this?.longOrNull

internal fun JsonPrimitive?.intValueOrNull(): Int? = this?.intOrNull
