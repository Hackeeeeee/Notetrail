package com.mechanicalaxiom.notetrail

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class HistoryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val data: Map<String, Any>,
    val paperKey: String,
    val subjectCode: String,
    val timeSpent: Int = 0,
    val hasAnnotations: Boolean = false
)

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: HistoryAdapter
    private lateinit var subjectChipGroup: ChipGroup
    private lateinit var emptyHistoryContainer: View
    private lateinit var progressBar: ProgressBar
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var allSubjects: List<Subject> = emptyList()
    private var allHistoryItems: List<HistoryItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.historyToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.historyRecyclerView)
        subjectChipGroup = findViewById(R.id.subjectChipGroup)
        emptyHistoryContainer = findViewById(R.id.emptyHistoryContainer)
        progressBar = findViewById(R.id.historyProgressBar)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        setupRecyclerView()
        setupChipGroup()

        allSubjects = IgcseDataHolder.subjects
        if (allSubjects.isEmpty()) {
            Toast.makeText(this, "Could not load subject data. Please restart the app.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        loadHistory()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear_history -> {
                showClearHistoryConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(mutableListOf()) { item, isMarkScheme ->
            val paper = findPaperByKey(item.paperKey)
            if (paper != null) {
                val url = if (isMarkScheme) paper.msUrl else paper.fileUrl
                openDocument(url, paper, item.hasAnnotations && !isMarkScheme)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = itemView.findViewById<View>(R.id.deleteBackground)
                
                if (dX < 0) { // Swiping left
                    background.visibility = View.VISIBLE
                } else {
                    background.visibility = View.INVISIBLE
                }
                
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = adapter.getItemAt(position)
                    showDeleteItemConfirmation(item, position)
                }
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }

    private fun setupChipGroup() {
        subjectChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val selectedChipId = checkedIds.firstOrNull()
            if (selectedChipId == null || selectedChipId == R.id.chipAll) {
                adapter.updateList(allHistoryItems)
            } else {
                val chip = group.findViewById<Chip>(selectedChipId)
                val subjectCode = chip.tag as? String
                val filtered = allHistoryItems.filter { it.subjectCode == subjectCode }
                adapter.updateList(filtered)
            }
            updateEmptyState()
        }
    }

    private fun loadHistory() {
        progressBar.visibility = View.VISIBLE
        val currentUser = auth.currentUser

        if (currentUser == null) {
            progressBar.visibility = View.GONE
            emptyHistoryContainer.visibility = View.VISIBLE
            findViewById<TextView>(R.id.emptyHistoryText).text = getString(R.string.login_to_view_history)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch both progress data (marks/completion) and the root document (for hasAnnotations flag)
                val uid = currentUser.uid
                val progressDoc = db.collection("users").document(uid).collection("progress").document("data").get().await()
                val userDoc = db.collection("users").document(uid).get().await()

                // Get general progress flags (e.g., hasAnnotations saved via DocumentViewerActivity)
                @Suppress("UNCHECKED_CAST")
                val rootProgress = userDoc.get("progress") as? Map<String, Any> ?: emptyMap()

                val historyItems = mutableListOf<HistoryItem>()

                if (progressDoc.exists()) {
                    progressDoc.data?.forEach { (key, value) ->
                        if (value is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val paperData = value as? Map<String, Any>
                            if (paperData != null) {
                                // 1. Attempt decryption if encryptedMeta exists
                                var marksObtained: Int? = null
                                var totalMarks: Int? = null
                                var isCompleted = false
                                var timeSpent = 0
                                var hasAnnotations = false

                                val encryptedMeta = paperData["encryptedMeta"] as? String
                                if (encryptedMeta != null && CryptoManager.hasCloudMasterKey()) {
                                    try {
                                        val decryptedJson = CryptoManager.decrypt(encryptedMeta)
                                        @Suppress("UNCHECKED_CAST")
                                        val meta = Gson().fromJson(decryptedJson, Map::class.java) as? Map<String, Any> ?: emptyMap()
                                        
                                        isCompleted = meta["isCompleted"] as? Boolean ?: false
                                        marksObtained = (meta["marksObtained"] as? Number)?.toInt()
                                        totalMarks = (meta["totalMarks"] as? Number)?.toInt()
                                        timeSpent = (meta["timeSpent"] as? Number)?.toInt() ?: 0
                                        hasAnnotations = meta["hasAnnotations"] as? Boolean ?: false
                                    } catch (e: Exception) {
                                        Log.e("HistoryActivity", "Decryption failed for $key: ${e.message}")
                                    }
                                }

                                // 2. Fallback to legacy fields if not completed via encryption
                                if (!isCompleted) {
                                    isCompleted = paperData["isCompleted"] == true
                                    marksObtained = (paperData["marksObtained"] as? Number)?.toInt()
                                    totalMarks = (paperData["totalMarks"] as? Number)?.toInt()
                                    timeSpent = (paperData["timeSpent"] as? Number)?.toInt() ?: 0
                                    hasAnnotations = paperData["hasAnnotations"] == true
                                }
                                
                                // 3. Cross-reference with root hasAnnotations flag if false
                                if (!hasAnnotations) {
                                    @Suppress("UNCHECKED_CAST")
                                    val paperMeta = rootProgress[key] as? Map<String, Any>
                                    hasAnnotations = paperMeta?.get("hasAnnotations") == true
                                }

                                if (isCompleted || hasAnnotations) {
                                    val paper = findPaperByKey(key)
                                    if (paper != null) {
                                        val subject = findSubjectForPaper(paper)
                                        val title = "${subject?.name ?: getString(R.string.unknown_subject)} (${paper.paperKey.substringBefore("_")})"
                                        val subtitle = "Paper ${paper.variantCode} - ${paper.paperType}"
                                        
                                        historyItems.add(
                                            HistoryItem(
                                                id = key,
                                                title = title,
                                                subtitle = subtitle,
                                                data = paperData.toMutableMap().apply {
                                                    put("marksObtained", marksObtained ?: 0)
                                                    put("totalMarks", totalMarks ?: 0)
                                                    put("isCompleted", isCompleted)
                                                    put("hasAnnotations", hasAnnotations)
                                                },
                                                paperKey = key,
                                                subjectCode = subject?.code ?: "",
                                                timeSpent = timeSpent,
                                                hasAnnotations = hasAnnotations
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                allHistoryItems = historyItems.sortedByDescending { it.paperKey }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    populateSubjectChips()
                    adapter.updateList(allHistoryItems)
                    updateEmptyState()
                }

            } catch (e: Exception) {
                Log.e("HistoryActivity", "Error loading history", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    emptyHistoryContainer.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.emptyHistoryText).text = getString(R.string.error_loading_history)
                }
            }
        }
    }

    private fun populateSubjectChips() {
        val uniqueSubjectCodes = allHistoryItems.map { it.subjectCode }.distinct()
        // Remove old chips except "All"
        val chipsToRemove = mutableListOf<View>()
        for (i in 0 until subjectChipGroup.childCount) {
            val child = subjectChipGroup.getChildAt(i)
            if (child.id != R.id.chipAll) {
                chipsToRemove.add(child)
            }
        }
        chipsToRemove.forEach { subjectChipGroup.removeView(it) }

        uniqueSubjectCodes.forEach { code ->
            if (code.isEmpty()) return@forEach
            val subjectName = allSubjects.find { it.code == code }?.name ?: code
            val chip = Chip(this).apply {
                text = subjectName
                tag = code
                isCheckable = true
                setTextColor(getColor(R.color.text_primary))
                chipBackgroundColor = ColorStateList.valueOf(getColor(R.color.glass_surface))
                chipStrokeColor = ColorStateList.valueOf(getColor(R.color.text_primary))
                chipStrokeWidth = 1f * resources.displayMetrics.density
            }
            subjectChipGroup.addView(chip)
        }
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            emptyHistoryContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyHistoryContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showDeleteItemConfirmation(item: HistoryItem, position: Int) {
        val dialog = AlertDialog.Builder(this, R.style.GlassDialogTheme)
            .setTitle("Delete History")
            .setMessage("Remove this paper from your history?")
            .setPositiveButton("Delete") { _, _ ->
                deleteHistoryItem(item, position)
            }
            .setNegativeButton("Cancel") { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .setOnCancelListener {
                adapter.notifyItemChanged(position)
            }
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun deleteHistoryItem(item: HistoryItem, position: Int) {
        val currentUser = auth.currentUser ?: return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Update Firestore: Remove the specific paper key from the progress data
                val docRef = db.collection("users").document(currentUser.uid).collection("progress").document("data")
                docRef.update(item.id, FieldValue.delete()).await()

                withContext(Dispatchers.Main) {
                    // Update Local Lists
                    allHistoryItems = allHistoryItems.filter { it.id != item.id }
                    
                    // Reset paper in memory
                    val paper = findPaperByKey(item.id)
                    paper?.apply {
                        isCompleted = false
                        marksObtained = null
                        totalMarks = null
                        notes = null
                    }
                    saveProgressToLocal()

                    adapter.removeItem(position)
                    updateEmptyState()
                    Toast.makeText(this@HistoryActivity, "Item deleted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("HistoryActivity", "Error deleting item", e)
                withContext(Dispatchers.Main) {
                    adapter.notifyItemChanged(position)
                    Toast.makeText(this@HistoryActivity, "Failed to delete item", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openDocument(url: String?, paper: PaperVariant, autoAnnotate: Boolean = false) {
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, "Document URL is missing.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fullUrl = if (!url.startsWith("http")) LinkProtector.getHiddenLink() + url else url
            val intent = Intent(this, DocumentViewerActivity::class.java).apply {
                putExtra("pdf_url", fullUrl)
                putExtra("paper_key", paper.paperKey)
                putExtra("paper_type", paper.paperType)
                putExtra("auto_annotate", autoAnnotate)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("HistoryActivity", "Failed to start DocumentViewerActivity", e)
            Toast.makeText(this, "Could not open document: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findPaperByKey(key: String): PaperVariant? {
        allSubjects.forEach { subject ->
            subject.yearGroups.forEach { yearGroup ->
                yearGroup.sessions.forEach { sessionGroup ->
                    sessionGroup.variants.forEach { paper ->
                        if (paper.paperKey == key) {
                            return paper
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findSubjectForPaper(paper: PaperVariant): Subject? {
        val subjectCode = paper.paperKey.substringBefore("_")
        return allSubjects.find { it.code == subjectCode }
    }

    private fun showClearHistoryConfirmation() {
        val dialog = AlertDialog.Builder(this, R.style.GlassDialogTheme)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to delete all your completed paper history? This action cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                clearHistory()
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun clearHistory() {
        val currentUser = auth.currentUser ?: return
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val docRef = db.collection("users").document(currentUser.uid).collection("progress").document("data")
                docRef.delete().await()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    
                    // Reset all papers in memory
                    IgcseDataHolder.subjects.forEach { subject ->
                        subject.yearGroups.forEach { yearGroup ->
                            yearGroup.sessions.forEach { sessionGroup ->
                                sessionGroup.variants.forEach { paper ->
                                    paper.isCompleted = false
                                    paper.marksObtained = null
                                    paper.totalMarks = null
                                    paper.notes = null
                                }
                            }
                        }
                    }
                    saveProgressToLocal()

                    allHistoryItems = emptyList()
                    adapter.updateList(allHistoryItems)
                    updateEmptyState()
                    Toast.makeText(this@HistoryActivity, "History cleared.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("HistoryActivity", "Error clearing history", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@HistoryActivity, "Failed to clear history.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveProgressToLocal() {
        val progressMap = mutableMapOf<String, Any>()
        IgcseDataHolder.subjects.forEach { subject ->
            subject.yearGroups.forEach { yearGroup ->
                yearGroup.sessions.forEach { sessionGroup ->
                    sessionGroup.variants.forEach { paper ->
                        if (paper.isCompleted) {
                            val data = mutableMapOf<String, Any>()
                            paper.marksObtained?.let { data["marksObtained"] = it }
                            paper.totalMarks?.let { data["totalMarks"] = it }
                            data["isCompleted"] = paper.isCompleted
                            data["timestamp"] = System.currentTimeMillis()
                            paper.notes?.let { data["notes"] = it }
                            progressMap[paper.paperKey] = data
                        }
                    }
                }
            }
        }
        val jsonString = Gson().toJson(progressMap)
        getSharedPreferences("IgcseProgressPrefs", MODE_PRIVATE).edit {
            putString("LocalProgressData", jsonString)
        }
    }

    private inner class HistoryAdapter(
        private val items: MutableList<HistoryItem>,
        private val onAction: (HistoryItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.historyItemTitle)
            val subtitle: TextView = view.findViewById(R.id.historyItemSubtitle)
            val timeSpent: TextView = view.findViewById(R.id.timeSpentText)
            val penIcon: ImageView = view.findViewById(R.id.penIcon)
            val viewPaper: Button = view.findViewById(R.id.viewPaperButton)
            val viewMS: Button = view.findViewById(R.id.viewMarkSchemeButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.history_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            
            val marks = (item.data["marksObtained"] as? Number)?.toInt() ?: 0
            val total = (item.data["totalMarks"] as? Number)?.toInt() ?: 0
            holder.subtitle.text = getString(R.string.paper_history_subtitle_format, item.subtitle, marks, total)
            
            if (item.timeSpent > 0) {
                holder.timeSpent.visibility = View.VISIBLE
                holder.timeSpent.text = "Time spent: ${item.timeSpent} min"
            } else {
                holder.timeSpent.visibility = View.GONE
            }
            
            holder.penIcon.visibility = if (item.hasAnnotations) View.VISIBLE else View.GONE
            
            holder.viewPaper.setOnClickListener { onAction(item, false) }
            holder.viewMS.setOnClickListener { onAction(item, true) }
        }

        override fun getItemCount() = items.size

        fun getItemAt(position: Int) = items[position]

        fun removeItem(position: Int) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }

        fun updateList(newList: List<HistoryItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
