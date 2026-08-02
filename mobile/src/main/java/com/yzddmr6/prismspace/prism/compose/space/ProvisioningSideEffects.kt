package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.analytics.DiagnosticLog

internal const val MAX_USERS_PROPERTY = "fw.max_users"

internal data class RootProvisioningCommandInput(
    val parentUserId: Int,
    val temporaryMaxUsers: Int,
    val packageName: String,
    val adminComponent: String,
    val maxUsersOriginal: String?,
)

internal fun buildRootProvisioningCommand(input: RootProvisioningCommandInput): String {
    val maxUsersRestore = "setprop $MAX_USERS_PROPERTY ${shellQuote(input.maxUsersOriginal.orEmpty())}"
    val maxUsersWrite = if (input.maxUsersOriginal == input.temporaryMaxUsers.toString()) {
        "echo ${shellQuote("PRISM_SIDE_EFFECT_WRITE max_users unchanged original=${input.maxUsersOriginal}")}"
    } else {
        "setprop $MAX_USERS_PROPERTY ${input.temporaryMaxUsers}; " +
            "echo ${shellQuote("PRISM_SIDE_EFFECT_WRITE max_users original=${input.maxUsersOriginal ?: "<unset>"} temporary=${input.temporaryMaxUsers}")}"
    }
    return """
        PROFILE_ID=""
        PRISM_PROVISION_SUCCEEDED=0
        restore_prism_side_effects() {
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
        $maxUsersWrite
        CREATE_OUTPUT="$(pm create-user --profileOf ${input.parentUserId} --managed PrismSpace 2>&1)"
        CREATE_STATUS="${'$'}?"
        printf '%s\n' "${'$'}CREATE_OUTPUT"
        [ "${'$'}CREATE_STATUS" -eq 0 ] || fail_prism_provisioning 20 create
        PROFILE_ID="$(printf '%s\n' "${'$'}CREATE_OUTPUT" | sed -n 's/.*[Ii][Dd] \([0-9][0-9]*\).*/\1/p' | tail -n 1)"
        [ -n "${'$'}PROFILE_ID" ] || fail_prism_provisioning 20 parse_user
        pm install-existing --user "${'$'}PROFILE_ID" ${shellQuote(input.packageName)} || fail_prism_provisioning 30 install_existing
        dpm set-profile-owner --user "${'$'}PROFILE_ID" ${shellQuote(input.adminComponent)} || fail_prism_provisioning 40 owner
        am start-user "${'$'}PROFILE_ID" || fail_prism_provisioning 50 start
        PRISM_PROVISION_SUCCEEDED=1
        finish_prism_transaction
        echo "PRISM_PROVISION_SUCCESS user=${'$'}PROFILE_ID"
    """.trimIndent()
}

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

internal fun detachedRootProvisioningLauncher(command: String, outputPath: String): String =
    "setsid sh -c ${shellQuote(command)} >${shellQuote(outputPath)} 2>&1 </dev/null & echo ${'$'}!"

internal fun provisioningTransactionFinished(lines: List<String>): Boolean =
    lines.any { it.startsWith("PRISM_PROVISION_SUCCESS ") || it.startsWith("PRISM_PROVISION_FAILED ") }

internal fun provisioningCompleted(lines: List<String>?, userId: Int): Boolean =
    lines?.any { it.trim() == "PRISM_PROVISION_SUCCESS user=$userId" } == true

internal fun provisioningFailure(lines: List<String>?): String? =
    lines?.firstOrNull { it.startsWith("PRISM_PROVISION_FAILED ") }

internal object ProvisioningSideEffects {
    private const val TAG = "Prism.SpaceProvision"

    fun logMaxUsersWrite(original: String?, temporary: Int) {
        DiagnosticLog.i(TAG, "side_effect write key=$MAX_USERS_PROPERTY original=${original ?: "<unset>"} temporary=$temporary")
    }
}
