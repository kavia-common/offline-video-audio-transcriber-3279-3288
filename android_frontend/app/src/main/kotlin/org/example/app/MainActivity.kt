package org.example.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.app.stt.ModelManager
import org.example.app.ui.StatusAdapter
import org.example.app.work.TranscriptionWorker
import java.util.UUID

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

    private lateinit var statusAdapter: StatusAdapter

    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private var currentWorkId: UUID? = null
    private var currentObserver: Observer<WorkInfo>? = null

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

        statusAdapter = StatusAdapter(this)
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
        btnStartProcessing.setOnClickListener { startTranscriptionWork() }

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

    private fun startTranscriptionWork() {
        val uri = selectedVideoUri
        if (uri == null) {
            Toast.makeText(this, "Please select a video.", Toast.LENGTH_SHORT).show()
            return
        }
        // Clear previous observer if any
        clearCurrentObserver()

        // Reset UI
        statusAdapter.clear()
        statusAdapter.addInfo("Processing started…")
        progress.isVisible = true
        btnStartProcessing.isEnabled = false

        // Build Data for worker
        val input: Data = Data.Builder()
            .putString(TranscriptionWorker.KEY_INPUT_VIDEO_URI, uri.toString())
            .build()

        // Build OneTimeWorkRequest
        val req = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        currentWorkId = req.id

        // Enqueue
        WorkManager.getInstance(this).enqueue(req)

        // Observe progress and state
        val liveData = WorkManager.getInstance(this).getWorkInfoByIdLiveData(req.id)
        val observer = Observer<WorkInfo> { info ->
            if (info == null) return@Observer

            // Update progress if available
            val progressData = info.progress
            val stage = progressData.getString(TranscriptionWorker.PROGRESS_STAGE)
            val message = progressData.getString(TranscriptionWorker.PROGRESS_MESSAGE)
            val percent = progressData.getInt(TranscriptionWorker.PROGRESS_PERCENT, -1)

            if (!message.isNullOrBlank()) {
                val display = if (percent in 0..100) "$message ($percent%)" else message
                statusAdapter.addInfo(display)
            }

            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    progress.isVisible = false
                    val output = info.outputData
                    val wav = output.getString(TranscriptionWorker.KEY_OUTPUT_WAV_PATH)
                    val srt = output.getString(TranscriptionWorker.KEY_OUTPUT_SRT_PATH)

                    if (!wav.isNullOrBlank()) {
                        statusAdapter.addSuccess("WAV saved: $wav")
                    }
                    if (!srt.isNullOrBlank()) {
                        statusAdapter.addSuccess("SRT saved: $srt")
                    }
                    statusAdapter.addSuccess("Completed successfully.")
                    updateStartButtonState()
                    clearCurrentObserver()
                }
                WorkInfo.State.FAILED -> {
                    progress.isVisible = false
                    val msg = info.outputData.getString("message") ?: "Unknown error"
                    statusAdapter.addError("Failed: $msg")
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    // Allow retry if prerequisites are still met
                    updateStartButtonState()
                    clearCurrentObserver()
                }
                WorkInfo.State.CANCELLED -> {
                    progress.isVisible = false
                    statusAdapter.addError("Cancelled")
                    updateStartButtonState()
                    clearCurrentObserver()
                }
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED -> {
                    // keep showing progress
                    progress.isVisible = true
                }
            }
        }
        liveData.observe(this, observer)
        currentObserver = observer
    }

    private fun clearCurrentObserver() {
        currentWorkId?.let { id ->
            currentObserver?.let { obs ->
                WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).removeObserver(obs)
            }
        }
        currentWorkId = null
        currentObserver = null
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
                statusAdapter.addInfo(getString(R.string.status_video_selected, display, uri.toString()))
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
        statusAdapter.addInfo(getString(R.string.status_import_started))
        uiScope.launch {
            try {
                val modelName = withContext(Dispatchers.IO) {
                    ModelManager.importModel(this@MainActivity, uri, takePersistablePermission = persist)
                }
                modelSelected = true
                tvModelStatus.text = getString(R.string.label_selected_model, modelName)
                statusAdapter.addSuccess(getString(R.string.status_import_success, modelName))
            } catch (ex: Exception) {
                statusAdapter.addError(getString(R.string.status_import_error, ex.message ?: "Unknown error"))
                Toast.makeText(this@MainActivity, getString(R.string.toast_import_failed), Toast.LENGTH_LONG).show()
            } finally {
                progress.isVisible = false
                updateStartButtonState()
            }
        }
    }

    private fun updateStartButtonState() {
        btnStartProcessing.isEnabled = modelSelected && videoSelected
    }

    override fun onDestroy() {
        super.onDestroy()
        clearCurrentObserver()
    }
}
