package com.mechanicalaxiom.notetrail

data class PaperVariant(
    val variantCode: String,
    val paperType: String,
    val paperKey: String,
    val fileUrl: String,
    val msUrl: String,
    var marksObtained: Int? = null,
    var totalMarks: Int? = null,
    var isCompleted: Boolean = false,
    var notes: String? = null,
    var timestamp: Long? = null
)

data class SessionGroup(
    val sessionCode: String,
    val variants: List<PaperVariant>
)

data class YearGroup(
    val year: Int,
    val sessions: List<SessionGroup>
)

data class Subject(
    val name: String,
    val code: String,
    val yearGroups: List<YearGroup>,
    var isIncludedInTotal: Boolean = false
)
