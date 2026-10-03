package com.diegonmarcos.superapp.fleetconfig

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.superapp.core.FleetConfigEngine
import com.diegonmarcos.superapp.core.IFleetConfigEngine
import org.json.JSONObject

/**
 * #825 Cloud-Lib-Fleetconfig: the fleet configuration policy, served to every fleet app's
 * `<package>.fleetconfig` provider over [IFleetConfigEngine]. The answers are
 * [FleetPolicyEngine]'s (libs:fleetconfig-model, which the SuperApp also compiles); this class is
 * only the binder, the method list and the never-throw contract.
 */
class FleetConfigEngineService : Service() {

    fun methodNames(): Array<String> = arrayOf(PLAN, EXPORT, IMPORT)

    fun dispatch(method: String, args: Array<String>): String = when (method) {
        PLAN -> FleetPolicyEngine.plan(args[0], args[1])
        EXPORT -> FleetPolicyEngine.export(args[0], args[1], args[2])
        IMPORT -> FleetPolicyEngine.import(args[0], args[1], args[2], args[3])
        else -> JSONObject().put("error", "unknown method $method").toString()
    }

    private val binder = object : IFleetConfigEngine.Stub() {
        override fun contractVersion(): Int = FleetConfigEngine.CONTRACT_VERSION

        override fun methods(): Array<String> = methodNames()

        override fun call(method: String?, args: Array<out String>?): String =
            runCatching { dispatch(method.orEmpty(), args?.map { it }?.toTypedArray() ?: emptyArray()) }
                // Never let an exception cross the binder: the caller would see a bare
                // DeadObjectException with nothing to report.
                .getOrElse { t -> JSONObject().put("error", "${t.javaClass.simpleName}: ${t.message.orEmpty().take(160)}").toString() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val PLAN = "plan"
        const val EXPORT = "export"
        const val IMPORT = "import"
    }
}
