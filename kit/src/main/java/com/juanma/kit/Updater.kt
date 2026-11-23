package com.juanma.kit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates from GitHub Releases of a public repo ([repo] is "owner/name"), so
 * neither the release list nor the APK needs a token. A release is tagged
 * v<N> where N is the versionCode (tools/release.sh); a tag above the
 * installed one is offered. Android never installs silently: the user
 * confirms, and it only accepts an APK signed with the same key as the
 * installed one.
 *
 * The download reports its progress, is written whole or not at all, and is
 * kept: if the installer cannot open (the app left the screen while it
 * downloaded, and Android blocks opening screens from the background) the
 * next tap opens it without downloading again.
 *
 * The app's manifest needs REQUEST_INSTALL_PACKAGES and a FileProvider with
 * authority "<applicationId>.fileprovider" exposing cache-path "update/".
 */
class Updater(private val repo: String) {
    class Release(val version: Long, val apkUrl: String)

    sealed interface State {
        /** The settings page to allow installs was opened; tap again after. */
        object NeedsPermission : State
        /** [percent] of the APK so far, or -1 while the size is unknown. */
        class Downloading(val percent: Int) : State
        /** The APK is complete on disk: [openInstaller] shows Android's dialog. */
        object Ready : State
        object Failed : State
    }

    @Volatile private var downloading = false

    fun installedVersion(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (e: Exception) { 0L }

    /** Asks GitHub in the background; [onNewer] runs on the main thread, only for a newer release. */
    fun check(activity: Activity, onNewer: (Release) -> Unit) {
        val installed = installedVersion(activity)
        thread(name = "update-check") {
            val release = try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "android-app")  // GitHub refuses requests without one
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                try {
                    if (conn.responseCode !in 200..299) null
                    else parse(conn.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null  // offline, or no release yet
            }
            if (release != null && release.version > installed) activity.runOnUiThread { onNewer(release) }
        }
    }

    fun isDownloading() = downloading

    /** True once [release]'s APK is complete on disk. */
    fun isDownloaded(context: Context, release: Release) = apkFile(context, release).isFile

    /**
     * Downloads the APK (or finds it already downloaded) and says so through
     * [onState], on the main thread. A tap while a download runs is ignored.
     */
    fun install(activity: Activity, release: Release, onState: (State) -> Unit) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            onState(State.NeedsPermission)
            runCatching {
                activity.startActivity(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return
        }
        val file = apkFile(activity, release)
        if (file.isFile) {
            onState(State.Ready)
            return
        }
        if (downloading) return
        downloading = true
        onState(State.Downloading(-1))
        thread(name = "update") {
            val ok = try {
                // Older versions' APKs and a half download from a killed app go.
                file.parentFile?.listFiles()?.forEach { it.delete() }
                download(release.apkUrl, file) { percent ->
                    activity.runOnUiThread { onState(State.Downloading(percent)) }
                }
                true
            } catch (e: Exception) {
                Log.w("Updater", "download of v${release.version} failed", e)
                false
            } finally {
                downloading = false
            }
            activity.runOnUiThread { onState(if (ok) State.Ready else State.Failed) }
        }
    }

    /** Shows Android's install dialog for a downloaded APK; false if it could not open. */
    fun openInstaller(activity: Activity, release: Release): Boolean = try {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile(activity, release))
        activity.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w("Updater", "could not open the installer", e)
        false
    }

    private fun apkFile(context: Context, release: Release) =
        File(File(context.cacheDir, "update"), "update-v${release.version}.apk")

    companion object {
        /** Pure: what a "latest release" answer offers, or null. Tested without a device. */
        fun parse(json: String): Release? {
            val release = try { JSONObject(json) } catch (e: Exception) { return null }
            val version = release.optString("tag_name").trimStart('v', 'V').toLongOrNull() ?: return null
            val assets = release.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url")
                if (asset.optString("name").endsWith(".apk") && url.startsWith("https://")) {
                    return Release(version, url)
                }
            }
            return null
        }

        /**
         * Downloads [url] to [target], following redirects, calling [onProgress]
         * with each new whole percent (-1 if the server gives no size). Written
         * to a ".part" file renamed only when complete, so [target] either holds
         * the whole file or does not exist. A server silent for 20 s, an error
         * status or a short body throws. Tested without a device.
         */
        fun download(url: String, target: File, onProgress: (Int) -> Unit) {
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var shown = Int.MIN_VALUE
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != shown) {
                                shown = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("got $done of $total bytes")
                if (!part.renameTo(target)) throw IOException("could not keep $target")
            } catch (e: Exception) {
                part.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }
}
