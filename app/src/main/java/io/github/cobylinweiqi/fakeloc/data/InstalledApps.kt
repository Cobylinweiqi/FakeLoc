package io.github.cobylinweiqi.fakeloc.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One selectable entry in the target-app picker. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
) {
    /** Pre-computed so the search filter never allocates per keystroke. */
    val searchKey: String = (label + '\u0000' + packageName).lowercase()
}

/**
 * Enumerates installed apps for the target picker.
 *
 * Only apps with a launcher entry are listed. `getInstalledApplications` also
 * returns hundreds of invisible components — providers, service-only packages,
 * shared libraries — and surfacing those would bury the handful of apps a user
 * actually wants to spoof. Packages that are already selected are still kept in
 * the list even if they have no launcher entry, so a previously-saved choice can
 * always be deselected.
 */
object InstalledApps {

    suspend fun load(context: Context, keepPackages: Set<String> = emptySet()): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName

            val launchable = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != self }
                .filter { info ->
                    pm.getLaunchIntentForPackage(info.packageName) != null ||
                        keepPackages.contains(info.packageName)
                }
                .map { info ->
                    AppEntry(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()

            launchable
        }
}
