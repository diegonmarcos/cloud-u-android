package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.superapp.bluetooth.BluetoothHub
import com.diegonmarcos.superapp.bluetooth.BluetoothPanel
import com.diegonmarcos.superapp.bluetooth.BtTab
import com.diegonmarcos.superapp.nfc.NfcHub
import com.diegonmarcos.superapp.nfc.NfcPanel
import com.diegonmarcos.superapp.nfc.NfcTab
import com.diegonmarcos.superapp.wifi.WifiHostConfig
import com.diegonmarcos.superapp.wifi.WifiHub
import com.diegonmarcos.superapp.wifi.WifiPanel
import com.diegonmarcos.superapp.wifi.WifiTab
import org.json.JSONObject

// #1264 Measure > WiFi, Measure > Bluetooth and Measure > NFC: the shared libs' pages, one tab per declared mode (spec.tab). No radio code of
// this app's own: the permissions are the libs' (merged manifests; Bluetooth's runtime ones asked for by BluetoothPanel's gate, from
// a tap), the controllers are the libs' process-wide hubs, so a tab switch keeps the connection, the last tag and the history.

private fun tabOf(mode: Declarations.Mode): String = JSONObject(mode.spec).optString("tab")

@Composable
private fun RadioHeader(why: Int) {
    Text(stringResource(why), Modifier.padding(bottom = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun BluetoothMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val controller = remember { BluetoothHub.controller(ctx.applicationContext) }
    val state = LocalCalcState.current
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter)) {
        RadioHeader(R.string.radio_bt_why)
        BluetoothPanel(controller, BtTab.of(tabOf(mode))) { next -> state.show("bt_" + next.id) }
    }
}

@Composable
fun NfcMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val controller = remember { NfcHub.controller(ctx.applicationContext) }
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter)) {
        RadioHeader(R.string.radio_nfc_why)
        NfcPanel(controller, NfcTab.of(tabOf(mode)))
    }
}

@Composable
fun WifiMode(mode: Declarations.Mode) {
    val ctx = LocalContext.current
    val controller = remember { WifiHub.controller(ctx.applicationContext, WifiHostConfig(appLabel = "Cloud Calc")) }
    val state = LocalCalcState.current
    Column(Modifier.fillMaxSize().padding(CalcMetrics.gutter)) {
        RadioHeader(R.string.radio_wifi_why)
        WifiPanel(controller, WifiTab.of(tabOf(mode)), bottomClearance = 24.dp) { next -> state.show("wifi_" + next.id) }
    }
}
