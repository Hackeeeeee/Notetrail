package com.mechanicalaxiom.notetrail

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class EmailVerificationActivity : AppCompatActivity() {

    private val TAG = "EmailVerification"
    private lateinit var auth: FirebaseAuth
    private lateinit var progressBar: ProgressBar
    private lateinit var checkButton: Button
    private lateinit var resendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_verification)

        auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        if (user == null) {
            Log.w(TAG, "No user found in EmailVerificationActivity, redirecting to Login")
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val descTextView = findViewById<TextView>(R.id.verifyDesc)
        descTextView.text = Html.fromHtml(getString(R.string.desc_verify_email, user.email), Html.FROM_HTML_MODE_LEGACY)

        progressBar = findViewById(R.id.verifyProgressBar)
        checkButton = findViewById(R.id.checkVerificationButton)
        resendButton = findViewById(R.id.resendEmailButton)
        val backButton = findViewById<Button>(R.id.backToLoginButton)

        checkButton.setOnClickListener { checkVerificationStatus() }
        resendButton.setOnClickListener { resendVerificationEmail() }
        backButton.setOnClickListener {
            Log.d(TAG, "User chose to go back to login")
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun checkVerificationStatus() {
        val user = auth.currentUser ?: return
        
        Log.d(TAG, "Refreshing user state to check verification")
        setLoading(true)
        user.reload().addOnCompleteListener { task ->
            setLoading(false)
            if (task.isSuccessful) {
                Log.d(TAG, "User reloaded. isEmailVerified: ${user.isEmailVerified}")
                if (user.isEmailVerified) {
                    // Success! Proceed to the app
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, getString(R.string.toast_still_not_verified), Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "Error refreshing status", task.exception)
                Toast.makeText(this, "Error refreshing status: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resendVerificationEmail() {
        val user = auth.currentUser ?: return
        
        Log.d(TAG, "Attempting to send verification email to ${user.email}")
        setLoading(true)
        user.sendEmailVerification().addOnCompleteListener { task ->
            setLoading(false)
            if (task.isSuccessful) {
                Log.d(TAG, "Verification email sent successfully")
                Toast.makeText(this, getString(R.string.toast_verification_sent), Toast.LENGTH_SHORT).show()
            } else {
                val exception = task.exception
                Log.e(TAG, "Failed to send verification email", exception)
                val errorMessage = exception?.message ?: "Unknown error"
                Toast.makeText(this, "Failed to send: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        checkButton.isEnabled = !isLoading
        resendButton.isEnabled = !isLoading
    }
}
