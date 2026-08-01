package com.yzddmr6.prismspace.bridge

import android.content.Context
import android.os.Bundle
import com.yzddmr6.prismspace.analytics.DiagnosticLog

enum class BridgeErrorCategory { HandlerUnavailable, InvalidRequest, ExecutionFailed }

data class BridgeError(val category: BridgeErrorCategory, val operation: String, val message: String)

class BridgeExecutionException(val bridgeError: BridgeError) : RuntimeException(
    "${bridgeError.category}: ${bridgeError.operation}: ${bridgeError.message}",
)

internal object BridgeDispatcher {
    fun dispatch(context: Context, command: BridgeCommand<*>): Bundle = try {
        when (command) {
            Ping -> success(Ping, true)
            is SetAppFrozen -> appControl(command) { setAppFrozen(context, command.packageName, command.frozen) }
            is EnsureAppHiddenState -> appControl(command) { ensureAppHiddenState(context, command.packageName, command.hidden) }
            is SetPackageSuspended -> appControl(command) { setPackageSuspended(context, command.packageName, command.suspended) }
            is SetPackagesSuspended -> appControl(command) {
                setPackagesSuspended(context, command.packageNames, command.suspended)
            }
            is SetPackagesFrozen -> appControl(command) {
                setPackagesFrozen(context, command.packageNames, command.frozen)
            }
            is EnsureAppFreeToLaunch -> appControl(command) { ensureAppFreeToLaunch(context, command.packageName) }
            is MarkClonedSystemApp -> appControl(command) { markClonedSystemApp(context, command.packageName) }
            is EnableSystemApp -> appControl(command) { enableSystemApp(context, command.packageName) }
        }
    } catch (error: Throwable) {
        failure(command.id, BridgeErrorCategory.ExecutionFailed, error.javaClass.name + ": " + error.message.orEmpty())
    }

    private fun <R> appControl(command: BridgeCommand<R>, block: AppControlPort.() -> R): Bundle {
        val port = BridgePorts.handlers()?.appControl
            ?: return failure(command.id, BridgeErrorCategory.HandlerUnavailable, "AppControlPort")
        return success(command, port.block())
    }

    private fun <R> success(command: BridgeCommand<R>, result: R) = Bundle().apply {
        command.encodeResult(result, this)
    }

    private fun failure(operation: String, category: BridgeErrorCategory, message: String) = Bundle().apply {
        putString(ERROR_CATEGORY, category.name)
        putString(ERROR_OPERATION, operation)
        putString(ERROR_MESSAGE, message)
        DiagnosticLog.e(TAG, "bridge_dispatch_failed operation=$operation category=$category message=$message")
    }
}

internal object BridgeWire {
    const val KEY_COMMAND = "bridge.command"

    fun methodName(command: BridgeCommand<*>) = command.id

    fun readCommand(extras: Bundle): BridgeCommand<*>? {
        extras.classLoader = BridgeCommand::class.java.classLoader
        @Suppress("DEPRECATION")
        return extras.getParcelable(KEY_COMMAND)
    }

    fun request(command: BridgeCommand<*>) = Bundle(1).apply {
        classLoader = BridgeCommand::class.java.classLoader
        putParcelable(KEY_COMMAND, command)
    }

    fun <R> decode(command: BridgeCommand<R>, response: Bundle): R {
        response.classLoader = BridgeCommand::class.java.classLoader
        val category = response.getString(ERROR_CATEGORY)?.let(BridgeErrorCategory::valueOf)
        if (category != null) throw BridgeExecutionException(
            BridgeError(
                category,
                response.getString(ERROR_OPERATION).orEmpty(),
                response.getString(ERROR_MESSAGE).orEmpty(),
            ),
        )
        return command.decodeResult(response)
    }

    fun invalidRequest(operation: String, message: String): Bundle = Bundle().apply {
        putString(ERROR_CATEGORY, BridgeErrorCategory.InvalidRequest.name)
        putString(ERROR_OPERATION, operation)
        putString(ERROR_MESSAGE, message)
        DiagnosticLog.e(TAG, "bridge_invalid_request operation=$operation message=$message")
    }
}

private const val ERROR_CATEGORY = "bridge.error.category"
private const val ERROR_OPERATION = "bridge.error.operation"
private const val ERROR_MESSAGE = "bridge.error.message"
private const val TAG = "Prism.Bridge"
