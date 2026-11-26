package com.kayx.manager.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiToolRegistryTest {
    private class EchoTool(
        override val spec: AiToolSpec = AiToolSpec(
            name = "lookup_item",
            description = "Look up a harmless test item",
            parameters = mapOf("query" to AiArgumentType.Text(maxLength = 20)),
            required = setOf("query"),
            requiredPermission = "files.read"
        )
    ) : KayXAiTool {
        var executions = 0
        override suspend fun execute(arguments: Map<String, Any?>, context: AiToolExecutionContext): String {
            executions++
            return arguments.getValue("query") as String
        }
    }

    @Test fun rejectsUnregisteredTool() = runBlocking {
        val registry = registry(emptyList())
        val result = registry.execute(AiToolCall("1", "delete_everything", emptyMap()), context())
        assertEquals(AiToolResult.Rejected("Tool is not available"), result)
    }

    @Test fun rejectsUnknownAndMissingArgumentsBeforeExecution() = runBlocking {
        val tool = EchoTool()
        val registry = registry(listOf(tool))
        val unknown = registry.execute(call(mapOf("query" to "ok", "extra" to true)), context())
        val missing = registry.execute(call(emptyMap()), context())
        assertTrue(unknown is AiToolResult.Rejected)
        assertTrue(missing is AiToolResult.Rejected)
        assertEquals(0, tool.executions)
    }

    @Test fun rejectsWrongTypeAndOversizedText() = runBlocking {
        val tool = EchoTool()
        val registry = registry(listOf(tool))
        assertTrue(registry.execute(call(mapOf("query" to 12)), context()) is AiToolResult.Rejected)
        assertTrue(registry.execute(call(mapOf("query" to "x".repeat(21))), context()) is AiToolResult.Rejected)
        assertEquals(0, tool.executions)
    }

    @Test fun requiresPermissionBeforeExecution() = runBlocking {
        val tool = EchoTool()
        var permissionChecks = 0
        val registry = AiToolRegistry(
            listOf(tool),
            AiToolPermissionGate { permissionChecks++; false },
            AiDestructiveConfirmation { _, _ -> true }
        )
        assertEquals(AiToolResult.PermissionDenied, registry.execute(call(mapOf("query" to "ok")), context()))
        assertEquals(1, permissionChecks)
        assertEquals(0, tool.executions)
    }

    @Test fun destructiveToolRequiresExplicitConfirmation() = runBlocking {
        val tool = EchoTool(AiToolSpec(
            name = "remove_item",
            description = "Remove one selected item",
            parameters = mapOf("id" to AiArgumentType.Text(64)),
            required = setOf("id"),
            access = AiToolAccess.DESTRUCTIVE
        ))
        var confirmations = 0
        val registry = AiToolRegistry(
            listOf(tool),
            AiToolPermissionGate { true },
            AiDestructiveConfirmation { _, _ -> confirmations++; false }
        )
        assertEquals(
            AiToolResult.ConfirmationRequired,
            registry.execute(AiToolCall("1", "remove_item", mapOf("id" to "item-1")), context())
        )
        assertEquals(1, confirmations)
        assertEquals(0, tool.executions)
    }

    @Test fun executesReadOnlyToolAfterValidation() = runBlocking {
        val tool = EchoTool()
        val registry = registry(listOf(tool))
        assertEquals(
            AiToolResult.Success("hello"),
            registry.execute(call(mapOf("query" to "hello")), context())
        )
        assertEquals(1, tool.executions)
    }

    @Test fun timesOutSlowToolAndReturnsSanitizedResult() = runBlocking {
        val slow = object : KayXAiTool {
            override val spec = AiToolSpec(
                name = "slow_lookup",
                description = "Slow lookup",
                parameters = emptyMap(),
                timeoutMs = 10
            )
            override suspend fun execute(arguments: Map<String, Any?>, context: AiToolExecutionContext): String {
                delay(500)
                return "should not finish"
            }
        }
        val result = registry(listOf(slow)).execute(
            AiToolCall("1", "slow_lookup", emptyMap()), context()
        )
        assertEquals(AiToolResult.TimedOut, result)
    }

    @Test fun permissionCheckFailureFailsClosed() = runBlocking {
        val tool = EchoTool()
        val registry = AiToolRegistry(
            listOf(tool),
            AiToolPermissionGate { error("permission backend unavailable") },
            AiDestructiveConfirmation { _, _ -> true }
        )
        assertEquals(AiToolResult.PermissionDenied, registry.execute(call(mapOf("query" to "ok")), context()))
        assertEquals(0, tool.executions)
    }

    @Test fun rejectsDuplicateToolNames() {
        val first = EchoTool()
        val second = EchoTool()
        try {
            registry(listOf(first, second))
            fail("Duplicate names must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: app code must not register ambiguous tools.
        }
    }

    @Test fun propagatesCallerCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val waiting = object : KayXAiTool {
            override val spec = AiToolSpec("waiting", "Wait for cancellation", emptyMap())
            override suspend fun execute(arguments: Map<String, Any?>, context: AiToolExecutionContext): String {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch {
            registry(listOf(waiting)).execute(AiToolCall("1", "waiting", emptyMap()), context())
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun doesNotLeakToolExceptionMessage() = runBlocking {
        val broken = object : KayXAiTool {
            override val spec = AiToolSpec("broken", "Broken test", emptyMap())
            override suspend fun execute(arguments: Map<String, Any?>, context: AiToolExecutionContext): String {
                error("secret path and credential must not leak")
            }
        }
        assertEquals(
            AiToolResult.Failed,
            registry(listOf(broken)).execute(AiToolCall("1", "broken", emptyMap()), context())
        )
    }

    private fun registry(tools: Collection<KayXAiTool>) = AiToolRegistry(
        tools,
        AiToolPermissionGate { true },
        AiDestructiveConfirmation { _, _ -> true }
    )

    private fun call(args: Map<String, Any?>) = AiToolCall("call-1", "lookup_item", args)
    private fun context() = AiToolExecutionContext("request-1")
}
