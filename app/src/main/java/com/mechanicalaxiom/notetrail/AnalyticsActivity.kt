package com.mechanicalaxiom.notetrail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mechanicalaxiom.notetrail.ui.theme.IgcsecloudappTheme
import java.text.SimpleDateFormat
import java.util.*

class AnalyticsActivity : ComponentActivity() {
    private val engine = GradeAnalyticsEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IgcsecloudappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AnalyticsScreen(engine, IgcseDataHolder.subjects, onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(engine: GradeAnalyticsEngine, subjects: List<Subject>, onBack: () -> Unit) {
    val allGrades = remember(subjects) { 
        engine.extractGradesFromSubjects(subjects)
    }
    
    val completedGrades = remember(allGrades) { 
        allGrades.filter { it.isCompleted }.sortedBy { it.timestamp } 
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("My Grades", "Predictor")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Performance Analytics") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (selectedTab == 0) {
                MyGradesView(completedGrades, engine)
            } else {
                PredictorView(completedGrades, allGrades, engine)
            }
        }
    }
}

@Composable
fun MyGradesView(completedGrades: List<GradeEntry>, engine: GradeAnalyticsEngine) {
    val overallAverage = remember(completedGrades) { engine.calculateWeightedAverage(completedGrades) }
    val subjectAverages = remember(completedGrades) { engine.calculateSubjectAverages(completedGrades) }
    val trend = remember(completedGrades) { engine.calculateTrend(completedGrades) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        // 1. Big Hero Progress Ring Card
        item {
            CircularGradeCard(overallPercentage = overallAverage, trend = trend)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 2. Performance Trajectory Chart
        item {
            DetailedGradeTrendChart(grades = completedGrades)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 3. Section Header
        item {
            Text(
                text = "Subject Analysis",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        // 4. Empty State Handling
        if (subjectAverages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No papers solved yet. Complete some past papers in the main screen to generate analytics!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 5. List of Subject Progress Bars with Expandable Details
        items(subjectAverages.toList().sortedBy { it.second }) { (subject, average) ->
            val subjectGrades = completedGrades.filter { it.subjectName == subject }
            ExpandableSubjectCard(
                subjectName = subject, 
                percentage = average, 
                paperGrades = subjectGrades
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun PredictorView(
    completedGrades: List<GradeEntry>, 
    allGrades: List<GradeEntry>,
    engine: GradeAnalyticsEngine
) {
    val subjectsWithHistory = completedGrades.groupBy { it.subjectName }
    val subjectAverages = remember(completedGrades) { engine.calculateSubjectAverages(completedGrades) }
    val upcomingGrades = remember(allGrades) { allGrades.filter { !it.isCompleted } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Text(
                text = "🔮 Goal Planner",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Simulate your upcoming papers based on actual IGCSE blueprints.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        if (subjectsWithHistory.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Complete at least one paper to start predicting your future grades!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        items(subjectAverages.toList().sortedBy { it.second }) { (subject, average) ->
            val subjectHistory = completedGrades.filter { it.subjectName == subject }
            val subjectUpcoming = upcomingGrades.filter { it.subjectName == subject }
            
            SubjectBlueprintCard(
                subjectName = subject,
                currentAverage = average,
                completedPapers = subjectHistory,
                upcomingPapers = subjectUpcoming,
                engine = engine
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SubjectBlueprintCard(
    subjectName: String,
    currentAverage: Double,
    completedPapers: List<GradeEntry>,
    upcomingPapers: List<GradeEntry>,
    engine: GradeAnalyticsEngine
) {
    var expanded by remember { mutableStateOf(false) }
    var targetGoal by remember { mutableFloatStateOf(90f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subjectName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Current Avg: ${String.format(Locale.US, "%.1f%%", currentAverage)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = "What is your target overall grade?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = targetGoal,
                        onValueChange = { targetGoal = it },
                        valueRange = 50f..100f,
                        steps = 50
                    )
                    
                    Text(
                        text = "To hit ${targetGoal.toInt()}% overall, you need:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    val requiredScore = engine.calculateScoreNeeded(
                        completed = completedPapers,
                        upcoming = upcomingPapers,
                        targetOverall = targetGoal.toDouble()
                    )

                    upcomingPapers.take(3).forEach { paper ->
                        // Estimate weight based on IGCSE paper type
                        val weight = when {
                            paper.id.contains("qp_4") || paper.id.contains("qp_2") -> 50.0 // Theory/Extended
                            paper.id.contains("qp_1") -> 30.0 // MCQ
                            else -> 20.0 // Practical/Alt
                        }

                        PaperTargetRow(paperName = "Paper ${paper.id.substringAfterLast("_")}", weight = weight, required = requiredScore)
                    }
                    
                    if (upcomingPapers.isEmpty()) {
                        Text(
                            text = "No upcoming papers found for simulation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PaperTargetRow(paperName: String, weight: Double, required: Double) {
    val isImpossible = required > 100.0
    val color = if (isImpossible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = paperName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(text = "Weight: ${weight.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = if (isImpossible) "Impossible" else String.format(Locale.US, "%.1f%%", required),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun ExpandableSubjectCard(
    subjectName: String,
    percentage: Double,
    paperGrades: List<GradeEntry>
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) 
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subjectName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (expanded) "Tap to collapse" else "Tap for breakdown",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f%%", percentage),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = if (percentage >= 85) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val animatedProgress = animateFloatAsState(
                targetValue = (percentage / 100f).toFloat().coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 800),
                label = "barAnimation"
            )
            
            LinearProgressIndicator(
                progress = { animatedProgress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = when {
                    percentage >= 85 -> Color(0xFF4CAF50)
                    percentage >= 70 -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                },
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    Text(
                        text = "Paper Breakdown",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    paperGrades.forEach { grade ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Paper ${grade.id.substringAfterLast("_")}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${grade.scoreAchieved.toInt()}/${grade.maxScore.toInt()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = " (${String.format(Locale.US, "%.1f", grade.percentage)}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(60.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailedGradeTrendChart(
    grades: List<GradeEntry>,
    modifier: Modifier = Modifier
) {
    if (grades.size < 2) return
    
    val sortedGrades = remember(grades) { grades.sortedBy { it.timestamp } }
    val primaryColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Academic Trajectory",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Recent score fluctuations",
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    
                    val maxGrade = sortedGrades.maxOf { it.percentage }.toFloat()
                    val minGrade = sortedGrades.minOf { it.percentage }.toFloat()

                    val yUpperBounds = (maxGrade + 10f).coerceAtMost(100f)
                    val yLowerBounds = (minGrade - 10f).coerceAtLeast(0f)
                    val yRange = (yUpperBounds - yLowerBounds).coerceAtLeast(1f)

                    val gridLines = 4
                    for (i in 0..gridLines) {
                        val y = height - (i.toFloat() / gridLines * height)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val spacingX = width / (sortedGrades.size - 1)
                    val points = sortedGrades.mapIndexed { index, grade ->
                        val x = index * spacingX
                        val fraction = (grade.percentage.toFloat() - yLowerBounds) / yRange
                        val y = height - (fraction * height)
                        Offset(x, y)
                    }
                    
                    val fillPath = Path().apply {
                        moveTo(points.first().x, height)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, height)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(primaryColor.copy(alpha = 0.3f), Color.Transparent)
                        )
                    )

                    val strokePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }
                    drawPath(
                        path = strokePath,
                        color = primaryColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    
                    points.forEach { point ->
                        drawCircle(
                            color = Color.White,
                            radius = 6.dp.toPx(),
                            center = point
                        )
                        drawCircle(
                            color = highlightColor,
                            radius = 4.dp.toPx(),
                            center = point,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val sdf = SimpleDateFormat("MMM dd", Locale.US)
                Text(text = sdf.format(Date(sortedGrades.first().timestamp)), style = MaterialTheme.typography.labelSmall, color = textColor)
                Text(text = sdf.format(Date(sortedGrades.last().timestamp)), style = MaterialTheme.typography.labelSmall, color = textColor)
            }
        }
    }
}

@Composable
fun CircularGradeCard(
    overallPercentage: Double,
    trend: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Overall Performance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Box(contentAlignment = Alignment.Center) {
                val animatedProgress = animateFloatAsState(
                    targetValue = (overallPercentage / 100f).toFloat().coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
                    label = "progressAnimation"
                )
                
                val trackColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
                val primaryGradient = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                
                Canvas(modifier = Modifier.size(180.dp)) {
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                    )
                    
                    drawArc(
                        brush = Brush.linearGradient(primaryGradient),
                        startAngle = -90f,
                        sweepAngle = animatedProgress.value * 360f,
                        useCenter = false,
                        style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format(Locale.US, "%.1f%%", overallPercentage),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            overallPercentage >= 90 -> "Excellent"
                            overallPercentage >= 75 -> "Good Stand"
                            overallPercentage > 0 -> "Needs Focus"
                            else -> "No Data"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            if (trend != 0.0) {
                val trendText = if (trend > 0) "Improving by ${String.format(Locale.US, "%.1f", trend)}%" else "Slipping by ${String.format(Locale.US, "%.1f", -trend)}%"
                val trendColor = if (trend > 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                Text(
                    text = trendText,
                    color = trendColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
