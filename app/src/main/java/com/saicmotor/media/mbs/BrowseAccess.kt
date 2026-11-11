package com.saicmotor.media.mbs

import android.os.Process

/**
 * Who may browse the library (MediaService and the launcher's browse
 * services, which Android lets any app connect to).
 *
 * What's handed out carries the Navidrome login: the cover addresses hold
 * the username and a token that works as a login until the password is
 * changed.  So it's for the car's own apps — the launcher and the system's
 * screens, which run as the system user, as this app does — and not for any
 * other app on the head unit that asks.
 */
object BrowseAccess {

    /** Android gives each user of the head unit its own range of ids: user × 100000 + app. */
    private const val PER_USER = 100_000

    fun allowed(uid: Int): Boolean {
        val app = uid % PER_USER
        return app == Process.SYSTEM_UID || app == Process.myUid() % PER_USER
    }
}
