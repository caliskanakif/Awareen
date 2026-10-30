package com.andebugulin.awareen.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.andebugulin.awareen.data.AppUsageRepository
import com.andebugulin.awareen.data.ScreenTimeRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private const val KEY_DOC_PREFIX = "doc_uri_"
    private const val LISTING_WAIT_MS = 15_000L
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
            prefs(c).all.keys.filter { it.startsWith(KEY_LAST_PREFIX) || it.startsWith(KEY_DOC_PREFIX) }.forEach { remove(it) }
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

    /**
     * Synchronous variant for the "Sync now" button (call from a background thread).
     * Synchronized: two overlapping runs would both miss a file and create it twice.
     */
    @Synchronized
    fun exportNow(context: Context): String {
        val app = context.applicationContext
        val result = try {
            val tree = treeUri(app) ?: return "Sync is off"
            val root = DocumentFile.fromTreeUri(app, tree)
            if (root == null || !root.canWrite()) throw IllegalStateException("Folder not writable - choose it again")
            val dir = child(app, root, deviceId(app)) ?: throw IllegalStateException("Cannot create device folder")
            val files = DirIndex(app, dir)
            files.write("device.json", deviceJson(app).toString(2))

            val repo = ScreenTimeRepository(app)
            val appUsage = AppUsageRepository(app)
            appUsage.recordRecentDays()   // no-op without usage access
            val p = prefs(app)
            var written = 0
            for (dateKey in repo.getAnalyticsDateKeys()) {
                val date = parseDateKey(dateKey) ?: continue
                val seconds = repo.getDailyScreenTime(dateKey)
                val hourly = repo.getHourlyBreakdown(dateKey)
                val apps = appUsage.getAppUsage(dateKey)
                val sig = (seconds.toLong() * 31 + hourly.hashCode()) * 31 + apps.hashCode()
                if (p.getLong(KEY_LAST_PREFIX + dateKey, -1L) == sig) continue   // unchanged since last export
                if (seconds <= 0) continue
                val appsByName = linkedMapOf<String, Int>()
                // Two packages can share a label; the schema keys by name, so sum them.
                apps.forEach { (pkg, s) -> appUsage.appLabel(pkg).let { appsByName[it] = (appsByName[it] ?: 0) + s } }
                files.write("$date.json", dayJson(app, date, seconds, hourly, appsByName).toString(2))
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

    /** [apps] is app name → foreground seconds; empty when usage access isn't granted. */
    internal fun dayJson(
        c: Context, date: LocalDate, seconds: Int, hourly: Map<Int, Int>, apps: Map<String, Int>,
    ): JSONObject {
        val hours = JSONArray()
        for (h in 0..23) hours.put(hourly[h] ?: 0)
        val appsJson = JSONObject()
        apps.forEach { (name, s) -> appsJson.put(name, s) }
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
            .put("apps", appsJson)
    }

    private fun deviceJson(c: Context) = JSONObject()
        .put("schema", SCHEMA)
        .put("id", deviceId(c))
        .put("name", deviceName(c))
        .put("platform", "android")
        .put("source", "awareen-fork")
        .put("updatedAt", Instant.now().toString())

    // ---------------------------------------------------------------- SAF helpers

    // Cloud providers (Google Drive) allow several files with the same name, and their listing can
    // lag behind or fail while offline. A missed lookup then creates a second "2026-10-26.json" or
    // a second device folder. So every document we create is remembered and reused while it exists.

    private fun remembered(c: Context, key: String, asTree: Boolean): DocumentFile? {
        val uri = prefs(c).getString(KEY_DOC_PREFIX + key, null)?.let(Uri::parse) ?: return null
        return runCatching {
            val doc = if (asTree) DocumentFile.fromTreeUri(c, uri) else DocumentFile.fromSingleUri(c, uri)
            doc?.takeIf { it.exists() }
        }.getOrNull()
    }

    private fun remember(c: Context, key: String, doc: DocumentFile) =
        prefs(c).edit().putString(KEY_DOC_PREFIX + key, doc.uri.toString()).apply()

    /**
     * Google Drive first answers a listing from its cache, often empty, flagged EXTRA_LOADING, and
     * delivers the real one later, sometimes in several rounds. Listing too early made us create a
     * second device folder, so wait (bounded) until the provider says it is done.
     */
    private fun awaitListing(c: Context, dir: DocumentFile) {
        val uri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, DocumentsContract.getDocumentId(dir.uri))
        }.getOrNull() ?: return
        val deadline = System.currentTimeMillis() + LISTING_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val loading = runCatching {
                c.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    if (cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) != true) return@use false
                    val changed = CountDownLatch(1)
                    val observer = object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) = changed.countDown()
                    }
                    cursor.registerContentObserver(observer)
                    changed.await(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                    cursor.unregisterContentObserver(observer)
                    true
                }
            }.getOrNull() ?: false
            if (!loading) return
        }
    }

    private fun child(c: Context, parent: DocumentFile, name: String): DocumentFile? {
        remembered(c, name, asTree = true)?.takeIf { it.isDirectory }?.let { return it }
        awaitListing(c, parent)
        val dir = parent.findFile(name) ?: parent.createDirectory(name) ?: return null
        remember(c, name, dir)
        return dir
    }

    /** Lists the device folder once per export instead of once per file (findFile lists every time). */
    private class DirIndex(private val c: Context, private val dir: DocumentFile) {
        private val byName = HashMap<String, DocumentFile>().apply {
            awaitListing(c, dir)
            for (f in dir.listFiles()) f.name?.let { putIfAbsent(it, f) }
        }

        fun write(name: String, text: String) {
            val key = "${dir.name}/$name"
            val file = byName[name] ?: remembered(c, key, asTree = false)
                ?: dir.createFile("application/json", name)
                ?: throw IllegalStateException("Cannot create $name")
            byName[name] = file
            remember(c, key, file)
            // "wt" truncates; plain "w" can leave old bytes behind when the new content is shorter
            c.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Cannot open $name for writing")
        }
    }
}
