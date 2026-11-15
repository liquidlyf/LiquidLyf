package io.github.liquidlyf.feature.about

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher

/** "Version 1.0.0", plus the flavour on test builds so a screenshot shows which build it was. */
internal fun versionLabel(): String =
    if (BuildConfig.FLAVOR == "prod") BuildConfig.VERSION_NAME else "${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}"

/**
 * Phase 8.1, Settings → About. It also carries the AGPL "Appropriate Legal Notices": the
 * copyright, the licence and no-warranty line, the source-code link (section 13) and the
 * "Based on LiquidLyf" credit that forks must keep (LICENSING.md, section 7(b)).
 */
@Composable
fun AboutRoute(
    onBack: () -> Unit,
    onOpenMedicalDisclaimer: () -> Unit,
    onOpenHowMatchingWorks: () -> Unit,
    onOpenLicences: () -> Unit,
) {
    val links = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    val version = versionLabel()
    val contactSubject = stringResource(R.string.app_name)
    val feedbackSubject = stringResource(R.string.about_feedback_subject)
    val feedbackBody = stringResource(R.string.about_feedback_body, version, Build.VERSION.RELEASE)
    AboutScreen(
        version = version,
        onBack = onBack,
        onOpenPrivacy = { links.openUrl(Links.privacy(locale)) },
        onOpenTerms = { links.openUrl(Links.terms(locale)) },
        onOpenDeletionPage = { links.openUrl(Links.deleteAccount(locale)) },
        onOpenMedicalDisclaimer = onOpenMedicalDisclaimer,
        onOpenHowMatchingWorks = onOpenHowMatchingWorks,
        onOpenLicences = onOpenLicences,
        onOpenSourceCode = { links.openUrl(Links.SOURCE_CODE) },
        onContact = { links.email(Links.SUPPORT_EMAIL, contactSubject) },
        onSendFeedback = { links.email(Links.SUPPORT_EMAIL, feedbackSubject, feedbackBody) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenDeletionPage: () -> Unit,
    onOpenMedicalDisclaimer: () -> Unit,
    onOpenHowMatchingWorks: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onContact: () -> Unit,
    onSendFeedback: () -> Unit,
) {
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.title_about), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val gutter = screenGutter()
            Column(Modifier.padding(horizontal = gutter, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(LLIcons.BloodDrop), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                }
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge)
            }
            LinkRow(R.string.title_how_matching_works, onClick = onOpenHowMatchingWorks)
            LinkRow(R.string.title_medical_disclaimer, onClick = onOpenMedicalDisclaimer)
            LinkRow(R.string.about_privacy, onClick = onOpenPrivacy)
            LinkRow(R.string.about_terms, onClick = onOpenTerms)
            LinkRow(R.string.about_deletion_page, R.string.about_deletion_page_summary, onClick = onOpenDeletionPage)
            HorizontalDivider(Modifier.padding(vertical = Spacing.xs))
            LinkRow(R.string.about_contact, R.string.about_contact_summary, onClick = onContact)
            LinkRow(R.string.about_feedback, onClick = onSendFeedback)
            HorizontalDivider(Modifier.padding(vertical = Spacing.xs))
            LinkRow(R.string.about_source_code, R.string.about_source_code_summary, onClick = onOpenSourceCode)
            LinkRow(R.string.title_licences, onClick = onOpenLicences)
            // The Appropriate Legal Notices (AGPL section 0 and 7(b)).
            Column(Modifier.padding(horizontal = gutter, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val small = MaterialTheme.typography.bodySmall
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.about_legal_copyright), style = small, color = muted)
                Text(stringResource(R.string.about_legal_licence), style = small, color = muted)
                Text(stringResource(R.string.about_based_on), style = small, color = muted)
                Text(stringResource(R.string.about_trademark), style = small, color = muted)
                Text(stringResource(R.string.about_area_credit), style = small, color = muted)
            }
        }
    }
}

@Composable
private fun LinkRow(@StringRes title: Int, @StringRes summary: Int? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** A page of headed paragraphs: the medical disclaimer and "How matching works". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextPage(@StringRes title: Int, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { LLTopBar(title = stringResource(title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

@Composable
private fun Section(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 8.3: the full disclaimer (research/play-store-compliance.md §2.11). */
@Composable
fun MedicalDisclaimerScreen(onBack: () -> Unit) {
    TextPage(R.string.title_medical_disclaimer, onBack) {
        Text(stringResource(R.string.medical_disclaimer_full), style = MaterialTheme.typography.bodyLarge)
    }
}

/** 8.1 Safety: who is alerted, and when a phone number is shared. Kept true to the Worker's tiers (doc 06 §4.3). */
@Composable
fun HowMatchingWorksScreen(onBack: () -> Unit) {
    TextPage(R.string.title_how_matching_works, onBack) {
        Text(stringResource(R.string.matching_intro), style = MaterialTheme.typography.bodyLarge)
        MATCHING.forEach { (icon, title, body) -> Section(icon, title, body) }
    }
}

private val MATCHING = listOf(
    Triple(LLIcons.BloodDrop, R.string.matching_who_title, R.string.matching_who_body),
    Triple(LLIcons.Location, R.string.matching_nearest_title, R.string.matching_nearest_body),
    Triple(LLIcons.Notifications, R.string.matching_limits_title, R.string.matching_limits_body),
    Triple(LLIcons.Donors, R.string.matching_find_title, R.string.matching_find_body),
    Triple(LLIcons.Shield, R.string.matching_contact_title, R.string.matching_contact_body),
    Triple(LLIcons.Schedule, R.string.matching_pause_title, R.string.matching_pause_body),
)

/**
 * Third-party notices: the libraries (AboutLibraries reads them from the build) and, first, the
 * area data, which isn't a library (bd-geo.json: BBS via OCHA HDX, CC BY-IGO; Bangla names MIT).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.title_licences), onBack = onBack) }) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            header = {
                item(key = "area-data") {
                    Column(
                        Modifier.padding(horizontal = screenGutter(), vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(stringResource(R.string.licences_area_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.licences_area_hdx), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.licences_area_bn), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.licence_mit_bangladesh_geojson),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            },
        )
    }
}
