package com.yzddmr6.prismspace.controller

import android.content.Context

internal interface ClonePreparationPersistence {
    fun read(): Set<String>
    fun write(packages: Set<String>)
}

internal class ClonePreparationStateStore(
    private val persistence: ClonePreparationPersistence,
) {
    private val lock = Any()

    fun add(packageName: String) = synchronized(lock) {
        require(packageName.isNotBlank())
        val current = persistence.read()
        if (packageName !in current) persistence.write(current + packageName)
    }

    fun remove(packageName: String) = synchronized(lock) {
        val current = persistence.read()
        if (packageName in current) persistence.write(current - packageName)
    }

    fun contains(packageName: String): Boolean = synchronized(lock) {
        packageName in persistence.read()
    }

    fun reconcileInstalled(installedPackages: Set<String>): Set<String> = synchronized(lock) {
        val current = persistence.read()
        val pending = current - installedPackages
        if (pending != current) persistence.write(pending)
        pending
    }

    /** Drops every pending marker (space deletion — the tasks target a space that no longer exists). */
    fun clear() = synchronized(lock) { persistence.write(emptySet()) }
}

/** Main-space workflow state only; copied APKs are not treated as proof that installation succeeded. */
object ClonePreparationStore {
    private const val KEY = "prism_pending_clone_install_packages"
    private const val FILE = "clone_preparation"
    private val lock = Any()

    private fun persistence(context: Context) = object : ClonePreparationPersistence {
        private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun read(): Set<String> =
            preferences.getStringSet(KEY, emptySet()).orEmpty().toSet()

        override fun write(packages: Set<String>) {
            preferences.edit().putStringSet(KEY, packages).apply()
        }
    }

    fun add(context: Context, packageName: String) = synchronized(lock) {
        ClonePreparationStateStore(persistence(context)).add(packageName)
    }

    fun remove(context: Context, packageName: String) = synchronized(lock) {
        ClonePreparationStateStore(persistence(context)).remove(packageName)
    }

    fun clear(context: Context) = synchronized(lock) {
        ClonePreparationStateStore(persistence(context)).clear()
    }

    fun contains(context: Context, packageName: String): Boolean = synchronized(lock) {
        ClonePreparationStateStore(persistence(context)).contains(packageName)
    }

    fun reconcileInstalled(context: Context, installedPackages: Set<String>): Set<String> = synchronized(lock) {
        ClonePreparationStateStore(persistence(context)).reconcileInstalled(installedPackages)
    }
}
