package io.dossier.app.data.local

import android.content.Context

/**
 * Persists acceptance of Dossier's one-time usage notice.
 *
 * This is deliberately not an identity-verification mechanism. Dossier does not
 * require documents, account linking, selfies, or proof that the operator is the
 * person represented by an audit seed. The notice simply records that the operator
 * acknowledged the lawful/authorized-use boundary and network-processing disclosure.
 */
object UsageNoticeStore {
    private const val PREFS = "dossier-usage-notice"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    private const val CURRENT_VERSION = 1

    fun isAccepted(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= CURRENT_VERSION

    fun accept(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
            .apply()
    }

    fun reset(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACCEPTED_VERSION)
            .apply()
    }
}
