package com.mechanicalaxiom.notetrail

data class IgcseVariant(
    val variant: String
)

data class IgcsePaperType(
    val type: String,
    val variants: MutableList<IgcseVariant>
)

data class IgcseYear(
    val year: String,
    val types: MutableList<IgcsePaperType>
)

data class IgcseSubject(
    val name: String,
    val code: String,
    val years: MutableList<IgcseYear>
)
