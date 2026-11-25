package com.mechanicalaxiom.notetrail

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.common.SignInButton
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var progressBar: ProgressBar
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var skipButton: Button
    private lateinit var googleSignInButton: SignInButton
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var termsCheckBox: CheckBox
    private lateinit var termsTextView: TextView

    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Initialize UI Elements
        emailInput = findViewById(R.id.emailEditText)
        passwordInput = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        registerButton = findViewById(R.id.registerButton)
        skipButton = findViewById(R.id.skipLoginButton)
        googleSignInButton = findViewById(R.id.googleSignInButton)
        progressBar = findViewById(R.id.loginProgressBar)
        termsCheckBox = findViewById(R.id.termsCheckBox)
        termsTextView = findViewById(R.id.termsTextView)

        // Initialize Firebase
        initializeFirebase()
        credentialManager = CredentialManager.create(this)

        // Check if user is already signed in
        if (::auth.isInitialized && auth.currentUser != null) {
            val user = auth.currentUser
            if (user != null && !user.isAnonymous && user.providerData.any { it.providerId == "password" } && !user.isEmailVerified) {
                navigateToVerification()
                return
            }
            // For already logged in users, we don't have the password, so we try a passive handshake
            performSilentHandshake(null)
            return
        }

        loadSavedEmail()
        setupClickListeners()
        setupTermsAndConditions()
        updateButtonStates()
    }

    private fun setupTermsAndConditions() {
        val fullText = "I agree to the Terms & Privacy Policy"
        val linkText = "Terms & Privacy Policy"
        val spannableString = SpannableString(fullText)
        val start = fullText.indexOf(linkText)
        val end = start + linkText.length

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                showTermsDialog()
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = true
                ds.color = ContextCompat.getColor(this@LoginActivity, R.color.gradient_accent)
            }
        }

        spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.gradient_accent)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        termsTextView.text = spannableString
        termsTextView.movementMethod = LinkMovementMethod.getInstance()

        termsCheckBox.setOnCheckedChangeListener { _, _ ->
            updateButtonStates()
        }
    }

    private fun showTermsDialog() {
        AlertDialog.Builder(this)
            .setTitle("User Agreement & Privacy Policy")
            .setMessage(AGREEMENT_TEXT)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun updateButtonStates() {
        val isAccepted = termsCheckBox.isChecked
        loginButton.isEnabled = isAccepted
        registerButton.isEnabled = isAccepted
        googleSignInButton.isEnabled = isAccepted
        skipButton.isEnabled = isAccepted
    }

    private fun initializeFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e("LoginActivity", "Error initializing Firebase", e)
            Toast.makeText(this, "Configuration Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            loginButton.isEnabled = false
            registerButton.isEnabled = false
            googleSignInButton.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (validateInput(email, password)) {
                performLogin(email, password)
            }
        }

        registerButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (validateInput(email, password)) {
                performRegistration(email, password)
            }
        }

        skipButton.setOnClickListener {
            performGuestLogin()
        }

        googleSignInButton.setOnClickListener {
            signInWithGoogle()
        }
    }

    private fun signInWithGoogle() {
        showLoading(true)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(this@LoginActivity, request)
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                firebaseAuthWithGoogle(googleIdTokenCredential)
            } catch (e: GetCredentialException) {
                showLoading(false)
                Log.e("LoginActivity", "Google Sign-In Error: ${e.type}", e)
                Toast.makeText(this@LoginActivity, "Google Sign-In Error: ${e.message}", Toast.LENGTH_LONG).show()
                handleAuthError(e)
            }
        }
    }

    private fun firebaseAuthWithGoogle(credential: GoogleIdTokenCredential) {
        val authCredential = GoogleAuthProvider.getCredential(credential.idToken, null)
        auth.signInWithCredential(authCredential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    getSharedPreferences("IgcseProgressPrefs", MODE_PRIVATE).edit { clear() }
                    createNewUserInFirestore()
                    fetchAndSaveGroupId()
                    // Google Sign-In doesn't give us a password, so we use a constant salt/seed 
                    // or ideally just rely on the Google ID token for future recovery if needed.
                    // For now, we'll use a fixed recovery hook for Google users.
                    performSilentHandshake("GOOGLE_PROVIDER_RECOVERY_HOOK")
                } else {
                    showLoading(false)
                    handleAuthError(task.exception)
                }
            }
    }

    private fun performLogin(email: String, password: String) {
        if (!::auth.isInitialized) {
            Toast.makeText(this, "Authentication not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    getSharedPreferences("IgcseProgressPrefs", MODE_PRIVATE).edit { clear() }
                    saveEmailLocally(email)
                    fetchAndSaveGroupId()
                    
                    val user = auth.currentUser
                    if (user != null && !user.isEmailVerified) {
                        navigateToVerification()
                    } else {
                        performSilentHandshake(password)
                    }
                } else {
                    showLoading(false)
                    when (task.exception) {
                        is FirebaseAuthInvalidUserException -> showRegistrationPrompt(email, password)
                        is FirebaseAuthInvalidCredentialsException -> {
                            passwordInput.error = "Incorrect password"
                            passwordInput.requestFocus()
                        }
                        else -> handleAuthError(task.exception)
                    }
                }
            }
    }

    private fun performSilentHandshake(password: String?) {
        showLoading(true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Silent Key Restore from Hardware
                withContext(Dispatchers.IO) {
                    CryptoManager.init(this@LoginActivity)
                }
                
                // 2. Critical Flow: If key was NOT restored, or it's a new temporary key, we need to handle it
                if (CryptoManager.hasCloudMasterKey()) {
                    if (CryptoManager.isKeyRestored()) {
                        // Success: Hardware linked and key matched local storage
                        Log.d("LoginActivity", "Hardware key matched. Proceeding to main.")
                        
                        // If we have the password, ensure backup is up to date (Silent Update)
                        if (password != null) {
                            withContext(Dispatchers.IO) {
                                try {
                                    RecoveryManager.backupSyncVault(password)
                                } catch (e: Exception) {
                                    Log.e("LoginActivity", "Silent vault backup failed: ${e.message}")
                                }
                            }
                        }
                        
                        showLoading(false)
                        navigateToMain()
                    } else {
                        // Mismatch: Key was newly generated or wiped
                        Log.w("LoginActivity", "Key mismatch or missing. Attempting Automated Restoration...")
                        
                        val hasVault = withContext(Dispatchers.IO) {
                            try {
                                RecoveryManager.hasVaultBackup()
                            } catch (e: Exception) {
                                Log.e("LoginActivity", "Vault check failed: ${e.message}")
                                false
                            }
                        }
                        
                        if (hasVault) {
                            if (password != null) {
                                // Attempt automatic restoration with the password we just got
                                val restored = withContext(Dispatchers.IO) {
                                    RecoveryManager.restoreSyncVault(this@LoginActivity, password)
                                }
                                showLoading(false)
                                if (restored) {
                                    Log.i("LoginActivity", "Vault automatically restored using password.")
                                    navigateToMain()
                                } else {
                                    Log.e("LoginActivity", "Vault restoration failed with provided password.")
                                    // Fallback to main, but annotations might fail to decrypt
                                    navigateToMain()
                                }
                            } else {
                                // Already logged in but hardware key missing - need password to restore
                                // In a real app, you might show a "Enter Password to Unlock Vault" dialog here.
                                // For now, we go to main, and DocumentViewer will handle the error if it happens.
                                showLoading(false)
                                navigateToMain()
                            }
                        } else {
                            // Truly a new user or vault was never created
                            if (password != null) {
                                Log.i("LoginActivity", "No vault found. Creating new vault with password.")
                                withContext(Dispatchers.IO) {
                                    try {
                                        RecoveryManager.backupSyncVault(password)
                                    } catch (e: Exception) {
                                        Log.e("LoginActivity", "Initial vault backup failed: ${e.message}")
                                    }
                                }
                            }
                            showLoading(false)
                            navigateToMain()
                        }
                    }
                } else {
                    // Fallback for unexpected states
                    showLoading(false)
                    navigateToMain()
                }
            } catch (e: Exception) {
                showLoading(false)
                Log.e("LoginActivity", "Handshake critical failure", e)
                navigateToMain()
            }
        }
    }

    private fun showRegistrationPrompt(email: String, password: String) {
        AlertDialog.Builder(this)
            .setTitle("Account Not Found")
            .setMessage("No account exists for $email. Would you like to create one?")
            .setPositiveButton("Create Account") { _, _ ->
                performRegistration(email, password)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRegistration(email: String, password: String) {
        if (!::auth.isInitialized) {
            Toast.makeText(this, "Authentication not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    getSharedPreferences("IgcseProgressPrefs", MODE_PRIVATE).edit { clear() }
                    saveEmailLocally(email)
                    createNewUserInFirestore()
                    
                    val user = auth.currentUser
                    user?.sendEmailVerification()
                    
                    // Automate initial vault creation for new users
                    CoroutineScope(Dispatchers.Main).launch {
                        withContext(Dispatchers.IO) {
                            CryptoManager.init(this@LoginActivity)
                            try {
                                RecoveryManager.backupSyncVault(password)
                            } catch (e: Exception) {
                                Log.e("LoginActivity", "Initial registration vault backup failed: ${e.message}")
                            }
                        }
                        showLoading(false)
                        navigateToVerification()
                    }
                } else {
                    showLoading(false)
                    handleAuthError(task.exception)
                }
            }
    }

    private fun performGuestLogin() {
        if (!::auth.isInitialized) {
            Toast.makeText(this, "Entering Offline Mode", Toast.LENGTH_SHORT).show()
            navigateToMain()
            return
        }

        showLoading(true)
        auth.signInAnonymously()
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    Toast.makeText(this, "Signed in as Guest", Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    Log.w("LoginActivity", "Guest login failed", task.exception)
                    Toast.makeText(this, "Guest Login Failed. Continuing Offline.", Toast.LENGTH_LONG).show()
                    navigateToMain()
                }
            }
    }

    private fun handleAuthError(exception: Exception?) {
        val message = when (exception) {
            is FirebaseAuthInvalidCredentialsException -> "Invalid email or password."
            is FirebaseAuthUserCollisionException -> "An account with this email already exists."
            is GetCredentialException -> "Google Sign-In failed: ${exception.message}"
            else -> {
                Log.e("LoginActivity", "Auth error", exception)
                if (exception?.message?.contains("CONFIGURATION_NOT_FOUND") == true) {
                    "Configuration Error. Please check google-services.json."
                } else {
                    exception?.message ?: "Authentication failed."
                }
            }
        }
        Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
    }

    private fun validateInput(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            emailInput.error = "Email is required"
            emailInput.requestFocus()
            return false
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.error = "Invalid email format"
            emailInput.requestFocus()
            return false
        }
        if (password.isEmpty()) {
            passwordInput.error = "Password is required"
            passwordInput.requestFocus()
            return false
        }
        if (password.length < 8) {
            passwordInput.error = "Password must be at least 8 characters"
            passwordInput.requestFocus()
            return false
        }
        return true
    }

    private fun createNewUserInFirestore() {
        val userId = auth.currentUser?.uid ?: return
        val user = hashMapOf(
            "email" to auth.currentUser?.email,
            "groupId" to null,
            "termsAccepted" to true,
            "termsAcceptedAt" to com.google.firebase.Timestamp.now()
        )
        db.collection("users").document(userId).set(user, SetOptions.merge())
    }

    private fun fetchAndSaveGroupId() {
        val userId = auth.currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val document = db.collection("users").document(userId).get().await()
                val groupId = document.getString("groupId")
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit {
                    putString("groupId", groupId)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Failed to fetch group info", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveEmailLocally(email: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {
            putString(KEY_EMAIL, email)
        }
    }

    private fun loadSavedEmail() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedEmail = prefs.getString(KEY_EMAIL, null)
        if (!savedEmail.isNullOrEmpty()) {
            emailInput.setText(savedEmail)
        }
    }

    private fun showLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        val isAccepted = termsCheckBox.isChecked
        loginButton.isEnabled = !isLoading && isAccepted
        registerButton.isEnabled = !isLoading && isAccepted
        skipButton.isEnabled = !isLoading && isAccepted
        googleSignInButton.isEnabled = !isLoading && isAccepted
        emailInput.isEnabled = !isLoading
        passwordInput.isEnabled = !isLoading
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

    companion object {
        private const val PREFS_NAME = "LoginPrefs"
        private const val KEY_EMAIL = "saved_email"

        private const val AGREEMENT_TEXT = """
⚖️ NoteTrail: User Agreement & Privacy Policy
Last Updated: May 2026

1. Acceptance of Terms
By downloading or using NoteTrail Application (NoteTrail), you agree to be bound by these terms. This app is an independent, non-commercial educational tool developed by a student for the (International General Certificate of Secondary Education (IGCSE) community.

2. Nature of Service 
Archive Indexing: NoteTrail acts as a specialized View port for publicly available educational archives. It does not host copyrighted papers on its servers.

No Ownership:  NoteTrail is not affiliated with or endorsed by any examination board such as IGCSE, etc.

Accuracy: While we strive for accuracy, the developer is not responsible for any errors in the papers utilised for the user's final exam results or any other purpose whatsoever.

3. Non-Commercial: Users may not use NoteTrail or data extracted from NoteTrail for any commercial purposes. Also resell or replication access to the app's features in not allowed.

4. Privacy & Data Security (The "Zero-Knowledge" Standard)
Encryption: All user annotations and personal notes are protected by AES-256 Hardware-Backed Encryption. This data is "Interlinked" with your device's security chip.

Developer Access: The developer cannot read your notes, view your drawings, or access your device files.

Email Usage: We collect your email address solely for account authentication (via Firebase) and to sync your study progress across devices. We do not sell or share your data with third parties.

5. Remote Management 
The developer reserves the right to temporarily or permanently disable the app via a Remote Kill Switch for maintenance, legal compliance, or security updates. In such cases, a status message will be displayed to all users.

6. DMCA & Takedowns
NoteTrail respects intellectual property rights. If you are a copyright holder and wish to have specific content removed from our indexing service, please contact the developer directly. Links to the requested content will be removed shortly.

7. Limitation of Liability
The app is provided "as-is." The developer shall not be liable for any data loss, technical glitches, or hardware issues. Usage is at the user’s own risk.

8. Account Deletion
Developer reserves the right to delete your account if any virus, malware or malpractice done through your account in the NoteTrail App.

Developer's Note :
If you find a bug, please reach out via the app's contact section. So we will work or best on the concern to get it resolved. 
Please reach out to us through Email: mechanicalaxiom@gmail.com
"""
    }
}
