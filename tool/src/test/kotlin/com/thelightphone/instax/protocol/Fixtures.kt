package com.thelightphone.instax.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loader for instax-fixtures/fixtures.json — the protocol ground truth
 *  cross-generated from javl/InstaxBLE (see scripts/instax/README.md). */
object Fixtures {
    data class Fixture(val name: String, val bytes: ByteArray, val meaning: JsonObject)

    private val root: JsonObject by lazy {
        val text = requireNotNull(
            Thread.currentThread().contextClassLoader.getResource("instax-fixtures/fixtures.json")
        ) { "fixtures.json not on test classpath" }.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    val all: List<Fixture> by lazy {
        root["fixtures"]!!.jsonArray.map {
            val o = it.jsonObject
            Fixture(
                name = o["name"]!!.jsonPrimitive.content,
                bytes = o["hex"]!!.jsonPrimitive.content.hexToByteArray(),
                meaning = o["meaning"]!!.jsonObject,
            )
        }
    }

    fun get(name: String): Fixture = all.first { it.name == name }
    fun bytes(name: String): ByteArray = get(name).bytes
    fun wideChunkSize(): Int = root["wide_chunk_size"]!!.jsonPrimitive.int
    fun meaningInt(f: Fixture, key: String): Int = f.meaning[key]!!.jsonPrimitive.int
    fun meaningHex(f: Fixture, key: String): ByteArray =
        f.meaning[key]!!.jsonPrimitive.content.hexToByteArray()
    fun meaning(f: Fixture, key: String): JsonElement? = f.meaning[key]

    @OptIn(ExperimentalStdlibApi::class)
    private fun String.hexToByteArray(): ByteArray =
        if (isEmpty()) ByteArray(0) else hexToByteArray(HexFormat.Default)
}
