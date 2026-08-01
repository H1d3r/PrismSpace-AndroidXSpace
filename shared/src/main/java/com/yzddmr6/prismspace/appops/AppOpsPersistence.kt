package com.yzddmr6.prismspace.appops

/** Android-free codec for the persisted `op:mode,op:mode` format. */
internal object AppOpsPersistence {
    fun encode(entries: Iterable<Pair<Int, Int>>): String =
        entries.joinToString(",") { (op, mode) -> "$op:$mode" }

    fun decode(flat: String): LinkedHashMap<Int, Int> {
        val result = linkedMapOf<Int, Int>()
        flat.splitToSequence(',').map(String::trim).filter(String::isNotEmpty).forEach { entry ->
            val separator = entry.indexOf(':')
            if (separator <= 0 || separator == entry.lastIndex) return@forEach
            val op = entry.substring(0, separator).trim().toIntOrNull() ?: return@forEach
            val mode = entry.substring(separator + 1).trim().toIntOrNull() ?: return@forEach
            result[op] = mode
        }
        return result
    }
}
