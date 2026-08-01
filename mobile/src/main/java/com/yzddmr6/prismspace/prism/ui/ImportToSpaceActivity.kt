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
import com.yzddmr6.prismspace.prism.service.TransferCancellationSignal
import com.yzddmr6.prismspace.prism.service.TransferDirection
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
 * Flow: receive shared file → copy it to a private cache temp (reads the cross-profile-granted URI
 * immediately, before it can expire) → let the user choose the exact destination file via the
 * system save panel (CREATE_DOCUMENT) → write the file there → record in the persisted transfer
 * history.
 */
class ImportToSpaceActivity : Activity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(PrismLocale.wrap(newBase))

    private data class PendingImport(val file: File, val displayName: String, val mime: String, val isImage: Boolean)

    private val pending = mutableListOf<PendingImport>()
    private var saveAsIndex = 0
    private var saveAsSuccesses = 0
    private var saveAsLastFailure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = receivedUris()
        if (uris.isEmpty()) {
            DiagnosticLog.w(TAG, "import receive failed reason=no_uri")
            toast(getString(R.string.lz_io_no_file))
            finish()
            return
        }
        if (uris.size > MAX_BATCH) toast(getString(R.string.lz_io_too_many, MAX_BATCH))
        Thread {
            uris.take(MAX_BATCH).forEachIndexed { index, uri ->
                try {
                    val mime = intent?.type?.takeUnless { it == "*/*" }
                        ?: contentResolver.getType(uri) ?: "application/octet-stream"
                    val displayName = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    DiagnosticLog.i(TAG, "import receive start index=$index name=$displayName mime=$mime uri=$uri")
                    val temp = copyToCache(uri, index)
                    if (temp != null) pending += PendingImport(temp, displayName, mime, isImageMime(mime))
                    else DiagnosticLog.w(TAG, "import receive failed index=$index reason=SourceUnreadable")
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
        }.apply { name = "Prism-import-cache" }.start()
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
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.lz_io_transfer_progress, pending.size))
            setCancelable(true)
            setOnCancelListener { cancellation.cancel() }
            show()
        }
        Thread {
            var successes = 0
            var lastFailure: String? = null
            pending.forEachIndexed { index, item ->
                if (cancellation.isCancelled()) return@forEachIndexed
                val service = FileBridgeService()
                val result = if (toOtherSpace) {
                    val direction = if (Users.isParentProfile()) TransferDirection.ToProfile else TransferDirection.ToMain
                    service.transferToOtherSpace(this, item.file, item.displayName, item.mime, direction, cancellation)
                } else {
                    service.saveInCurrentSpace(this, item.file, item.displayName, item.mime, cancellation)
                }
                if (result.success) {
                    successes++
                    DiagnosticLog.i(TAG, "import receive done index=$index result=success otherSpace=$toOtherSpace name=${item.displayName}")
                } else {
                    lastFailure = result.message
                    DiagnosticLog.w(TAG, "import receive failed index=$index reason=${result.failureReason} otherSpace=$toOtherSpace name=${item.displayName}")
                }
            }
            runOnUiThread {
                progress.dismiss()
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
            writeToDocument(target, item.file)
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
        pending.forEach { it.file.delete() }
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

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        private const val TAG = "Prism.ImportToSpace"
        private const val REQ_CREATE_DOCUMENT = 4201
        private const val MAX_BATCH = 20
    }
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
