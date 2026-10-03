package com.geoguy89.refinersfire

/** The installed version, shown in Options. Set by each app at startup. */
object AppVersion {
    var name: String = "dev"
    var build: Int = 0
    val label: String get() = if (build > 0) "Version $name (build $build)" else "Version $name"
}

/** Wall-clock time, for dating Hall of Fame entries. */
expect fun epochMillis(): Long

/** A short, locale-appropriate date. */
expect fun formatDate(epochMillis: Long): String

/** The player's local date as a day number (days since 1 January 1970). */
expect fun localDay(epochMillis: Long): Long

/** A day number as a long date, e.g. "Saturday, October 3". */
expect fun formatDay(day: Long): String

/** Cryptographically secure random bytes (chat keys and nonces). */
expect fun secureRandomBytes(n: Int): ByteArray

/** True in the web version (iPhone, iPad, any browser). */
expect val isWeb: Boolean

/**
 * In a Safari tab on an iPhone or iPad (not the Home Screen app), a note to add the game to the Home Screen before
 * choosing a name: the Home Screen app keeps its own saved data, so an account made in the tab wouldn't carry over.
 * Null everywhere else.
 */
expect fun installFirstHint(): String?

/** Asks the browser to keep the saved data (account, chats, scores) even when the device runs low on space. No-op in the apps. */
expect fun keepSavedDataSafe()
