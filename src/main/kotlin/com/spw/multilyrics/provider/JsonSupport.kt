package com.spw.multilyrics.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val providerJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

/**
 * 将非标准单引号 JSON（如酷我返回的 `{'k':'v'}`）转换为标准双引号 JSON。
 * 仅处理单引号定界的字符串字面量，避免误伤已正确转义的内容。
 */
internal fun normalizeJsonQuotes(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('\'') < 0) return raw
    val sb = StringBuilder(raw.length)
    var inSingle = false
    var inDouble = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            inDouble -> {
                sb.append(c)
                if (c == '\\' && i + 1 < raw.length) { sb.append(raw[i + 1]); i += 2; continue }
                if (c == '"') inDouble = false
            }
            inSingle -> {
                if (c == '\\' && i + 1 < raw.length) {
                    // 转义字符原样保留（单引号字符串中罕见）
                    sb.append(c).append(raw[i + 1]); i += 2; continue
                }
                if (c == '\'') { sb.append('"'); inSingle = false }
                else sb.append(c)
            }
            else -> when (c) {
                '"' -> { sb.append(c); inDouble = true }
                '\'' -> { sb.append('"'); inSingle = true }
                else -> sb.append(c)
            }
        }
        i++
    }
    return sb.toString()
}
