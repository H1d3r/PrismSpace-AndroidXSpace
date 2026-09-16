package com.yzddmr6.prismspace

import android.app.Application
import android.os.Build
import com.yzddmr6.prismspace.analytics.CrashReport
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.util.Hacks
import com.yzddmr6.prismspace.util.RomVariants

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
		CrashReport.initCrashHandler()
		// ROM identity separates vendor-profile quirks (MIUI XSpace, OEM clone users) from AOSP behavior.
		val miuiVersion = Hacks.SystemProperties_get.invoke("ro.miui.ui.version.name").statically().orEmpty()
		DiagnosticLog.i("Prism.Diag", "rom miui=${RomVariants.isMiui()} miui_version=$miuiVersion incremental=${Build.VERSION.INCREMENTAL}")
	}
}
