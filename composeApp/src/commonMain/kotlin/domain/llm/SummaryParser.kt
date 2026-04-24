package com.meetingnotes.domain.llm

import com.meetingnotes.domain.model.ActionItem
import com.meetingnotes.domain.model.MeetingSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses raw LLM output into a [MeetingSummary].
 *
 * Handles all common LLM response variants:
 *   1. Clean JSON object
 *   2. JSON wrapped in ```json … ``` code fences
 *   3. JSON embedded in narrative prose (extracted by brace-matching)
 *   4. Completely unparseable — stores raw text in [MeetingSummary.rawLlmResponse]
 */
object SummaryParser {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(meetingId: String, rawResponse: String): MeetingSummary {
        val cleaned = rawResponse.trim()

        // Strategy 1: parse as-is
        tryParseJson(meetingId, cleaned, rawResponse)?.let { return it }

        // Strategy 2: strip ```json … ``` or ``` … ``` fences
        val unfenced = stripCodeFence(cleaned)
        if (unfenced != cleaned) {
            tryParseJson(meetingId, unfenced, rawResponse)?.let { return it }
        }

        // Strategy 3: extract first {...} block by brace counting
        extractJsonObject(cleaned)?.let { extracted ->
            tryParseJson(meetingId, extracted, rawResponse)?.let { return it }
        }

        // Strategy 4: fallback — preserve raw output
        return MeetingSummary(meetingId = meetingId, rawLlmResponse = rawResponse)
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun tryParseJson(meetingId: String, text: String, raw: String): MeetingSummary? = runCatching {
        val obj: JsonObject = lenientJson.parseToJsonElement(text).jsonObject
        MeetingSummary(
            meetingId = meetingId,
            keyPoints = obj["keyPoints"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            decisions = obj["decisions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            actionItems = obj["actionItems"]?.jsonArray?.mapNotNull { parseActionItem(it.jsonObject) } ?: emptyList(),
            discussionPoints = obj["discussionPoints"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            rawLlmResponse = raw,
        )
    }.getOrNull()

    private fun parseActionItem(obj: JsonObject): ActionItem? = runCatching {
        ActionItem(
            owner = obj["owner"]?.jsonPrimitive?.contentOrNull,
            text = obj["text"]?.jsonPrimitive?.content ?: return@runCatching null,
            dueDate = obj["dueDate"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    private fun stripCodeFence(text: String): String {
        val fenceStart = Regex("^```(?:json)?\\s*\\n?", RegexOption.IGNORE_CASE)
        val fenceEnd = Regex("\\n?```\\s*$", RegexOption.IGNORE_CASE)
        return fenceStart.replace(fenceEnd.replace(text, ""), "").trim()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else if (content == "null") null else content
}
