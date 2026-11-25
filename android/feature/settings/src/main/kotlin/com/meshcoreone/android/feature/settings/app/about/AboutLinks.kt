// PortedFrom: MC1/Views/Settings/Sections/AboutSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/FeedbackView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Support/Sections/SupportContactSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: the support row is the sideload variant (external GitHub Sponsors link); there is no purchase, tip, refund or restore.
package com.meshcoreone.android.feature.settings.app.about

import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings

enum class AboutDestination { SUPPORT, FEEDBACK, LICENSES }

/** A row in the About section: either an in-app destination or an external link. */
sealed interface AboutRow {
    val labelResource: Int
    data class Destination(override val labelResource: Int, val destination: AboutDestination) : AboutRow
    data class ExternalLink(override val labelResource: Int, val url: String) : AboutRow
}

object AboutLinks {
    const val WEBSITE = "https://meshcore.io"
    const val ONLINE_MAP = "https://map.meshcore.io/"
    const val GITHUB = "https://github.com/Avi0n/MeshCoreOne"
    const val PRIVACY_POLICY = "https://meshcoreone.com/privacy.html"
    const val ISSUES = "https://github.com/Avi0n/MeshCoreOne/issues"
    const val SPONSORS = "https://github.com/sponsors/Avi0n"
    const val CONTACT_EMAIL = "info@meshcoreone.com"
    const val CONTACT_MAILTO = "mailto:$CONTACT_EMAIL"

    /** Rows in display order: support, feedback, website, online map, GitHub, privacy policy. */
    val rows: List<AboutRow> = listOf(
        AboutRow.Destination(AppSettingsStrings.supportTitle, AboutDestination.SUPPORT),
        AboutRow.Destination(AppSettingsStrings.feedbackTitle, AboutDestination.FEEDBACK),
        AboutRow.ExternalLink(AppSettingsStrings.aboutWebsite, WEBSITE),
        AboutRow.ExternalLink(AppSettingsStrings.aboutOnlineMap, ONLINE_MAP),
        AboutRow.ExternalLink(AppSettingsStrings.aboutGithub, GITHUB),
        AboutRow.ExternalLink(AppSettingsStrings.aboutPrivacyPolicy, PRIVACY_POLICY),
    )

    /** Feedback contacts: GitHub issues first (preferred), then email. */
    val feedbackLinks: List<AboutRow.ExternalLink> = listOf(
        AboutRow.ExternalLink(AppSettingsStrings.feedbackGitHubLink, ISSUES),
        AboutRow.ExternalLink(AppSettingsStrings.feedbackEmailLink, CONTACT_MAILTO),
    )

    /** Support screen: sponsors link and developer email. */
    val supportLinks: List<AboutRow.ExternalLink> = listOf(
        AboutRow.ExternalLink(AppSettingsStrings.supportTitle, SPONSORS),
        AboutRow.ExternalLink(AppSettingsStrings.supportContactLink, CONTACT_MAILTO),
    )
}
