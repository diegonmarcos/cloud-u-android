package com.diegonmarcos.cloudcontacts

import android.app.Application
import com.diegonmarcos.superapp.fleetconfig.SetupStores

/** Registers the contacts store on the setup contract, so Cloud Account's backup and restore carry the contacts. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SetupStores.register("contacts_vcards", ContactsBackup)
    }
}
