package com.diegonmarcos.cloudkeyboard

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug builds only: a text field that has the focus and the keyboard from the start, for the CI screenshot job. */
class ShotActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply { hint = "Cloud Keyboard screenshot field"; isSingleLine = false }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(this@ShotActivity).apply { text = "Cloud Keyboard" ; textSize = 18f })
            addView(field)
        })
        field.requestFocus()
    }
}
