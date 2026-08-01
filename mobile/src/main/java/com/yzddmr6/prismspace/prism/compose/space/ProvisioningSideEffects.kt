package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.provider.Settings
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import eu.chainfire.libsuperuser.Shell
import java.util.UUID

internal const val VERIFIER_SETTING = "verifier_verify_adb_installs"
internal const val MAX_USERS_PROPERTY = "fw.max_users"

internal data class RootProvisioningCommandInput(
    val parentUserId: Int,
    val temporaryMaxUsers: Int,
    val apkPath: String,
    val adminComponent: String,
    val debugBuild: Boolean,
    val verifierOriginal: String?,
    val maxUsersOriginal: String?,
)

internal fun buildRootProvisioningCommand(input: RootProvisioningCommandInput): String {
    val verifierRestore = verifierRestoreCommand(input.verifierOriginal)
    val maxUsersRestore = "setprop $MAX_USERS_PROPERTY ${shellQuote(input.maxUsersOriginal.orEmpty())}"
    val verifierWrite = if (input.verifierOriginal == "0") {
        "echo 'PRISM_SIDE_EFFECT_WRITE verifier unchanged original=0'"
    } else {
        "settings put global $VERIFIER_SETTING 0; " +
            "echo ${shellQuote("PRISM_SIDE_EFFECT_WRITE verifier original=${input.verifierOriginal ?: "<unset>"} temporary=0")}"
    }
    val maxUsersWrite = if (input.maxUsersOriginal == input.temporaryMaxUsers.toString()) {
        "echo ${shellQuote("PRISM_SIDE_EFFECT_WRITE max_users unchanged original=${input.maxUsersOriginal}")}"
    } else {
        "setprop $MAX_USERS_PROPERTY ${input.temporaryMaxUsers}; " +
            "echo ${shellQuote("PRISM_SIDE_EFFECT_WRITE max_users original=${input.maxUsersOriginal ?: "<unset>"} temporary=${input.temporaryMaxUsers}")}"
    }
    val debugFlag = if (input.debugBuild) " -t" else ""
    return """
        PROFILE_ID=""
        PRISM_PROVISION_SUCCEEDED=0
        restore_prism_side_effects() {
          $verifierRestore
          echo ${shellQuote("PRISM_SIDE_EFFECT_RESTORE verifier original=${input.verifierOriginal ?: "<unset>"}")}
          $maxUsersRestore
          echo ${shellQuote("PRISM_SIDE_EFFECT_RESTORE max_users original=${input.maxUsersOriginal ?: "<unset>"}")}
        }
        finish_prism_transaction() {
          trap - EXIT HUP INT TERM
          restore_prism_side_effects
          if [ "${'$'}PRISM_PROVISION_SUCCEEDED" -ne 1 ] && [ -n "${'$'}PROFILE_ID" ]; then
            PRISM_ROLLBACK_OUTPUT="$(pm remove-user "${'$'}PROFILE_ID" 2>&1)"
            PRISM_ROLLBACK_STATUS="${'$'}?"
            printf '%s\n' "${'$'}PRISM_ROLLBACK_OUTPUT"
            if [ "${'$'}PRISM_ROLLBACK_STATUS" -eq 0 ]; then
              echo "PRISM_PROVISION_ROLLBACK user=${'$'}PROFILE_ID result=success"
            else
              echo "PRISM_PROVISION_ROLLBACK user=${'$'}PROFILE_ID result=failed status=${'$'}PRISM_ROLLBACK_STATUS"
            fi
          fi
        }
        fail_prism_provisioning() {
          PRISM_FAILURE_STATUS="${'$'}1"
          PRISM_FAILURE_STAGE="${'$'}2"
          finish_prism_transaction
          echo "PRISM_PROVISION_FAILED stage=${'$'}PRISM_FAILURE_STAGE"
          exit "${'$'}PRISM_FAILURE_STATUS"
        }
        handle_prism_unexpected_exit() {
          finish_prism_transaction
          [ "${'$'}PRISM_PROVISION_SUCCEEDED" -eq 1 ] || echo "PRISM_PROVISION_FAILED stage=unexpected_exit"
        }
        trap handle_prism_unexpected_exit EXIT
        trap 'fail_prism_provisioning 70 interrupted' HUP INT TERM
        $verifierWrite
        $maxUsersWrite
        CREATE_OUTPUT="$(pm create-user --profileOf ${input.parentUserId} --managed PrismSpace 2>&1)"
        CREATE_STATUS="${'$'}?"
        printf '%s\n' "${'$'}CREATE_OUTPUT"
        [ "${'$'}CREATE_STATUS" -eq 0 ] || fail_prism_provisioning 20 create
        PROFILE_ID="$(printf '%s\n' "${'$'}CREATE_OUTPUT" | sed -n 's/.*[Ii][Dd] \([0-9][0-9]*\).*/\1/p' | tail -n 1)"
        [ -n "${'$'}PROFILE_ID" ] || fail_prism_provisioning 20 parse_user
        pm install -r --user "${'$'}PROFILE_ID"$debugFlag ${shellQuote(input.apkPath)} || fail_prism_provisioning 30 install
        restore_prism_side_effects
        dpm set-profile-owner --user "${'$'}PROFILE_ID" ${shellQuote(input.adminComponent)} || fail_prism_provisioning 40 owner
        am start-user "${'$'}PROFILE_ID" || fail_prism_provisioning 50 start
        PRISM_PROVISION_SUCCEEDED=1
        finish_prism_transaction
        echo "PRISM_PROVISION_SUCCESS user=${'$'}PROFILE_ID"
    """.trimIndent()
}

internal fun verifierRestoreCommand(original: String?): String =
    if (original == null) "settings delete global $VERIFIER_SETTING"
    else "settings put global $VERIFIER_SETTING ${shellQuote(original)}"

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

internal fun detachedRootProvisioningLauncher(command: String, outputPath: String): String =
    "setsid sh -c ${shellQuote(command)} >${shellQuote(outputPath)} 2>&1 </dev/null & echo ${'$'}!"

internal fun provisioningTransactionFinished(lines: List<String>): Boolean =
    lines.any { it.startsWith("PRISM_PROVISION_SUCCESS ") || it.startsWith("PRISM_PROVISION_FAILED ") }

internal fun provisioningCompleted(lines: List<String>?, userId: Int): Boolean =
    lines?.any { it.trim() == "PRISM_PROVISION_SUCCESS user=$userId" } == true

internal fun provisioningFailure(lines: List<String>?): String? =
    lines?.firstOrNull { it.startsWith("PRISM_PROVISION_FAILED ") }

internal enum class PendingRestoreAction { ClearRecord, RequirePrivilege }

internal fun pendingRestoreAction(original: String?, current: String?): PendingRestoreAction =
    if (current == original) PendingRestoreAction.ClearRecord else PendingRestoreAction.RequirePrivilege

internal object ProvisioningSideEffects {
    private const val PREFS = "root_provisioning_side_effects"
    private const val KEY_PENDING = "verifier_restore_pending"
    private const val KEY_ORIGINAL_PRESENT = "verifier_original_present"
    private const val KEY_ORIGINAL_VALUE = "verifier_original_value"
    private const val KEY_ATTEMPT_TOKEN = "attempt_token"
    private const val TAG = "Prism.SpaceProvision"

    fun verifierOriginal(context: Context): String? =
        Settings.Global.getString(context.contentResolver, VERIFIER_SETTING)

    @Synchronized
    fun recordBeforeWrite(context: Context, original: String?): String? {
        val token = UUID.randomUUID().toString()
        val recorded = prefs(context).edit()
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_ORIGINAL_PRESENT, original != null)
            .putString(KEY_ORIGINAL_VALUE, original.orEmpty())
            .putString(KEY_ATTEMPT_TOKEN, token)
            .commit()
        if (!recorded) return null
        DiagnosticLog.i(TAG, "side_effect write key=$VERIFIER_SETTING original=${original ?: "<unset>"} temporary=0")
        return token
    }

    fun logMaxUsersWrite(original: String?, temporary: Int) {
        DiagnosticLog.i(TAG, "side_effect write key=$MAX_USERS_PROPERTY original=${original ?: "<unset>"} temporary=$temporary")
    }

    @Synchronized
    fun onShellCompleted(
        context: Context,
        attemptToken: String?,
        verifierOriginal: String?,
        maxUsersOriginal: String?,
    ) {
        val verifierRestored = verifierOriginal(context) == verifierOriginal
        val recordCleared = verifierRestored && attemptToken != null && clearPendingIfOwned(context, attemptToken)
        DiagnosticLog.i(
            TAG,
            "side_effect restore key=$VERIFIER_SETTING original=${verifierOriginal ?: "<unset>"} " +
                "restored=$verifierRestored record_cleared=$recordCleared",
        )
        DiagnosticLog.i(
            TAG,
            "side_effect restore key=$MAX_USERS_PROPERTY original=${maxUsersOriginal ?: "<unset>"} shell_completed=true",
        )
    }

    /** Worker-thread preflight. It never calls su. */
    @JvmStatic @Synchronized
    fun hasPendingRestore(context: Context): Boolean {
        val pending = readPending(context) ?: return false
        if (!pendingRecordOwned(pending.token, prefs(context).getString(KEY_ATTEMPT_TOKEN, null))) return false
        return when (pendingRestoreAction(pending.original, verifierOriginal(context))) {
            PendingRestoreAction.ClearRecord -> {
                val cleared = clearPendingIfOwned(context, pending.token)
                DiagnosticLog.i(
                    TAG,
                    "side_effect startup verification already_restored original=${pending.original ?: "<unset>"} " +
                        "record_cleared=$cleared",
                )
                false
            }
            PendingRestoreAction.RequirePrivilege -> {
                DiagnosticLog.w(TAG, "side_effect startup recovery pending original=${pending.original ?: "<unset>"}")
                true
            }
        }
    }

    /** Invoke only after an explicit foreground user confirmation. */
    @JvmStatic @Synchronized
    fun restorePendingAfterUserApproval(context: Context): Boolean {
        val pending = readPending(context) ?: return true
        if (!pendingRecordOwned(pending.token, prefs(context).getString(KEY_ATTEMPT_TOKEN, null))) return false
        return runCatching {
            Shell.SU.run(verifierRestoreCommand(pending.original))
            val restored = verifierOriginal(context) == pending.original
            val cleared = restored && clearPendingIfOwned(context, pending.token)
            DiagnosticLog.i(
                TAG,
                "side_effect restore key=$VERIFIER_SETTING original=${pending.original ?: "<unset>"} " +
                    "user_approved=true restored=$restored record_cleared=$cleared",
            )
            restored && cleared
        }.onFailure {
            DiagnosticLog.e(TAG, "side_effect user-approved recovery failed", it)
        }.getOrDefault(false)
    }

    private fun readPending(context: Context): PendingRestore? {
        val preferences = prefs(context)
        if (!preferences.getBoolean(KEY_PENDING, false)) return null
        val original = if (preferences.getBoolean(KEY_ORIGINAL_PRESENT, false)) {
            preferences.getString(KEY_ORIGINAL_VALUE, "").orEmpty()
        } else null
        val token = preferences.getString(KEY_ATTEMPT_TOKEN, null) ?: LEGACY_PENDING_TOKEN
        return PendingRestore(token, original)
    }

    private fun clearPendingIfOwned(context: Context, expectedToken: String): Boolean {
        val preferences = prefs(context)
        if (!pendingRecordOwned(expectedToken, preferences.getString(KEY_ATTEMPT_TOKEN, null))) return false
        return preferences.edit().clear().commit()
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class PendingRestore(val token: String, val original: String?)
}

internal const val LEGACY_PENDING_TOKEN = "<legacy>"

internal fun pendingRecordOwned(expectedToken: String, currentToken: String?): Boolean =
    expectedToken == currentToken || expectedToken == LEGACY_PENDING_TOKEN && currentToken == null
