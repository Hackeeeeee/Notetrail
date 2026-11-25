package com.mechanicalaxiom.notetrail

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Rect
import android.os.*
import android.print.*
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.mechanicalaxiom.notetrail.ui.theme.GitHubScanner
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
// Removed top-level private lateinit var igcseSubjects and filteredSubjects

class MainActivity : AppCompatActivity() {

    private lateinit var igcseSubjects: MutableList<Subject>
    private var filteredSubjects: List<Subject> = emptyList()
    private var lastLoadedUserId: String? = null

    companion object {
        const val PREFS_NAME = "IgcseProgressPrefs"
        const val THEME_KEY = "SelectedTheme"
        const val SUBJECT_SELECTION_KEY = "SubjectSelectionMap"
        const val PROGRESS_DATA_KEY = "LocalProgressData"
        const val DISCLAIMER_SHOWN_KEY = "DisclaimerShown"
        const val EXAM_YEAR_KEY = "SelectedExamYear"
        const val FILTER_KEY = "SelectedFilter"

        val SUBJECT_DEFINITIONS = listOf(
            "0500" to "English - First Language",
            "0990" to "English - First Language (9–1)",
            "0510" to "English - Second Language (Speaking)",
            "0511" to "English - Second Language (Count-in)",
            "0991" to "English - Second Language (9–1)",
            "0993" to "English - Second Language (Count-in 9–1)",
            "0475" to "Literature in English",
            "0992" to "Literature in English (9–1)",
            "0508" to "Arabic - First Language",
            "0544" to "Arabic - Foreign Language",
            "0509" to "Chinese - First Language",
            "0547" to "Chinese - Foreign Language",
            "0520" to "French - Foreign Language",
            "0525" to "German - Foreign Language",
            "0535" to "Italian - Foreign Language",
            "0530" to "Spanish - Foreign Language",
            "0539" to "Urdu - Second Language",
            "0460" to "Geography",
            "0470" to "History",
            "0448" to "Pakistan Studies",
            "0493" to "Islamiyat",
            "0490" to "Religious Studies",
            "0449" to "Bangladesh Studies",
            "0457" to "Global Perspectives",
            "0455" to "Economics",
            "0450" to "Business Studies",
            "0452" to "Accounting",
            "0471" to "Travel & Tourism",
            "0610" to "Biology",
            "0970" to "Biology (9–1)",
            "0620" to "Chemistry",
            "0971" to "Chemistry (9–1)",
            "0625" to "Physics",
            "0972" to "Physics (9–1)",
            "0653" to "Physical Science (Combined)",
            "0654" to "Co-ordinated Sciences (Double Award)",
            "0680" to "Environmental Management",
            "0580" to "Mathematics",
            "0980" to "Mathematics (9–1)",
            "0606" to "Additional Mathematics",
            "0607" to "International Mathematics",
            "0444" to "Mathematics(USA)",
            "0400" to "Art & Design",
            "0417" to "Information & Communication Technology (ICT)",
            "0983" to "Information & Communication Technology (9–1)",
            "0478" to "Computer Science",
            "0984" to "Computer Science (9–1)",
            "0445" to "Design & Technology",
            "0413" to "Physical Education",
            "0648" to "Food & Nutrition",
            "0600" to "Agriculture"
        ).sortedBy { it.second }
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var mainLayout: View
    private lateinit var totalProgressText: TextView
    private lateinit var totalProgressCard: View
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var searchView: SearchView
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var currentFilter: String = "All" // "All", "Core", "Extended"

    private var selectedExamYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            firebaseAnalytics = FirebaseAnalytics.getInstance(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Analytics init failed", e)
        }

        applySavedTheme()

        setContentView(R.layout.layout)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        drawerLayout = findViewById(R.id.drawerLayout)
        toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.app_name, R.string.app_name)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        mainLayout = findViewById(R.id.mainContent)
        totalProgressText = findViewById(R.id.totalProgressText)
        totalProgressCard = findViewById(R.id.totalProgressCard)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        searchView = findViewById(R.id.searchView)

        setupSessionHeader()
        setupSearch()
        setupFilter()
        setupSidebar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchView.query.isNotEmpty() || searchView.hasFocus()) {
                    exitSearch()
                } else if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } catch(_: IllegalStateException) {}
                }
            }
        })

        totalProgressCard.setOnClickListener {
            showSubjectSelectionDialog()
        }

        mainLayout.post {
            if (::igcseSubjects.isInitialized && !loadingProgressBar.isVisible) {
                mainLayout.visibility = View.VISIBLE
            }
        }

        loadDataAsync {
            handleIntent(intent)
        }

        checkAndShowDisclaimer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateLastActive()

        val currentUserId = auth.currentUser?.uid
        if (currentUserId != null && lastLoadedUserId != currentUserId) {
            Log.d("MainActivity", "User changed or first load for user: $currentUserId. Triggering sync.")
            loadDataAsync()
        }

        if (::igcseSubjects.isInitialized && filteredSubjects.isEmpty()) {
            renderInitialSubjects()
        }
    }

    private fun updateLastActive() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).set(
            mapOf("lastActive" to System.currentTimeMillis()),
            SetOptions.merge()
        )
    }

    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra("open_pdf_url") && intent.hasExtra("paper_key_for_history")) {
            val url = intent.getStringExtra("open_pdf_url")
            val paperKey = intent.getStringExtra("paper_key_for_history")
            if (url != null && paperKey != null) {
                val paper = findPaperByKey(paperKey)
                if (paper != null) {
                    viewDocumentUrl(url, paper)
                } else {
                    Toast.makeText(this, "Could not find paper data.", Toast.LENGTH_SHORT).show()
                }
            }
            intent.removeExtra("open_pdf_url")
            intent.removeExtra("paper_key_for_history")
        }
    }

    private fun findPaperByKey(key: String): PaperVariant? {
        if (!::igcseSubjects.isInitialized) return null
        for (subject in igcseSubjects) {
            for (yearGroup in subject.yearGroups) {
                for (sessionGroup in yearGroup.sessions) {
                    for (paper in sessionGroup.variants) {
                        if (paper.paperKey == key) {
                            return paper
                        }
                    }
                }
            }
        }
        return null
    }

    private fun checkAndShowDisclaimer() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val shown = prefs.getBoolean(DISCLAIMER_SHOWN_KEY, false)
        if (!shown) {
            val dialog = AlertDialog.Builder(this, R.style.GlassDialogTheme)
                .setTitle("Disclaimer")
                .setMessage("IGCSE is a registered trademark of Cambridge University Press & Assessment. This app is independently developed and is not affiliated with, endorsed by, or connected to Cambridge University Press & Assessment or any other examination board.")
                .setPositiveButton("OK") { _, _ ->
                    prefs.edit { putBoolean(DISCLAIMER_SHOWN_KEY, true) }
                }
                .setCancelable(false)
                .create()
            
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterSubjects(query)
                hideKeyboard()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterSubjects(newText)
                return true
            }
        })
    }

    private fun exitSearch() {
        searchView.setQuery("", false)
        searchView.clearFocus()
        filterSubjects("")
        hideKeyboard()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText || (v is ViewGroup && v.hasFocus())) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    val searchViewRect = Rect()
                    searchView.getGlobalVisibleRect(searchViewRect)
                    if (!searchViewRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        hideKeyboard()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setupFilter() {
        val radioGroup = findViewById<RadioGroup>(R.id.filterRadioGroup)
        
        // Load initial filter state from local prefs before sync
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentFilter = prefs.getString(FILTER_KEY, "All") ?: "All"
        
        when(currentFilter) {
            "Core" -> radioGroup.check(R.id.filterCore)
            "Extended" -> radioGroup.check(R.id.filterExtended)
            else -> radioGroup.check(R.id.filterAll)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when(checkedId) {
                R.id.filterCore -> "Core"
                R.id.filterExtended -> "Extended"
                else -> "All"
            }
            prefs.edit { putString(FILTER_KEY, currentFilter) }
            saveSubjectSelectionsToFirebase() // Sync preference change
            renderInitialSubjects()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchView.windowToken, 0)
        searchView.clearFocus()
    }

    private fun filterSubjects(query: String?) {
        if (!::igcseSubjects.isInitialized) return

        if (query.isNullOrEmpty()) {
            filteredSubjects = igcseSubjects
        } else {
            val lowerQuery = query.lowercase()
            filteredSubjects = igcseSubjects.filter {
                it.name.lowercase().contains(lowerQuery) || it.code.contains(lowerQuery)
            }
        }
        renderInitialSubjects()
    }

    private fun loadDataAsync(onComplete: (() -> Unit)? = null) {
        loadingProgressBar.isVisible = true
        mainLayout.isVisible = false
        
        val currentUserId = auth.currentUser?.uid

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!::igcseSubjects.isInitialized || lastLoadedUserId != currentUserId) {
                    igcseSubjects = generateIgcseData()
                    IgcseDataHolder.subjects = igcseSubjects
                    filteredSubjects = igcseSubjects
                }

                GitHubScanner.fetchRepoFiles()
                
                loadSubjectSelectionsFromPrefs()
                loadProgressFromLocal()
                loadProgressFromFirebase()
                
                lastLoadedUserId = currentUserId

                withContext(Dispatchers.Main) {
                    renderInitialSubjects()
                    loadingProgressBar.isVisible = false
                    mainLayout.visibility = View.VISIBLE
                    mainLayout.requestLayout()
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading data", e)
                withContext(Dispatchers.Main) {
                    loadingProgressBar.isVisible = false
                    mainLayout.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Error loading data: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupSidebar() {
        val currentUser = auth.currentUser
        val userEmail = currentUser?.email ?: "Guest"

        val navUserEmail = findViewById<TextView>(R.id.navUserEmail)
        if (navUserEmail != null) {
            navUserEmail.text = userEmail
        }

        val logoutButton = findViewById<Button>(R.id.navLogoutButton)
        logoutButton?.setOnClickListener {
            logout()
        }

        findViewById<Button>(R.id.navSettingsButton)?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        val groupsButton = findViewById<Button>(R.id.navGroupsButton)
        groupsButton?.setOnClickListener {
            val intent = Intent(this, GroupActivity::class.java)
            startActivity(intent)
        }

        findViewById<Button>(R.id.navAnalyticsButton)?.setOnClickListener {
            val intent = Intent(this, AnalyticsActivity::class.java)
            startActivity(intent)
        }

        val syncButton = findViewById<Button>(R.id.navSyncButton)
        syncButton?.setOnClickListener {
            Toast.makeText(this, "Syncing from cloud...", Toast.LENGTH_SHORT).show()
            loadDataAsync {
                Toast.makeText(this, "Sync Complete!", Toast.LENGTH_SHORT).show()
            }
        }

        val historyHeader = findViewById<LinearLayout>(R.id.historyHeader)
        historyHeader?.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupSessionHeader() {
        val headerCard = findViewById<androidx.cardview.widget.CardView>(R.id.sessionHeaderCard) ?: return
        
        // Load selected year
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedExamYear = prefs.getInt(EXAM_YEAR_KEY, Calendar.getInstance().get(Calendar.YEAR))
        if (selectedExamYear < Calendar.getInstance().get(Calendar.YEAR)) {
            selectedExamYear = Calendar.getInstance().get(Calendar.YEAR)
        }

        headerCard.setOnClickListener {
            showYearSelectionDialog()
        }

        // Swipe to dismiss logic
        @Suppress("ClickableViewAccessibility")
        headerCard.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private val swipeThreshold = 300f
            private var isSwiping = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        isSwiping = false
                        return true 
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - startX
                        if (kotlin.math.abs(deltaX) > 20) {
                            isSwiping = true
                            v.translationX = deltaX
                            v.parent.requestDisallowInterceptTouchEvent(true)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isSwiping) {
                            val deltaX = event.rawX - startX
                            if (kotlin.math.abs(deltaX) > swipeThreshold) {
                                val endX = if (deltaX > 0) v.width.toFloat() * 2 else -v.width.toFloat() * 2
                                v.animate().translationX(endX).alpha(0f).setDuration(300).withEndAction {
                                    v.visibility = View.GONE
                                }.start()
                            } else {
                                v.animate().translationX(0f).setDuration(200).start()
                            }
                        } else if (event.action == MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        updateSessionHeaderText()
        startCountdownTimer()
    }

    private fun showYearSelectionDialog() {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = (currentYear..currentYear + 5).map { it.toString() }.toTypedArray()
        val dialog = AlertDialog.Builder(this, R.style.GlassDialogTheme)
            .setTitle("Select Examination Year")
            .setItems(years) { _, which ->
                selectedExamYear = years[which].toInt()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                    putInt(EXAM_YEAR_KEY, selectedExamYear)
                }
                updateSessionHeaderText()
                startCountdownTimer()
                saveSubjectSelectionsToFirebase() // Sync preference change
            }
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun updateSessionHeaderText() {
        val sessionTitle = findViewById<TextView>(R.id.sessionTitle) ?: return
        
        val sessionName = when (Calendar.getInstance().get(Calendar.MONTH)) {
            in 1..3 -> "Feb/March"
            in 4..7 -> "May/June"
            in 8..11 -> "Oct/Nov"
            else -> if (Calendar.getInstance().get(Calendar.MONTH) == 0) "Oct/Nov" else "May/June"
        }
        
        sessionTitle.text = getString(R.string.session_title_format, sessionName, selectedExamYear)
    }

    private fun startCountdownTimer() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        
        val timerText = findViewById<TextView>(R.id.nextExamTimer) ?: return
        
        countdownRunnable = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance()
                val target = Calendar.getInstance()
                target.set(Calendar.YEAR, selectedExamYear)
                
                // Determine next series
                val currentMonth = now.get(Calendar.MONTH)
                val currentYear = now.get(Calendar.YEAR)
                
                val (targetMonth, targetDay) = when {
                    currentYear < selectedExamYear -> Pair(1, 1) // Next Feb
                    currentMonth < 1 -> Pair(1, 1) // Feb
                    currentMonth < 4 -> Pair(4, 1) // May
                    currentMonth < 8 -> Pair(9, 1) // Oct
                    else -> Pair(1, 1).also { 
                        target.add(Calendar.YEAR, 1) 
                    }
                }
                
                target.set(Calendar.MONTH, targetMonth)
                target.set(Calendar.DAY_OF_MONTH, targetDay)
                target.set(Calendar.HOUR_OF_DAY, 0)
                target.set(Calendar.MINUTE, 0)
                target.set(Calendar.SECOND, 0)

                val diff = target.timeInMillis - now.timeInMillis
                if (diff > 0) {
                    val days = diff / (24 * 60 * 60 * 1000)
                    val hours = (diff / (60 * 60 * 1000)) % 24
                    timerText.text = getString(R.string.next_exam_countdown, days, hours)
                } else {
                    timerText.text = getString(R.string.exams_in_progress)
                }
                
                countdownHandler.postDelayed(this, 60000)
            }
        }
        countdownHandler.post(countdownRunnable!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
    }

    private fun logout() {
        SessionManager.logout(this)
    }

    private fun applySavedTheme() {
        val mode = getSavedThemeMode()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun getSavedThemeMode(): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getInt(THEME_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun showSubjectSelectionDialog() {
        val subjectNames = igcseSubjects.map { subject ->
            val total = subject.yearGroups.flatMap { it.sessions }.flatMap { it.variants }.size
            val solved = subject.yearGroups.flatMap { it.sessions }.flatMap { it.variants }.count { it.isCompleted }
            "${subject.name} (${subject.code}) - $solved/$total solved"
        }.toTypedArray()
        val checkedItems = igcseSubjects.map { it.isIncludedInTotal }.toBooleanArray()

        val dialog = AlertDialog.Builder(this, R.style.GlassDialogTheme)
            .setTitle("Select Subjects for Progress")
            .setMultiChoiceItems(subjectNames, checkedItems) { _, which, isChecked ->
                igcseSubjects[which].isIncludedInTotal = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                saveSubjectSelectionsToPrefs()
                saveSubjectSelectionsToFirebase()
                updateTotalProgress()

                val bundle = Bundle()
                bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "subject_selection")
                firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun saveSubjectSelectionsToPrefs() {
        val selectionMap = igcseSubjects.associate { it.code to it.isIncludedInTotal }
        val jsonString = Gson().toJson(selectionMap)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(SUBJECT_SELECTION_KEY, jsonString)
        }
    }

    private fun loadSubjectSelectionsFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonString = prefs.getString(SUBJECT_SELECTION_KEY, null)

        if (jsonString != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val savedSelections = Gson().fromJson(jsonString, Map::class.java) as? Map<String, Boolean> ?: emptyMap()

                igcseSubjects.forEach { subject ->
                    savedSelections[subject.code]?.let { isIncluded ->
                        subject.isIncludedInTotal = isIncluded
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error parsing subject selections", e)
            }
        }
    }


    private fun generateIgcseData(): MutableList<Subject> {
        val allSubjects = mutableListOf<Subject>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val yearsToGenerate = (currentYear downTo 2015).toList()
        val sessionCodes = mapOf("M/J" to "s", "O/N" to "w", "F/M" to "m")

        for ((code, name) in SUBJECT_DEFINITIONS) {
            val yearGroups = mutableListOf<YearGroup>()
            val paperVariants = getPaperVariantsForSubject(code)

            for (year in yearsToGenerate) {
                val sessions = mutableListOf<SessionGroup>()
                val shortYear = year % 100

                for ((sessionName, sessionLetter) in sessionCodes) {
                    val variants = mutableListOf<PaperVariant>()
                    paperVariants.forEach { (variantCode, paperType) ->
                        val qpFileName = "${code}_${sessionLetter}${shortYear}_qp_${variantCode}.pdf"
                        val msFileName = "${code}_${sessionLetter}${shortYear}_ms_${variantCode}.pdf"
                        val paperKey = "${code}_${year}_${sessionName.replace("/", "")}_${variantCode}"

                        variants.add(
                            PaperVariant(
                                variantCode = variantCode,
                                paperType = paperType,
                                paperKey = paperKey,
                                fileUrl = qpFileName,
                                msUrl = msFileName
                            )
                        )
                    }
                    if (variants.isNotEmpty()) {
                        sessions.add(SessionGroup(sessionName, variants))
                    }
                }
                if (sessions.isNotEmpty()) {
                    yearGroups.add(YearGroup(year, sessions))
                }
            }
            allSubjects.add(Subject(name, code, yearGroups))
        }
        return allSubjects
    }

    private fun getPaperVariantsForSubject(subjectCode: String): Map<String, String> {
        val defaultVariants = mapOf(
            "11" to "Multiple Choice (Core)", "12" to "Multiple Choice (Core)", "13" to "Multiple Choice (Core)",
            "21" to "Multiple Choice (Extended)", "22" to "Multiple Choice (Extended)", "23" to "Multiple Choice (Extended)",
            "31" to "Theory (Core)", "32" to "Theory (Core)", "33" to "Theory (Core)",
            "41" to "Theory (Extended)", "42" to "Theory (Extended)", "43" to "Theory (Extended)",
            "61" to "Alternative to Practical", "62" to "Alternative to Practical", "63" to "Alternative to Practical"
        )

        return when (subjectCode) {
            "0607" -> mapOf(
                "11" to "Paper 1 (Core)", "12" to "Paper 1 (Core)", "13" to "Paper 1 (Core)",
                "21" to "Paper 2 (Extended)", "22" to "Paper 2 (Extended)", "23" to "Paper 2 (Extended)",
                "31" to "Paper 3 (Core)", "32" to "Paper 3 (Core)", "33" to "Paper 3 (Core)",
                "41" to "Paper 4 (Extended)", "42" to "Paper 4 (Extended)", "43" to "Paper 4 (Extended)",
                "51" to "Paper 5 (Core)", "52" to "Paper 5 (Core)", "53" to "Paper 5 (Core)",
                "61" to "Paper 6 (Extended)", "62" to "Paper 6 (Extended)", "63" to "Paper 6 (Extended)"
            )
            "0417" -> mapOf(
                "11" to "Theory", "12" to "Theory", "13" to "Theory",
                "21" to "Practical (Doc Prod/Data Man)", "22" to "Practical (Doc Prod/Data Man)",
                "31" to "Practical (Web/Pres)", "32" to "Practical (Web/Pres)"
            )
            "0478" -> mapOf(
                "11" to "Computer Systems", "12" to "Computer Systems", "13" to "Computer Systems",
                "21" to "Algorithms & Programming", "22" to "Algorithms & Programming", "23" to "Algorithms & Programming"
            )
            "0520", "0530", "0525", "0544", "0547", "0535", "0549" -> mapOf(
                "11" to "Listening", "12" to "Listening", "13" to "Listening",
                "21" to "Reading", "22" to "Reading", "23" to "Reading",
                "41" to "Writing", "42" to "Writing", "43" to "Writing",
                "03" to "Speaking"
            )
            "0510", "0511" -> mapOf(
                "11" to "Reading/Writing (Core)", "12" to "Reading/Writing (Core)", "13" to "Reading/Writing (Core)",
                "21" to "Reading/Writing (Ext)", "22" to "Reading/Writing (Ext)", "23" to "Reading/Writing (Ext)",
                "31" to "Listening (Core)", "32" to "Listening (Core)", "33" to "Listening (Core)",
                "41" to "Listening (Ext)", "42" to "Listening (Ext)", "43" to "Listening (Ext)"
            )
            "0500" -> mapOf(
                "11" to "Reading", "12" to "Reading", "13" to "Reading",
                "21" to "Directed Writing", "22" to "Directed Writing", "23" to "Directed Writing"
            )
            "0606" -> mapOf(
                "11" to "Paper 1", "12" to "Paper 1", "13" to "Paper 1",
                "21" to "Paper 2", "22" to "Paper 2", "23" to "Paper 2"
            )
            "0450" -> mapOf(
                "11" to "Short Answer", "12" to "Short Answer", "13" to "Short Answer",
                "21" to "Case Study", "22" to "Case Study", "23" to "Case Study"
            )
            "0470" -> mapOf(
                "11" to "Paper 1", "12" to "Paper 1", "13" to "Paper 1",
                "21" to "Paper 2", "22" to "Paper 2", "23" to "Paper 2",
                "41" to "Alt to Coursework", "42" to "Alt to Coursework", "43" to "Alt to Coursework"
            )
            else -> defaultVariants
        }
    }

    private fun getTotalMarksForPaper(paper: PaperVariant, subjectCode: String): Int {
        val type = paper.paperType.lowercase()
        return when {
            type.contains("theory") -> 80
            type.contains("multiple choice") -> 40
            type.contains("alternative to practical") -> 40
            type.contains("practical") && subjectCode == "0417" -> 70
            type.contains("practical") -> 40
            type.contains("reading") -> 50
            type.contains("writing") -> 50
            type.contains("listening") -> 30
            else -> 100
        }
    }

    private suspend fun loadProgressFromFirebase() {
        val currentUser = auth.currentUser
        val firestore = db
        if (currentUser != null) {
            try {
                val docRef = firestore.collection("users").document(currentUser.uid).collection("progress").document("data")
                val snapshot = docRef.get().await()
                if (snapshot.exists()) {
                    val dataMap = snapshot.data
                    if (dataMap != null) {
                        applyProgressData(dataMap)
                        saveProgressToLocal()
                    }
                }
                val settingsRef = firestore.collection("users").document(currentUser.uid).collection("settings").document("preferences")
                val settingsSnapshot = settingsRef.get().await()
                if (settingsSnapshot.exists()) {
                    // Load Subject Selections
                    @Suppress("UNCHECKED_CAST")
                    val selectionsMap = settingsSnapshot.get("subjectSelections") as? Map<String, Boolean> ?: emptyMap()
                    applySubjectSelections(selectionsMap)
                    saveSubjectSelectionsToPrefs()

                    // Load Filter Preference
                    val savedFilter = settingsSnapshot.getString("currentFilter")
                    if (savedFilter != null) {
                        currentFilter = savedFilter
                        withContext(Dispatchers.Main) {
                            val radioGroup = findViewById<RadioGroup>(R.id.filterRadioGroup)
                            when(currentFilter) {
                                "Core" -> radioGroup.check(R.id.filterCore)
                                "Extended" -> radioGroup.check(R.id.filterExtended)
                                else -> radioGroup.check(R.id.filterAll)
                            }
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                                putString(FILTER_KEY, currentFilter)
                            }
                        }
                    }

                    // Load Exam Year Preference
                    val savedYear = settingsSnapshot.getLong("selectedExamYear")?.toInt()
                    if (savedYear != null) {
                        selectedExamYear = savedYear
                        withContext(Dispatchers.Main) {
                            updateSessionHeaderText()
                            startCountdownTimer()
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                                putInt(EXAM_YEAR_KEY, selectedExamYear)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Firebase", "Error loading progress: ${e.message}", e)
                if (e.message?.contains("PERMISSION_DENIED") == true) {
                    Log.e("Firebase", "CRITICAL: Permission Denied. Check App Check status or connectivity.")
                }
            }
        }
    }

    private fun saveProgressToFirebase() {
        val currentUser = auth.currentUser ?: run {
            saveProgressToLocal()
            return
        }
        val firestore = db
        saveProgressToLocal()
        val progressMap = collectProgressData()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("progress").document("data")
                    .set(progressMap, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.e("Firebase", "Error saving progress", e)
            }
        }
        updateTotalProgress()
    }

    private fun saveProgressToLocal() {
        val progressMap = collectProgressData()
        val jsonString = Gson().toJson(progressMap)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(PROGRESS_DATA_KEY, jsonString)
        }
    }

    private fun loadProgressFromLocal() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonString = prefs.getString(PROGRESS_DATA_KEY, null)
        if (jsonString != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val dataMap = Gson().fromJson(jsonString, Map::class.java) as? Map<String, Any> ?: emptyMap()
                applyProgressData(dataMap)
            } catch (e: Exception) {
                Log.e("LocalStorage", "Error parsing local progress", e)
            }
        }
    }

    private fun collectProgressData(): Map<String, Any> {
        val progressMap = mutableMapOf<String, Any>()
        igcseSubjects.forEach { subject ->
            subject.yearGroups.forEach { yearGroup ->
                yearGroup.sessions.forEach { sessionGroup ->
                    sessionGroup.variants.forEach { paper ->
                        if (paper.isCompleted) {
                            val data = mutableMapOf<String, Any>()
                            
                            // Absolute Privacy: Bundle all metadata into an encrypted blob
                            val meta = mutableMapOf<String, Any>(
                                "isCompleted" to paper.isCompleted,
                                "timestamp" to (paper.timestamp ?: System.currentTimeMillis())
                            )
                            paper.marksObtained?.let { meta["marksObtained"] = it }
                            paper.totalMarks?.let { meta["totalMarks"] = it }
                            paper.notes?.let { meta["notes"] = it }

                            if (CryptoManager.hasCloudMasterKey()) {
                                try {
                                    val encryptedMeta = CryptoManager.encrypt(Gson().toJson(meta))
                                    data["encryptedMeta"] = encryptedMeta
                                    
                                    // Wipe raw fields to prevent any visibility
                                    data["marksObtained"] = FieldValue.delete()
                                    data["totalMarks"] = FieldValue.delete()
                                    data["isCompleted"] = FieldValue.delete()
                                    data["timestamp"] = FieldValue.delete()
                                    data["notes"] = FieldValue.delete()
                                } catch (e: Exception) {
                                    Log.e("SYNC", "Failed to encrypt progress meta")
                                    // Fallback to legacy fields if encryption fails (should not happen if key is ready)
                                    data.putAll(meta)
                                }
                            } else {
                                data.putAll(meta)
                            }
                            progressMap[paper.paperKey] = data
                        }
                    }
                }
            }
        }
        return progressMap
    }

    private fun applyProgressData(dataMap: Map<String, Any>) {
        igcseSubjects.forEach { subject ->
            subject.yearGroups.forEach { yearGroup ->
                yearGroup.sessions.forEach { sessionGroup ->
                    sessionGroup.variants.forEach { paper ->
                        val paperData = dataMap[paper.paperKey]
                        if (paperData is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val paperDataMap = paperData as? Map<String, Any>
                            if (paperDataMap != null) {
                                // Try to decrypt metadata first
                                val encryptedMeta = paperDataMap["encryptedMeta"] as? String
                                if (encryptedMeta != null && CryptoManager.hasCloudMasterKey()) {
                                    try {
                                        val decryptedJson = CryptoManager.decrypt(encryptedMeta)
                                        @Suppress("UNCHECKED_CAST")
                                        val meta = Gson().fromJson(decryptedJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
                                        
                                        paper.marksObtained = (meta["marksObtained"] as? Number)?.toInt()
                                        paper.totalMarks = (meta["totalMarks"] as? Number)?.toInt()
                                        paper.isCompleted = meta["isCompleted"] as? Boolean ?: false
                                        paper.notes = meta["notes"] as? String
                                        paper.timestamp = (meta["timestamp"] as? Number)?.toLong()
                                        return@forEach // Skip legacy parsing
                                    } catch (e: Exception) {
                                        Log.e("SYNC", "Failed to decrypt progress meta")
                                    }
                                }

                                // Fallback to legacy unencrypted fields
                                val marks = paperDataMap["marksObtained"]
                                val total = paperDataMap["totalMarks"]
                                paper.marksObtained = (marks as? Number)?.toInt()
                                paper.totalMarks = (total as? Number)?.toInt()
                                paper.isCompleted = paperDataMap["isCompleted"] as? Boolean ?: false
                                paper.timestamp = (paperDataMap["timestamp"] as? Number)?.toLong()

                                // Handle decryption of legacy notes field if it exists
                                val encryptedNotes = paperDataMap["encryptedNotes"] as? String
                                if (encryptedNotes != null && CryptoManager.hasCloudMasterKey()) {
                                    try {
                                        paper.notes = CryptoManager.decrypt(encryptedNotes)
                                    } catch (e: Exception) {
                                        paper.notes = paperDataMap["notes"] as? String
                                    }
                                } else {
                                    paper.notes = paperDataMap["notes"] as? String
                                }
                            }
                        } else {
                            // Reset state if not in dataMap (e.g., deleted from history)
                            paper.marksObtained = null
                            paper.totalMarks = null
                            paper.isCompleted = false
                            paper.notes = null
                        }
                    }
                }
            }
        }
    }

    private fun renderInitialSubjects() {
        if (!::igcseSubjects.isInitialized) return
        val container = findViewById<LinearLayout>(R.id.subjectsContainer)
        container.removeAllViews()
        
        updateSessionHeaderText()

        filteredSubjects.forEach { subject ->
            val subjectLayout = createSubjectHeader(subject, container)
            container.addView(subjectLayout)
            val yearsContainer = createLinearLayout()
            yearsContainer.isVisible = false
            yearsContainer.setPadding(32, 0, 0, 0)
            container.addView(yearsContainer)
            subjectLayout.setOnClickListener {
                if (yearsContainer.isEmpty()) {
                    renderYearsForSubject(subject, yearsContainer)
                }
                yearsContainer.isVisible = !yearsContainer.isVisible
            }
        }
        updateTotalProgress()
    }

    private fun renderYearsForSubject(subject: Subject, container: LinearLayout) {
        subject.yearGroups.forEach { yearGroup ->
            val yearHeader = createYearHeader(yearGroup, container)
            container.addView(yearHeader)
            val sessionsContainer = createLinearLayout()
            sessionsContainer.isVisible = false
            sessionsContainer.setPadding(32, 0, 0, 0)
            container.addView(sessionsContainer)
            
            yearHeader.setOnClickListener {
                if (sessionsContainer.isEmpty()) {
                    renderSessionsForYear(yearGroup, sessionsContainer)
                }
                sessionsContainer.isVisible = !sessionsContainer.isVisible
            }
        }
    }

    private fun renderSessionsForYear(yearGroup: YearGroup, container: LinearLayout) {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)

        yearGroup.sessions.forEach { sessionGroup ->
            // Filter sessions for the current year or future years
            if (yearGroup.year == currentYear) {
                val shouldShow = when (sessionGroup.sessionCode) {
                    "F/M" -> currentMonth >= Calendar.APRIL    // F/M visible only from April
                    "M/J" -> currentMonth >= Calendar.JULY     // M/J visible only from July
                    "O/N" -> currentMonth >= Calendar.DECEMBER // O/N visible only from December
                    else -> true
                }
                if (!shouldShow) return@forEach
            } else if (yearGroup.year > currentYear) {
                // Hide all sessions for any year in the future
                return@forEach
            }

            val sessionHeader = createSessionHeader(sessionGroup, container)
            container.addView(sessionHeader)
            val papersContainer = createLinearLayout()
            papersContainer.isVisible = false
            papersContainer.setPadding(32, 0, 0, 0)
            container.addView(papersContainer)

            sessionHeader.setOnClickListener {
                if (papersContainer.isEmpty()) {
                    renderPapersForSession(sessionGroup, papersContainer)
                }
                papersContainer.isVisible = !papersContainer.isVisible
            }
        }
    }

    private fun renderPapersForSession(sessionGroup: SessionGroup, container: LinearLayout) {
        sessionGroup.variants.forEach { paper ->
            val isCore = paper.paperType.contains("Core", ignoreCase = true)
            val isExtended = paper.paperType.contains("Extended", ignoreCase = true)
            var shouldShow = true
            if (currentFilter == "Core" && !isCore) shouldShow = false
            if (currentFilter == "Extended" && !isExtended) shouldShow = false
            if (shouldShow) {
                val paperCodeRow = createPaperCodeRow(paper, container)
                container.addView(paperCodeRow)
                val paperCard = createPaperVariantView(paper, container)
                paperCard.visibility = View.GONE
                container.addView(paperCard)
                paperCodeRow.setOnClickListener {
                    paperCard.isVisible = !paperCard.isVisible
                }
            }
        }
    }

    private fun createSubjectHeader(subject: Subject, parent: ViewGroup): View {
        val layout = layoutInflater.inflate(R.layout.item_subject, parent, false) as LinearLayout
        val title = layout.findViewById<TextView>(R.id.subjectTitle)
        val grade = layout.findViewById<TextView>(R.id.subjectGrade)
        title.text = getString(R.string.subject_header_format, "${subject.name} (${subject.code})")
        val totalMarks = subject.yearGroups.flatMap { it.sessions }.flatMap { it.variants }.filter { it.isCompleted }.sumOf { it.totalMarks ?: 0 }
        val obtainedMarks = subject.yearGroups.flatMap { it.sessions }.flatMap { it.variants }.filter { it.isCompleted }.sumOf { it.marksObtained ?: 0 }
        if (totalMarks > 0) {
            val percentage = (obtainedMarks * 100) / totalMarks
            grade.text = getString(R.string.subject_grade_format, getGradeFromPercentage(percentage))
            grade.visibility = View.VISIBLE
        } else {
            grade.visibility = View.GONE
        }
        return layout
    }

    private fun createYearHeader(yearGroup: YearGroup, parent: ViewGroup): View {
        val layout = layoutInflater.inflate(R.layout.item_year, parent, false) as LinearLayout
        val text = layout.findViewById<TextView>(R.id.yearTitle)
        text.text = "${yearGroup.year}"
        return layout
    }

    private fun createSessionHeader(sessionGroup: SessionGroup, parent: ViewGroup): View {
        val layout = layoutInflater.inflate(R.layout.item_session, parent, false) as LinearLayout
        val text = layout.findViewById<TextView>(R.id.sessionTitle)
        val fullSessionName = when(sessionGroup.sessionCode) {
            "s" -> "M/J (Summer)"
            "w" -> "O/N (Winter)"
            "m" -> "F/M (Spring)"
            else -> sessionGroup.sessionCode
        }
        text.text = fullSessionName
        return layout
    }

    private fun createPaperCodeRow(paper: PaperVariant, parent: ViewGroup): View {
        val layout = layoutInflater.inflate(R.layout.item_paper_code, parent, false) as LinearLayout
        val text = layout.findViewById<TextView>(R.id.paperCodeTitle)
        text.text = getString(R.string.paper_code_row_format, paper.variantCode, paper.paperType)
        return layout
    }

    private fun createPaperVariantView(paper: PaperVariant, parent: ViewGroup): View {
        val paperLayout = layoutInflater.inflate(R.layout.paper_item, parent, false) as androidx.cardview.widget.CardView
        
        // Apply Blur RenderEffect (minSdk is 31, so RenderEffect is available)
        val glassBackground = paperLayout.findViewById<View>(R.id.glassBackground)
        val blurEffect = android.graphics.RenderEffect.createBlurEffect(20f, 20f, android.graphics.Shader.TileMode.CLAMP)
        glassBackground.setRenderEffect(blurEffect)

        val title = paperLayout.findViewById<TextView>(R.id.paperTitle)
        val progressText = paperLayout.findViewById<TextView>(R.id.paperProgress)
        val openQpButton = paperLayout.findViewById<Button>(R.id.openPaperButton)
        val downloadQpButton = paperLayout.findViewById<ImageButton>(R.id.downloadPaperButton)
        val printQpButton = paperLayout.findViewById<ImageButton>(R.id.printPaperButton)
        val openMsButton = paperLayout.findViewById<Button>(R.id.openMsButton)
        val downloadMsButton = paperLayout.findViewById<ImageButton>(R.id.downloadMsButton)
        val printMsButton = paperLayout.findViewById<ImageButton>(R.id.printMsButton)
        val marksDisplay = paperLayout.findViewById<TextView>(R.id.marksDisplay)
        val enterMarksButton = paperLayout.findViewById<Button>(R.id.enterMarksButton)
        val noteEntry = paperLayout.findViewById<EditText>(R.id.noteEntry)
        val btnNoteOk = paperLayout.findViewById<ImageButton>(R.id.btnNoteOk)
        
        title.text = getString(R.string.paper_variant_title)
        updatePaperCardState(paper, progressText, marksDisplay)
        if (paper.notes != null) {
            noteEntry.setText(paper.notes)
        }

        noteEntry.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                paper.notes = s?.toString()
                saveProgressToLocal()
                btnNoteOk.isVisible = true
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        noteEntry.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnNoteOk.isVisible = true
            } else {
                btnNoteOk.isVisible = false
                saveProgressToFirebase() // Sync notes on focus loss
            }
        }

        noteEntry.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                noteEntry.clearFocus()
                hideKeyboard()
                true
            } else {
                false
            }
        }

        btnNoteOk.setOnClickListener {
            noteEntry.clearFocus()
            hideKeyboard()
            saveProgressToFirebase() // Sync notes to cloud
        }

        openQpButton.setOnClickListener {
            val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                this, paperLayout, "paper_transition"
            )
            viewDocumentUrl(paper.fileUrl, paper, options)
        }
        downloadQpButton.setOnClickListener {
            downloadAndOpenFile(paper.fileUrl, paper.paperKey + "_qp.pdf")
        }
        printQpButton.setOnClickListener {
            downloadAndPrintFile(paper.fileUrl, paper.paperKey + "_qp.pdf")
        }
        openMsButton.setOnClickListener {
            val options = androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation(
                this, paperLayout, "paper_transition"
            )
            viewDocumentUrl(paper.msUrl, paper, options)
        }
        downloadMsButton.setOnClickListener {
            downloadAndOpenFile(paper.msUrl, paper.paperKey + "_ms.pdf")
        }
        printMsButton.setOnClickListener {
            downloadAndPrintFile(paper.msUrl, paper.paperKey + "_ms.pdf")
        }
        enterMarksButton.setOnClickListener {
            val subjectCode = paper.paperKey.substringBefore("_")
            val note = noteEntry.text.toString()
            showMarksDialog(paper, progressText, marksDisplay, subjectCode, note)
        }
        return paperLayout
    }

    private fun updatePaperCardState(paper: PaperVariant, progressText: TextView, marksDisplay: TextView) {
        if (paper.isCompleted) {
            progressText.text = getString(R.string.status_completed)
            marksDisplay.text = getString(R.string.marks_display_format, paper.marksObtained, paper.totalMarks)
        } else {
            progressText.text = getString(R.string.status_incomplete)
            marksDisplay.text = getString(R.string.marks_display_empty)
        }
    }

    private fun showMarksDialog(paper: PaperVariant, progressText: TextView, marksDisplay: TextView, subjectCode: String, note: String?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_enter_marks, null)
        val obtainedInput = dialogView.findViewById<EditText>(R.id.dialogObtainedInput)
        val calculatedTotal = getTotalMarksForPaper(paper, subjectCode)
        if (paper.marksObtained != null) obtainedInput.setText(paper.marksObtained.toString())
        val dialog = AlertDialog.Builder(this, R.style.GlassDialogTheme)
            .setView(dialogView)
            .setTitle("Enter Marks (Total: $calculatedTotal)")
            .setPositiveButton("Save") { dialogInterface, _ ->
                val obtainedStr = obtainedInput.text.toString()
                val obtained = obtainedStr.toIntOrNull()
                if (obtained != null && obtained <= calculatedTotal) {
                    paper.marksObtained = obtained
                    paper.totalMarks = calculatedTotal
                    paper.isCompleted = true
                    paper.notes = note
                    paper.timestamp = System.currentTimeMillis()
                    saveProgressToFirebase()
                    updatePaperCardState(paper, progressText, marksDisplay)
                    updateTotalProgress()
                    val bundle = Bundle()
                    bundle.putString(FirebaseAnalytics.Param.ITEM_ID, paper.paperKey)
                    bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "Paper Completed")
                    bundle.putInt(FirebaseAnalytics.Param.SCORE, obtained)
                    firebaseAnalytics.logEvent("paper_completed", bundle)
                    Toast.makeText(this, "Marks Saved!", Toast.LENGTH_SHORT).show()
                    dialogInterface.dismiss()
                } else {
                    Toast.makeText(this, "Invalid Marks! Must be <= $calculatedTotal", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun getGradeFromPercentage(percentage: Int): String {
        return when {
            percentage >= 90 -> "A*"
            percentage >= 80 -> "A"
            percentage >= 70 -> "B"
            percentage >= 60 -> "C"
            percentage >= 50 -> "D"
            percentage >= 40 -> "E"
            else -> "U"
        }
    }


    private fun updateTotalProgress() {
        if (!::igcseSubjects.isInitialized) return
        var totalPossibleMarksOfSolvedAcrossSelected = 0
        var totalObtainedMarksAcrossSelected = 0
        var totalPapersAcrossSelected = 0
        var solvedPapersAcrossSelected = 0

        igcseSubjects.forEach { subject ->
            if (subject.isIncludedInTotal) {
                subject.yearGroups.forEach { year ->
                    year.sessions.forEach { session ->
                        session.variants.forEach { paper ->
                            totalPapersAcrossSelected++
                            if (paper.isCompleted) {
                                solvedPapersAcrossSelected++
                                totalObtainedMarksAcrossSelected += (paper.marksObtained ?: 0)
                                totalPossibleMarksOfSolvedAcrossSelected += getTotalMarksForPaper(paper, subject.code)
                            }
                        }
                    }
                }
            }
        }

        val overallPercentage = if (totalPossibleMarksOfSolvedAcrossSelected > 0) {
            (totalObtainedMarksAcrossSelected * 100) / totalPossibleMarksOfSolvedAcrossSelected
        } else 0

        totalProgressText.text = getString(R.string.total_progress_format, overallPercentage, solvedPapersAcrossSelected, totalPapersAcrossSelected)
    }

    private fun createLinearLayout(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return layout
    }

    private fun viewDocumentUrl(url: String, paper: PaperVariant, options: androidx.core.app.ActivityOptionsCompat? = null) {
        val fullUrl = if (!url.startsWith("http")) LinkProtector.getHiddenLink() + url else url
        val intent = Intent(this, DocumentViewerActivity::class.java).apply {
            putExtra("pdf_url", fullUrl)
            putExtra("paper_key", paper.paperKey)
            putExtra("paper_type", paper.paperType)
        }
        Log.d("MainActivity", "viewDocumentUrl: url=$fullUrl paper_key=${paper.paperKey}")
        startActivity(intent, options?.toBundle())
    }

    fun downloadAndOpenFile(url: String, fileName: String) {
        val fullUrl = if (!url.startsWith("http")) LinkProtector.getHiddenLink() + url else url
        val gitHubFileName = if (fullUrl.contains("/")) fullUrl.substringAfterLast("/") else fileName
        val apiUrl = "https://api.github.com/repos/${GitHubScanner.REPO_OWNER}/${GitHubScanner.REPO_NAME}/contents/$gitHubFileName?ref=${GitHubScanner.BRANCH}"

        val request = DownloadManager.Request(apiUrl.toUri())
            .setTitle(fileName)
            .setDescription("Downloading paper...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .addRequestHeader("Authorization", "Bearer ${GitHubScanner.GITHUB_TOKEN}")
            .addRequestHeader("Accept", "application/vnd.github.v3.raw")
            .addRequestHeader("User-Agent", LinkProtector.getUserAgent())

        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
        Toast.makeText(this, "Starting download...", Toast.LENGTH_SHORT).show()
    }

    private fun downloadAndPrintFile(url: String, fileName: String) {
        val fullUrl = if (!url.startsWith("http")) LinkProtector.getHiddenLink() + url else url
        val gitHubFileName = if (fullUrl.contains("/")) fullUrl.substringAfterLast("/") else fileName
        val request = GitHubScanner.getAuthenticatedRequest(gitHubFileName)
        val client = okhttp3.OkHttpClient()

        Toast.makeText(this, "Downloading for printing...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Failed to download file: ${response.code}")

                    val file = File(cacheDir, fileName)
                    val sink = FileOutputStream(file)
                    response.body?.byteStream()?.copyTo(sink)
                    sink.close()

                    withContext(Dispatchers.Main) {
                        printPdf(file)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Print failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun printPdf(pdfFile: File) {
        val printManager = getSystemService(PRINT_SERVICE) as PrintManager
        val jobName = "${getString(R.string.app_name)} Document: ${pdfFile.name}"

        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onWrite(pages: Array<PageRange>, destination: ParcelFileDescriptor,
                                 cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
                try {
                    val input = FileInputStream(pdfFile)
                    val output = FileOutputStream(destination.fileDescriptor)
                    input.copyTo(output)
                    input.close()
                    output.close()
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }

            override fun onLayout(oldAttributes: PrintAttributes, newAttributes: PrintAttributes,
                                  cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle) {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val pdi = PrintDocumentInfo.Builder(pdfFile.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(pdi, true)
            }
        }, null)
    }

    /*
    private fun getDynamicPaperSession(): String {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR).toString().takeLast(2) 
        val month = calendar.get(Calendar.MONTH) // Jan=0, Feb=1, March=2... May=4

        return when (month) {
            in 1..3 -> "m$year"   // Feb to April: Show March (m)
            in 4..7 -> "s$year"   // May to August: Show May/June (s)
            in 8..11 -> "w$year"  // Sept to Dec: Show Oct/Nov (w)
            0 -> {                // January: Show previous year's Winter papers
                val lastYear = (year.toInt() - 1).toString().padStart(2, '0')
                "w$lastYear"
            }
            else -> "s$year"      // Fallback
        }
    }
    */

    private suspend fun applySubjectSelections(selections: Map<String, Boolean>) {
        igcseSubjects.forEach { subject ->
            subject.isIncludedInTotal = selections[subject.code] ?: false
        }
        withContext(Dispatchers.Main) {
            updateTotalProgress()
        }
    }

    private fun saveSubjectSelectionsToFirebase() {
        if (!::igcseSubjects.isInitialized) return
        val currentUser = auth.currentUser ?: return
        val firestore = db
        val selectionMap = igcseSubjects.associate { it.code to it.isIncludedInTotal }
        val preferences = mapOf(
            "subjectSelections" to selectionMap,
            "currentFilter" to currentFilter,
            "selectedExamYear" to selectedExamYear
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                firestore.collection("users").document(currentUser.uid)
                    .collection("settings").document("preferences")
                    .set(preferences, SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                Log.e("Firebase", "Error saving preferences", e)
            }
        }
    }
}
