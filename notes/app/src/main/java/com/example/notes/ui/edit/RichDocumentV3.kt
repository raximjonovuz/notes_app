package com.example.notes.ui.edit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ParagraphKind {
    BODY,
    H1,
    H2,
    CHECKLIST
}

@Serializable
data class ParagraphRun(
    val start: Int,
    val end: Int,
    val kind: ParagraphKind = ParagraphKind.BODY,
    val checked: Boolean = false
)

@Serializable
data class CharSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val colorKey: String? = null
)

@Serializable
sealed class V3Block {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val paragraphs: List<ParagraphRun> = emptyList(),
        val spans: List<CharSpan> = emptyList()
    ) : V3Block()

    @Serializable
    @SerialName("img")
    data class Image(
        val uri: String
    ) : V3Block()
}

@Serializable
data class RichDocumentV3(
    val v: Int = 3,
    val blocks: List<V3Block>
)

private val v3Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "t"
}

fun serializeV3(doc: RichDocumentV3): String = v3Json.encodeToString(doc)

fun parseV3(raw: String): RichDocumentV3? {
    if (raw.isBlank()) return null
    val trimmed = raw.trimStart()
    if (!trimmed.startsWith("{\"v\":3") && !trimmed.startsWith("{\"v\": 3")) return null
    return runCatching {
        v3Json.decodeFromString<RichDocumentV3>(trimmed).takeIf { it.v == 3 }
    }.getOrNull()
}
