package org.example.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.stt.ModelManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val STATE_SELECTED_VIDEO_URI = "state_selected_video_uri"
        private const val REQ_OPEN_DOCUMENT = 1001
        private const val REQ_OPEN_DOCUMENT_TREE = 1002
        private const val REQ_OPEN_VIDEO = 2001
    }

    private lateinit var btnImportModel: Button
    private lateinit var btnSelectVideo: Button
    private lateinit var btnStartProcessing: Button
    private lateinit var tvModelStatus: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var lvStatus: ListView
    private lateinit var progress: ProgressBar

    private var modelSelected = false
    private var videoSelected = false
    private var selectedVideoUri: Uri? = null

    private val statusItems = mutableListOf<String>()
    private lateinit var statusAdapter: ArrayAdapter<String>

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnImportModel = findViewById(R.id.btnImportModel)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnStartProcessing = findViewById(R.id.btnStartProcessing)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        lvStatus = findViewById(R.id.lvStatus)
        progress = findViewById(R.id.progress)

        statusAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, statusItems)
        lvStatus.adapter = statusAdapter

        val restored = savedInstanceState?.getString(STATE_SELECTED_VIDEO_URI)
        if (!restored.isNullOrEmpty()) {
            selectedVideoUri = Uri.parse(restored)
            videoSelected = true
            val display = queryDisplayName(selectedVideoUri) ?: restored
            tvVideoStatus.text = getString(R.string.label_selected_video, display)
        }

        btnImportModel.setOnClickListener { showModelPicker() }
        btnSelectVideo.setOnClickListener { showVideoPicker() }
        btnStartProcessing.setOnClickListener {
            progress.isVisible = true
            addStatus("Processing started…")
        }

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

        updateStartButtonState()
    }

    private fun showModelPicker() {
        try {
            val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip","application/x-zip-compressed","application/octet-stream","*/*"))
            }
            startActivityForResult(open, REQ_OPEN_DOCUMENT)
        } catch (_: ActivityNotFoundException) {
            try {
                val tree = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(tree, REQ_OPEN_DOCUMENT_TREE)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.err_no_file_picker), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showVideoPicker() {
        try {
            val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            startActivityForResult(open, REQ_OPEN_VIDEO)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.err_no_file_picker), Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedVideoUri?.let { outState.putString(STATE_SELECTED_VIDEO_URI, it.toString()) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_OPEN_DOCUMENT -> {
                val uri = data?.data ?: return
                contentResolverSafeTakePersist(uri)
                importModelFromUri(uri, true)
            }
            REQ_OPEN_DOCUMENT_TREE -> {
                val uri = data?.data ?: return
                contentResolverSafeTakePersist(uri)
                importModelFromUri(uri, true)
            }
            REQ_OPEN_VIDEO -> {
                val uri = data?.data ?: return
                contentResolverSafeTakePersist(uri)
                selectedVideoUri = uri
                videoSelected = true
                val display = queryDisplayName(uri) ?: uri.toString()
                tvVideoStatus.text = getString(R.string.label_selected_video, display)
                addStatus(getString(R.string.status_video_selected, display, uri.toString()))
                updateStartButtonState()
            }
        }
    }

    private fun contentResolverSafeTakePersist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun queryDisplayName(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c: Cursor ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
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

    private fun updateStartButtonState() {
        btnStartProcessing.isEnabled = modelSelected && videoSelected
    }
}
