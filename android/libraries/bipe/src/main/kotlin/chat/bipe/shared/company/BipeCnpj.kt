// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import java.time.LocalDate

/** Display helpers for a normalised CNPJ (bipe-shared `formatCnpj`, test-vectors/cnpj.json). Same as BipeKit `BipeCNPJ`. */
object BipeCnpj {
    /** `12.ABC.345/01DE-35` from the 14 characters of a normalised CNPJ. Other input comes back unchanged. */
    fun formatted(cnpj: String): String =
        if (cnpj.length != 14) cnpj else "${cnpj.substring(0, 2)}.${cnpj.substring(2, 5)}.${cnpj.substring(5, 8)}/${cnpj.substring(8, 12)}-${cnpj.substring(12)}"

    /** A `YYYY-MM` registry release month as its first day. */
    fun checkedDate(month: String): LocalDate? = runCatching { LocalDate.parse("$month-01") }.getOrNull()
}
