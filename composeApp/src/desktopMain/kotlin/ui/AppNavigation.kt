package com.meetingnotes.ui

/** App-level navigation destinations. */
sealed class AppDestination {
    data object Recording : AppDestination()
    data object History : AppDestination()
    data class Transcript(val meetingId: String) : AppDestination()
    data object Settings : AppDestination()
    data object Onboarding : AppDestination()
}
