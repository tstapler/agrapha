package com.meetingnotes.data.llm

import com.meetingnotes.domain.llm.LlmProvider
import com.meetingnotes.domain.model.AppSettings
import com.meetingnotes.domain.model.LlmProvider as LlmProviderEnum

/**
 * Creates the correct [LlmProvider] implementation based on [AppSettings].
 *
 * Tested by S4-UNIT-10.
 */
object LlmProviderFactory {

    fun create(settings: AppSettings): LlmProvider = when (settings.llmProvider) {
        LlmProviderEnum.OLLAMA -> OllamaProvider()
        LlmProviderEnum.OPENAI -> OpenAiProvider()
        LlmProviderEnum.ANTHROPIC -> AnthropicProvider()
    }
}
