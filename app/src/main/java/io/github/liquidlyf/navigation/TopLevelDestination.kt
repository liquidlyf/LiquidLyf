package io.github.liquidlyf.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons

/** The four bottom-navigation tabs, in order. Each has its own back stack. */
enum class TopLevelDestination(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
    /** The tab's nested graph, navigated to when the tab is selected. */
    val graph: Any,
    /** The first screen of the tab. */
    val root: Any,
) {
    HOME(R.string.nav_home, LLIcons.Home, LLIcons.HomeFilled, HomeGraph, Home),
    REQUESTS(R.string.nav_requests, LLIcons.Requests, LLIcons.RequestsFilled, RequestsGraph, Requests),
    DONORS(R.string.nav_donors_tab, LLIcons.Donors, LLIcons.DonorsFilled, DonorsGraph, Donors),
    PROFILE(R.string.nav_profile, LLIcons.Profile, LLIcons.ProfileFilled, ProfileGraph, Profile),
    ;

    companion object {
        /** The tab with this [name], or null for unknown or missing names (e.g. from an older version). */
        fun fromName(name: String?): TopLevelDestination? = entries.firstOrNull { it.name == name }
    }
}
