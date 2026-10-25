package com.diegonmarcos.cloudcalc.sensors

import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.measure.Sensors

/** build.json::sensors, decoded once from the baked blob and validated by measure/Sensors.kt; the Sensors screens read nothing else about what a sensor is. */
object SensorDecl {
    val catalog: Sensors.Catalog by lazy {
        Sensors.parse(String(java.util.Base64.getDecoder().decode(BuildConfig.SENSORS_CONFIG_B64), Charsets.UTF_8))
    }
}
