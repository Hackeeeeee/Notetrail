package com.mechanicalaxiom.notetrail

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName

// The Group object saved in Firestore
data class Group(
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val pin: String? = null,
    @get:PropertyName("private") @set:PropertyName("private") var isPrivate: Boolean = false,
    val dailyGoal: Int = 10,
    @get:Exclude val leaderboard: List<LeaderboardEntry> = emptyList() // Nested for automatic diffing
)

// The entry calculated for the Leaderboard
data class LeaderboardEntry(
    val userId: String = "",
    val userName: String = "",
    val averageScore: Double = 0.0,
    val papersSolved: Int = 0,
    val papersSolvedToday: Int = 0,
    var rank: Int = 0,
    var badge: String = "",
    val isCurrentUser: Boolean = false,
    val lastActive: Long = 0L
)

data class PaperComparison(
    val paperId: String,
    val paperName: String,
    val groupAverage: Double,
    val userAverage: Double,
    val totalAttempts: Int
)

data class SubjectComparison(
    val subjectCode: String,
    val groupAverage: Double,
    val userAverage: Double,
    val papers: List<PaperComparison>
)

// State management for the UI
sealed class GroupState {
    object Loading : GroupState()
    data class InGroups(val groups: List<Group>, val leaderboard: Map<String, List<LeaderboardEntry>>) : GroupState()
    object NoGroup : GroupState()
    data class Error(val message: String) : GroupState()
}