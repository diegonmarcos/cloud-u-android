package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetconfig.MailEndpointsContract
import com.diegonmarcos.superapp.mail.JmapPrefs
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs

/**
 * #1090 What Cloud Account publishes to Cloud Mail: per fleet mail account, the mesh URL and the public URL
 * ([MailEndpointsContract.Entry]) derived from the host's one declaration, and the fleet bearer when the caller
 * holds that grant. The accounts are the vault's declared mail accounts as this device stored them; before the
 * Fleet apply has run they come from the imported vault with the same rules the apply uses. An account on no
 * declared service gets nothing: it is not a fleet account and keeps its single endpoint.
 */
object MailEndpointsPublisher {

    /** (email, JMAP host) of every declared mail account on this device. */
    fun accounts(ctx: Context, vault: AccountVault = AccountVault(ctx)): List<Pair<String, String>> {
        val stored = JmapPrefs(ctx).accounts().map { it.email to it.jmap }.filter { it.second.isNotBlank() }
        if (stored.isNotEmpty()) return stored
        val bundle = vault.connections()
        val host = VaultCockpit.mailEndpoints(bundle).jmap
        val owner = ConfigsPrefs(ctx).autheliaEmail.ifBlank { ProfilePrefs(ctx).email.trim() }
        return VaultCockpit.mailAccounts(bundle, owner.substringAfter('@', "")).map { it.email to host }.filter { it.second.isNotBlank() }
    }

    /** The reply bundle for [caller]: entries always (the caller was checked for `mail.endpoints`), the bearer only when granted. */
    fun publish(ctx: Context, caller: String, vault: AccountVault = AccountVault(ctx)): Bundle {
        val services = MailEndpointsContract.parseDecl(AccountHost.mailEndpointsDecl())
        val entries = MailEndpointsContract.entries(services, accounts(ctx, vault))
        val mayHaveBearer = entries.isNotEmpty() &&
            (caller == ctx.packageName || vault.grants.allow(caller, MailEndpointsContract.GRANT_BEARER))
        val bearer = if (mayHaveBearer) vault.connection(MailEndpointsContract.GRANT_BEARER)?.toString().orEmpty() else ""
        return Bundle().apply {
            putBoolean(MailEndpointsContract.KEY_OK, true)
            putString(MailEndpointsContract.KEY_JSON, MailEndpointsContract.toJson(entries, bearer))
        }
    }
}
