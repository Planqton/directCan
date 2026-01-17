package at.planqton.directcan.data.gemini

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AiProvider"

/**
 * Supported AI providers
 */
enum class AiProviderType {
    GEMINI,
    OPENAI,
    ANTHROPIC,
    OPENROUTER  // Free models available!
}

/**
 * Model info for display
 */
@Serializable
data class AiModelInfo(
    val id: String,
    val name: String,
    val provider: AiProviderType
)

/**
 * Message for conversation history
 */
data class AiMessage(
    val role: String,  // "user", "assistant", "system"
    val content: String
)

/**
 * Result from AI generation
 */
sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Error(val message: String, val code: Int? = null) : AiResult()
}

/**
 * Abstract AI Provider interface
 */
interface AiProvider {
    val type: AiProviderType
    val name: String

    suspend fun loadModels(apiKey: String): Result<List<AiModelInfo>>
    suspend fun testConnection(apiKey: String, model: String): Result<Boolean>
    suspend fun generateContent(
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        systemPrompt: String? = null
    ): AiResult
}

/**
 * Google Gemini Provider
 */
class GeminiProvider : AiProvider {
    override val type = AiProviderType.GEMINI
    override val name = "Google Gemini"

    override suspend fun loadModels(apiKey: String): Result<List<AiModelInfo>> {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val response = url.readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val models = jsonResponse["models"]?.jsonArray?.mapNotNull { model ->
                val modelObj = model.jsonObject
                val fullName = modelObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val supportedMethods = modelObj["supportedGenerationMethods"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList()

                val modelId = fullName.removePrefix("models/")
                if (modelId.startsWith("gemini-") && supportedMethods.contains("generateContent")) {
                    AiModelInfo(modelId, modelId, AiProviderType.GEMINI)
                } else null
            } ?: emptyList()

            Result.success(models.sortedByDescending { it.id })
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Gemini models", e)
            Result.failure(e)
        }
    }

    override suspend fun testConnection(apiKey: String, model: String): Result<Boolean> {
        return try {
            val result = generateContent(
                apiKey, model,
                listOf(AiMessage("user", "Say OK")),
                null
            )
            when (result) {
                is AiResult.Success -> Result.success(true)
                is AiResult.Error -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateContent(
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        systemPrompt: String?
    ): AiResult {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Build contents array
            val contents = buildJsonArray {
                // System prompt as first user message
                if (systemPrompt != null) {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", systemPrompt) })
                        })
                    })
                    add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", "Verstanden. Ich bin bereit zu helfen.") })
                        })
                    })
                }

                messages.forEach { msg ->
                    val role = if (msg.role == "assistant") "model" else msg.role
                    add(buildJsonObject {
                        put("role", role)
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                }
            }

            val requestBody = buildJsonObject {
                put("contents", contents)
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.7)
                    put("topK", 40)
                    put("topP", 0.95)
                    put("maxOutputTokens", 8192)
                })
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                return AiResult.Error("API Error: $responseCode - $errorStream", responseCode)
            }

            val response = connection.inputStream.bufferedReader().readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val text = jsonResponse["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return AiResult.Error("No response text")

            AiResult.Success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini generation error", e)
            AiResult.Error(e.message ?: "Unknown error")
        }
    }
}

/**
 * OpenAI Provider (GPT-4, etc.)
 */
class OpenAiProvider : AiProvider {
    override val type = AiProviderType.OPENAI
    override val name = "OpenAI"

    private val defaultModels = listOf(
        AiModelInfo("gpt-4o", "GPT-4o", AiProviderType.OPENAI),
        AiModelInfo("gpt-4o-mini", "GPT-4o Mini", AiProviderType.OPENAI),
        AiModelInfo("gpt-4-turbo", "GPT-4 Turbo", AiProviderType.OPENAI),
        AiModelInfo("gpt-4", "GPT-4", AiProviderType.OPENAI),
        AiModelInfo("gpt-3.5-turbo", "GPT-3.5 Turbo", AiProviderType.OPENAI)
    )

    override suspend fun loadModels(apiKey: String): Result<List<AiModelInfo>> {
        return try {
            val url = URL("https://api.openai.com/v1/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer $apiKey")

            if (connection.responseCode != 200) {
                // Return default models if API fails
                return Result.success(defaultModels)
            }

            val response = connection.inputStream.bufferedReader().readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val models = jsonResponse["data"]?.jsonArray?.mapNotNull { model ->
                val id = model.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (id.startsWith("gpt-")) {
                    AiModelInfo(id, id, AiProviderType.OPENAI)
                } else null
            }?.sortedByDescending { it.id } ?: defaultModels

            Result.success(if (models.isEmpty()) defaultModels else models)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading OpenAI models, using defaults", e)
            Result.success(defaultModels)
        }
    }

    override suspend fun testConnection(apiKey: String, model: String): Result<Boolean> {
        return try {
            val result = generateContent(
                apiKey, model,
                listOf(AiMessage("user", "Say OK")),
                null
            )
            when (result) {
                is AiResult.Success -> Result.success(true)
                is AiResult.Error -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateContent(
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        systemPrompt: String?
    ): AiResult {
        return try {
            val url = URL("https://api.openai.com/v1/chat/completions")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true

            val messagesArray = buildJsonArray {
                if (systemPrompt != null) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == "model") "assistant" else msg.role)
                        put("content", msg.content)
                    })
                }
            }

            val requestBody = buildJsonObject {
                put("model", model)
                put("messages", messagesArray)
                put("temperature", 0.7)
                put("max_tokens", 8192)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                return AiResult.Error("API Error: $responseCode - $errorStream", responseCode)
            }

            val response = connection.inputStream.bufferedReader().readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val text = jsonResponse["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?: return AiResult.Error("No response text")

            AiResult.Success(text)
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI generation error", e)
            AiResult.Error(e.message ?: "Unknown error")
        }
    }
}

/**
 * Anthropic Claude Provider
 */
class AnthropicProvider : AiProvider {
    override val type = AiProviderType.ANTHROPIC
    override val name = "Anthropic Claude"

    private val defaultModels = listOf(
        AiModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", AiProviderType.ANTHROPIC),
        AiModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", AiProviderType.ANTHROPIC),
        AiModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", AiProviderType.ANTHROPIC),
        AiModelInfo("claude-3-opus-20240229", "Claude 3 Opus", AiProviderType.ANTHROPIC),
        AiModelInfo("claude-3-sonnet-20240229", "Claude 3 Sonnet", AiProviderType.ANTHROPIC),
        AiModelInfo("claude-3-haiku-20240307", "Claude 3 Haiku", AiProviderType.ANTHROPIC)
    )

    override suspend fun loadModels(apiKey: String): Result<List<AiModelInfo>> {
        // Anthropic doesn't have a models API, return known models
        return Result.success(defaultModels)
    }

    override suspend fun testConnection(apiKey: String, model: String): Result<Boolean> {
        return try {
            val result = generateContent(
                apiKey, model,
                listOf(AiMessage("user", "Say OK")),
                null
            )
            when (result) {
                is AiResult.Success -> Result.success(true)
                is AiResult.Error -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateContent(
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        systemPrompt: String?
    ): AiResult {
        return try {
            val url = URL("https://api.anthropic.com/v1/messages")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true

            val messagesArray = buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == "model") "assistant" else msg.role)
                        put("content", msg.content)
                    })
                }
            }

            val requestBody = buildJsonObject {
                put("model", model)
                put("max_tokens", 8192)
                put("messages", messagesArray)
                if (systemPrompt != null) {
                    put("system", systemPrompt)
                }
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                return AiResult.Error("API Error: $responseCode - $errorStream", responseCode)
            }

            val response = connection.inputStream.bufferedReader().readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val text = jsonResponse["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return AiResult.Error("No response text")

            AiResult.Success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Anthropic generation error", e)
            AiResult.Error(e.message ?: "Unknown error")
        }
    }
}

/**
 * OpenRouter Provider - Access to many models including FREE ones!
 * Free models: meta-llama/llama-3.2-3b-instruct:free, google/gemma-2-9b-it:free, etc.
 */
class OpenRouterProvider : AiProvider {
    override val type = AiProviderType.OPENROUTER
    override val name = "OpenRouter (Free Models!)"

    // Default models including free ones
    private val defaultModels = listOf(
        // Free models (marked with :free suffix)
        AiModelInfo("meta-llama/llama-3.2-3b-instruct:free", "Llama 3.2 3B (Free)", AiProviderType.OPENROUTER),
        AiModelInfo("google/gemma-2-9b-it:free", "Gemma 2 9B (Free)", AiProviderType.OPENROUTER),
        AiModelInfo("mistralai/mistral-7b-instruct:free", "Mistral 7B (Free)", AiProviderType.OPENROUTER),
        AiModelInfo("huggingfaceh4/zephyr-7b-beta:free", "Zephyr 7B (Free)", AiProviderType.OPENROUTER),
        AiModelInfo("openchat/openchat-7b:free", "OpenChat 7B (Free)", AiProviderType.OPENROUTER),
        AiModelInfo("nousresearch/nous-capybara-7b:free", "Nous Capybara 7B (Free)", AiProviderType.OPENROUTER),
        // Paid models
        AiModelInfo("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet", AiProviderType.OPENROUTER),
        AiModelInfo("openai/gpt-4o", "GPT-4o", AiProviderType.OPENROUTER),
        AiModelInfo("openai/gpt-4o-mini", "GPT-4o Mini", AiProviderType.OPENROUTER),
        AiModelInfo("google/gemini-pro-1.5", "Gemini Pro 1.5", AiProviderType.OPENROUTER),
        AiModelInfo("meta-llama/llama-3.1-70b-instruct", "Llama 3.1 70B", AiProviderType.OPENROUTER),
        AiModelInfo("mistralai/mixtral-8x7b-instruct", "Mixtral 8x7B", AiProviderType.OPENROUTER)
    )

    override suspend fun loadModels(apiKey: String): Result<List<AiModelInfo>> {
        return try {
            val url = URL("https://openrouter.ai/api/v1/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer $apiKey")

            if (connection.responseCode != 200) {
                return Result.success(defaultModels)
            }

            val response = connection.inputStream.bufferedReader().readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val models = jsonResponse["data"]?.jsonArray?.mapNotNull { model ->
                val obj = model.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: id

                // Mark free models
                val displayName = if (id.endsWith(":free")) "$name (Free)" else name
                AiModelInfo(id, displayName, AiProviderType.OPENROUTER)
            }?.sortedWith(compareBy(
                { !it.id.endsWith(":free") },  // Free models first
                { it.name }
            )) ?: defaultModels

            Result.success(if (models.isEmpty()) defaultModels else models)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading OpenRouter models, using defaults", e)
            Result.success(defaultModels)
        }
    }

    override suspend fun testConnection(apiKey: String, model: String): Result<Boolean> {
        return try {
            val result = generateContent(
                apiKey, model,
                listOf(AiMessage("user", "Say OK")),
                null
            )
            when (result) {
                is AiResult.Success -> Result.success(true)
                is AiResult.Error -> Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateContent(
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        systemPrompt: String?
    ): AiResult {
        return try {
            val url = URL("https://openrouter.ai/api/v1/chat/completions")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("HTTP-Referer", "https://directcan.app")
            connection.setRequestProperty("X-Title", "DirectCAN")
            connection.doOutput = true

            val messagesArray = buildJsonArray {
                if (systemPrompt != null) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", if (msg.role == "model") "assistant" else msg.role)
                        put("content", msg.content)
                    })
                }
            }

            val requestBody = buildJsonObject {
                put("model", model)
                put("messages", messagesArray)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                return AiResult.Error("API Error: $responseCode - $errorStream", responseCode)
            }

            val response = connection.inputStream.bufferedReader().readText()
            val jsonResponse = Json.parseToJsonElement(response).jsonObject

            val text = jsonResponse["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?: return AiResult.Error("No response text")

            AiResult.Success(text)
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter generation error", e)
            AiResult.Error(e.message ?: "Unknown error")
        }
    }
}

/**
 * Factory for creating AI providers
 */
object AiProviderFactory {
    private val providers = mapOf(
        AiProviderType.GEMINI to GeminiProvider(),
        AiProviderType.OPENAI to OpenAiProvider(),
        AiProviderType.ANTHROPIC to AnthropicProvider(),
        AiProviderType.OPENROUTER to OpenRouterProvider()
    )

    fun getProvider(type: AiProviderType): AiProvider {
        return providers[type] ?: providers[AiProviderType.GEMINI]!!
    }

    fun getAllProviders(): List<AiProvider> = providers.values.toList()
}
