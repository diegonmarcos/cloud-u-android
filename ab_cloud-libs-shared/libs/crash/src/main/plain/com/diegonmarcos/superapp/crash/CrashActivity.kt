package com.diegonmarcos.superapp.crash

import android.os.Bundle
import androidx.activity.ComponentActivity

/** #1122 the crash screen of an app whose build has no compose compiler: the report goes to the share sheet. */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { CrashActions.share(this, CrashActions.load(this)) }
        finish()
    }
}
