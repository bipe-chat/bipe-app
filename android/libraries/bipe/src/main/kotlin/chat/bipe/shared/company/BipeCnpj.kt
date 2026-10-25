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

/**
 * The CNPJ as 14 uppercase characters, or null when the format or a check digit is wrong (bipe-shared normalizeCnpj,
 * test-vectors/cnpj.json). Numeric and alphanumeric CNPJs. Same as BipeKit `BipeCNPJ.normalized`.
 */
fun BipeCnpj.normalized(input: String): String? {
    val s = input.filterNot { it in ".-/ \t\n" }.uppercase()
    if (!Regex("[0-9A-Z]{12}[0-9]{2}").matches(s) || s.toSet().size == 1) return null
    val values = s.map { it.code - 48 }
    fun digit(count: Int, weights: List<Int>): Int {
        val r = values.take(count).zip(weights).sumOf { (v, w) -> v * w } % 11
        return if (r < 2) 0 else 11 - r
    }
    val d1 = digit(12, listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
    val d2 = digit(13, listOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
    return if (values[12] == d1 && values[13] == d2) s else null
}

/** A website as the API takes it: lower case, without scheme, path, `www.` or a final dot. null when it is not a host name. */
fun BipeCnpj.domain(input: String): String? {
    var s = input.trim().lowercase()
    s.indexOf("://").takeIf { it >= 0 }?.let { s = s.substring(it + 3) }
    s = s.takeWhile { it != '/' && it != '?' && it != '#' }
    if (s.startsWith("www.")) s = s.removePrefix("www.")
    s = s.removeSuffix(".")
    return s.takeIf { it.length <= 253 && Regex("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}").matches(it) }
}
