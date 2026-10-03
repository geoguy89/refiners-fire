package com.geoguy89.refinersfire

// The web version's platform pieces, through small JavaScript snippets (Kotlin/Wasm `js()` functions).

private fun jsNow(): Double = js("Date.now()")

private fun jsShortDate(t: Double): String = js("new Date(t).toLocaleDateString(undefined, { year: '2-digit', month: 'numeric', day: 'numeric' })")

private fun jsTimezoneOffsetMinutes(t: Double): Int = js("new Date(t).getTimezoneOffset()")

private fun jsLongDay(t: Double): String = js("new Date(t).toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', timeZone: 'UTC' })")

private fun jsRandomByte(): Int = js("crypto.getRandomValues(new Uint8Array(1))[0]")

actual fun epochMillis(): Long = jsNow().toLong()

actual fun formatDate(epochMillis: Long): String = jsShortDate(epochMillis.toDouble())

actual fun localDay(epochMillis: Long): Long {
    // getTimezoneOffset is minutes *behind* UTC (e.g. 240 for US Eastern in summer).
    val local = epochMillis - jsTimezoneOffsetMinutes(epochMillis.toDouble()) * 60_000L
    return local.floorDiv(86_400_000L)
}

actual fun formatDay(day: Long): String = jsLongDay(day * 86_400_000.0)

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n) { jsRandomByte().toByte() }

actual val isWeb: Boolean = true

private fun jsIosSafariTab(): Boolean = js(
    """{
        var ua = navigator.userAgent || '';
        // iPadOS reports itself as a Mac, so a touch screen gives it away.
        var ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
        var installed = navigator.standalone === true || (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
        return ios && !installed;
    }""",
)

private fun jsPersistStorage(): Unit = js(
    "{ try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(function () {}); } catch (e) {} }",
)

actual fun installFirstHint(): String? =
    if (jsIosSafariTab()) "Playing on an iPhone or iPad? Add the game to your Home Screen first (tap Share, then Add to Home Screen) and choose your name there. The Home Screen app keeps its own progress, so a name chosen here in Safari won't carry over." else null

actual fun keepSavedDataSafe() = jsPersistStorage()
