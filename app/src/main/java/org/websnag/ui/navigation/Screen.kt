package org.websnag.ui.navigation

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Schedule : Screen("schedule")
    data object ScheduleEditor : Screen("schedule_editor/{scheduleId}") {
        fun createRoute(scheduleId: String? = null) = "schedule_editor/${scheduleId ?: "new"}"
    }
    data object Activity : Screen("activity")
    data object Profiles : Screen("profiles")
    data object ProfileEditor : Screen("profile_editor/{profileId}") {
        fun createRoute(profileId: String? = null) = "profile_editor/${profileId ?: "new"}"
    }
    data object Tags : Screen("tags")
    data object EnrollTag : Screen("enroll_tag")
    data object Setup : Screen("setup")
}
