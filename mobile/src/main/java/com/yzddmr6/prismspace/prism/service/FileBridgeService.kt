package com.yzddmr6.prismspace.prism.service

import android.app.Activity
import android.app.admin.DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
import android.content.ComponentName
import android.content.ContentValues
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
import android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import com.yzddmr6.prismspace.bridge.AbortWriteSession
import com.yzddmr6.prismspace.bridge.BridgeFileStore
import com.yzddmr6.prismspace.bridge.BridgeTarget
import com.yzddmr6.prismspace.bridge.BridgeTargets
import com.yzddmr6.prismspace.bridge.BridgeTransferDirection
import com.yzddmr6.prismspace.bridge.CrossProfileForwardingKind
import com.yzddmr6.prismspace.bridge.DeletePerAppShareMarker
import com.yzddmr6.prismspace.bridge.FileBridgePort
import com.yzddmr6.prismspace.bridge.FinishWriteSession
import com.yzddmr6.prismspace.bridge.ImportApkSet
import com.yzddmr6.prismspace.bridge.InstallCrossProfileForwarding
import com.yzddmr6.prismspace.bridge.MAX_APK_PATH_COUNT
import com.yzddmr6.prismspace.bridge.OpenImagePickerInProfile
import com.yzddmr6.prismspace.bridge.OpenLatestForRead
import com.yzddmr6.prismspace.bridge.OpenWriteSession
import com.yzddmr6.prismspace.bridge.ProfileMediaEntryDto
import com.yzddmr6.prismspace.bridge.QueryLatestVisibleImage
import com.yzddmr6.prismspace.bridge.ReadSessionDto
import com.yzddmr6.prismspace.bridge.RunBridgeSelfTest
import com.yzddmr6.prismspace.bridge.SelfTestResultDto
import com.yzddmr6.prismspace.bridge.TransferHistoryDto
import com.yzddmr6.prismspace.bridge.WritePerAppShareMarker
import com.yzddmr6.prismspace.bridge.WriteSessionDto
import com.yzddmr6.prismspace.engine.CrossProfile
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.model.PerAppFileSharePolicy
import com.yzddmr6.prismspace.prism.model.PerAppFileShareSpec
import com.yzddmr6.prismspace.prism.model.PerAppShareDestination
import com.yzddmr6.prismspace.prism.ui.ProfileImagePickerActivity
import com.yzddmr6.prismspace.util.DPM
import com.yzddmr6.prismspace.util.DevicePolicies
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.charset.StandardCharsets

data class FileBridgeSelfTestResult(
    val success: Boolean,
    val message: String,
    val cloneUri: String? = null,
    val mainUri: String? = null,
)

data class FileTransferResult(
    val success: Boolean,
    val message: String,
    val displayName: String? = null,
    val targetUri: String? = null,
    val failureReason: FileTransferFailureReason? = null,
)

enum class FileTransferFailureReason {
    SpaceMissing,
    SpaceInactive,
    SourceUnreadable,
    BridgeNotReady,
    TimedOut,
    IOError,
    SpaceUnavailable,
    TargetWriteFailed,
    Cancelled,
}

enum class TransferDirection(val wireValue: String) {
    ToMain("toMain"),
    ToProfile("toProfile");

    companion object {
        fun fromWireValue(value: String?): TransferDirection? = entries.firstOrNull { it.wireValue == value }
    }
}

data class FileTransferDestination(val relativePath: String, val displayLocation: String, val isImage: Boolean)

internal object CrossSpaceFileTransferPolicy {
    fun destination(mimeType: String?): FileTransferDestination =
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) {
            FileTransferDestination(FileBridgeMediaWriter.DEFAULT_RELATIVE_PATH, "Pictures/PrismSpace", true)
        } else {
            FileTransferDestination(FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH, "Download/PrismSpace", false)
        }
}

class TransferCancellationSignal {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get()
}

/** A single-use source for one transfer. It carries metadata, not a private cached copy. */
class TransferSource private constructor(
    val displayName: String,
    val mime: String,
    val declaredSize: Long?,
    private val opener: () -> InputStream,
) {
    private val opened = AtomicBoolean(false)

    fun openOnce(): InputStream {
        check(opened.compareAndSet(false, true)) { "Transfer source was already opened" }
        return opener()
    }

    companion object {
        fun fromFile(file: File, displayName: String = file.name, mime: String): TransferSource =
            TransferSource(displayName, mime, file.length().takeIf { it >= 0L }) { file.inputStream() }

        fun fromUri(
            resolver: ContentResolver,
            uri: Uri,
            displayName: String,
            mime: String,
            declaredSize: Long?,
        ): TransferSource = fromUriCandidates(
            resolver, listOf(uri), displayName, mime, declaredSize,
        )

        fun fromUriCandidates(
            resolver: ContentResolver,
            uris: List<Uri>,
            displayName: String,
            mime: String,
            declaredSize: Long?,
        ): TransferSource = TransferSource(displayName, mime, declaredSize?.takeIf { it >= 0L }) {
            openFirstReadableCandidate(uris) { resolver.openInputStream(it) }.let { opened ->
                if (opened.index > 0) {
                    DiagnosticLog.i(
                        "Prism.FileSource",
                        "source URI opened with paired-user fallback authority=${opened.candidate.encodedAuthority}",
                    )
                }
                opened.stream
            }
        }

        internal fun testing(
            displayName: String = "test.bin",
            mime: String = "application/octet-stream",
            declaredSize: Long? = null,
            opener: () -> InputStream,
        ): TransferSource = TransferSource(displayName, mime, declaredSize, opener)
    }
}

internal data class OpenedSource<T>(
    val candidate: T,
    val index: Int,
    val stream: InputStream,
)

internal fun <T> openFirstReadableCandidate(
    candidates: List<T>,
    open: (T) -> InputStream?,
): OpenedSource<T> {
    require(candidates.isNotEmpty()) { "At least one source candidate is required" }
    var lastFailure: Exception? = null
    candidates.forEachIndexed { index, candidate ->
        try {
            val stream = open(candidate)
            if (stream != null) return OpenedSource(candidate, index, stream)
        } catch (failure: Exception) {
            lastFailure = failure
        }
    }
    throw FileNotFoundException("No readable source candidate").also { error ->
        lastFailure?.let(error::initCause)
    }
}

data class PerAppShareFolderResult(
    val success: Boolean,
    val message: String,
    val relativePath: String? = null,
    val markerDisplayName: String? = null,
)

class FileBridgeService {

    fun saveInCurrentSpace(
        context: Context,
        localFile: File,
        displayName: String,
        mime: String,
        cancellation: TransferCancellationSignal = TransferCancellationSignal(),
        onProgress: (Long) -> Unit = {},
    ): FileTransferResult {
        val safeName = FileTransferPolicy.safeDisplayName(displayName)
        if (!localFile.isFile || !localFile.canRead()) return FileTransferResult(
            false, str(context, R.string.fb_transfer_source_unreadable), safeName,
            failureReason = FileTransferFailureReason.SourceUnreadable,
        )
        val destination = CrossSpaceFileTransferPolicy.destination(mime)
        val store = if (destination.isImage) PROFILE_WRITE_MEDIA else PROFILE_WRITE_DOWNLOAD
        val session = runCatching { openProfileWriteSession(context, store, safeName, mime, destination.relativePath) }
            .getOrElse { return FileTransferResult(false, str(context, R.string.fb_transfer_target_failed), safeName,
                failureReason = FileTransferFailureReason.TargetWriteFailed) }
        val targetUri = session.getString(BRIDGE_KEY_URI)
            ?: return FileTransferResult(false, str(context, R.string.fb_transfer_target_failed), safeName,
                failureReason = FileTransferFailureReason.TargetWriteFailed)
        val pfd = session.getPfd()
        if (pfd == null) {
            abortProfileWriteSession(context, store, targetUri)
            return FileTransferResult(false, str(context, R.string.fb_transfer_target_failed), safeName,
                failureReason = FileTransferFailureReason.TargetWriteFailed)
        }
        return try {
            localFile.inputStream().use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                    copyCancellable(input, output, cancellation, onProgress)
                }
            }
            val uri = finishProfileTransfer(
                context, store, targetUri, safeName, destination.displayLocation,
                destination.isImage, null,
            )
            FileTransferResult(true, str(context, R.string.fb_transfer_saved_here, safeName), safeName, uri)
        } catch (e: Throwable) {
            abortProfileWriteSession(context, store, targetUri)
            FileTransferResult(
                false,
                if (e is TransferCancelledException) str(context, R.string.fb_transfer_cancelled)
                else str(context, R.string.fb_transfer_target_failed),
                safeName,
                failureReason = FileTransferFailureReason.TargetWriteFailed,
            )
        }
    }

    fun transferToOtherSpace(
        context: Context,
        source: TransferSource,
        direction: TransferDirection,
        cancellation: TransferCancellationSignal = TransferCancellationSignal(),
        onProgress: (Long) -> Unit = {},
    ): FileTransferResult {
        val safeName = FileTransferPolicy.safeDisplayName(source.displayName)
        val target = when (direction) {
            TransferDirection.ToProfile -> BridgeTargets.profile()
            TransferDirection.ToMain -> BridgeTargets.parent().takeIf { !Users.isParentProfile() }
        } ?: return FileTransferResult(
            false,
            str(context, R.string.fb_transfer_space_unavailable),
            safeName,
            failureReason = FileTransferFailureReason.SpaceUnavailable,
        )
        val destination = CrossSpaceFileTransferPolicy.destination(source.mime)
        val store = if (destination.isImage) PROFILE_WRITE_MEDIA else PROFILE_WRITE_DOWNLOAD
        val operation = "cross-space transfer direction=${direction.wireValue} name=$safeName"
        val session = when (val result = runDestinationBridgeOperation(
            context,
            TAG,
            "$operation open",
            target,
            command = OpenWriteSession(store.toBridgeStore(), safeName, source.mime, destination.relativePath),
        )) {
            is ProfileBridgeResult.Value -> result.value
            else -> return bridgeFailureResult(context, result, str(context, R.string.fb_transfer_bridge_not_ready))
                .copy(failureReason = crossSpaceFailureReason(result))
        } ?: return FileTransferResult(false, str(context, R.string.fb_transfer_target_failed), safeName,
            failureReason = FileTransferFailureReason.TargetWriteFailed)
        val targetUri = session.uri
        val pfd = session.descriptor

        val write = transferSingleCopy(
            source = source,
            output = ParcelFileDescriptor.AutoCloseOutputStream(pfd),
            cancellation = cancellation,
            onProgress = onProgress,
            abort = { abortTransfer(context, target, store, targetUri, "$operation abort") },
        )
        if (write !is SingleCopyTransferResult.Written) {
            val reason = when (write) {
                SingleCopyTransferResult.SourceUnreadable -> FileTransferFailureReason.SourceUnreadable
                SingleCopyTransferResult.TargetWriteFailed -> FileTransferFailureReason.TargetWriteFailed
                SingleCopyTransferResult.Cancelled -> FileTransferFailureReason.Cancelled
                is SingleCopyTransferResult.Written -> error("Handled above")
            }
            val message = when (write) {
                SingleCopyTransferResult.SourceUnreadable -> str(context, R.string.fb_transfer_source_unreadable)
                SingleCopyTransferResult.Cancelled -> str(context, R.string.fb_transfer_cancelled)
                else -> str(context, R.string.fb_transfer_target_failed)
            }
            return FileTransferResult(false, message, safeName, failureReason = reason)
        }

        return try {
            val finished = runDestinationBridgeOperation(
                context,
                TAG,
                "$operation finish",
                target,
                command = FinishWriteSession(
                    store.toBridgeStore(),
                    targetUri,
                    TransferHistoryDto(
                        safeName,
                        destination.displayLocation,
                        destination.isImage,
                        direction.toBridgeDirection(),
                    ),
                ),
            )
            when (finished) {
                is ProfileBridgeResult.Value -> FileTransferResult(
                    true,
                    str(context, R.string.fb_transfer_done, safeName),
                    safeName,
                    finished.value ?: targetUri,
                )
                else -> {
                    // The bytes may be complete but the MediaStore row is still pending. A failed
                    // commit is not a usable transfer, so remove the half-created target as well.
                    abortTransfer(context, target, store, targetUri, "$operation finish-failed")
                    bridgeFailureResult(context, finished, str(context, R.string.fb_transfer_target_failed))
                        .copy(failureReason = FileTransferFailureReason.TargetWriteFailed)
                }
            }
        } catch (e: Throwable) {
            abortTransfer(context, target, store, targetUri, "$operation abort")
            DiagnosticLog.w(TAG, "$operation failed", e)
            FileTransferResult(false, str(context, R.string.fb_transfer_target_failed), safeName,
                failureReason = FileTransferFailureReason.TargetWriteFailed)
        }
    }

    private fun abortTransfer(context: Context, target: BridgeTarget, store: Int, targetUri: String, operation: String) {
        runCatching {
            runDestinationBridgeOperation(
                context,
                TAG,
                operation,
                target,
                command = AbortWriteSession(store.toBridgeStore(), targetUri),
            )
        }
    }

    /** Localized user-facing message (follows the app's chosen language, not the system default). */
    private fun str(context: Context, id: Int, vararg args: Any): String =
        PrismLocale.wrap(context).getString(id, *args)

    fun runSelfTest(context: Context): FileBridgeSelfTestResult {
        return try {
            DiagnosticLog.i(TAG, "self-test start package=${context.packageName}")
            val payload = buildPayload(context)
            val cloneResult = when (val result = runProfileBridgeOperation(
                context,
                TAG,
                "self-test",
                command = RunBridgeSelfTest(payload),
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileBridgeSelfTestResult(
                    success = false,
                    message = str(context, R.string.fb_apk_transfer_failed),
                )
                else -> return FileBridgeSelfTestResult(
                    success = false,
                    message = bridgeFailureMessage(context, result, str(context, R.string.fb_space_not_ready)),
                )
            }
            DiagnosticLog.i(TAG, "shuttle returned bytes=${cloneResult.bytes.size} uri=${cloneResult.location}")

            DiagnosticLog.i(TAG, "main write start")
            val mainUri = context.writeDownload(
                displayName = MAIN_FILE,
                mimeType = MIME_TEXT,
                bytes = cloneResult.bytes,
            )
            DiagnosticLog.i(TAG, "main write done uri=$mainUri")
            FileBridgeSelfTestResult(
                success = true,
                message = "文件桥自检通过：主空间 -> 双开空间 -> 主空间",
                cloneUri = cloneResult.location,
                mainUri = mainUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "self-test failed", e)
            FileBridgeSelfTestResult(
                success = false,
                message = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    fun importToProfile(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "import start uri=$sourceUri")
            val payload = readPayloadMetadata(context, sourceUri)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import file to profile",
                PROFILE_WRITE_DOWNLOAD,
                payload.displayName,
                payload.mimeType,
                FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_import_file_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_import_file_failed))
            }
            DiagnosticLog.i(TAG, "import done display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_imported_to_dual, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "import failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_import_file_failed),
            )
        }
    }

    /**
     * Export from the dual space to the main space by copying the user-selected system URI into
     * the main user's MediaStore Downloads/PrismSpace folder. The system grants the main process
     * read access for the picked URI, so this stays local and avoids Binder-size limits.
     */
    fun importToMain(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            val payload = readPayloadMetadata(context, sourceUri)
            val uri = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                AndroidFileBridgeDownloadStore(context).insertFromStream(
                    payload.displayName,
                    payload.mimeType,
                    input,
                    FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
                )
            } ?: error(str(context, R.string.fb_read_selected_failed))
            FileTransferResult(true, str(context, R.string.fb_imported_to_main, payload.displayName), payload.displayName, uri)
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "import to main failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_import_file_failed))
        }
    }

    /** Export an image from the dual space to the main user's Pictures/PrismSpace folder. */
    fun importImageToMainGallery(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            val payload = readSharedMediaMetadata(context, sourceUri)
            val uri = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                AndroidFileBridgeMediaStore(context).insertFromStream(
                    payload.displayName,
                    payload.mimeType,
                    input,
                    FileBridgeMediaWriter.DEFAULT_RELATIVE_PATH,
                )
            } ?: error(str(context, R.string.fb_read_selected_image_failed))
            FileTransferResult(true, str(context, R.string.fb_imported_to_main_gallery, payload.displayName), payload.displayName, uri)
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "image import to main failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_import_image_failed))
        }
    }

    /**
     * 普通模式克隆: transfer a COMPLETE app — base + ALL split APKs — into the dual space's
     * Download/PrismSpace/, recording a single incoming history entry as "label-package". Copying
     * the whole split set keeps split packages installable. Only the bounded APK path list crosses the typed bridge; the profile process reads
     * each /data/app file by path (world-readable, same absolute path across users) and streams it into
     * its own MediaStore — avoiding the Binder byte cap and non-serializable PFDs, so it supports big APKs.
     */
    fun importApksToProfile(context: Context, apkFiles: List<java.io.File>, label: String, packageName: String): FileTransferResult {
        return try {
            val paths = ArrayList(apkFiles.filter { it.canRead() }.map { it.absolutePath })
            if (paths.isEmpty()) return FileTransferResult(
                false,
                str(context, R.string.fb_apk_unreadable),
                failureReason = FileTransferFailureReason.SourceUnreadable,
            )
            val safeBase = FileTransferPolicy.safeDisplayName("$label-$packageName")
            val cloneLocation = "Download/PrismSpace"
            val firstUriResult = runProfileBridgeOperation(
                context,
                TAG,
                "apk import package=$packageName apkCount=${apkFiles.size} readableCount=${paths.size}",
                command = ImportApkSet(paths, label, packageName, cloneLocation),
            )
            val firstUri = when (firstUriResult) {
                is ProfileBridgeResult.Value -> firstUriResult.value ?: return FileTransferResult(
                    false,
                    str(context, R.string.fb_apk_transfer_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, firstUriResult, str(context, R.string.fb_apk_transfer_failed))
            }
            FileTransferResult(true, str(context, R.string.fb_apk_transferred, safeBase), safeBase, firstUri)
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "apks import failed", e)
            FileTransferResult(false, e.message ?: str(context, R.string.fb_apk_transfer_failed), failureReason = FileTransferFailureReason.IOError)
        }
    }

    fun importImageToProfileGallery(context: Context, sourceUri: Uri): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "media sync start uri=$sourceUri")
            val payload = readSharedMediaMetadata(context, sourceUri)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import image to profile gallery",
                PROFILE_WRITE_MEDIA,
                payload.displayName,
                payload.mimeType,
                FileBridgeMediaWriter.DEFAULT_RELATIVE_PATH,
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_sync_photo_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_sync_photo_failed))
            }
            DiagnosticLog.i(TAG, "media sync done display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_synced_dual_gallery, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "media sync failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_sync_photo_failed),
            )
        }
    }

    fun importImageToPerAppShare(context: Context, sourceUri: Uri, packageName: String): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "shared media import start sourcePackage=$packageName uri=$sourceUri")
            val payload = readSharedMediaMetadata(context, sourceUri)
            val relativePath = PerAppShareDestination.mediaRelativePath(packageName)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import image to per-app share package=$packageName",
                PROFILE_WRITE_MEDIA,
                payload.displayName,
                payload.mimeType,
                relativePath,
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_sync_photo_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_sync_photo_failed))
            }
            DiagnosticLog.i(TAG, "shared media import done sourcePackage=$packageName display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_synced_shared_gallery, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "shared media import failed sourcePackage=$packageName", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_sync_photo_failed),
            )
        }
    }

    fun importFileToPerAppShare(context: Context, sourceUri: Uri, packageName: String): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "shared file import start sourcePackage=$packageName uri=$sourceUri")
            val payload = readPayloadMetadata(context, sourceUri)
            val relativePath = PerAppShareDestination.downloadRelativePath(packageName)
            val profileUri = when (val result = streamUriToProfile(
                context,
                sourceUri,
                "import file to per-app share package=$packageName",
                PROFILE_WRITE_DOWNLOAD,
                payload.displayName,
                payload.mimeType,
                relativePath,
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_import_file_failed),
                    failureReason = FileTransferFailureReason.IOError,
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_import_file_failed))
            }
            DiagnosticLog.i(TAG, "shared file import done sourcePackage=$packageName display=${payload.displayName} uri=$profileUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_imported_shared_dir, payload.displayName),
                displayName = payload.displayName,
                targetUri = profileUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "shared file import failed sourcePackage=$packageName", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_import_file_failed),
            )
        }
    }

    fun verifyProfileGalleryVisibility(context: Context): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "media visibility start")
            val entry = when (val result = runProfileBridgeOperation(
                context,
                TAG,
                "media visibility",
                command = QueryLatestVisibleImage,
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_no_dual_media),
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_check_media_failed))
            }
            DiagnosticLog.i(TAG, "media visibility found display=${entry.displayName} uri=${entry.uri}")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_dual_media_visible, entry.displayName),
                displayName = entry.displayName,
                targetUri = entry.uri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "media visibility failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_check_media_failed),
            )
        }
    }

    fun openProfileImagePicker(activity: Activity): FileTransferResult {
            val context = activity.applicationContext
            return try {
                DiagnosticLog.i(TAG, "profile image picker launch start")
            val launched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (val result = openProfileImagePickerViaForwarder(activity)) {
                    is ProfileBridgeResult.Value -> result.value == true
                    else -> return bridgeFailureResult(context, result, str(context, R.string.fb_open_image_picker_failed))
                }
            } else {
                when (val result = runProfileBridgeOperation(
                    context,
                    TAG,
                    "profile image picker launch",
                    command = OpenImagePickerInProfile,
                )) {
                    is ProfileBridgeResult.Value -> result.value == true
                    else -> return bridgeFailureResult(context, result, str(context, R.string.fb_open_image_picker_failed))
                }
            }
            if (!launched) return FileTransferResult(
                success = false,
                message = str(context, R.string.fb_space_not_ready),
                failureReason = FileTransferFailureReason.IOError,
            )
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_opened_image_picker),
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "profile image picker launch failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_open_image_picker_failed),
            )
        }
    }

    private fun openProfileImagePickerViaForwarder(activity: Activity): ProfileBridgeResult<Boolean> {
        val context = activity.applicationContext
        val installResult = installProfileImagePickerForwarding(context)
        if (installResult !is ProfileBridgeResult.Value || installResult.value != true) return installResult
        val intent = prepareProfileImagePickerForwarderIntent(context) ?: return ProfileBridgeResult.Value(false)
        activity.startActivity(intent)
        return ProfileBridgeResult.Value(true)
    }

    fun openProfileDownloadsFolder(activity: Activity): FileTransferResult =
        ProfileDownloadsOpener().openDownloadsFolder(activity)

    fun openProfileInstallEntry(activity: Activity): FileTransferResult =
        ProfileDownloadsOpener().openInstallEntry(activity)

    fun openProfileInstallSourceSettings(activity: Activity, packageName: String): FileTransferResult =
        ProfileDownloadsOpener().openInstallSourceSettings(activity, packageName)

    private fun prepareProfileImagePickerForwarderIntent(context: Context): Intent? {
        val intent = ProfileImagePickerLauncher.buildCrossProfileActivityIntent()
        val forwarder = findCrossProfileForwarder(context, intent)
            ?: return null
        return intent.setComponent(forwarder).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private fun installProfileImagePickerForwarding(context: Context): ProfileBridgeResult<Boolean> {
        return runProfileBridgeOperation(
            context,
            TAG,
            "profile image picker forwarding install",
            command = InstallCrossProfileForwarding(CrossProfileForwardingKind.ImagePicker),
        )
    }

    private fun findCrossProfileForwarder(context: Context, intent: Intent): ComponentName? {
        return context.packageManager.queryIntentActivities(
            Intent(intent).setComponent(null),
            MATCH_DISABLED_COMPONENTS or MATCH_DEFAULT_ONLY,
        )
            .firstOrNull { it.activityInfo.packageName == "android" }
            ?.activityInfo
            ?.run { ComponentName(packageName, name) }
    }

    fun exportLatestToMain(context: Context): FileTransferResult {
        return try {
            DiagnosticLog.i(TAG, "export latest start")
            val payload = when (val result = runProfileBridgeOperation(
                context,
                TAG,
                "export latest to main",
                command = OpenLatestForRead(BridgeFileStore.Downloads),
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return FileTransferResult(
                    success = false,
                    message = str(context, R.string.fb_no_files_to_export),
                )
                else -> return bridgeFailureResult(context, result, str(context, R.string.fb_export_failed))
            }
            val displayName = payload.displayName
            val mimeType = payload.mimeType
            val pfd = payload.descriptor
            val targetUri = context.writeDownload(
                displayName = displayName,
                mimeType = mimeType,
                input = ParcelFileDescriptor.AutoCloseInputStream(pfd),
            )
            DiagnosticLog.i(TAG, "export latest done display=$displayName uri=$targetUri")
            FileTransferResult(
                success = true,
                message = str(context, R.string.fb_exported_to_main, displayName),
                displayName = displayName,
                targetUri = targetUri,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "export latest failed", e)
            FileTransferResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_export_failed),
            )
        }
    }

    fun enablePerAppShareFolder(context: Context, packageName: String): PerAppShareFolderResult {
        return try {
            DiagnosticLog.i(TAG, "per-app share enable start package=$packageName")
            val spec = PerAppFileSharePolicy.specFor(packageName)
            val markerUri = when (val result = runProfileBridgeOperation(
                context,
                TAG,
                "per-app share enable package=$packageName",
                command = WritePerAppShareMarker(packageName),
            )) {
                is ProfileBridgeResult.Value -> result.value ?: return PerAppShareFolderResult(
                    success = false,
                    message = str(context, R.string.fb_prepare_shared_dir_failed),
                    relativePath = spec.relativePath,
                    markerDisplayName = spec.markerDisplayName,
                )
                else -> return bridgeFailureShareResult(context, result, spec, str(context, R.string.fb_prepare_shared_dir_failed))
            }
            DiagnosticLog.i(TAG, "per-app share enabled package=$packageName marker=$markerUri")
            PerAppShareFolderResult(
                success = true,
                message = str(context, R.string.fb_shared_dir_ready, spec.relativePath),
                relativePath = spec.relativePath,
                markerDisplayName = spec.markerDisplayName,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "per-app share enable failed package=$packageName", e)
            PerAppShareFolderResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_prepare_shared_dir_failed),
            )
        }
    }

    fun disablePerAppShareFolder(context: Context, packageName: String): PerAppShareFolderResult {
        return try {
            DiagnosticLog.i(TAG, "per-app share disable start package=$packageName")
            val spec = PerAppFileSharePolicy.specFor(packageName)
            val deleted = when (val result = runProfileBridgeOperation(
                context,
                TAG,
                "per-app share disable package=$packageName",
                command = DeletePerAppShareMarker(packageName),
            )) {
                is ProfileBridgeResult.Value -> result.value == true
                else -> return bridgeFailureShareResult(context, result, spec, str(context, R.string.fb_restore_isolation_failed))
            }
            if (!deleted) return PerAppShareFolderResult(
                success = false,
                message = str(context, R.string.fb_restore_isolation_failed),
                relativePath = spec.relativePath,
                markerDisplayName = spec.markerDisplayName,
            )
            DiagnosticLog.i(TAG, "per-app share disabled package=$packageName")
            PerAppShareFolderResult(
                success = true,
                message = str(context, R.string.fb_isolation_restored),
                relativePath = spec.relativePath,
                markerDisplayName = spec.markerDisplayName,
            )
        } catch (e: Throwable) {
            DiagnosticLog.e(TAG, "per-app share disable failed package=$packageName", e)
            PerAppShareFolderResult(
                success = false,
                message = e.message ?: str(context, R.string.fb_restore_isolation_failed),
            )
        }
    }

    private fun bridgeFailureResult(
        context: Context,
        result: ProfileBridgeResult<*>,
        fallbackMessage: String,
    ): FileTransferResult =
        FileTransferResult(
            success = false,
            message = bridgeFailureMessage(context, result, fallbackMessage),
            failureReason = result.failureReason(),
        )

    private fun bridgeFailureShareResult(
        context: Context,
        result: ProfileBridgeResult<*>,
        spec: PerAppFileShareSpec,
        fallbackMessage: String,
    ): PerAppShareFolderResult =
        PerAppShareFolderResult(
            success = false,
            message = bridgeFailureMessage(context, result, fallbackMessage),
            relativePath = spec.relativePath,
            markerDisplayName = spec.markerDisplayName,
        )

    private fun bridgeFailureMessage(
        context: Context,
        result: ProfileBridgeResult<*>,
        fallbackMessage: String,
    ): String = profileBridgeFailureMessage(context, result, fallbackMessage)

    @Suppress("DEPRECATION")
    private fun Bundle.getPfd(): ParcelFileDescriptor? = getParcelable(BRIDGE_KEY_FD)

    private fun streamUriToProfile(
        context: Context,
        sourceUri: Uri,
        operation: String,
        store: Int,
        displayName: String,
        mimeType: String,
        relativePath: String,
    ): ProfileBridgeResult<String> {
        val target = BridgeTargets.profile()
        val session = when (val result = runDestinationBridgeOperation(
            context,
            TAG,
            "$operation open",
            target,
            command = OpenWriteSession(store.toBridgeStore(), displayName, mimeType, relativePath),
        )) {
            is ProfileBridgeResult.Value -> result.value ?: return ProfileBridgeResult.Failed(
                IllegalStateException("Profile write session returned no descriptor")
            )
            else -> return result.asFailureResult()
        }
        val targetUri = session.uri
        val pfd = session.descriptor
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                    input.copyTo(output, STREAM_BUFFER_SIZE)
                }
            } ?: return ProfileBridgeResult.Failed(IllegalStateException(str(context, R.string.fb_read_selected_failed)))
            when (val finish = runDestinationBridgeOperation(
                context,
                TAG,
                "$operation finish",
                target,
                command = FinishWriteSession(store.toBridgeStore(), targetUri),
            )) {
                is ProfileBridgeResult.Value -> ProfileBridgeResult.Value(finish.value ?: targetUri)
                else -> finish.asFailureResult()
            }
        } catch (e: Throwable) {
            runCatching {
                runDestinationBridgeOperation(
                    context,
                    TAG,
                    "$operation abort",
                    target,
                    command = AbortWriteSession(store.toBridgeStore(), targetUri),
                )
            }
            ProfileBridgeResult.Failed(e)
        }
    }

    private fun readPayloadMetadata(context: Context, sourceUri: Uri): FileBridgeMetadata {
        val resolver = context.contentResolver
        val displayName = FileTransferPolicy.safeDisplayName(queryDisplayName(resolver, sourceUri))
        val mimeType = resolver.getType(sourceUri) ?: "application/octet-stream"
        return FileBridgeMetadata(displayName, mimeType)
    }

    private fun readSharedMediaMetadata(context: Context, sourceUri: Uri): FileBridgeMetadata {
        val resolver = context.contentResolver
        val displayName = FileTransferPolicy.safeDisplayName(queryDisplayName(resolver, sourceUri))
        val rawMimeType = resolver.getType(sourceUri)
        val mimeType = FileTransferPolicy.resolveSharedMediaMimeType(rawMimeType, displayName)
            ?: error(str(context, R.string.fb_select_image))
        if (!FileTransferPolicy.isSupportedSharedMediaMimeType(mimeType, displayName)) error(str(context, R.string.fb_select_image))
        return FileBridgeMetadata(displayName, mimeType)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }

    private fun buildPayload(context: Context): ByteArray {
        val text = buildString {
            appendLine("PrismSpace file bridge self-test")
            appendLine("package=${context.packageName}")
            appendLine("timestamp=${System.currentTimeMillis()}")
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private companion object {
        private const val TAG = "Prism.FileBridge"
        private const val MIME_TEXT = "text/plain"
        private const val CLONE_FILE = "prismspace-bridge-clone.txt"
        private const val MAIN_FILE = "prismspace-bridge-main.txt"
    }
}

internal object MobileFileBridgePort : FileBridgePort {
    override fun openWriteSession(
        context: Context,
        store: BridgeFileStore,
        safeName: String,
        mimeType: String,
        relativePath: String,
    ): WriteSessionDto {
        val session = openProfileWriteSession(context, store.toWire(), safeName, mimeType, relativePath)
        val uri = requireNotNull(session.getString(BRIDGE_KEY_URI)) { "Write session missing URI" }
        @Suppress("DEPRECATION")
        val descriptor = requireNotNull(session.getParcelable<ParcelFileDescriptor>(BRIDGE_KEY_FD)) {
            "Write session missing descriptor"
        }
        return WriteSessionDto(uri, descriptor)
    }

    override fun finishWriteSession(
        context: Context,
        store: BridgeFileStore,
        targetUri: String,
        history: TransferHistoryDto?,
    ): String = if (history == null) {
        finishProfileWriteSession(context, store.toWire(), targetUri)
    } else {
        finishProfileTransfer(
            context,
            store.toWire(),
            targetUri,
            history.displayName,
            history.displayLocation,
            history.isImage,
            history.direction?.toTransferDirection()?.wireValue,
        )
    }

    override fun abortWriteSession(context: Context, store: BridgeFileStore, targetUri: String) =
        abortProfileWriteSession(context, store.toWire(), targetUri)

    override fun importApkSet(
        context: Context,
        paths: List<String>,
        label: String,
        packageName: String,
        cloneLocation: String,
    ): String? {
        require(paths.size <= MAX_APK_PATH_COUNT) { "APK set exceeds $MAX_APK_PATH_COUNT entries" }
        val safeBase = FileTransferPolicy.safeDisplayName("$label-$packageName")
        var first: String? = null
        paths.forEachIndexed { index, path ->
            val name = if (index == 0) "$safeBase.apk" else "$safeBase.split$index.apk"
            val uri = AndroidFileBridgeDownloadStore(context).insertFromFile(
                name,
                "application/vnd.android.package-archive",
                File(path),
                FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
            )
            if (first == null) first = uri
        }
        TransferHistoryStore.record(context, label, cloneLocation, false, packageName)
        return first
    }

    override fun queryLatestVisibleImage(context: Context): ProfileMediaEntryDto? =
        FileBridgeMediaVisibilityVerifier(AndroidFileBridgeMediaQueryStore(context)).latestVisibleImage()?.let {
            ProfileMediaEntryDto(it.displayName, it.mimeType, it.uri)
        }

    override fun openImagePicker(context: Context): Boolean = true.also { ProfileImagePickerLauncher.open(context) }

    override fun openLatestForRead(context: Context, store: BridgeFileStore): ReadSessionDto? {
        require(store == BridgeFileStore.Downloads) { "Read sessions only support Downloads" }
        val session = AndroidFileBridgeDownloadStore(context).openLatestInPrismFolderForRead() ?: return null
        val displayName = requireNotNull(session.getString(BRIDGE_KEY_DISPLAY_NAME))
        val mimeType = session.getString(BRIDGE_KEY_MIME_TYPE) ?: "application/octet-stream"
        @Suppress("DEPRECATION")
        val descriptor = requireNotNull(session.getParcelable<ParcelFileDescriptor>(BRIDGE_KEY_FD))
        return ReadSessionDto(displayName, mimeType, descriptor)
    }

    override fun writePerAppShareMarker(context: Context, packageName: String): String =
        AndroidPerAppShareFolderStore(context).writeMarker(PerAppFileSharePolicy.specFor(packageName))

    override fun deletePerAppShareMarker(context: Context, packageName: String): Boolean = true.also {
        AndroidPerAppShareFolderStore(context).deleteMarker(PerAppFileSharePolicy.specFor(packageName))
    }

    override fun runSelfTest(context: Context, marker: ByteArray): SelfTestResultDto? {
        val cloneUri = context.writeDownload(SELF_TEST_CLONE_FILE, SELF_TEST_MIME_TEXT, marker)
        val bytes = context.contentResolver.openInputStream(Uri.parse(cloneUri))?.use { it.readBytes() } ?: return null
        return SelfTestResultDto(bytes, cloneUri)
    }

    override fun installCrossProfileForwarding(context: Context, kind: CrossProfileForwardingKind): Boolean {
        val policies = DevicePolicies(context)
        val filter: IntentFilter
        val flags: Int
        val component: ComponentName
        when (kind) {
            CrossProfileForwardingKind.ImagePicker -> {
                filter = ProfileImagePickerLauncher.crossProfileActivityIntentFilter()
                flags = ProfileImagePickerLauncher.crossProfileForwardingFlags()
                component = ProfileImagePickerLauncher.crossProfilePreferredActivityComponent(context)
            }
            CrossProfileForwardingKind.ProfileDownloads -> {
                filter = ProfileDownloadsLauncher.crossProfileActivityIntentFilter()
                flags = ProfileDownloadsLauncher.crossProfileForwardingFlags()
                component = ProfileDownloadsLauncher.crossProfilePreferredActivityComponent(context)
            }
        }
        policies.addCrossProfileIntentFilter(filter, flags)
        policies.execute(DPM::addPersistentPreferredActivity, filter, component)
        return true
    }

    private fun BridgeFileStore.toWire() = when (this) {
        BridgeFileStore.Downloads -> PROFILE_WRITE_DOWNLOAD
        BridgeFileStore.Media -> PROFILE_WRITE_MEDIA
    }

    private fun BridgeTransferDirection.toTransferDirection() = when (this) {
        BridgeTransferDirection.ToMain -> TransferDirection.ToMain
        BridgeTransferDirection.ToProfile -> TransferDirection.ToProfile
    }

}

private const val BRIDGE_KEY_URI = "uri"
private const val BRIDGE_KEY_FD = "fd"
private const val BRIDGE_KEY_DISPLAY_NAME = "display_name"
private const val BRIDGE_KEY_MIME_TYPE = "mime_type"
private const val STREAM_BUFFER_SIZE = 64 * 1024
private const val PROFILE_WRITE_DOWNLOAD = 1
private const val PROFILE_WRITE_MEDIA = 2
private const val SELF_TEST_MIME_TEXT = "text/plain"
private const val SELF_TEST_CLONE_FILE = "prismspace-bridge-clone.txt"

private fun Int.toBridgeStore() = when (this) {
    PROFILE_WRITE_DOWNLOAD -> BridgeFileStore.Downloads
    PROFILE_WRITE_MEDIA -> BridgeFileStore.Media
    else -> error("Unknown profile write store: $this")
}

private fun TransferDirection.toBridgeDirection() = when (this) {
    TransferDirection.ToMain -> BridgeTransferDirection.ToMain
    TransferDirection.ToProfile -> BridgeTransferDirection.ToProfile
}

private fun openProfileWriteSession(
    context: Context,
    store: Int,
    displayName: String,
    mimeType: String,
    relativePath: String,
): Bundle = when (store) {
    PROFILE_WRITE_DOWNLOAD -> AndroidFileBridgeDownloadStore(context)
        .openPendingWrite(displayName, mimeType, relativePath)
    PROFILE_WRITE_MEDIA -> AndroidFileBridgeMediaStore(context)
        .openPendingWrite(displayName, mimeType, relativePath)
    else -> error("Unknown profile write store: $store")
}

private fun finishProfileWriteSession(context: Context, store: Int, targetUri: String): String = when (store) {
    PROFILE_WRITE_DOWNLOAD -> AndroidFileBridgeDownloadStore(context).finishPendingWrite(targetUri)
    PROFILE_WRITE_MEDIA -> AndroidFileBridgeMediaStore(context).finishPendingWrite(targetUri)
    else -> error("Unknown profile write store: $store")
}

private fun finishProfileTransfer(
    context: Context,
    store: Int,
    targetUri: String,
    displayName: String,
    location: String,
    isImage: Boolean,
    directionWireValue: String?,
): String {
    val uri = finishProfileWriteSession(context, store, targetUri)
    TransferHistoryStore.record(
        context,
        displayName,
        location,
        isImage,
        direction = TransferDirection.fromWireValue(directionWireValue),
    )
    return uri
}

private fun abortProfileWriteSession(context: Context, store: Int, targetUri: String) {
    when (store) {
        PROFILE_WRITE_DOWNLOAD -> AndroidFileBridgeDownloadStore(context).abortPendingWrite(targetUri)
        PROFILE_WRITE_MEDIA -> AndroidFileBridgeMediaStore(context).abortPendingWrite(targetUri)
        else -> error("Unknown profile write store: $store")
    }
}

private class TransferCancelledException : java.io.IOException("Transfer cancelled")
private class SourceReadException(cause: Throwable) : java.io.IOException(cause)
private class TargetWriteException(cause: Throwable) : java.io.IOException(cause)

internal sealed interface SingleCopyTransferResult {
    data class Written(val bytes: Long) : SingleCopyTransferResult
    object SourceUnreadable : SingleCopyTransferResult
    object TargetWriteFailed : SingleCopyTransferResult
    object Cancelled : SingleCopyTransferResult
}

internal fun transferSingleCopy(
    source: TransferSource,
    output: OutputStream,
    cancellation: TransferCancellationSignal,
    onProgress: (Long) -> Unit = {},
    abort: () -> Unit,
): SingleCopyTransferResult {
    val input = try {
        source.openOnce()
    } catch (_: Exception) {
        runCatching(abort)
        runCatching { output.close() }
        return SingleCopyTransferResult.SourceUnreadable
    }
    val result = try {
        input.use {
            output.use {
                SingleCopyTransferResult.Written(copyCancellable(input, output, cancellation, onProgress))
            }
        }
    } catch (_: TransferCancelledException) {
        SingleCopyTransferResult.Cancelled
    } catch (_: SourceReadException) {
        SingleCopyTransferResult.SourceUnreadable
    } catch (_: TargetWriteException) {
        SingleCopyTransferResult.TargetWriteFailed
    } catch (_: Exception) {
        SingleCopyTransferResult.TargetWriteFailed
    }
    if (result !is SingleCopyTransferResult.Written) runCatching(abort)
    return result
}

internal fun copyCancellable(
    input: InputStream,
    output: OutputStream,
    cancellation: TransferCancellationSignal,
    onProgress: (Long) -> Unit = {},
): Long {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        if (cancellation.isCancelled()) throw TransferCancelledException()
        val read = try {
            input.read(buffer)
        } catch (e: Exception) {
            throw SourceReadException(e)
        }
        if (read < 0) break
        try {
            output.write(buffer, 0, read)
        } catch (e: Exception) {
            throw TargetWriteException(e)
        }
        copied += read
        onProgress(copied)
    }
    try {
        output.flush()
    } catch (e: Exception) {
        throw TargetWriteException(e)
    }
    return copied
}

private data class FileBridgeMetadata(
    val displayName: String,
    val mimeType: String,
)

data class ProfileMediaEntry(
    val displayName: String,
    val mimeType: String,
    val uri: String,
)

private fun ContentResolver.openPendingWriteSession(
    collectionUri: Uri,
    displayName: String,
    mimeType: String,
    relativePath: String,
): Bundle {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val uri = insert(collectionUri, values) ?: error("Unable to create MediaStore entry")
    val pfd = openFileDescriptor(uri, "w") ?: error("Unable to open MediaStore output descriptor")
    return Bundle().apply {
        putString(BRIDGE_KEY_URI, uri.toString())
        putParcelable(BRIDGE_KEY_FD, pfd)
    }
}

private fun ContentResolver.finishPendingWrite(uriString: String): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        update(Uri.parse(uriString), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }
    return uriString
}

private fun ContentResolver.deleteMediaStoreUri(uriString: String) {
    delete(Uri.parse(uriString), null, null)
}

private fun ContentResolver.openReadSession(
    uri: Uri,
    displayName: String,
    mimeType: String,
): Bundle {
    val pfd = openFileDescriptor(uri, "r") ?: error("Unable to open MediaStore input descriptor")
    return Bundle().apply {
        putString(BRIDGE_KEY_URI, uri.toString())
        putString(BRIDGE_KEY_DISPLAY_NAME, displayName)
        putString(BRIDGE_KEY_MIME_TYPE, mimeType)
        putParcelable(BRIDGE_KEY_FD, pfd)
    }
}

private fun Context.writeDownload(displayName: String, mimeType: String, bytes: ByteArray): String {
    return FileBridgeDownloadWriter(AndroidFileBridgeDownloadStore(this)).write(displayName, mimeType, bytes)
}

private fun Context.writeDownload(displayName: String, mimeType: String, input: InputStream): String {
    return input.use {
        AndroidFileBridgeDownloadStore(this).insertFromStream(
            displayName,
            mimeType,
            it,
            FileBridgeDownloadWriter.DEFAULT_RELATIVE_PATH,
        )
    }
}

internal class FileBridgeDownloadWriter(private val store: FileBridgeDownloadStore) {

    fun write(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        relativePath: String = DEFAULT_RELATIVE_PATH,
    ): String {
        // Do NOT delete an existing same-name file — that silently destroyed the user's data.
        // MediaStore auto-appends " (1)" on a DISPLAY_NAME collision in the same RELATIVE_PATH (Q+),
        // so a second "report.pdf" becomes "report (1).pdf" and the original is preserved.
        return store.insert(displayName, mimeType, bytes, relativePath)
    }

    companion object {
        const val DEFAULT_RELATIVE_PATH = "Download/PrismSpace/"
    }
}

internal interface FileBridgeDownloadStore {
    fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String
}

internal class FileBridgeMediaWriter(private val store: FileBridgeMediaStore) {

    fun write(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        relativePath: String = DEFAULT_RELATIVE_PATH,
    ): String {
        // MediaStore auto-dedups the on-disk name on collision.
        return store.insert(displayName, mimeType, bytes, relativePath)
    }

    companion object {
        const val DEFAULT_RELATIVE_PATH = "Pictures/PrismSpace/"
    }
}

internal interface FileBridgeMediaStore {
    fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String
}

internal class FileBridgeMediaVisibilityVerifier(private val store: FileBridgeMediaQueryStore) {
    fun latestVisibleImage(): ProfileMediaEntry? = store.readLatestInPrismPictures()
}

internal interface FileBridgeMediaQueryStore {
    fun readLatestInPrismPictures(): ProfileMediaEntry?
}

internal object ProfileImagePickerLauncher {
    private const val ACTION_PROFILE_IMAGE_PICKER = "com.yzddmr6.prismspace.action.PROFILE_IMAGE_PICKER"

    fun intentSpec() = ProfileImagePickerIntentSpec(
        action = Intent.ACTION_GET_CONTENT,
        type = "image/*",
        categories = setOf(Intent.CATEGORY_OPENABLE),
        flags = Intent.FLAG_ACTIVITY_NEW_TASK,
    )

    fun crossProfileActivityIntentSpec() = ProfileImagePickerActivityIntentSpec(
        action = ACTION_PROFILE_IMAGE_PICKER,
        categories = setOf(CrossProfile.CATEGORY_MANAGED_PROFILE, Intent.CATEGORY_DEFAULT),
    )

    fun buildIntent(): Intent {
        val spec = intentSpec()
        return Intent(spec.action)
            .setType(spec.type)
            .addFlags(spec.flags)
            .also { intent -> spec.categories.forEach(intent::addCategory) }
    }

    fun buildCrossProfileActivityIntent(): Intent {
        val spec = crossProfileActivityIntentSpec()
        return Intent(spec.action)
            .also { intent -> spec.categories.forEach(intent::addCategory) }
    }

    fun crossProfileActivityIntentFilter(): IntentFilter {
        val spec = crossProfileActivityIntentSpec()
        return IntentFilter(spec.action)
            .also { filter -> spec.categories.forEach(filter::addCategory) }
    }

    fun crossProfileForwardingFlags() = FLAG_MANAGED_CAN_ACCESS_PARENT

    fun crossProfilePreferredActivityClassName() = ProfileImagePickerActivity::class.java.name

    fun crossProfilePreferredActivityComponent(context: Context) = ComponentName(
        context.packageName,
        crossProfilePreferredActivityClassName(),
    )

    fun open(context: Context) {
        context.startActivity(buildIntent())
    }
}

internal data class ProfileImagePickerIntentSpec(
    val action: String,
    val type: String,
    val categories: Set<String>,
    val flags: Int,
)

internal data class ProfileImagePickerActivityIntentSpec(
    val action: String,
    val categories: Set<String>,
)

internal object FileTransferPolicy {
    // No fixed upper size limit: file bridge must handle large payloads such as APKs.
    // Large transfers stream via ParcelFileDescriptor to avoid Binder transaction size limits.
    fun isAllowedSize(size: Long) = size >= 0

    fun safeDisplayName(name: String?): String {
        val normalized = name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        return normalized.ifBlank { "prismspace-import.bin" }
    }

    fun isSupportedSharedMediaMimeType(mimeType: String?, displayName: String): Boolean =
        resolveSharedMediaMimeType(mimeType, displayName)?.startsWith("image/") == true

    fun resolveSharedMediaMimeType(mimeType: String?, displayName: String): String? {
        val normalized = mimeType?.lowercase()?.takeUnless { it == "application/octet-stream" }
        if (normalized?.startsWith("image/") == true) return normalized
        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> normalized
        }
    }
}

private class AndroidFileBridgeDownloadStore(private val context: Context) : FileBridgeDownloadStore {

    override fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Downloads entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to open Downloads output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    /** Stream from a source File (read locally inside this — the profile — process) into a MediaStore
     *  Downloads entry. No full-memory load, no Binder byte limit. Used by the file-sync clone path to copy a
     *  shared, world-readable /data/app APK into the dual space for manual install. */
    fun insertFromFile(displayName: String, mimeType: String, src: java.io.File, relativePath: String): String {
        // No pre-delete: cloning the same app twice now yields "App (1).apk" instead of destroying the first.
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Downloads entry")
        src.inputStream().use { input ->
            resolver.openOutputStream(uri)?.use { output -> input.copyTo(output, 64 * 1024) }
                ?: error("Unable to open Downloads output stream")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    fun insertFromStream(displayName: String, mimeType: String, input: InputStream, relativePath: String): String {
        val resolver = context.contentResolver
        val session = resolver.openPendingWriteSession(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )
        val uri = session.getString(BRIDGE_KEY_URI) ?: error("Unable to create Downloads entry")
        @Suppress("DEPRECATION")
        val pfd = session.getParcelable<ParcelFileDescriptor>(BRIDGE_KEY_FD)
            ?: error("Unable to open Downloads output descriptor")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
            input.copyTo(output, STREAM_BUFFER_SIZE)
        }
        return resolver.finishPendingWrite(uri)
    }

    fun openPendingWrite(displayName: String, mimeType: String, relativePath: String): Bundle =
        context.contentResolver.openPendingWriteSession(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )

    fun finishPendingWrite(uri: String): String = context.contentResolver.finishPendingWrite(uri)

    fun abortPendingWrite(uri: String) {
        context.contentResolver.deleteMediaStoreUri(uri)
    }

    fun openLatestInPrismFolderForRead(): Bundle? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(PRISM_RELATIVE_PATH)
        } else {
            null
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val displayName = FileTransferPolicy.safeDisplayName(
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                )
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val mimeType = if (mimeTypeIndex >= 0 && !cursor.isNull(mimeTypeIndex)) {
                    cursor.getString(mimeTypeIndex)
                } else {
                    "application/octet-stream"
                }
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                return resolver.openReadSession(uri, displayName, mimeType)
            }
        }
        return null
    }

    private companion object {
        private const val PRISM_RELATIVE_PATH = "Download/PrismSpace/"
    }
}

private class AndroidPerAppShareFolderStore(private val context: Context) {

    fun writeMarker(spec: PerAppFileShareSpec): String {
        deleteMarker(spec)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.markerDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create per-app share marker")
        resolver.openOutputStream(uri)?.use { it.write(spec.markerBytes) }
            ?: error("Unable to write per-app share marker")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    fun deleteMarker(spec: PerAppFileShareSpec) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(spec.markerDisplayName, spec.relativePath)
        } else {
            arrayOf(spec.markerDisplayName)
        }
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
    }
}

private class AndroidFileBridgeMediaStore(private val context: Context) : FileBridgeMediaStore {

    override fun insert(displayName: String, mimeType: String, bytes: ByteArray, relativePath: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Images entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to open Images output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        return uri.toString()
    }

    fun openPendingWrite(displayName: String, mimeType: String, relativePath: String): Bundle =
        context.contentResolver.openPendingWriteSession(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )

    fun insertFromStream(displayName: String, mimeType: String, input: InputStream, relativePath: String): String {
        val resolver = context.contentResolver
        val session = resolver.openPendingWriteSession(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName,
            mimeType,
            relativePath,
        )
        val uri = session.getString(BRIDGE_KEY_URI) ?: error("Unable to create Images entry")
        @Suppress("DEPRECATION")
        val pfd = session.getParcelable<ParcelFileDescriptor>(BRIDGE_KEY_FD)
            ?: error("Unable to open Images output descriptor")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
            input.copyTo(output, STREAM_BUFFER_SIZE)
        }
        return resolver.finishPendingWrite(uri)
    }

    fun finishPendingWrite(uri: String): String = context.contentResolver.finishPendingWrite(uri)

    fun abortPendingWrite(uri: String) {
        context.contentResolver.deleteMediaStoreUri(uri)
    }
}

private class AndroidFileBridgeMediaQueryStore(private val context: Context) : FileBridgeMediaQueryStore {

    override fun readLatestInPrismPictures(): ProfileMediaEntry? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(PRISM_PICTURES_RELATIVE_PATH)
        } else {
            null
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val displayName = FileTransferPolicy.safeDisplayName(
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                )
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val mimeType = if (mimeTypeIndex >= 0 && !cursor.isNull(mimeTypeIndex)) {
                    cursor.getString(mimeTypeIndex)
                } else {
                    "image/*"
                }
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                return ProfileMediaEntry(displayName, mimeType, uri.toString())
            }
        }
        return null
    }

    private companion object {
        private const val PRISM_PICTURES_RELATIVE_PATH = "Pictures/PrismSpace/"
    }
}
