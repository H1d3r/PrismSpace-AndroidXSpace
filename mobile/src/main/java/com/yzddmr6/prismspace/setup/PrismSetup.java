package com.yzddmr6.prismspace.setup;

import static com.yzddmr6.prismspace.analytics.Analytics.Param.CONTENT;
import static java.lang.Boolean.FALSE;
import static java.util.stream.Collectors.toList;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.yzddmr6.prismspace.util.Dialogs;
import com.yzddmr6.prismspace.common.app.AppInfo;
import com.yzddmr6.prismspace.common.app.AppListProvider;
import com.yzddmr6.prismspace.analytics.Analytics;
import com.yzddmr6.prismspace.mobile.R;
import com.yzddmr6.prismspace.prism.compose.space.CreateSpaceResult;
import com.yzddmr6.prismspace.prism.compose.space.DeleteSpaceResult;
import com.yzddmr6.prismspace.prism.compose.space.RootSetupPresentation;
import com.yzddmr6.prismspace.prism.compose.space.RootSetupResultMapping;
import com.yzddmr6.prismspace.prism.compose.space.RootSetupUiOutcome;
import com.yzddmr6.prismspace.prism.compose.space.SpaceProvisioningEngine;
import com.yzddmr6.prismspace.prism.compose.space.SpaceDeletionCoordinator;
import com.yzddmr6.prismspace.prism.compose.vm.ProvisioningFeedbackBridge;
import com.yzddmr6.prismspace.bridge.Bridge;
import com.yzddmr6.prismspace.bridge.BridgeTargets;
import com.yzddmr6.prismspace.bridge.ParentTarget;
import com.yzddmr6.prismspace.bridge.QueryParentIsProfileOwner;
import com.yzddmr6.prismspace.shuttle.ShuttleOutcome;
import com.yzddmr6.prismspace.util.DevicePolicies;
import com.yzddmr6.prismspace.util.OwnerUser;
import com.yzddmr6.prismspace.util.ProfileUser;
import com.yzddmr6.prismspace.util.SafeAsyncTask;
import com.yzddmr6.prismspace.util.Users;

import java.util.List;
import java.util.stream.Stream;

/**
 * Implementation of PrismSpace / MainSpace setup & shutdown.
 *
 * Created by Oasis on 2017/3/8.
 */
public class PrismSetup {

	public static void requestProfileOwnerSetupWithRoot(final Activity activity) {
		final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.setup_root_profile_progress), true);
		SafeAsyncTask.execute(activity,
				SpaceProvisioningEngine::createSpaceBlocking,
				(context, result) -> showRootSetupResult(context, progress, result));
	}

	private static void showProfileAlreadyExistsDialog(final Activity activity) {
		Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_root_setup_profile_exists)
				.withOkButton(null).show();
			Analytics.$().event("setup_prism_root_skipped").withRaw("reason", "existing_profile").send();
	}

	private static void showRootSetupResult(final Activity activity, final ProgressDialog progress,
	                                        final CreateSpaceResult result) {
		final RootSetupPresentation presentation = RootSetupResultMapping.presentation(result);
		if (presentation.getOutcome() == RootSetupUiOutcome.Success) {
			dismissProgress(progress);
			Analytics.$().event("setup_prism_root_done").send();
			activity.finish();
		} else if (presentation.getOutcome() == RootSetupUiOutcome.ExistingProfile) {
			dismissProgress(progress);
			showProfileAlreadyExistsDialog(activity);
		} else {
			final int phase = presentation.getAnalyticsPhase() == null ? 2 : presentation.getAnalyticsPhase();
			Analytics.$().event("setup_prism_root_failed")
					.withRaw("phase", String.valueOf(phase))
					.with(CONTENT, result.toString()).send();
			dismissProgressAndShowError(activity, progress, phase, result);
		}
	}

	private static void dismissProgressAndShowError(final Activity activity, final ProgressDialog progress,
	                                                final int stage, final CreateSpaceResult result) {
		dismissProgress(progress);
		final String specificFailure = ProvisioningFeedbackBridge.specificRootSetupFailure(activity, result);
		final String message = specificFailure != null
				? specificFailure : activity.getString(R.string.dialog_space_setup_failed, stage);
		Dialogs.buildAlert(activity, null, message).withOkButton(null).show();
	}

	private static void dismissProgress(final ProgressDialog progress) {
		if (progress.isShowing()) progress.dismiss();
	}

	@OwnerUser public static void requestDeviceOrProfileOwnerDeactivation(final Activity activity) {
		new AlertDialog.Builder(activity).setTitle(R.string.dialog_title_warning).setMessage(R.string.dialog_rescind_message)
				.setPositiveButton(android.R.string.no, null).setNeutralButton(R.string.action_rescind, (d, w) -> {
					try {
						final DevicePolicies policies = new DevicePolicies(activity);
						final AppListProvider<AppInfo> provider = AppListProvider.getInstance(activity);
						final Stream<AppInfo> apps = provider.installedAppsInOwnerUser().stream();

						final List<String> frozen_pkgs = apps.filter(app -> app.isHidden()).map(app -> app.packageName).collect(toList());
						for (final String pkg : frozen_pkgs)
							policies.setApplicationHidden(pkg, false);

						final String[] suspended_pkgs = apps.filter(AppInfo::isSuspended).map(app -> app.packageName).toArray(String[]::new);
						policies.invoke(DevicePolicyManager::setPackagesSuspended, suspended_pkgs, false);
					} finally {
						deactivateDeviceOrProfileOwner(activity);
					}
				}).show();
	}

	private static void deactivateDeviceOrProfileOwner(final Activity activity) {
		Analytics.$().event("action_deactivate").send();
		final DevicePolicies policies = new DevicePolicies(activity);
		if (policies.isActiveDeviceOwner())
			policies.getManager().clearDeviceOwnerApp(activity.getPackageName());
		else clearProfileOwner(policies);
		try {	// Since Android 7.1, clearDeviceOwnerApp() itself does remove active device-admin,
			policies.execute(DevicePolicyManager::removeActiveAdmin);
		} catch (final SecurityException ignored) {}		//   thus SecurityException will be thrown here.

		activity.finishAffinity();	// Finish the whole activity stack.
		System.exit(0);		// Force termination of the whole app, to avoid potential inconsistency.
	}

	@SuppressLint("NewApi"/* hidden before N */) private static void clearProfileOwner(final DevicePolicies policies) {
		policies.execute(DevicePolicyManager::clearProfileOwner);
	}

	@ProfileUser public static void requestProfileRemoval(final Activity activity) {
		if (Users.isParentProfile()) throw new IllegalStateException("Must be called in managed profile");
		if (! new DevicePolicies(activity).isProfileOwner()) {
			showPromptForProfileManualRemoval(activity);
			return;
		}
		new AlertDialog.Builder(activity).setTitle(R.string.dialog_title_warning)
				.setMessage(R.string.dialog_destroy_message)
				.setPositiveButton(android.R.string.no, null)
				.setNeutralButton(R.string.action_destroy, (dd, ww) -> requestProfileRemovalConfirmed(activity)).show();
	}

	private static void requestProfileRemovalConfirmed(final Activity activity) {
		final ParentTarget target = BridgeTargets.INSTANCE.parent(activity);
		final ShuttleOutcome<Boolean> outcome = target == null ? null
				: Bridge.INSTANCE.inParent(activity, target).execute(QueryParentIsProfileOwner.INSTANCE);
		final Boolean parentIsProfileOwner = outcome instanceof ShuttleOutcome.Value
				? ((ShuttleOutcome.Value<Boolean>) outcome).getValue() : null;
		if (parentIsProfileOwner == FALSE)
			destroyProfileLegacy(activity);
		else new AlertDialog.Builder(activity).setTitle(R.string.dialog_title_warning)
				.setMessage(R.string.dialog_destroy_message_for_managed_user)
				.setPositiveButton(android.R.string.no, null)
				.setNeutralButton(R.string.action_destroy, (d, w) -> destroyProfileLegacy(activity)).show();
	}

	private static void showPromptForProfileManualRemoval(final Activity activity) {
		final AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setMessage(R.string.dialog_cannot_destroy_message)
				.setNegativeButton(android.R.string.ok, null);
		final Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);
		if (intent.resolveActivity(activity.getPackageManager()) == null) intent.setAction(Settings.ACTION_SETTINGS);	// Fallback to entrance of Settings
		if (intent.resolveActivity(activity.getPackageManager()) != null)
			dialog.setPositiveButton(R.string.open_settings, (d, w) -> activity.startActivity(intent));
		dialog.show();
		Analytics.$().event("cannot_destroy").send();
	}

	/** Compose-path entry: surface the system manual-removal prompt (used when the
	 *  feedback mapper reports routeToSystemRemoval). Delegates to the private impl. */
	@ProfileUser public static void promptManualRemoval(final Activity activity) {
		showPromptForProfileManualRemoval(activity);
	}

	/** Legacy entry preserving the original "any failure -> manual-removal prompt" UX
	 *  for the non-Compose callers (requestProfileRemovalConfirmed paths). */
	@ProfileUser private static void destroyProfileLegacy(final Activity activity) {
		if (! (SpaceDeletionCoordinator.deleteCurrentProfile(activity) instanceof DeleteSpaceResult.Success))
			showPromptForProfileManualRemoval(activity);
	}
}
