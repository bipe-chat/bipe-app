// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

/**
 * An OS age range (ADR 0229): whole years, inclusive, either end open. [source] is `play`, or `none` when the OS gave no
 * range. The server maps it to a band and keeps only the band.
 */
data class BipeAgeSignal(val source: String, val lower: Int? = null, val upper: Int? = null, val method: String? = null) {
    companion object {
        val NONE = BipeAgeSignal("none")
    }
}

/** Where this build reads the age: Google Play Age Signals in the Play build (ADR 0229). The foss build uses [None]. */
fun interface BipeAgeSignalSource {
    /** Never throws: no range, a refusal or an error gives [BipeAgeSignal.NONE]. */
    suspend fun read(): BipeAgeSignal

    object None : BipeAgeSignalSource {
        override suspend fun read() = BipeAgeSignal.NONE
    }
}
