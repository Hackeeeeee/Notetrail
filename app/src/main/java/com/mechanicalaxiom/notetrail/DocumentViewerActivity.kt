package com.mechanicalaxiom.notetrail

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.net.Uri
import android.os.*
import android.os.ext.SdkExtensions
import android.util.Log
import android.util.SparseArray
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresExtension
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.util.size
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import androidx.pdf.view.PdfView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mechanicalaxiom.notetrail.databinding.ActivityDocumentViewerBinding
import com.mechanicalaxiom.notetrail.ui.theme.GitHubScanner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class DocumentViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentViewerBinding
    private var pdfViewerFragment: NoteTrailPdfViewerFragment? = null
    

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private var countDownTimer: CountDownTimer? = null
    private var timeRemainingMillis: Long = 0
    private var isTimerRunning = false

    private val pdfViewerFragmentTag = "PDF_VIEWER"

    // Annotations List Support
    private val annotationsList = mutableListOf<AnnotationView.Annotation>()
    private lateinit var annotationsAdapter: AnnotationsAdapter

    private var isAnnotationSyncActive = false
    private var isPdfLoaded = false
    private var activeEditText: EditText? = null
    private var isInitialSyncSuccessful = true // Flag to prevent overwriting undecryptable data
    
    private var currentPageSyncListener: ListenerRegistration? = null
    private var lastSyncedPage = -1

    // Cache for received data to handle PDF layout races
    private val receivedPageData = mutableMapOf<Int, PageAnnotations>()
    
    // Time tracking
    private var studySessionStartTime: Long = 0
    
    // Movable bar coordinates
    private var dX = 0f
    private var dY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Igcsecloudapp_PdfViewer)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupInsets()
        setupIntentsAndTimer(savedInstanceState)
        setupMainControls()
        setupNavigationDrawer()
        setupAnnotationBar()
        setupMovableAnnotationBar()
        
        studySessionStartTime = System.currentTimeMillis()
        
        // Handle auto-annotate from history
        if (intent.getBooleanExtra("auto_annotate", false)) {
            binding.root.post {
                if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
                    toggleAnnotationMode()
                }
            }
        }
        
        showControls()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val topPadding = (insets.top + (16 * density)).toInt()
            val sidePadding = (16 * density).toInt()
            
            binding.topControlsContainer.setPadding(sidePadding, topPadding, sidePadding, (8 * density).toInt())
            windowInsets
        }
    }

    private fun setupIntentsAndTimer(savedInstanceState: Bundle?) {
        val paperKey = intent.getStringExtra("paper_key") ?: ""
        val pdfUrl = intent.getStringExtra("pdf_url") ?: ""
        
        Log.d("DocumentViewer", "setupIntentsAndTimer: paperKey=$paperKey pdfUrl=$pdfUrl")

        if (savedInstanceState != null) {
            timeRemainingMillis = savedInstanceState.getLong("timeRemainingMillis")
            isTimerRunning = savedInstanceState.getBoolean("isTimerRunning")
            if (isTimerRunning) startCountDown() else updateTimerUI()
        } else {
            initializeTimerDuration()
        }

        if (pdfUrl.isNotEmpty()) {
            loadPdfWithCache(pdfUrl, paperKey)
        }
    }

    private fun setupMainControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { toggleTimer() }
        binding.btnSave.setOnClickListener { saveProgressToFirebase() }
        binding.btnDrawer.setOnClickListener { 
            updateAnnotationsList()
            binding.drawerLayout.openDrawer(GravityCompat.END) 
        }
        
        // Button visibility will be handled by PDF load callback
        
        binding.btnAnnotate.setOnClickListener { 
            if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
                toggleAnnotationMode()
            } else {
                Toast.makeText(this, "Annotation requires Android 12 (S) or higher", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Ensure sync status is correctly positioned and visible
        binding.ivSyncStatus.post {
            binding.ivSyncStatus.isVisible = true
            binding.ivSyncStatus.bringToFront()
        }
    }

    private fun setupAnnotationBar() {
        binding.btnPenTool.setOnClickListener {
            binding.annotationOverlay.mode = AnnotationView.Mode.PEN
            binding.annotationOverlay.penSize = binding.thicknessWheel.thickness
            animateToolSelection(it)
        }
        binding.btnPencilTool.setOnClickListener {
            binding.annotationOverlay.mode = AnnotationView.Mode.PENCIL
            binding.annotationOverlay.penSize = binding.thicknessWheel.thickness * 0.5f
            animateToolSelection(it)
        }
        binding.btnGelPenTool.setOnClickListener {
            binding.annotationOverlay.mode = AnnotationView.Mode.PEN
            binding.annotationOverlay.penSize = binding.thicknessWheel.thickness
            binding.annotationOverlay.penColor = "#00008B".toColorInt()
            animateToolSelection(it)
        }
        binding.btnHighlighterTool.setOnClickListener {
            binding.annotationOverlay.mode = AnnotationView.Mode.HIGHLIGHTER
            binding.annotationOverlay.penSize = binding.thicknessWheel.thickness * 3f
            animateToolSelection(it)
        }
        
        binding.btnShapesTool.setOnClickListener {
            // Quick toggle between Pen and last selected shape?
            // Or just a standard tap behavior. 
            // The user requested long press for selection.
            animateToolSelection(it)
        }
        
        binding.btnShapesTool.setOnLongClickListener { view ->
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val popupView = inflater.inflate(R.layout.layout_shape_selector, binding.root, false)
            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
            
            popupView.findViewById<View>(R.id.btn_rect).setOnClickListener {
                binding.annotationOverlay.mode = AnnotationView.Mode.RECT
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.btn_circle).setOnClickListener {
                binding.annotationOverlay.mode = AnnotationView.Mode.CIRCLE
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.btn_line).setOnClickListener {
                binding.annotationOverlay.mode = AnnotationView.Mode.LINE
                popupWindow.dismiss()
            }
            popupView.findViewById<View>(R.id.btn_ruler).setOnClickListener {
                binding.annotationOverlay.mode = AnnotationView.Mode.LINE // Ruler is a straight line
                popupWindow.dismiss()
            }
            
            popupWindow.elevation = 20f
            popupWindow.showAsDropDown(view, 0, -view.height - 250)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }

        binding.btnTextTool.setOnClickListener {
            binding.annotationOverlay.mode = AnnotationView.Mode.TEXT
            animateToolSelection(it)
        }
        binding.btnEraserTool.setOnClickListener {
            binding.annotationOverlay.mode = AnnotationView.Mode.ERASER
            animateToolSelection(it)
        }

        binding.btnEraserTool.setOnLongClickListener { view ->
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val popupView = inflater.inflate(R.layout.layout_eraser_selector, binding.root, false)
            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
            
            val seekBar = popupView.findViewById<SeekBar>(R.id.sb_eraser_size)
            val textView = popupView.findViewById<TextView>(R.id.tv_eraser_size)
            
            seekBar.progress = binding.annotationOverlay.eraserSize.toInt()
            textView.text = getString(R.string.eraser_size, seekBar.progress)
            
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val finalProgress = progress.coerceAtLeast(10)
                    binding.annotationOverlay.eraserSize = finalProgress.toFloat()
                    textView.text = getString(R.string.eraser_size, finalProgress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            },)
            
            popupWindow.elevation = 20f
            popupWindow.showAsDropDown(view, 0, -view.height - 250)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        binding.btnUndo.setOnClickListener {
            binding.annotationOverlay.undo()
            animateToolSelection(it)
        }
        binding.btnClearTool.setOnClickListener {
            binding.annotationOverlay.clear()
            animateToolSelection(it)
        }
        
        binding.colorSpectrumPicker.onColorSelected = { color ->
            binding.annotationOverlay.penColor = color
            binding.btnColorPicker.imageTintList = ColorStateList.valueOf(color)
        }

        binding.thicknessWheel.onThicknessChanged = { size ->
            binding.annotationOverlay.penSize = size
        }

        binding.btnColorPicker.setOnClickListener {
            binding.colorSpectrumPicker.isVisible = !binding.colorSpectrumPicker.isVisible
            animateToolSelection(it)
        }

        binding.annotationOverlay.onAnnotationsUpdated = {
            updateAnnotationsList()
            saveAnnotationsToFirebase()
        }
        
        binding.annotationOverlay.onTextRequest = { x, y, _ ->
            showInPlaceEditText(x, y)
        }
        
        binding.annotationOverlay.onOutsideTap = {
            activeEditText?.let { commitText(it) }
        }

        binding.annotationOverlay.onDrawingStateChanged = { isDrawing ->
            binding.annotationBarCard.animate()
                .alpha(if (isDrawing) 0.5f else 1.0f)
                .setDuration(200)
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMovableAnnotationBar() {
        val bar = binding.annotationBarCard
        val dragHandle = binding.btnDragHandle
        
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = bar.x - event.rawX
                    dY = bar.y - event.rawY
                    bar.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bar.x = event.rawX + dX
                    bar.y = event.rawY + dY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bar.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    snapToEdge(bar)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        
        val padding = 32f
        
        // Calculate distances to all four edges based on current position
        val distLeft = view.x
        val distRight = screenWidth - (view.x + view.width)
        val distTop = view.y
        val distBottom = screenHeight - (view.y + view.height)
        
        val minDist = minOf(distLeft, distRight, distTop, distBottom)
        
        val shouldBeVertical = (minDist == distLeft || minDist == distRight)
        
        updateBarOrientation(shouldBeVertical)

        // Post the animation to ensure layout has updated with new orientation dimensions
        view.post {
            var targetX = view.x
            var targetY = view.y
            
            when (minDist) {
                distLeft -> {
                    targetX = padding
                }
                distRight -> {
                    targetX = screenWidth - view.width - padding
                }
                distTop -> {
                    targetY = padding
                }
                distBottom -> {
                    targetY = screenHeight - view.height - padding - 100 // Avoid bottom controls
                }
            }

            view.animate()
                .x(targetX)
                .y(targetY)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun updateBarOrientation(isVertical: Boolean) {
        val bar = binding.annotationBar
        val hScroll = binding.horizontalScrollContainer
        val vScroll = binding.verticalScrollContainer
        val card = binding.annotationBarCard
        
        if (isVertical && bar.orientation == LinearLayout.HORIZONTAL) {
            // Switch to Vertical
            (bar.parent as? ViewGroup)?.removeView(bar)
            bar.orientation = LinearLayout.VERTICAL
            vScroll.addView(bar)
            vScroll.visibility = View.VISIBLE
            hScroll.visibility = View.GONE
            
            card.layoutParams.width = 64.dpToPx()
            card.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            
            // Adjust internal views for vertical
            binding.colorSpectrumPicker.layoutParams.width = 32.dpToPx()
            binding.colorSpectrumPicker.layoutParams.height = 120.dpToPx()
            binding.thicknessWheel.layoutParams.width = 48.dpToPx()
            binding.thicknessWheel.layoutParams.height = 60.dpToPx()
            
            // Adjust padding/gravity
            bar.setPadding(0, 12.dpToPx(), 0, 20.dpToPx())
            binding.btnDragHandle.setPadding(0, 0, 0, 8.dpToPx())
            
            // Adjust spaces and separators
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (child is android.widget.Space) {
                    params.width = 0
                    params.height = 12.dpToPx()
                } else if (child.id == View.NO_ID) {
                    // Assume it's a separator View
                    params.width = 24.dpToPx()
                    params.height = 1.dpToPx()
                    params.setMargins(0, 12.dpToPx(), 0, 12.dpToPx())
                }
                child.layoutParams = params
            }
            
        } else if (!isVertical && bar.orientation == LinearLayout.VERTICAL) {
            // Switch to Horizontal
            (bar.parent as? ViewGroup)?.removeView(bar)
            bar.orientation = LinearLayout.HORIZONTAL
            hScroll.addView(bar)
            hScroll.visibility = View.VISIBLE
            vScroll.visibility = View.GONE
            
            card.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            card.layoutParams.height = 64.dpToPx()
            
            // Adjust internal views for horizontal
            binding.colorSpectrumPicker.layoutParams.width = 120.dpToPx()
            binding.colorSpectrumPicker.layoutParams.height = 32.dpToPx()
            binding.thicknessWheel.layoutParams.width = 60.dpToPx()
            binding.thicknessWheel.layoutParams.height = 48.dpToPx()
            
            // Adjust padding/gravity
            bar.setPadding(12.dpToPx(), 0, 20.dpToPx(), 0)
            binding.btnDragHandle.setPadding(0, 0, 8.dpToPx(), 0)

            // Adjust spaces and separators
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                val params = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (child is android.widget.Space) {
                    params.width = 12.dpToPx()
                    params.height = 0
                } else if (child.id == View.NO_ID) {
                    // Assume it's a separator View
                    params.width = 1.dpToPx()
                    params.height = 24.dpToPx()
                    params.setMargins(12.dpToPx(), 0, 12.dpToPx(), 0)
                }
                child.layoutParams = params
            }
        }
        card.requestLayout()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-snap to nearest edge after orientation change
        binding.annotationBarCard.post {
            snapToEdge(binding.annotationBarCard)
        }
    }

    private fun showInPlaceEditText(screenX: Float, screenY: Float) {
        if (activeEditText != null) {
            commitText(activeEditText!!)
        }

        val textInput = EditText(this).apply {
            background = null
            hint = "Type here..."
            setHintTextColor(Color.GRAY)
            setTextColor(binding.annotationOverlay.penColor)
            textSize = 20f
            setPadding(8, 4, 8, 4)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            
            // Initial Position
            x = screenX
            y = screenY
        }

        activeEditText = textInput
        binding.main.addView(textInput)
        
        textInput.post {
            textInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT)
        }

        textInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                commitText(textInput)
            }
        }
        
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                commitText(textInput)
                true
            } else {
                false
            }
        }
    }

    private fun commitText(editText: EditText) {
        if (editText.parent == null || editText.tag == "committing") return 
        editText.tag = "committing"

        val content = editText.text.toString()
        val screenX = editText.x
        val screenY = editText.y

        try {
            if (content.isNotEmpty()) {
                if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
                    // Find page at this screen coordinate for sticky logic
                    var targetPage = -1
                    var pageRect: RectF? = null
                    for ((idx, rect) in binding.annotationOverlay.pageOffsets) {
                        if (rect.contains(screenX, screenY)) {
                            targetPage = idx
                            pageRect = rect
                            break
                        }
                    }

                    if (targetPage != -1 && pageRect != null) {
                        // Convert screen coordinates back to page-relative document coordinates
                        val docX = (screenX - pageRect.left) / binding.annotationOverlay.currentZoom
                        val docY = (screenY - pageRect.top) / binding.annotationOverlay.currentZoom
                        
                        binding.annotationOverlay.currentPage = targetPage
                        
                        // Match the EditText settings perfectly to prevent "jumping"
                        val textPaint = Paint().apply {
                            isAntiAlias = true
                            color = editText.currentTextColor
                            textSize = editText.textSize // Pixels
                            style = Paint.Style.FILL
                        }
                        binding.annotationOverlay.addTextAnnotationWithPaint(content, docX, docY, textPaint)
                    }
                }
            }

            // Safe Removal
            (editText.parent as? ViewGroup)?.removeView(editText)
            if (activeEditText == editText) {
                activeEditText = null
            }
            
            // Auto-switch back to Pen mode for a smoother experience
            binding.annotationOverlay.mode = AnnotationView.Mode.PEN
            animateToolSelection(binding.btnPenTool)
            
            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
            
            // Clear focus from the window to prevent re-triggering
            window.decorView.clearFocus()
            
            saveAnnotationsToFirebase()
        } catch (e: Exception) {
            Log.e("NoteTrail", "Commit failed: ${e.message}")
            editText.tag = null // Allow retry if failed
        }
    }

    private fun animateToolSelection(view: View) {
        view.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(100)
            .withEndAction {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
            .start()
        
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun toggleAnnotationMode() {
        if (!isPdfLoaded) {
            Toast.makeText(this, "Please wait for PDF to load...", Toast.LENGTH_SHORT).show()
            return
        }
        if (binding.annotationBarCard.isVisible) {
            binding.annotationBarCard.visibility = View.GONE
            binding.annotationOverlay.isLocked = true
            binding.annotationOverlay.mode = AnnotationView.Mode.HAND
        } else {
            binding.annotationBarCard.isVisible = true
            binding.annotationOverlay.isVisible = true
            binding.annotationOverlay.isLocked = false
            binding.annotationOverlay.mode = AnnotationView.Mode.PEN
            
            // Ensure sync is active when entering annotation mode
            if (!isAnnotationSyncActive) {
                setupAnnotationSync()
                startRealtimeSync()
                isAnnotationSyncActive = true
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun setupAnnotationSync() {
        val pdfView = pdfViewerFragment?.getPublicPdfView() ?: return
        
        binding.annotationOverlay.onTouchIntercepted = { event ->
            pdfView.dispatchTouchEvent(event)
        }

        // Manual initial update to fix "scroll before draw" bug
        binding.annotationOverlay.currentZoom = pdfView.zoom
        
        // Force a viewport update by triggering a tiny scroll
        pdfView.post {
            pdfView.scrollBy(0, 1)
            pdfView.scrollBy(0, -1)
        }

        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: SparseArray<RectF>,
                zoomLevel: Float,
            ) {
                Log.d("DocumentViewer", "onViewportChanged: first=$firstVisiblePage count=$visiblePagesCount zoom=$zoomLevel")
                binding.annotationOverlay.pageOffsets.clear()
                for (i in 0 until pageLocations.size) {
                    val pageIdx = pageLocations.keyAt(i)
                    val rect = pageLocations.valueAt(i)
                    binding.annotationOverlay.pageOffsets[pageIdx] = rect
                    
                    // Update intrinsic size cache
                    binding.annotationOverlay.updateIntrinsicSize(
                        pageIdx, 
                        rect.width() / zoomLevel, 
                        rect.height() / zoomLevel
                    )
                    
                    // Re-apply data if it was waiting for this page layout
                    receivedPageData[pageIdx]?.let { data ->
                        Log.d("SYNC", "Layout ready for page $pageIdx, applying cached data")
                        applyPageDataIfReady(pageIdx, data)
                    }
                }
                binding.annotationOverlay.currentZoom = zoomLevel
                binding.annotationOverlay.scrollYOffset = pdfView.scrollY.toFloat()
                binding.annotationOverlay.scrollXOffset = pdfView.scrollX.toFloat()
                binding.annotationOverlay.invalidate()

                if (firstVisiblePage != lastSyncedPage) {
                    startPageSpecificSync(firstVisiblePage)
                }
            }
        })
        
        binding.annotationOverlay.onScrollRequested = { dx, dy ->
            pdfView.scrollBy(dx.toInt(), dy.toInt())
        }
    }

    private fun startPageSpecificSync(pageIdx: Int) {
        val uid = auth.currentUser?.uid ?: run {
            Log.e("SYNC", "startPageSpecificSync: No UID")
            return
        }
        val paperKey = intent.getStringExtra("paper_key") ?: run {
            Log.e("SYNC", "startPageSpecificSync: No paper_key")
            return
        }
        
        lastSyncedPage = pageIdx
        currentPageSyncListener?.remove()

        val docRef = firestore.collection("users").document(uid)
            .collection("annotations").document(paperKey)
            .collection("pages").document(pageIdx.toString())

        Log.d("SYNC", "Starting page sync for: $pageIdx path: ${docRef.path}")
        currentPageSyncListener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e("SYNC", "Page listen failed for $pageIdx: ${e.message}")
                binding.ivSyncStatus.post {
                    binding.ivSyncStatus.imageTintList = ColorStateList.valueOf(Color.RED)
                }
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                Log.d("SYNC", "Snapshot received for page $pageIdx. Keys: ${snapshot.data?.keys}")
                try {
                    // Try to decrypt if encrypted data exists (Silent Hardware Decryption)
                    val encryptedData = snapshot.getString("encryptedData")
                    val pageData = if (encryptedData != null) {
                        if (CryptoManager.hasCloudMasterKey()) {
                            try {
                                Log.d("SYNC", "Decrypting page data for $pageIdx...")
                                val decryptedJson = CryptoManager.decrypt(encryptedData)
                                Gson().fromJson(decryptedJson, PageAnnotations::class.java)
                            } catch (e: Exception) {
                                Log.e("SYNC", "Hardware decryption failed for $pageIdx: ${e.message}")
                                isInitialSyncSuccessful = false // Mark as failed to prevent overwrite
                                null
                            }
                        } else {
                            Log.e("SYNC", "Encrypted data found but Cloud Master Key missing for $pageIdx")
                            isInitialSyncSuccessful = false
                            null
                        }
                    } else {
                        Log.d("SYNC", "Using unencrypted fallback/legacy data for $pageIdx")
                        snapshot.toObject(PageAnnotations::class.java)
                    }

                    pageData?.let {
                        Log.d("SYNC", "Parsed page $pageIdx: strokes=${it.strokes.size} texts=${it.texts.size}")
                        receivedPageData[pageIdx] = it
                        applyPageDataIfReady(pageIdx, it)
                    } ?: run {
                        Log.w("SYNC", "No data parsed for page $pageIdx")
                    }
                } catch (ex: Exception) {
                    Log.e("SYNC", "Error parsing page data for $pageIdx: ${ex.message}")
                }
            } else {
                Log.d("SYNC", "No snapshot/document exists for page $pageIdx")
            }
        }
    }

    private fun applyPageDataIfReady(pageIdx: Int, data: PageAnnotations) {
        val hasPageSize = binding.annotationOverlay.pageOffsets.containsKey(pageIdx)
        Log.d("SYNC", "applyPageDataIfReady: page=$pageIdx hasPageSize=$hasPageSize strokes=${data.strokes.size}")
        
        if (hasPageSize) {
            val fullData = AnnotationPageData(pages = mapOf(pageIdx.toString() to data))
            binding.annotationOverlay.post {
                binding.annotationOverlay.importFromData(fullData)
                binding.annotationOverlay.invalidate()
                updateAnnotationsList()
                Log.d("SYNC", "Page $pageIdx data applied to overlay")
            }
        } else {
            Log.d("SYNC", "Page $pageIdx size not ready, data cached")
        }
    }

    private fun startRealtimeSync() {
        if (auth.currentUser == null) {
            Log.e("SYNC", "No authenticated user found for sync")
            return
        }
        val paperKey = intent.getStringExtra("paper_key")
        if (paperKey == null) {
            Log.e("SYNC", "No paper_key found for sync")
            return
        }

        Log.d("SYNC", "startRealtimeSync: user=${auth.currentUser?.uid} paper=$paperKey")

        // Initial sync for the current page
        val firstVisiblePage = binding.annotationOverlay.currentPage
        startPageSpecificSync(firstVisiblePage)

        binding.ivSyncStatus.post {
            binding.ivSyncStatus.setImageResource(R.drawable.ic_cloud_sync)
            binding.ivSyncStatus.imageTintList = ColorStateList.valueOf(Color.GREEN)
        }
    }

    private fun saveAnnotationsToFirebase() {
        val uid = auth.currentUser?.uid ?: run {
            Log.e("FIREBASE", "Not saving: User not logged in")
            return
        }
        val paperKey = intent.getStringExtra("paper_key") ?: run {
            Log.e("FIREBASE", "Not saving: No paperKey")
            return
        }

        if (!isInitialSyncSuccessful) {
            Log.e("FIREBASE", "CRITICAL: Initial sync failed (possibly decryption error). Save aborted to prevent data loss.")
            binding.ivSyncStatus.post {
                binding.ivSyncStatus.imageTintList = ColorStateList.valueOf(Color.RED)
            }
            
            AlertDialog.Builder(this)
                .setTitle("Sync Disabled")
                .setMessage("Your notes are encrypted with a key missing from this device. Please log out and log back in to automatically restore your notes.")
                .setPositiveButton("Log Out") { _, _ ->
                    auth.signOut()
                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Later", null)
                .show()

            return
        }

        val fullData = binding.annotationOverlay.exportToData()
        
        Log.d("FIREBASE", "saveAnnotationsToFirebase: key=$paperKey pages=${fullData.pages.size}")

        binding.ivSyncStatus.post {
            binding.ivSyncStatus.imageTintList = ColorStateList.valueOf(Color.YELLOW) // Syncing
        }
        
        val batch = firestore.batch()
        val rootRef = firestore.collection("users").document(uid)
            .collection("annotations").document(paperKey)
            
        for ((pageKey, pageAnns) in fullData.pages) {
            val pageRef = rootRef.collection("pages").document(pageKey)
            
            if (CryptoManager.hasCloudMasterKey()) {
                val json = Gson().toJson(pageAnns)
                val encrypted = CryptoManager.encrypt(json) // Silent Scramble
                
                // IMPORTANT: Overwrite the document to remove any legacy raw fields
                batch.set(pageRef, mapOf("encryptedData" to encrypted))
            } else {
                Log.w("FIREBASE", "Not saving encrypted: Cloud Master Key missing. Retrying init...")
                CryptoManager.init(this)
                if (CryptoManager.hasCloudMasterKey()) {
                    val json = Gson().toJson(pageAnns)
                    val encrypted = CryptoManager.encrypt(json)
                    batch.set(pageRef, mapOf("encryptedData" to encrypted))
                } else {
                    // If STILL no key, do NOT save raw data for a security-focused app
                    // batch[pageRef] = pageAnns // Disabled fallback to prevent data leak
                    Log.e("FIREBASE", "CRITICAL: Key missing, save aborted to prevent data leak.")
                }
            }
        }
        
        // Also update the progress flag for hasAnnotations
        if (fullData.pages.isNotEmpty()) {
            val progressRef = firestore.collection("users").document(uid)
            batch.set(progressRef, mapOf("progress" to mapOf(paperKey to mapOf("hasAnnotations" to true))), SetOptions.merge())
        }
        
        batch.commit()
            .addOnSuccessListener {
                Log.d("FIREBASE", "✅ Page Sync Successful")
                binding.ivSyncStatus.post {
                    binding.ivSyncStatus.imageTintList = ColorStateList.valueOf(Color.GREEN) // Synced
                }
            }
            .addOnFailureListener { e ->
                Log.e("FIREBASE", "❌ Page Sync Failed: ${e.message}")
                binding.ivSyncStatus.post {
                    binding.ivSyncStatus.imageTintList = ColorStateList.valueOf(Color.RED) // Error
                }
                Toast.makeText(this, "Sync Failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setupNavigationDrawer() {
        annotationsAdapter = AnnotationsAdapter(annotationsList) { annotation ->
            binding.drawerLayout.closeDrawer(GravityCompat.END)
            jumpToPage(annotation.pageIdx)
        }
        binding.rvAnnotationsList.layoutManager = LinearLayoutManager(this)
        binding.rvAnnotationsList.adapter = annotationsAdapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(enabled = true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.END)
                } else {
                    finish()
                }
            }
        })
    }

    private fun jumpToPage(pageIdx: Int) {
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
            val pdfView = pdfViewerFragment?.getPublicPdfView()
            pdfView?.scrollToPage(pageIdx)
        }
    }

    private fun updateAnnotationsList() {
        val allAnnotations = binding.annotationOverlay.pageAnnotations.values
            .asSequence()
            .flatten()
            .filter { it is AnnotationView.Annotation.Text || it is AnnotationView.Annotation.Callout }
            .distinctBy { (it as? AnnotationView.Annotation.Text)?.let { t -> "${t.text}_${t.x}_${t.y}_${t.pageIdx}" } ?: it.timestamp }
            .sortedByDescending { it.timestamp }
            .toList()
        
        annotationsList.clear()
        annotationsList.addAll(allAnnotations)
        annotationsAdapter.notifyDataSetChanged()
        
        binding.navView.findViewById<TextView>(R.id.tv_empty_annotations).visibility = if (annotationsList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveProgressToFirebase() {
        val uid = auth.currentUser?.uid ?: return
        val paperKey = intent.getStringExtra("paper_key") ?: return
        
        val durationMillis = System.currentTimeMillis() - studySessionStartTime
        val durationMinutes = (durationMillis / 60000).toInt().coerceAtLeast(1)

        lifecycleScope.launch {
            try {
                // Absolute Privacy: Bundle metadata into an encrypted blob
                val meta = mutableMapOf<String, Any>(
                    "isCompleted" to true,
                    "timestamp" to System.currentTimeMillis(),
                    "timeSpent" to durationMinutes
                )
                
                // Check for annotations status to include in meta
                if (binding.annotationOverlay.exportToData().pages.isNotEmpty()) {
                    meta["hasAnnotations"] = true
                }

                val finalUpdate = mutableMapOf<String, Any>()
                if (CryptoManager.hasCloudMasterKey()) {
                    try {
                        val encryptedMeta = CryptoManager.encrypt(Gson().toJson(meta))
                        finalUpdate["encryptedMeta"] = encryptedMeta

                        // Force delete raw fields to maintain "Absolute Perfection"
                        finalUpdate["isCompleted"] = FieldValue.delete()
                        finalUpdate["timestamp"] = FieldValue.delete()
                        finalUpdate["timeSpent"] = FieldValue.delete()
                        finalUpdate["hasAnnotations"] = FieldValue.delete()
                    } catch (e: Exception) {
                        Log.e("SAVE", "Meta encryption failed")
                        finalUpdate.putAll(meta)
                    }
                } else {
                    finalUpdate.putAll(meta)
                }

                val progressMap = mapOf(paperKey to finalUpdate)
                firestore.collection("users").document(uid).set(mapOf("progress" to progressMap), SetOptions.merge()).await()
                
                // 2. Save annotated PDF locally
                saveAnnotatedPdfLocally(paperKey)
                
                Toast.makeText(this@DocumentViewerActivity, "Saved securely to cloud and local storage!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SAVE", "Failed", e)
                Toast.makeText(this@DocumentViewerActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun saveAnnotatedPdfLocally(paperKey: String) {
        withContext(Dispatchers.IO) {
            try {
                // 1. Save JSON progress (already done in parent)
                val data = binding.annotationOverlay.exportToData()
                var json = Gson().toJson(data)
                
                // Silent Scramble for local backup
                if (CryptoManager.hasCloudMasterKey()) {
                    try {
                        json = CryptoManager.encrypt(json)
                    } catch (e: Exception) {
                        Log.e("SAVE", "Failed to encrypt local backup: ${e.message}")
                    }
                }

                val storageDir = File(getExternalFilesDir(null), "AnnotatedPapers")
                if (!storageDir.exists()) storageDir.mkdirs()
                
                val jsonFile = File(storageDir, "${paperKey}_notes.json")
                jsonFile.writeText(json)
                
                // 2. Export current view as a High-Res Image (Snapshot)
                // This allows the user to see their annotations locally without a PDF editor
                val bitmap = withContext(Dispatchers.Main) {
                    val b = createBitmap(binding.main.width, binding.main.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(b)
                    binding.main.draw(canvas)
                    b
                }
                
                val imageFile = File(storageDir, "${paperKey}_snapshot.jpg")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                Log.d("SAVE", "Local snapshot saved: ${imageFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("SAVE", "Local save failed: ${e.message}")
            }
        }
    }

    private fun loadPdfWithCache(pdfUrl: String, key: String) {
        binding.loadingProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = if (pdfUrl.contains("/")) pdfUrl.substringAfterLast("/") else "$key.pdf"
                val cacheFile = File(cacheDir, fileName)
                if (!cacheFile.exists()) {
                    val client = OkHttpClient()
                    
                    // FALLBACK STRATEGY: Try direct Raw URL first, then API
                    val rawUrl = "https://raw.githubusercontent.com/${GitHubScanner.REPO_OWNER}/${GitHubScanner.REPO_NAME}/${GitHubScanner.BRANCH}/$fileName"
                    val rawRequest = okhttp3.Request.Builder()
                        .url(rawUrl)
                        .addHeader("Authorization", "Bearer ${GitHubScanner.GITHUB_TOKEN}")
                        .addHeader("User-Agent", LinkProtector.getUserAgent())
                        .build()

                    Log.d("DocumentViewer", "Attempting Raw URL: $rawUrl")

                    var success = false
                    client.newCall(rawRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.d("DocumentViewer", "Raw URL Success!")
                            response.body?.byteStream()?.use { input -> 
                                FileOutputStream(cacheFile).use { output -> input.copyTo(output) } 
                            }
                            success = true
                        } else {
                            Log.w("DocumentViewer", "Raw URL Failed (${response.code}), trying API...")
                        }
                    }

                    if (!success) {
                        val apiRequest = GitHubScanner.getAuthenticatedRequest(fileName)
                        client.newCall(apiRequest).execute().use { response ->
                            if (!response.isSuccessful) {
                                val errorBody = response.body?.string() ?: "No error body"
                                Log.e("DocumentViewer", "API Access Denied: ${response.code}")
                                Log.e("DocumentViewer", "GitHub Response: $errorBody")
                                
                                withContext(Dispatchers.Main) {
                                    val msg = when {
                                        response.code == 401 -> "GitHub rejected the token. Please regenerate your Personal Access Token."
                                        response.code == 404 -> "File not found or repository permission denied."
                                        else -> "Repository Error: ${response.code}"
                                    }
                                    Toast.makeText(this@DocumentViewerActivity, msg, Toast.LENGTH_LONG).show()
                                }
                                throw Exception("Repository Access Denied: ${response.code}")
                            }
                            response.body?.byteStream()?.use { input -> 
                                FileOutputStream(cacheFile).use { output -> input.copyTo(output) } 
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    binding.loadingProgressBar.visibility = View.GONE
                    
                    // Create content URI using FileProvider for better cross-process permissions
                    val contentUri = FileProvider.getUriForFile(
                        this@DocumentViewerActivity,
                        "com.mechanicalaxiom.igcsecloudapp.provider",
                        cacheFile
                    )
                    displayPdf(contentUri)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingProgressBar.visibility = View.GONE
                    Log.e("DocumentViewer", "Load Failed", e)
                    Toast.makeText(this@DocumentViewerActivity, "Load Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayPdf(fileUri: Uri) {
        Log.d("DocumentViewer", "displayPdf called with: $fileUri")
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
            initializePdfViewerFragment(fileUri)
        } else {
            Log.e("DocumentViewer", "Version check failed: Extension 13 not found")
            Toast.makeText(this, "Requires Android S+ (Extension 13)", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun initializePdfViewerFragment(fileUri: Uri) {
        Log.d("DocumentViewer", "Initializing Fragment...")
        try {
            // 1. Get the theme info ON THE MAIN PROCESS
            val prefs = getSharedPreferences("NoteTrailPrefs", MODE_PRIVATE)
            val isDarkMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) == AppCompatDelegate.MODE_NIGHT_YES

            var isNewFragment = false
            pdfViewerFragment = supportFragmentManager.findFragmentByTag(pdfViewerFragmentTag) as? NoteTrailPdfViewerFragment
            if (pdfViewerFragment == null) {
                pdfViewerFragment = NoteTrailPdfViewerFragment().apply {
                    arguments = Bundle().apply {
                        putBoolean("is_dark_mode", isDarkMode)
                    }
                }
                isNewFragment = true
                Log.d("DocumentViewer", "New fragment instance created with arguments")
            }
            
            // Set callbacks BEFORE commit
            pdfViewerFragment?.onDocumentLoaded = {
                Log.d("DocumentViewer", "onDocumentLoaded callback triggered")
                isPdfLoaded = true
                binding.btnAnnotate.isVisible = true
                binding.annotationOverlay.isVisible = true
                binding.annotationOverlay.isLocked = true // Start locked
                
                // AUTO-SYNC: Trigger sync immediately when PDF loads to restore existing annotations
                if (!isAnnotationSyncActive) {
                    setupAnnotationSync()
                    startRealtimeSync()
                    isAnnotationSyncActive = true
                }
            }
            
            pdfViewerFragment?.onDocumentError = { error ->
                isPdfLoaded = false
                Log.e("DocumentViewer", "Document Load Error", error)
                Toast.makeText(this, "Failed to load PDF: ${error.message}", Toast.LENGTH_LONG).show()
            }

            if (isNewFragment) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.pdf_container_view, pdfViewerFragment!!, pdfViewerFragmentTag)
                    .runOnCommit {
                        // Set URI AFTER commit is finished
                        pdfViewerFragment?.documentUri = fileUri
                        Log.d("DocumentViewer", "Document URI set onCommit: $fileUri")
                    }
                    .commit()
                Log.d("DocumentViewer", "Fragment replaced and committed")
            } else {
                pdfViewerFragment?.documentUri = fileUri
                Log.d("DocumentViewer", "Document URI set: $fileUri")
            }
            
            // Fallback: If after 5 seconds the button is still not visible, show it anyway
            binding.btnAnnotate.postDelayed({
                if (!binding.btnAnnotate.isVisible) {
                    Log.w("DocumentViewer", "Loading callback timeout - Force showing annotation button")
                    binding.btnAnnotate.isVisible = true
                    isPdfLoaded = true
                }
            }, 5000)

        } catch (e: Exception) {
            Log.e("DocumentViewer", "Fragment Init Failed", e)
            Toast.makeText(this, "PDF Viewer Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showControls() {
        animateView(binding.topControlsCard)
        animateView(binding.bottomControlsCard)
        animateView(binding.timerCard)
    }

    private fun animateView(view: View) {
        view.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun initializeTimerDuration() {
        timeRemainingMillis = 75 * 60000L
        startCountDown()
    }

    private fun startCountDown() {
        countDownTimer?.cancel()
        isTimerRunning = true
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        countDownTimer = object : CountDownTimer(timeRemainingMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemainingMillis = millisUntilFinished
                updateTimerUI()
            }
            override fun onFinish() {
                isTimerRunning = false
                updateTimerUI()
            }
        }.start()
    }

    private fun toggleTimer() {
        if (isTimerRunning) {
            countDownTimer?.cancel()
            isTimerRunning = false
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        } else {
            startCountDown()
        }
    }

    private fun updateTimerUI() {
        val h = TimeUnit.MILLISECONDS.toHours(timeRemainingMillis)
        val m = TimeUnit.MILLISECONDS.toMinutes(timeRemainingMillis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(timeRemainingMillis) % 60
        binding.timerText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    private class AnnotationsAdapter(
        private val list: List<AnnotationView.Annotation>,
        private val onClick: (AnnotationView.Annotation) -> Unit
    ) : RecyclerView.Adapter<AnnotationsAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.tv_annotation_text)
            val timestamp: TextView = view.findViewById(R.id.tv_annotation_timestamp)
            val page: TextView = view.findViewById(R.id.tv_annotation_page)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_annotation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.text.text = when (item) {
                is AnnotationView.Annotation.Text -> item.text
                is AnnotationView.Annotation.Callout -> item.text
                else -> "Annotation"
            }
            holder.timestamp.text = dateFormat.format(Date(item.timestamp))
            holder.page.text = "Page ${item.pageIdx + 1}"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }
}
