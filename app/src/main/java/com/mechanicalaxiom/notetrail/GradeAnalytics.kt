package com.mechanicalaxiom.notetrail

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class GradeEntry(
    val id: String = "",
    val subjectName: String = "",
    val scoreAchieved: Double = 0.0,
    val maxScore: Double = 100.0,
    val weight: Double = 1.0, // e.g., 20.0 for 20% of the total grade
    val timestamp: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = true
) {
    // Helper to get the absolute percentage of this individual assignment
    val percentage: Double
        get() = if (maxScore > 0) (scoreAchieved / maxScore) * 100 else 0.0
}

class GradeAnalyticsEngine {

    // 1. Calculate the overall running weighted average
    fun calculateWeightedAverage(grades: List<GradeEntry>): Double {
        if (grades.isEmpty()) return 0.0
        
        var totalWeightedScores = 0.0
        var totalWeights = 0.0
        
        for (grade in grades) {
            // (Percentage of assignment * its weight)
            totalWeightedScores += (grade.percentage * grade.weight)
            totalWeights += grade.weight
        }
        
        return if (totalWeights > 0) totalWeightedScores / totalWeights else 0.0
    }

    // 2. Group by subject to find performance per class
    fun calculateSubjectAverages(grades: List<GradeEntry>): Map<String, Double> {
        return grades.groupBy { it.subjectName.trim() }
            .mapValues { (_, subjectGrades) ->
                calculateWeightedAverage(subjectGrades)
            }
    }

    // 3. Velocity / Trend Line: Compare last 3 grades against overall average
    fun calculateTrend(grades: List<GradeEntry>): Double {
        if (grades.size < 2) return 0.0
        val overall = calculateWeightedAverage(grades)
        val recent = calculateWeightedAverage(grades.takeLast(3))
        return recent - overall
    }

    // 4. Convert IGCSE Subjects to GradeEntries for unified analytics
    fun extractGradesFromSubjects(subjects: List<Subject>): List<GradeEntry> {
        val grades = mutableListOf<GradeEntry>()
        subjects.forEach { subject ->
            subject.yearGroups.forEach { yearGroup ->
                yearGroup.sessions.forEach { session ->
                    session.variants.forEach { paper ->
                        val paperId = paper.paperKey
                        val weight = when {
                            paperId.contains("qp_4") || paperId.contains("qp_2") -> 50.0 // Theory/Extended
                            paperId.contains("qp_1") -> 30.0 // MCQ
                            else -> 20.0 // Practical/Alt
                        }
                        
                        grades.add(
                            GradeEntry(
                                id = paperId,
                                subjectName = subject.name,
                                scoreAchieved = paper.marksObtained?.toDouble() ?: 0.0,
                                maxScore = paper.totalMarks?.toDouble() ?: 100.0,
                                weight = weight,
                                timestamp = paper.timestamp ?: 0L,
                                isCompleted = paper.isCompleted
                            )
                        )
                    }
                }
            }
        }
        return grades
    }

    // 5. Predictor Logic: Calculate required score for ALL upcoming papers to hit a target
    fun calculateScoreNeeded(
        completed: List<GradeEntry>,
        upcoming: List<GradeEntry>,
        targetOverall: Double
    ): Double {
        if (upcoming.isEmpty()) return 0.0

        // 1. Group completed papers by their component key (SubjectCode/PaperCode/Variant)
        // Extract the common part of the ID (e.g., "0625_m_22_qp_42" -> "qp_42")
        // This allows averaging multiple attempts of the SAME component type across sessions
        val completedGrouped = completed.groupBy { it.id.substringAfterLast("_") }

        var earnedPoints = 0.0
        completedGrouped.forEach { (component, grades) ->
            val avgPercentage = grades.map { it.percentage }.average()
            val weight = grades.first().weight
            earnedPoints += (avgPercentage * (weight / 100.0))
        }

        // 2. Identify unique upcoming paper components and their total weight
        val upcomingGrouped = upcoming.groupBy { it.id.substringAfterLast("_") }

        var totalUpcomingWeight = 0.0
        upcomingGrouped.forEach { (component, _) ->
            // Use the same weight estimation as extractGradesFromSubjects
            val weight = when {
                component.startsWith("4") || component.startsWith("2") -> 50.0 // Theory/Extended
                component.startsWith("1") -> 30.0 // MCQ
                else -> 20.0 // Practical/Alt
            }
            totalUpcomingWeight += weight
        }

        if (totalUpcomingWeight <= 0) return 0.0

        // 3. Calculate required average percentage across all upcoming papers
        // targetOverall = earnedPoints + (Required% * (totalUpcomingWeight / 100.0))
        val neededContribution = targetOverall - earnedPoints
        val requiredPercentage = (neededContribution / (totalUpcomingWeight / 100.0))
        
        return requiredPercentage
    }
}

class GradeRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    fun streamUserGrades(userId: String): Flow<List<GradeEntry>> = callbackFlow {
        val listener = db.collection("users")
            .document(userId)
            .collection("grades")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                
                val gradesList = snapshot?.documents?.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val encryptedMeta = data["encryptedMeta"] as? String
                    
                    if (encryptedMeta != null) {
                        try {
                            val decryptedJson = CryptoManager.decrypt(encryptedMeta)
                            val grade = com.google.gson.Gson().fromJson(decryptedJson, GradeEntry::class.java)
                            grade.copy(id = doc.id)
                        } catch (e: Exception) {
                            Log.e("GradeRepository", "Failed to decrypt grade: ${e.message}")
                            null
                        }
                    } else {
                        // Legacy fallback
                        doc.toObject(GradeEntry::class.java)?.copy(id = doc.id)
                    }
                } ?: emptyList()
                
                trySend(gradesList)
            }
            
        awaitClose { listener.remove() }
    }

    fun addGrade(userId: String, grade: GradeEntry) {
        val json = com.google.gson.Gson().toJson(grade)
        val encryptedMeta = CryptoManager.encrypt(json)
        
        val data = mapOf(
            "encryptedMeta" to encryptedMeta,
            "timestamp" to grade.timestamp // Keep timestamp for server-side ordering if needed, 
                                          // but it's also inside encryptedMeta
        )

        db.collection("users")
            .document(userId)
            .collection("grades")
            .add(data)
    }
}
