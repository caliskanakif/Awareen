package com.andebugulin.awareen.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.andebugulin.awareen.sync.ScreenTimeSyncExporter as Sync

/** Minimal, code-built screen (no XML) to choose the sync folder and trigger a sync. */
class SyncSettingsActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var folderView: TextView

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Sync.setTree(this, uri)
            refresh()
            syncNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun text(s: String) = TextView(this).apply { text = s; setPadding(0, pad / 2, 0, pad / 2) }
        fun btn(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }

        col.addView(text("Sync screen time to a folder (e.g. for Google Drive). Awareen writes one small file per day. " +
            "Nothing is sent anywhere by Awareen itself; a sync app or Drive uploads the folder."))
        val name = EditText(this).apply {
            setText(Sync.deviceName(this@SyncSettingsActivity)); hint = "Device name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(text("Device name")); col.addView(name)
        folderView = text(""); col.addView(folderView)
        col.addView(btn("Choose folder") { Sync.setDeviceName(this, name.text.toString()); pickFolder.launch(null) })
        col.addView(btn("Sync now") { Sync.setDeviceName(this, name.text.toString()); syncNow() })
        col.addView(btn("Turn sync off") { Sync.setTree(this, null); refresh() })
        statusView = text(""); col.addView(statusView)
        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refresh()
    }

    private fun refresh() {
        folderView.text = "Folder: " + (Sync.treeUri(this)?.toString() ?: "not set (sync is off)")
        statusView.text = Sync.status(this)
    }

    private fun syncNow() {
        statusView.text = "Syncing..."
        Thread {
            Sync.exportNow(this)
            runOnUiThread { refresh() }
        }.start()
    }
}
