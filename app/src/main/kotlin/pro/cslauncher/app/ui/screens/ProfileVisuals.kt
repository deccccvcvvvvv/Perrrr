package pro.cslauncher.app.ui.screens

import pro.cslauncher.app.ui.isOptiFine
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.profile.Profile

private val PROFILE_ICONS = setOf("grass", "chest", "bolt", "pickaxe", "world", "book", "star", "shield", "gamepad", "sun", "painting", "head")
val PROFILE_ICON_CHOICES = PROFILE_ICONS.toList()

/** Pixel icon for a profile: the user's choice, else derived from loader / OptiFine. */
fun profileIcon(p: Profile): String = when {
    p.iconKey != "grass" && p.iconKey in PROFILE_ICONS -> p.iconKey
    p.isOptiFine -> "bolt"
    p.loader?.type == LoaderType.FABRIC || p.loader?.type == LoaderType.QUILT -> "pickaxe"
    else -> "grass"
}

fun profileSubtitle(p: Profile): String = listOfNotNull(
    p.versionId,
    p.loader?.takeIf { it.type != LoaderType.VANILLA }?.let { "${it.type.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.version}" },
    if (p.isOptiFine) "OptiFine ${p.launchVersionId!!.substringAfter("-OptiFine_")}" else null,
).joinToString(" · ")
