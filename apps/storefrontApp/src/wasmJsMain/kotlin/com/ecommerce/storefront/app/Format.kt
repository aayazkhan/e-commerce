package com.ecommerce.storefront.app

/** Minor-unit amount (e.g. paise) to a "1,234.56"-style display string, no currency symbol lookup. */
fun formatMinor(amountMinor: Long): String {
    val negative = amountMinor < 0
    val abs = kotlin.math.abs(amountMinor)
    val whole = abs / 100
    val fraction = (abs % 100).toString().padStart(2, '0')
    return (if (negative) "-" else "") + "$whole.$fraction"
}
