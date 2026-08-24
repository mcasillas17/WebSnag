package org.websnag.ui.navigation

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Profiles : Screen("profiles")
    data object ProfileEditor : Screen("profile_editor?profileId={profileId}") {
        fun createRoute(profileId: String? = null) = "profile_editor?profileId=${profileId ?: "new"}"
    }
    data object Tags : Screen("tags")
    data object EnrollTag : Screen("enroll_tag")
    data object Setup : Screen("setup")
}
