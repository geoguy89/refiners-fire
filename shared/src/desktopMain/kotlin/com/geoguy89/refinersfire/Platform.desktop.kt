package com.geoguy89.refinersfire

import java.text.DateFormat
import java.util.Date

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun formatDate(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMillis))

/** Today's local date as a day number (days since 1 January 1970): the Manna of the day. */
actual fun localDay(epochMillis: Long): Long =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** A day number as a long, friendly date: "Saturday, October 3". */
actual fun formatDay(day: Long): String =
    java.time.LocalDate.ofEpochDay(day).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d"))

private val secure = java.security.SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also(secure::nextBytes)

actual val isWeb: Boolean = false

actual fun installFirstHint(): String? = null

actual fun keepSavedDataSafe() = Unit
