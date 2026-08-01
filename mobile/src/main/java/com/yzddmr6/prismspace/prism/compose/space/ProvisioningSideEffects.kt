package com.yzddmr6.prismspace.prism.compose.space

import android.content.Context
import android.provider.Settings
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import eu.chainfire.libsuperuser.Shell
import java.util.concurrent.atomic.AtomicBoolean

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
        restore_prism_side_effects() {
          trap - EXIT HUP INT TERM
          $verifierRestore
          echo ${shellQuote("PRISM_SIDE_EFFECT_RESTORE verifier original=${input.verifierOriginal ?: "<unset>"}")}
          $maxUsersRestore
          echo ${shellQuote("PRISM_SIDE_EFFECT_RESTORE max_users original=${input.maxUsersOriginal ?: "<unset>"}")}
        }
        trap restore_prism_side_effects EXIT HUP INT TERM
        $verifierWrite
        $maxUsersWrite
        CREATE_OUTPUT="$(pm create-user --profileOf ${input.parentUserId} --managed PrismSpace 2>&1)"
        CREATE_STATUS="${'$'}?"
        printf '%s\n' "${'$'}CREATE_OUTPUT"
        [ "${'$'}CREATE_STATUS" -eq 0 ] || { echo 'PRISM_PROVISION_FAILED stage=create'; exit 20; }
        PROFILE_ID="$(printf '%s\n' "${'$'}CREATE_OUTPUT" | sed -n 's/.*[Ii][Dd] \([0-9][0-9]*\).*/\1/p' | tail -n 1)"
        [ -n "${'$'}PROFILE_ID" ] || { echo 'PRISM_PROVISION_FAILED stage=parse_user'; exit 20; }
        pm install -r --user "${'$'}PROFILE_ID"$debugFlag ${shellQuote(input.apkPath)} || { echo 'PRISM_PROVISION_FAILED stage=install'; exit 30; }
        dpm set-profile-owner --user "${'$'}PROFILE_ID" ${shellQuote(input.adminComponent)} || { echo 'PRISM_PROVISION_FAILED stage=owner'; exit 40; }
        am start-user "${'$'}PROFILE_ID" || { echo 'PRISM_PROVISION_FAILED stage=start'; exit 50; }
        echo "PRISM_PROVISION_SUCCESS user=${'$'}PROFILE_ID"
    """.trimIndent()
}

internal fun verifierRestoreCommand(original: String?): String =
    if (original == null) "settings delete global $VERIFIER_SETTING"
    else "settings put global $VERIFIER_SETTING ${shellQuote(original)}"

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

internal fun provisioningCompleted(lines: List<String>?, userId: Int): Boolean =
    lines?.any { it.trim() == "PRISM_PROVISION_SUCCESS user=$userId" } == true

internal fun provisioningFailure(lines: List<String>?): String? =
    lines?.firstOrNull { it.startsWith("PRISM_PROVISION_FAILED ") }

internal object ProvisioningSideEffects {
    private const val PREFS = "root_provisioning_side_effects"
    private const val KEY_PENDING = "verifier_restore_pending"
    private const val KEY_ORIGINAL_PRESENT = "verifier_original_present"
    private const val KEY_ORIGINAL_VALUE = "verifier_original_value"
    private const val TAG = "Prism.SpaceProvision"
    private val recoveryStarted = AtomicBoolean(false)

    fun verifierOriginal(context: Context): String? =
        Settings.Global.getString(context.contentResolver, VERIFIER_SETTING)

    fun recordBeforeWrite(context: Context, original: String?): Boolean {
        val recorded = prefs(context).edit()
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_ORIGINAL_PRESENT, original != null)
            .putString(KEY_ORIGINAL_VALUE, original.orEmpty())
            .commit()
        if (!recorded) return false
        DiagnosticLog.i(TAG, "side_effect write key=$VERIFIER_SETTING original=${original ?: "<unset>"} temporary=0")
        return true
    }

    fun logMaxUsersWrite(original: String?, temporary: Int) {
        DiagnosticLog.i(TAG, "side_effect write key=$MAX_USERS_PROPERTY original=${original ?: "<unset>"} temporary=$temporary")
    }

    fun onShellCompleted(context: Context, verifierOriginal: String?, maxUsersOriginal: String?) {
        val verifierRestored = verifierOriginal(context) == verifierOriginal
        if (verifierRestored) clearPending(context)
        DiagnosticLog.i(
            TAG,
            "side_effect restore key=$VERIFIER_SETTING original=${verifierOriginal ?: "<unset>"} restored=$verifierRestored",
        )
        DiagnosticLog.i(
            TAG,
            "side_effect restore key=$MAX_USERS_PROPERTY original=${maxUsersOriginal ?: "<unset>"} shell_completed=true",
        )
    }

    fun restorePendingOnStartup(context: Context) {
        if (!recoveryStarted.compareAndSet(false, true)) return
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_PENDING, false)) return
        val original = if (prefs.getBoolean(KEY_ORIGINAL_PRESENT, false)) {
            prefs.getString(KEY_ORIGINAL_VALUE, "").orEmpty()
        } else null
        Thread {
            if (verifierOriginal(context) == original) {
                clearPending(context)
                DiagnosticLog.i(TAG, "side_effect startup verification already_restored original=${original ?: "<unset>"}")
                return@Thread
            }
            DiagnosticLog.w(TAG, "side_effect startup recovery pending original=${original ?: "<unset>"}")
            Shell.SU.run(verifierRestoreCommand(original))
            val restored = verifierOriginal(context) == original
            if (restored) clearPending(context)
            DiagnosticLog.i(
                TAG,
                "side_effect restore key=$VERIFIER_SETTING original=${original ?: "<unset>"} startup=true restored=$restored",
            )
        }.apply { name = "Prism-verifier-restore" }.start()
    }

    private fun clearPending(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
