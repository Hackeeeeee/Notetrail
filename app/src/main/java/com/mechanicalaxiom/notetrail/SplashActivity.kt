package com.mechanicalaxiom.notetrail

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        progressBar = findViewById(R.id.splashProgressBar)
        statusText = findViewById(R.id.splashStatusText)

        AppSecurity.checkKillSwitch(
            onSuccess = {
                checkAppConfig()
            },
            onKill = { serverMessage ->
                val intent = Intent(this, KillSwitchActivity::class.java)
                intent.putExtra("MSG", serverMessage)
                startActivity(intent)
                finish()
            }
        )
    }

    private fun checkAppConfig() {
        CoroutineScope(Dispatchers.Main).launch {
            val config = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(5000L) {
                        db.collection("settings").document("app_config").get().await()
                    }
                } catch (e: Exception) {
                    Log.e("SplashActivity", "Error fetching config", e)
                    null
                }
            }

            if (config == null || !config.exists()) {
                // If offline or error, we might want a default behavior. 
                // For now, let's proceed to the next screen if we can't get config, 
                // OR show an error if you want it to be strictly online-first.
                proceedToNextScreen()
                return@launch
            }

            val minVersion = config.getLong("min_version_code") ?: 0L
            val maintenanceMode = config.getBoolean("maintenance_mode") ?: false
            val updateUrl = config.getString("update_url") ?: ""
            val currentVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode

            if (maintenanceMode) {
                showLockDialog("Maintenance Mode", "The app is temporarily down for maintenance. Please check back later.", null)
            } else if (currentVersion < minVersion) {
                showLockDialog("Update Required", "A new version of the app is available. Please update to continue.", updateUrl)
            } else {
                proceedToNextScreen()
            }
        }
    }

    private fun showLockDialog(title: String, message: String, updateUrl: String?) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Exit") { _, _ -> finish() }

        if (!updateUrl.isNullOrEmpty()) {
            builder.setNeutralButton("Update") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                startActivity(intent)
                finish()
            }
        }

        builder.show()
    }

    private fun proceedToNextScreen() {
        val user = auth.currentUser
        if (user != null) {
            // Check for email verification if using password provider
            if (!user.isAnonymous && user.providerData.any { it.providerId == "password" } && !user.isEmailVerified) {
                navigateToVerification()
                return
            }
            // User is logged in, perform security handshake
            performHandshakeAndNavigate()
        } else {
            // No user, go to login
            navigateToLogin()
        }
    }

    private fun performHandshakeAndNavigate() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Initialize hardware-backed encryption
                withContext(Dispatchers.IO) {
                    CryptoManager.init(this@SplashActivity)
                }
                
                if (CryptoManager.hasCloudMasterKey() && CryptoManager.isKeyRestored()) {
                    // Encryption ready, go to main
                    navigateToMain()
                } else {
                    // Hardware key missing or mismatch.
                    // We don't have the password here, so we proceed to Main.
                    // If the user tries to access encrypted data, they'll see the 
                    // "Restore" prompt in DocumentViewer or we can prompt for password later.
                    navigateToMain()
                }
            } catch (e: Exception) {
                Log.e("SplashActivity", "Handshake failed", e)
                // Fallback to main if handshake fails (offline mode or other issues)
                navigateToMain()
            }
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToVerification() {
        val intent = Intent(this, EmailVerificationActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
