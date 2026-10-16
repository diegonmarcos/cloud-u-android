package org.fossify.phone

import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.BLOCK_UNKNOWN_NUMBERS

/** Cloud Dialer application class (extends commons' FossifyApp). */
class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        // The fleet setup contract carries the call log and blocked numbers (store call_history).
        com.diegonmarcos.superapp.fleetconfig.SetupStores.register("call_history", org.fossify.phone.fleet.CallHistoryBackup)

        // First-run screening default = contacts_only, per the owner contract in
        // build.json::forks.dialer.call_screening.default_mode: unknown numbers
        // are silently rejected until the user picks another mode in Settings.
        // Seed-once (key-absence check) so the user's later choice always wins.
        if (!getSharedPrefs().contains(BLOCK_UNKNOWN_NUMBERS)) {
            baseConfig.blockUnknownNumbers = true
        }
    }
}
