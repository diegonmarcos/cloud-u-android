package com.x8bit.bitwarden.ui.platform.feature.settings.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus

private fun ownAutofill(ctx: android.content.Context): Boolean? =
    runCatching { ctx.getSystemService(android.view.autofill.AutofillManager::class.java)?.hasEnabledAutofillServices() }.getOrNull()

/** Re-reads the provider status every time the screen resumes (the user comes back from Android's settings). */
@Composable
private fun rememberProviderStatus(): CredentialProviderStatus.Status {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(CredentialProviderStatus.status(ctx, ctx.packageName, ownAutofill(ctx))) }
    LifecycleResumeEffect(Unit) {
        status = CredentialProviderStatus.status(ctx, ctx.packageName, ownAutofill(ctx))
        onPauseOrDispose { }
    }
    return status
}

/**
 * Setup step: make Cloud Vault the DEFAULT passwords/passkeys provider. Enable opens Android's own
 * screen (nothing is written from here); Skip hides the step; the status re-checks on resume.
 */
@Composable
fun CloudVaultProviderStep(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val status = rememberProviderStatus()
    var skipped by rememberSaveable { mutableStateOf(false) }
    if (skipped) return
    Column(modifier = modifier) {
        Text(
            text = "Make Cloud Vault your passwords & passkeys provider",
            style = BitwardenTheme.typography.titleMedium,
            color = BitwardenTheme.colorScheme.text.primary,
        )
        Text(
            text = "${status.overall.label} — ${status.detail()}",
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (!status.complete) {
            BitwardenFilledButton(
                label = "Enable",
                onClick = { CredentialProviderStatus.open(ctx, status.nextStep, ctx.packageName) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            BitwardenOutlinedButton(
                label = "Skip",
                onClick = { skipped = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Vault's settings: the same status as a persistent row; tapping opens the next Android screen. */
@Composable
fun CloudVaultProviderStatusRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val status = rememberProviderStatus()
    Column(
        modifier = modifier
            .clickable { CredentialProviderStatus.open(ctx, status.nextStep, ctx.packageName) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = "Passwords & passkeys provider: ${status.overall.label}",
            style = BitwardenTheme.typography.bodyLarge,
            color = BitwardenTheme.colorScheme.text.primary,
        )
        Text(
            text = status.detail(),
            style = BitwardenTheme.typography.bodySmall,
            color = BitwardenTheme.colorScheme.text.secondary,
        )
    }
}
