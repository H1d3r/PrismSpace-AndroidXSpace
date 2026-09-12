package com.yzddmr6.prismspace.prism.service

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Trace
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.yzddmr6.prismspace.util.LauncherAppsCompat
import com.yzddmr6.prismspace.util.UserHandles
import com.yzddmr6.prismspace.util.Users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** ViewModel-owned, bounded icon cache. UI cancellation does not restart an in-flight decode. */
class SpaceIconLoader(private val context: Context, private val scope: CoroutineScope) {
    private data class Key(val user: Int, val pkg: String, val version: String, val pixels: Int, val dark: Boolean)
    private val cache = object : LruCache<Key, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }
    private val mutex = Mutex()
    private val pending = mutableMapOf<Key, Deferred<Bitmap?>>()

    suspend fun load(userId: Int, packageName: String, version: String, pixels: Int, dark: Boolean): Bitmap? {
        val key = Key(userId, packageName, version, pixels, dark)
        cache.get(key)?.let { return it }
        val work = mutex.withLock {
            pending[key] ?: scope.async(Dispatchers.IO) {
                Trace.beginSection("PrismSpace.loadIcon")
                try {
                    val drawable = if (userId == Users.currentId()) context.packageManager.getApplicationIcon(packageName)
                    else LauncherAppsCompat(context).getApplicationInfoNoThrows(
                        packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, UserHandles.of(userId),
                    )?.loadIcon(context.packageManager)
                    drawable?.toBitmap(pixels, pixels)?.also { cache.put(key, it) }
                } catch (_: PackageManager.NameNotFoundException) { null }
                catch (_: RuntimeException) { null }
                finally { Trace.endSection() }
            }.also { job ->
                pending[key] = job
                job.invokeOnCompletion {
                    scope.launch { mutex.withLock { if (pending[key] === job) pending.remove(key) } }
                }
            }
        }
        return work.await()
    }
}
