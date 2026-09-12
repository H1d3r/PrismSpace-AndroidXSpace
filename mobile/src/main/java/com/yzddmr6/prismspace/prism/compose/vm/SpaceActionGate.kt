package com.yzddmr6.prismspace.prism.compose.vm

import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.space.SpaceUsability

/**
 * Presentation for space-dependent action entries (clone uninstall, pending-install continue),
 * gated on the same space-usability source as clone launch. When the space is not usable the entry
 * renders disabled and tapping it surfaces [guidance] instead of firing any request — the gate
 * fails closed and nothing is queued, removed, or launched.
 */
data class SpaceActionGate(val enabled: Boolean, val guidance: String?)

fun uninstallGate(usability: SpaceUsability, res: StringResolver): SpaceActionGate = when (usability) {
    SpaceUsability.Usable -> SpaceActionGate(enabled = true, guidance = null)
    SpaceUsability.Suspended ->
        SpaceActionGate(false, res(R.string.lz_uninstall_gate_suspended, emptyArray()))
    SpaceUsability.LockedNeedsUnlock ->
        SpaceActionGate(false, res(R.string.lz_uninstall_gate_locked, emptyArray()))
    SpaceUsability.BridgeNotReady ->
        SpaceActionGate(false, res(R.string.lz_uninstall_gate_bridge, emptyArray()))
    SpaceUsability.NotProvisioned ->
        SpaceActionGate(false, res(R.string.lz_uninstall_gate_missing, emptyArray()))
    SpaceUsability.Unknown ->
        SpaceActionGate(false, res(R.string.lz_uninstall_gate_unknown, emptyArray()))
}

/** Gate for the 「继续安装」 entry of a pending-install (待安装) task. The pending record itself is
 *  persistent and untouched by the gate; the entry re-enables automatically once the space is
 *  usable again. */
fun continueInstallGate(usability: SpaceUsability, res: StringResolver): SpaceActionGate = when (usability) {
    SpaceUsability.Usable -> SpaceActionGate(enabled = true, guidance = null)
    SpaceUsability.Suspended ->
        SpaceActionGate(false, res(R.string.lz_install_gate_suspended, emptyArray()))
    SpaceUsability.LockedNeedsUnlock ->
        SpaceActionGate(false, res(R.string.lz_install_gate_locked, emptyArray()))
    SpaceUsability.BridgeNotReady ->
        SpaceActionGate(false, res(R.string.lz_install_gate_bridge, emptyArray()))
    SpaceUsability.NotProvisioned ->
        SpaceActionGate(false, res(R.string.lz_install_gate_missing, emptyArray()))
    SpaceUsability.Unknown ->
        SpaceActionGate(false, res(R.string.lz_install_gate_unknown, emptyArray()))
}

/** Gate for the Files page send card: sending into the dual space needs a usable space + bridge. */
fun fileTransferGate(usability: SpaceUsability, res: StringResolver): SpaceActionGate = when (usability) {
    SpaceUsability.Usable -> SpaceActionGate(enabled = true, guidance = null)
    SpaceUsability.Suspended ->
        SpaceActionGate(false, res(R.string.lz_files_gate_suspended, emptyArray()))
    SpaceUsability.LockedNeedsUnlock ->
        SpaceActionGate(false, res(R.string.lz_files_gate_locked, emptyArray()))
    SpaceUsability.BridgeNotReady ->
        SpaceActionGate(false, res(R.string.lz_files_gate_bridge, emptyArray()))
    SpaceUsability.NotProvisioned ->
        SpaceActionGate(false, res(R.string.lz_files_gate_missing, emptyArray()))
    SpaceUsability.Unknown ->
        SpaceActionGate(false, res(R.string.lz_files_gate_unknown, emptyArray()))
}
