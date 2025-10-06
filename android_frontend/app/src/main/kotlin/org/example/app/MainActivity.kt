package org.example.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.stt.ModelManager

/**
 * PUBLIC_INTERFACE
 * MainActivity is the single-activity entry point that sets up the Ocean Professional themed UI
 * with actions to import a model, select a video, and start processing.
 * Implements model import via SAF (zip or directory) into app-internal storage.
 */
class MainActivity : Activity() {

    private lateinit var btnImportModel: Button
    private lateinit var btnSelectVideo: Button
    private lateinit var btnStartProcessing: Button
    private lateinit var tvModelStatus: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var lvStatus: ListView
    private lateinit var progress: ProgressBar

    // In-memory flags for enabling the Start button
    private var modelSelected: Boolean = false
    private var videoSelected: Boolean = false

    // List adapter
    private lateinit var statusAdapter: ArrayAdapter<String>
    private val statusItems = mutableListOf<String>()

    // Coroutines
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // Legacy request codes for Activity-based result handling
    private val REQ_OPEN_DOCUMENT = 1001
    private val REQ_OPEN_DOCUMENT_TREE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply layout
        setContentView(R.layout.activity_main)

        // Initialize views
        btnImportModel = findViewById(R.id.btnImportModel)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnStartProcessing = findViewById(R.id.btnStartProcessing)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        lvStatus = findViewById(R.id.lvStatus)
        progress = findViewById(R.id.progress)

        // Setup ListView adapter
        statusAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, statusItems)
        lvStatus.adapter = statusAdapter

        // Handlers
        btnImportModel.setOnClickListener {
            showModelPicker()
        }

        btnSelectVideo.setOnClickListener {
            // TODO: Implement video selection (file picker) in subsequent step
            videoSelected = true
            tvVideoStatus.text = getString(R.string.label_selected_video, "example-video.mp4")
            addStatus("Video selected: example-video.mp4")
            updateStartButtonState()
        }

        btnStartProcessing.setOnClickListener {
            // TODO: Trigger processing: extract audio, transcribe offline, generate SRT
            progress.isVisible = true
            addStatus("Processing started…")
            // TODO: Once processing completes or fails, hide progress and update status list
        }

        // Check existing model on startup
        uiScope.launch {
            progress.isVisible = true
            val hasModel = ModelManager.hasValidModel(this@MainActivity)
            modelSelected = hasModel
            val name = if (hasModel) ModelManager.getCurrentModelName(this@MainActivity) else null
            tvModelStatus.text = if (hasModel && name != null) {
                getString(R.string.label_selected_model, name)
            } else {
                getString(R.string.label_no_model)
            }
            progress.isVisible = false
            updateStartButtonState()
        }

        // Ensure start button is disabled initially until both selections are made
        updateStartButtonState()
    }

    private fun showModelPicker() {
        // First try ACTION_OPEN_DOCUMENT to pick a .zip (or any file if mime is misreported)
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                // Optionally hint for zip
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
            }
            startActivityForResult(intent, REQ_OPEN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            // Fallback to OpenDocumentTree for directory
            try {
                val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(treeIntent, REQ_OPEN_DOCUMENT_TREE)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.err_no_file_picker), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_OPEN_DOCUMENT -> {
                val uri: Uri? = data?.data
                if (uri != null) {
                    // Persist temporary read permission on returned URI
                    contentResolver.takePersistableUriPermissionSafe(uri)
                    importModelFromUri(uri, persist = true)
                }
            }
            REQ_OPEN_DOCUMENT_TREE -> {
                val uri: Uri? = data?.data
                if (uri != null) {
                    contentResolver.takePersistableUriPermissionSafe(uri)
                    importModelFromUri(uri, persist = true)
                }
            }
        }
    }

    private fun android.content.ContentResolver.takePersistableUriPermissionSafe(uri: Uri) {
        try {
            takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // ignore
        } catch (_: IllegalArgumentException) {
            // ignore
        }
    }

    private fun importModelFromUri(uri: Uri, persist: Boolean) {
        progress.isVisible = true
        addStatus(getString(R.string.status_import_started))
        uiScope.launch {
            try {
                val modelName = withContext(Dispatchers.IO) {
                    ModelManager.importModel(this@MainActivity, uri, takePersistablePermission = persist)
                }
                modelSelected = true
                tvModelStatus.text = getString(R.string.label_selected_model, modelName)
                addStatus(getString(R.string.status_import_success, modelName))
            } catch (ex: Exception) {
                addStatus(getString(R.string.status_import_error, ex.message ?: "Unknown error"))
                Toast.makeText(this@MainActivity, getString(R.string.toast_import_failed), Toast.LENGTH_LONG).show()
            } finally {
                progress.isVisible = false
                updateStartButtonState()
            }
        }
    }



    private fun addStatus(message: String) {
        statusItems.add(message)
        statusAdapter.notifyDataSetChanged()
    }

    // PUBLIC_INTERFACE
    /**
     * Updates the enabled state of the Start Processing button based on selections.
     */
    private fun updateStartButtonState() {
        btnStartProcessing.isEnabled = modelSelected && videoSelected
    }
}
