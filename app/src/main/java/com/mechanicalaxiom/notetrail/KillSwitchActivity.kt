package com.mechanicalaxiom.notetrail

import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class KillSwitchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kill_switch)

        val message = intent.getStringExtra("MSG") ?: "NoteTrail is temporarily offline for maintenance."
        findViewById<TextView>(R.id.killSwitchMessage).text = message

        // Prevent users from going back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing
            }
        })
    }
}
