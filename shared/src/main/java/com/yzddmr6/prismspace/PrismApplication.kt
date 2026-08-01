package com.yzddmr6.prismspace

import android.app.Application
import com.yzddmr6.prismspace.analytics.CrashReport
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.BridgePorts
import com.yzddmr6.prismspace.bridge.BridgePortsContributors

/**
 * For singleton instance purpose only.
 *
 * Created by Oasis on 2018/1/3.
 */
class PrismApplication : Application() {

	companion object {
		@JvmStatic fun get() = sInstance

		lateinit var sInstance: PrismApplication
	}

	init {
		sInstance = this
		CrashReport.initCrashHandler()
	}

	override fun onCreate() {
		super.onCreate()
		DiagnosticLog.init(this)
		// Auxiliary processes (for example :api) do not instantiate the main-process
		// contributor providers and never host ShuttleProvider command dispatch.
		if (BridgePortsContributors.hasContributors()) {
			BridgePorts.install(BridgePortsContributors.assemble())
			BridgePorts.verifyInstalled()
		}
		CrashReport.initCrashHandler()
	}
}
