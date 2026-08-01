package com.yzddmr6.prismspace.prism.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import com.yzddmr6.prismspace.analytics.DiagnosticLog
import android.widget.Toast
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.vm.isImageMime
import com.yzddmr6.prismspace.prism.service.TransferHistoryStore
import com.yzddmr6.prismspace.prism.service.FileBridgeService
import com.yzddmr6.prismspace.prism.service.FileTransferFailureReason
import com.yzddmr6.prismspace.prism.service.FileTransferResult
import com.yzddmr6.prismspace.prism.service.TransferCancellationSignal
import com.yzddmr6.prismspace.prism.service.TransferDirection
import com.yzddmr6.prismspace.prism.service.TransferSource
import com.yzddmr6.prismspace.util.PrismLocale
import com.yzddmr6.prismspace.util.Users
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Unified file-transfer receiver (bidirectional, normal permissions). PrismSpace is installed in
 * both the main and the dual space, so this single ACTION_SEND target appears in the system share
 * chooser's 个人/工作 tabs as "导入到此空间 PrismSpace". Whichever space's copy receives the share
 * writes the file into THAT space — selecting the 个人 tab imports to the main space, the 工作 tab
 * imports to the dual space. Same name, both directions correct.
 *
 * Why share (not SAF): some ROMs intercept the document picker across profile boundaries, while
 * the system share chooser keeps the Personal/Work routing explicit.
 *
 * Cross-space transfers stream the granted URI directly into the destination session. A private
 * cache file is created lazily only for the local CREATE_DOCUMENT branch, whose picker temporarily
 * leaves this Activity.
 */
class ImportToSpaceActivity : Activity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    private data class PendingImport(
        val uri: Uri,
        val displayName: String,
        val mime: String,
        val declaredSize: Long?,
        val isImage: Boolean,
        var cachedFile: File? = null,
    )

    private val pending = mutableListOf<PendingImport>()
    private var saveAsIndex = 0
    private var saveAsSuccesses = 0
    private var saveAsLastFailure: String? = null
    private var activeCancellation: TransferCancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = receivedUris()
        if (uris.isEmpty()) {
            DiagnosticLog.w(TAG, "import receive failed reason=no_uri")
            toast(getString(R.string.lz_io_no_file))
            finish()
            return
        }
        Thread {
            uris.forEachIndexed { index, uri ->
                try {
                    val mime = intent?.type?.takeUnless { it == "*/*" }
                        ?: contentResolver.getType(uri) ?: "application/octet-stream"
                    val metadata = queryMetadata(uri)
                    val displayName = metadata.first
                        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    DiagnosticLog.i(TAG, "import receive start index=$index name=$displayName mime=$mime uri=$uri")
                    pending += PendingImport(uri, displayName, mime, metadata.second, isImageMime(mime))
                } catch (e: Throwable) {
                    DiagnosticLog.w(TAG, "import receive failed index=$index reason=SourceUnreadable", e)
                }
            }
            runOnUiThread {
                if (pending.isEmpty()) {
                    toast(getString(R.string.lz_io_cant_read))
                    finish()
                } else if (intent.getBooleanExtra(CrossSpaceTransferEntry.EXTRA_FORCE_OTHER_SPACE, false)) {
                    runBatch(toOtherSpace = true)
                } else showDestinationDialog()
            }
        }.apply { name = "Prism-import-metadata" }.start()
    }

    private fun receivedUris(): List<Uri> {
        val result = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            intent?.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::addAll)
        } else {
            intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::add)
        }
        intent?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add)
        }
        return result.distinct()
    }

    private fun showDestinationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lz_io_choose_target)
            .setItems(arrayOf(
                getString(R.string.lz_io_target_here),
                getString(R.string.lz_io_target_save_as),
                getString(R.string.lz_io_target_other_space),
            )) { _, which ->
                when (which) {
                    0 -> runBatch(toOtherSpace = false)
                    1 -> saveAsNext()
                    2 -> runBatch(toOtherSpace = true)
                }
            }
            .setOnCancelListener {
                DiagnosticLog.i(TAG, "import receive done result=cancelled stage=target")
                cleanupAndFinish()
            }
            .show()
    }

    private fun runBatch(toOtherSpace: Boolean) {
        val cancellation = TransferCancellationSignal()
        activeCancellation = cancellation
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            isIndeterminate = true
            setMessage(getString(R.string.lz_io_transfer_progress, pending.size))
            setCancelable(true)
            setOnCancelListener { cancellation.cancel() }
            show()
        }
        Thread {
            val service = FileBridgeService()
            val results = runCancellableTransferQueue(pending, cancellation) { index, item ->
                updateProgress(progress, index, item, 0L)
                var lastPercent = -1
                val onProgress: (Long) -> Unit = { written ->
                    transferProgressPercent(item.declaredSize, written)?.let { percent ->
                        if (percent != lastPercent) {
                            lastPercent = percent
                            updateProgress(progress, index, item, written)
                        }
                    }
                }
                val result = if (toOtherSpace) {
                    val direction = if (Users.isParentProfile()) TransferDirection.ToProfile else TransferDirection.ToMain
                    val source = TransferSource.fromUri(
                        contentResolver, item.uri, item.displayName, item.mime, item.declaredSize,
                    )
                    service.transferToOtherSpace(this, source, direction, cancellation, onProgress)
                } else {
                    val localFile = item.cachedFile ?: copyToCache(item.uri, index)?.also { item.cachedFile = it }
                    if (localFile == null) {
                        FileTransferResult(
                            false,
                            getString(R.string.fb_transfer_source_unreadable),
                            item.displayName,
                            failureReason = FileTransferFailureReason.SourceUnreadable,
                        )
                    } else {
                        service.saveInCurrentSpace(
                            this, localFile, item.displayName, item.mime, cancellation, onProgress,
                        )
                    }
                }
                if (result.success) {
                    DiagnosticLog.i(TAG, "import receive done index=$index result=success otherSpace=$toOtherSpace name=${item.displayName}")
                } else {
                    DiagnosticLog.w(TAG, "import receive failed index=$index reason=${result.failureReason} otherSpace=$toOtherSpace name=${item.displayName}")
                }
                result
            }
            runOnUiThread {
                activeCancellation = null
                if (isDestroyed) return@runOnUiThread
                progress.dismiss()
                val successes = results.count { it.success }
                val lastFailure = results.lastOrNull { !it.success }?.message
                val message = when {
                    cancellation.isCancelled() -> getString(R.string.lz_io_cancelled)
                    successes == pending.size -> getString(R.string.lz_io_batch_done, successes)
                    else -> getString(R.string.lz_io_batch_partial, successes, pending.size, lastFailure.orEmpty())
                }
                toast(message)
                cleanupAndFinish()
            }
        }.apply { name = "Prism-import-transfer" }.start()
    }

    @Suppress("DEPRECATION")
    private fun updateProgress(progress: ProgressDialog, index: Int, item: PendingImport, written: Long) {
        val percent = transferProgressPercent(item.declaredSize, written)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            progress.setMessage(getString(R.string.lz_io_transfer_item_progress, index + 1, pending.size, item.displayName))
            progress.isIndeterminate = percent == null
            if (percent != null) progress.progress = percent
        }
    }

    private fun saveAsNext() {
        if (saveAsIndex >= pending.size) {
            val complete = saveAsSuccesses == pending.size
            DiagnosticLog.i(
                TAG,
                "import receive done result=${if (complete) "success" else "partial"} " +
                    "branch=save_as success=$saveAsSuccesses total=${pending.size}",
            )
            toast(
                if (complete) getString(R.string.lz_io_batch_done, saveAsSuccesses)
                else getString(
                    R.string.lz_io_batch_partial,
                    saveAsSuccesses,
                    pending.size,
                    saveAsLastFailure.orEmpty(),
                )
            )
            cleanupAndFinish()
            return
        }
        val item = pending[saveAsIndex]
        if (item.cachedFile == null) {
            val index = saveAsIndex
            Thread {
                val cached = copyToCache(item.uri, index)
                runOnUiThread {
                    if (cached == null) {
                        saveAsLastFailure = getString(R.string.fb_transfer_source_unreadable)
                        saveAsIndex++
                    } else {
                        item.cachedFile = cached
                    }
                    saveAsNext()
                }
            }.apply { name = "Prism-import-save-as-cache" }.start()
            return
        }
        try {
            startActivityForResult(
                ImportDestinationPlanner.buildCreateDocumentIntent(item.displayName, item.mime),
                REQ_CREATE_DOCUMENT,
            )
        } catch (e: Throwable) {
            DiagnosticLog.w(TAG, "import receive failed index=$saveAsIndex reason=TargetWriteFailed branch=save_as", e)
            toast(getString(R.string.lz_io_failed, e.message ?: e.javaClass.simpleName))
            cleanupAndFinish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CREATE_DOCUMENT) return
        val item = pending.getOrNull(saveAsIndex) ?: return cleanupAndFinish()
        val target = data?.data
        if (resultCode != RESULT_OK || target == null) {
            DiagnosticLog.i(TAG, "import receive done index=$saveAsIndex result=cancelled branch=save_as")
            saveAsLastFailure = getString(R.string.lz_io_cancelled)
            saveAsIndex++
            saveAsNext()
            return
        }
        try {
            writeToDocument(target, requireNotNull(item.cachedFile))
            val location = ImportDestinationPlanner.displayLocationForCreatedDocument(target.toString())
            TransferHistoryStore.record(this, item.displayName, location, item.isImage)
            saveAsSuccesses++
            DiagnosticLog.i(TAG, "import receive done index=$saveAsIndex result=success branch=save_as name=${item.displayName} target=$target")
        } catch (e: Throwable) {
            DiagnosticLog.w(TAG, "import receive failed index=$saveAsIndex reason=TargetWriteFailed branch=save_as", e)
            saveAsLastFailure = getString(R.string.lz_io_failed, e.message ?: e.javaClass.simpleName)
        }
        saveAsIndex++
        saveAsNext()
    }

    private fun cleanupAndFinish() {
        pending.forEach { it.cachedFile?.delete() }
        pending.clear()
        finish()
    }

    /** Copy [src] into a private cache temp; returns the temp file (or null on failure). */
    private fun copyToCache(src: Uri, index: Int): File? {
        val dir = File(cacheDir, "import").apply { mkdirs() }
        val out = File(dir, "in_${System.currentTimeMillis()}_$index")
        return try {
            contentResolver.openInputStream(src)!!.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            out
        } catch (_: Throwable) {
            out.delete()
            null
        }
    }

    /** Stream [src] into the user-created document. */
    private fun writeToDocument(targetUri: Uri, src: File) {
        contentResolver.openOutputStream(targetUri).use { output ->
            src.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output!!.write(buffer, 0, read)
                }
            }
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) c.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !c.isNull(sizeIndex)) {
                    c.getLong(sizeIndex).takeIf { it >= 0L }
                } else null
                name to size
            } else null to null
        } ?: (null to null)
    }.getOrDefault(null to null)

    override fun onStop() {
        activeCancellation?.cancel()
        super.onStop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        private const val TAG = "Prism.ImportToSpace"
        private const val REQ_CREATE_DOCUMENT = 4201
    }
}

internal fun <T> runCancellableTransferQueue(
    items: List<T>,
    cancellation: TransferCancellationSignal,
    transfer: (index: Int, item: T) -> FileTransferResult,
): List<FileTransferResult> {
    val results = ArrayList<FileTransferResult>(items.size)
    for ((index, item) in items.withIndex()) {
        if (cancellation.isCancelled()) break
        results += transfer(index, item)
    }
    return results
}

internal fun transferProgressPercent(declaredSize: Long?, written: Long): Int? = when {
    declaredSize == null || declaredSize < 0L -> null
    declaredSize == 0L -> 100
    else -> ((written.toDouble() / declaredSize) * 100).toInt().coerceIn(0, 100)
}

internal object CrossSpaceTransferEntry {
    const val EXTRA_FORCE_OTHER_SPACE = "com.yzddmr6.prismspace.extra.FORCE_OTHER_SPACE"

    fun launch(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val clip = ClipData.newUri(context.contentResolver, "PrismSpace transfer", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                .setClass(context, ImportToSpaceActivity::class.java)
                .setType("*/*")
                .putExtra(EXTRA_FORCE_OTHER_SPACE, true)
                .apply {
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        if (uris.size > 1) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        else intent.putExtra(Intent.EXTRA_STREAM, uris.first())
        context.startActivity(intent)
    }
}

internal object ImportDestinationPlanner {
    fun createDocumentIntentSpec(displayName: String, mimeType: String): CreateDocumentIntentSpec =
        CreateDocumentIntentSpec(
            action = Intent.ACTION_CREATE_DOCUMENT,
            type = mimeType,
            title = displayName,
            categories = setOf(Intent.CATEGORY_OPENABLE),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

    fun buildCreateDocumentIntent(displayName: String, mimeType: String): Intent {
        val spec = createDocumentIntentSpec(displayName, mimeType)
        return Intent(spec.action)
            .setType(spec.type)
            .putExtra(Intent.EXTRA_TITLE, spec.title)
            .addFlags(spec.flags)
            .also { intent -> spec.categories.forEach(intent::addCategory) }
    }

    fun displayLocationForCreatedDocument(uriString: String): String {
        documentIdFrom(uriString)?.let { docId ->
            val path = docId.substringAfter(':', docId)
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotBlank()) return parent
        }
        return authorityFrom(uriString)
    }

    private fun documentIdFrom(uriString: String): String? {
        val encodedDocumentId = uriString
            .substringAfter("/document/", missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
        return URLDecoder.decode(encodedDocumentId, StandardCharsets.UTF_8.name()).takeIf { it.isNotBlank() }
    }

    private fun authorityFrom(uriString: String): String =
        uriString
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
}

internal data class CreateDocumentIntentSpec(
    val action: String,
    val type: String,
    val title: String,
    val categories: Set<String>,
    val flags: Int,
)
