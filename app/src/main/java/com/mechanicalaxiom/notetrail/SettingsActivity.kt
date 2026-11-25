package com.mechanicalaxiom.notetrail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth

class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()
    private val auth by lazy { FirebaseAuth.getInstance() }

    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progressBar = findViewById(R.id.settingsProgressBar)

        setupUserInfo()
        setupDarkMode()
        setupAboutSection()
        setupDeleteAccount()
        setupWebDeletion()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupUserInfo() {
        val userEmailText = findViewById<TextView>(R.id.settingsUserEmail)
        userEmailText.text = auth.currentUser?.email ?: "Guest"
    }

    private fun setupDarkMode() {
        val radioGroup = findViewById<RadioGroup>(R.id.settingsDarkModeRadioGroup)
        val currentMode = getSavedThemeMode()

        when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> radioGroup.check(R.id.settingsRadioLight)
            AppCompatDelegate.MODE_NIGHT_YES -> radioGroup.check(R.id.settingsRadioDark)
            else -> radioGroup.check(R.id.settingsRadioAuto)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.settingsRadioLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.settingsRadioDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
            saveThemeMode(mode)
        }
    }

    private fun getSavedThemeMode(): Int {
        val prefs = getSharedPreferences("IgcseProgressPrefs", MODE_PRIVATE)
        return prefs.getInt("SelectedTheme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun saveThemeMode(mode: Int) {
        getSharedPreferences("IgcseProgressPrefs", MODE_PRIVATE).edit {
            putInt("SelectedTheme", mode)
        }
    }

    private fun setupDeleteAccount() {
        findViewById<Button>(R.id.settingsDeleteAccountButton).setOnClickListener {
            showDeleteConfirmation()
        }
    }

    private fun setupWebDeletion() {
        findViewById<TextView>(R.id.settingsWebDeletionLink).setOnClickListener {
            val deletionUrl = "https://docs.google.com/forms/d/e/1FAIpQLScYy8XV4QLBtEjgleypuoiGEf3SpUHc3qgWFGXVsGrSKUmkmg/viewform?usp=sharing&ouid=109862600289040012243"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deletionUrl))
            startActivity(intent)
        }
    }

    private fun setupAboutSection() {
        // Set Version Name
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            findViewById<TextView>(R.id.settingsVersionText).text = version
        } catch (e: Exception) {
            findViewById<TextView>(R.id.settingsVersionText).text = "1.0"
        }

        // Set About Developer Link
        findViewById<TextView>(R.id.settingsAboutDeveloperLink).setOnClickListener {
            showAboutDeveloperDialog()
        }
    }

    private fun showAboutDeveloperDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        
        val dialog = MaterialAlertDialogBuilder(this, R.style.GlassDialogTheme)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete Account?")
            .setMessage("This will permanently erase all your encrypted notes, progress, and saved papers. This action cannot be undone.")
            .setPositiveButton("Delete Everything") { _, _ ->
                performDeletion()
            }
            .setNegativeButton("Keep My Data", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(android.R.color.holo_red_dark))
            }
    }

    private fun performDeletion() {
        progressBar.visibility = View.VISIBLE
        viewModel.deleteUserAccount { success, error ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "Account deleted successfully", Toast.LENGTH_LONG).show()
                    SessionManager.logout(this)
                } else {
                    Toast.makeText(this, "Deletion failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
