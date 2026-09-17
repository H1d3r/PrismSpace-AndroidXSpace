package com.yzddmr6.prismspace.setup;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserManager;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.yzddmr6.prismspace.mobile.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE;

/**
 * Evidence collector for the "managed provisioning entry does not resolve" failure.
 *
 * <p>{@code resolveActivity(ACTION_PROVISION_MANAGED_PROFILE) == null} only states that the
 * system provisioning entry is unreachable — it cannot say why. This app holds
 * {@code QUERY_ALL_PACKAGES}, so package visibility is not a cause; the remaining realities
 * are: the package is absent from the ROM, the package or its activities are disabled
 * (vendor dual-apps features are known to occupy the entry — recoverable), or the component
 * is enabled yet declares no matching filter (ROM stripping). These need different user
 * guidance, so the probe separates them and the decision functions stay pure for JVM tests.
 * The privileged (Shizuku/ROOT) fallback never touches this package, so it is offered
 * whenever the platform can run managed users at all — withholding it in the ABSENT case
 * (ROM stripped the entry but supports profiles, e.g. HyperOS 1) strands exactly the users
 * the fallback exists for.
 *
 * <p>Everything collected is safe to log: package presence/enabled states, handler counts,
 * booleans and restriction key names — no personal data.
 */
public final class ProvisioningProbe {

	public enum MissingState {
		/** com.android.managedprovisioning is not installed on this ROM at all. */
		ABSENT,
		/** The whole package is disabled. Users can often re-enable it in system Settings. */
		PACKAGE_DISABLED,
		/** Package is enabled but a matching activity exists only in disabled state. */
		COMPONENT_DISABLED,
		/** Package is enabled yet nothing declares the action — the ROM stripped the handler. */
		NO_HANDLER,
		/** Handlers resolve but resolveActivity still returned null, or evidence is contradictory. */
		UNKNOWN
	}

	private static final String MANAGED_PROVISIONING_PACKAGE = "com.android.managedprovisioning";

	public final int enabled_handlers;
	public final int all_handlers;
	public final boolean package_present;
	public final @Nullable Integer package_enabled_setting;
	public final @Nullable Boolean provisioning_allowed;
	public final boolean managed_users_feature;
	public final String restrictions;

	private ProvisioningProbe(final int enabled_handlers, final int all_handlers, final boolean package_present,
			final @Nullable Integer package_enabled_setting, final @Nullable Boolean provisioning_allowed,
			final boolean managed_users_feature, final String restrictions) {
		this.enabled_handlers = enabled_handlers;
		this.all_handlers = all_handlers;
		this.package_present = package_present;
		this.package_enabled_setting = package_enabled_setting;
		this.provisioning_allowed = provisioning_allowed;
		this.managed_users_feature = managed_users_feature;
		this.restrictions = restrictions;
	}

	public static ProvisioningProbe collect(final Context context) {
		final PackageManager pm = context.getPackageManager();
		final Intent intent = new Intent(ACTION_PROVISION_MANAGED_PROFILE);
		int enabled = -1, all = -1;
		try { enabled = pm.queryIntentActivities(intent, 0).size(); } catch (final RuntimeException ignored) {}
		try { all = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS).size(); } catch (final RuntimeException ignored) {}

		boolean present = false;
		Integer enabled_setting = null;
		try {
			enabled_setting = pm.getApplicationEnabledSetting(MANAGED_PROVISIONING_PACKAGE);
			present = true;
		} catch (final RuntimeException ignored) {}	// NameNotFound surfaces as IllegalArgumentException

		Boolean allowed = null;
		try {
			final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
			if (dpm != null) allowed = dpm.isProvisioningAllowed(ACTION_PROVISION_MANAGED_PROFILE);
		} catch (final RuntimeException ignored) {}

		boolean managed_users = false;
		try { managed_users = pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS); } catch (final RuntimeException ignored) {}

		String restriction_keys = "";
		try {
			final UserManager um = context.getSystemService(UserManager.class);
			if (um != null) {
				final List<String> keys = new ArrayList<>(um.getUserRestrictions().keySet());
				keys.removeIf(key -> !key.contains("user") && !key.contains("profile") && !key.contains("managed") && !key.contains("add"));
				Collections.sort(keys);
				restriction_keys = keys.toString();
			}
		} catch (final RuntimeException ignored) {}

		return new ProvisioningProbe(enabled, all, present, enabled_setting, allowed, managed_users, restriction_keys);
	}

	public MissingState classify() {
		return classify(package_present, package_enabled_setting, enabled_handlers, all_handlers);
	}

	/** Pure decision, separated for JVM tests. Counts are -1 when the query itself failed. */
	static MissingState classify(final boolean package_present, final @Nullable Integer package_enabled_setting,
			final int enabled_handlers, final int all_handlers) {
		if (! package_present) return MissingState.ABSENT;
		if (package_enabled_setting != null && package_enabled_setting > PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
			return MissingState.PACKAGE_DISABLED;
		if (all_handlers > enabled_handlers && all_handlers > 0) return MissingState.COMPONENT_DISABLED;
		if (enabled_handlers == 0) return MissingState.NO_HANDLER;
		return MissingState.UNKNOWN;
	}

	/** Pure copy decision: the dual-apps-occupation copy is only honest when a handler exists to be occupied. */
	static @StringRes int errorMessageFor(final MissingState state, final int profile_count, final boolean managed_users_feature) {
		if (profile_count > 0 && state != MissingState.ABSENT && state != MissingState.NO_HANDLER)
			return R.string.setup_error_provisioning_blocked_by_profile;
		if (managed_users_feature)
			return R.string.setup_error_provisioning_entry_stripped;
		return R.string.setup_error_missing_managed_provisioning;
	}

	/** Pure policy: the privileged fallback never touches the missing provisioning package, so it
	 *  is worth offering whenever the platform itself can run managed users. Withholding it in the
	 *  ABSENT case (ROM stripped the entry but supports profiles, e.g. HyperOS 1) strands exactly
	 *  the users this path exists for. */
	static boolean shouldOfferPrivilegedFallback(final boolean managed_users_feature) {
		return managed_users_feature;
	}

	public String toLogString() {
		return String.format(Locale.US, "state=%s handlers=%d/%d mp_pkg=%s mp_enabled=%s dpm_allowed=%s feature_mu=%s restr=%s",
				classify(), enabled_handlers, all_handlers, package_present ? "present" : "absent",
				package_enabled_setting, provisioning_allowed, managed_users_feature, restrictions);
	}
}
