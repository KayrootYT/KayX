package com.kayx.manager.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Only tools registered by KayX application code can be invoked by an AI response. */
enum class AiToolAccess { READ_ONLY, DESTRUCTIVE }

sealed interface AiArgumentType {
    data class Text(val maxLength: Int = 2_048, val allowedValues: Set<String>? = null) : AiArgumentType
    data class Integer(val min: Long = Long.MIN_VALUE, val max: Long = Long.MAX_VALUE) : AiArgumentType
    data class Decimal(val min: Double = -Double.MAX_VALUE, val max: Double = Double.MAX_VALUE) : AiArgumentType
    data object BooleanValue : AiArgumentType
}

data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, AiArgumentType>,
    val required: Set<String> = emptySet(),
    val access: AiToolAccess = AiToolAccess.READ_ONLY,
    val requiredPermission: String = "ai.tool",
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    init {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")))
        require(description.isNotBlank() && description.length <= 512)
        // OpenAI strict function schemas require every declared property to be required.
        require(required == parameters.keys)
        require(timeoutMs in 1..MAX_TIMEOUT_MS)
        require(requiredPermission.isNotBlank() && requiredPermission.length <= 128)
        require(parameters.size <= MAX_PARAMETERS)
        parameters.forEach { (key, type) ->
            require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")))
            when (type) {
                is AiArgumentType.Text -> require(type.maxLength in 1..16_384)
                is AiArgumentType.Integer -> require(type.min <= type.max)
                is AiArgumentType.Decimal -> require(type.min.isFinite() && type.max.isFinite() && type.min <= type.max)
                AiArgumentType.BooleanValue -> Unit
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MAX_PARAMETERS = 32
        const val MAX_TIMEOUT_MS = 120_000L
    }
}

fun interface AiToolPermissionGate {
    suspend fun isGranted(permission: String): Boolean
}

fun interface AiDestructiveConfirmation {
    /** Must display a user-visible confirmation UI and return true only after an explicit user action. */
    suspend fun confirm(toolName: String, safeSummary: String): Boolean
}

fun interface AiToolAudit {
    /** Implementations must record metadata only; never pass arguments, prompts, credentials or results. */
    fun record(toolName: String, outcome: AiToolAuditOutcome)
}

enum class AiToolAuditOutcome { STARTED, SUCCEEDED, DENIED, REJECTED, TIMED_OUT, FAILED, CANCELLED }

data class AiToolCall(val id: String, val name: String, val arguments: Map<String, Any?>)
data class AiToolExecutionContext(val requestId: String)

sealed interface AiToolResult {
    data class Success(val value: String) : AiToolResult
    data class Rejected(val reason: String) : AiToolResult
    data object PermissionDenied : AiToolResult
    data object ConfirmationRequired : AiToolResult
    data object TimedOut : AiToolResult
    data object Failed : AiToolResult
}

interface KayXAiTool {
    val spec: AiToolSpec
    /** Safe, concise description shown in the confirmation UI; do not include secrets. */
    fun confirmationSummary(arguments: Map<String, Any?>): String = spec.description
    suspend fun execute(arguments: Map<String, Any?>, context: AiToolExecutionContext): String
}

/**
 * Strict allow-list executor. Unknown tools/arguments and wrong types are rejected before execution.
 * Read-only and destructive tools share permission checks, but destructive tools additionally require
 * a separate explicit user confirmation. No tools are registered by default.
 */
class AiToolRegistry(
    tools: Collection<KayXAiTool>,
    private val permissionGate: AiToolPermissionGate,
    private val confirmation: AiDestructiveConfirmation,
    private val audit: AiToolAudit = AiToolAudit { _, _ -> }
) {
    private val toolsByName: Map<String, KayXAiTool>

    init {
        require(tools.size <= 32) { "Too many AI tools" }
        require(tools.map { it.spec.name }.distinct().size == tools.size) { "Duplicate AI tool name" }
        toolsByName = tools.associateBy { it.spec.name }
    }

    fun definitions(): List<AiToolSpec> = toolsByName.values.map { it.spec }

    suspend fun execute(call: AiToolCall, context: AiToolExecutionContext): AiToolResult {
        if (call.id.isBlank() || call.id.length > 128 || context.requestId.isBlank() || context.requestId.length > 128) {
            return AiToolResult.Rejected("Invalid tool call")
        }
        val tool = toolsByName[call.name] ?: return AiToolResult.Rejected("Tool is not available")
        val spec = tool.spec
        if (call.arguments.size > AiToolSpec.MAX_PARAMETERS) return AiToolResult.Rejected("Too many arguments")
        val validationError = validateArguments(spec, call.arguments)
        if (validationError != null) {
            recordAudit(spec.name, AiToolAuditOutcome.REJECTED)
            return AiToolResult.Rejected(validationError)
        }

        val granted = try {
            permissionGate.isGranted(spec.requiredPermission)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!granted) {
            recordAudit(spec.name, AiToolAuditOutcome.DENIED)
            return AiToolResult.PermissionDenied
        }
        if (spec.access == AiToolAccess.DESTRUCTIVE) {
            val approved = try {
                confirmation.confirm(spec.name, tool.confirmationSummary(call.arguments).take(256))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!approved) {
                recordAudit(spec.name, AiToolAuditOutcome.DENIED)
                return AiToolResult.ConfirmationRequired
            }
        }

        recordAudit(spec.name, AiToolAuditOutcome.STARTED)
        return try {
            val value = withTimeout(spec.timeoutMs) { tool.execute(call.arguments.toMap(), context) }
            recordAudit(spec.name, AiToolAuditOutcome.SUCCEEDED)
            AiToolResult.Success(value)
        } catch (_: TimeoutCancellationException) {
            recordAudit(spec.name, AiToolAuditOutcome.TIMED_OUT)
            AiToolResult.TimedOut
        } catch (cancelled: CancellationException) {
            recordAudit(spec.name, AiToolAuditOutcome.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            recordAudit(spec.name, AiToolAuditOutcome.FAILED)
            AiToolResult.Failed
        }
    }

    private fun recordAudit(toolName: String, outcome: AiToolAuditOutcome) {
        // An audit sink must never crash execution or receive sensitive payloads.
        runCatching { audit.record(toolName, outcome) }
    }

    private fun validateArguments(spec: AiToolSpec, args: Map<String, Any?>): String? {
        if (args.keys.any { it !in spec.parameters }) return "Unknown argument"
        if (spec.required.any { it !in args }) return "Required argument is missing"
        for ((key, value) in args) {
            val valid = when (val type = spec.parameters.getValue(key)) {
                is AiArgumentType.Text -> value is String && value.length <= type.maxLength &&
                    (type.allowedValues == null || value in type.allowedValues)
                is AiArgumentType.Integer -> value is Long && value >= type.min && value <= type.max
                is AiArgumentType.Decimal -> value is Number && value.toDouble().isFinite() &&
                    value.toDouble() >= type.min && value.toDouble() <= type.max
                AiArgumentType.BooleanValue -> value is Boolean
            }
            if (!valid) return "Invalid argument: $key"
        }
        return null
    }
}
