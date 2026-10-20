package com.andebugulin.awareen.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.andebugulin.awareen.data.ScreenTimeRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional export of the daily screen-time history into a folder the user picks
 * (Storage Access Framework). One small JSON file per day, schema v1:
 *
 *     <folder>/<deviceId>/device.json
 *     <folder>/<deviceId>/yyyy-MM-dd.json
 *
 * Point the folder at a location that a sync app uploads to Google Drive.
 * Awareen itself needs no network permission and no Google login.
 *
 * Purely additive: reads through [ScreenTimeRepository] and never writes to Awareen's own storage.
 * Disabled until the user chooses a folder. Day files are whole-day snapshots, so exporting the
 * same day twice can never double count.
 */
object ScreenTimeSyncExporter {

    private const val PREFS = "awareen_sync"           // separate from Awareen's own prefs on purpose
    private const val KEY_TREE = "tree_uri"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_STATUS = "last_status"
    private const val KEY_LAST_PREFIX = "last_seconds_"
    private const val SCHEMA = 1

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    // ---------------------------------------------------------------- config

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(c: Context): Uri? = prefs(c).getString(KEY_TREE, null)?.let(Uri::parse)
    fun isEnabled(c: Context) = treeUri(c) != null
    fun status(c: Context): String = prefs(c).getString(KEY_STATUS, "Not synced yet") ?: ""

    fun deviceId(c: Context): String {
        val p = prefs(c)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = "android-" + UUID.randomUUID().toString().replace("-", "").take(8)
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun deviceName(c: Context): String =
        prefs(c).getString(KEY_DEVICE_NAME, null) ?: android.os.Build.MODEL ?: "Android"

    fun setDeviceName(c: Context, name: String) =
        prefs(c).edit().putString(KEY_DEVICE_NAME, name.trim()).apply()

    /** Call with the result of ACTION_OPEN_DOCUMENT_TREE (after taking the persistable permission). */
    fun setTree(c: Context, uri: Uri?) {
        prefs(c).edit().apply {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
            // forget what was exported so a new folder receives the whole history
            prefs(c).all.keys.filter { it.startsWith(KEY_LAST_PREFIX) }.forEach { remove(it) }
        }.apply()
    }

    // ---------------------------------------------------------------- export

    /** Cheap to call often: does nothing if disabled or if an export is already running. */
    fun requestExport(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { exportNow(app) } finally { running.set(false) }
        }
    }

    /** Synchronous variant for the "Sync now" button (call from a background thread). */
    fun exportNow(context: Context): String {
        val app = context.applicationContext
        val result = try {
            val tree = treeUri(app) ?: return "Sync is off"
            val root = DocumentFile.fromTreeUri(app, tree)
            if (root == null || !root.canWrite()) throw IllegalStateException("Folder not writable - choose it again")
            val dir = child(root, deviceId(app)) ?: throw IllegalStateException("Cannot create device folder")
            writeText(app, dir, "device.json", deviceJson(app).toString(2))

            val repo = ScreenTimeRepository(app)
            val p = prefs(app)
            var written = 0
            for (dateKey in repo.getAnalyticsDateKeys()) {
                val date = parseDateKey(dateKey) ?: continue
                val seconds = repo.getDailyScreenTime(dateKey)
                val hourly = repo.getHourlyBreakdown(dateKey)
                val sig = seconds.toLong() * 31 + hourly.hashCode()
                if (p.getLong(KEY_LAST_PREFIX + dateKey, -1L) == sig) continue   // unchanged since last export
                if (seconds <= 0) continue
                writeText(app, dir, "$date.json", dayJson(app, date, seconds, hourly).toString(2))
                p.edit().putLong(KEY_LAST_PREFIX + dateKey, sig).apply()
                written++
            }
            "Last sync ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}: $written day file(s) written"
        } catch (e: Exception) {
            "Sync failed (will retry): ${e.message}"
        }
        prefs(app).edit().putString(KEY_STATUS, result).apply()
        return result
    }

    // ---------------------------------------------------------------- format

    /** Awareen keys are analytics_YEAR_MONTH_DAY with a 0-based month (see AnalyticsKeys). */
    internal fun parseDateKey(key: String): LocalDate? {
        val parts = key.removePrefix("analytics_").split("_")
        if (parts.size != 3) return null
        val (y, m0, d) = parts.map { it.toIntOrNull() ?: return null }
        return try { LocalDate.of(y, m0 + 1, d) } catch (e: Exception) { null }
    }

    internal fun dayJson(c: Context, date: LocalDate, seconds: Int, hourly: Map<Int, Int>): JSONObject {
        val hours = JSONArray()
        for (h in 0..23) hours.put(hourly[h] ?: 0)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("deviceId", deviceId(c))
            .put("date", date.toString())
            .put("timeZone", ZoneId.systemDefault().id)
            .put("updatedAt", Instant.now().toString())
            .put("kind", "measured")
            .put("measurement", JSONObject().put("method", "awareen-screen-on"))
            .put("totalSeconds", seconds)
            .put("hours", hours)
            .put("apps", JSONObject())          // Awareen records no per-app data
    }

    private fun deviceJson(c: Context) = JSONObject()
        .put("schema", SCHEMA)
        .put("id", deviceId(c))
        .put("name", deviceName(c))
        .put("platform", "android")
        .put("source", "awareen-fork")
        .put("updatedAt", Instant.now().toString())

    // ---------------------------------------------------------------- SAF helpers

    private fun child(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name) ?: parent.createDirectory(name)

    private fun writeText(c: Context, dir: DocumentFile, name: String, text: String) {
        val existing = dir.findFile(name)
        val file = existing ?: dir.createFile("application/json", name)
            ?: throw IllegalStateException("Cannot create $name")
        // "wt" truncates; plain "w" can leave old bytes behind when the new content is shorter
        c.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Cannot open $name for writing")
    }
}
