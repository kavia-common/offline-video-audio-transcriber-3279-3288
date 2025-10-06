package org.example.app

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

/**
 * PUBLIC_INTERFACE
 * MainActivity is the single-activity entry point that sets up the Ocean Professional themed UI
 * with actions to import a model, select a video, and start processing.
 * This scaffold wires view references and basic enabled state logic, but it contains no
 * business logic for file picking or processing yet.
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

        // Setup ListView with a simple placeholder adapter
        val initialItems = mutableListOf<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, initialItems)
        lvStatus.adapter = adapter

        // Handlers
        btnImportModel.setOnClickListener {
            // TODO: Implement model import (file picker/copy to internal storage)
            modelSelected = true
            tvModelStatus.text = getString(R.string.label_selected_model, "example-model.bin")
            initialItems.add("Model imported: example-model.bin")
            adapter.notifyDataSetChanged()
            updateStartButtonState()
        }

        btnSelectVideo.setOnClickListener {
            // TODO: Implement video selection (file picker)
            videoSelected = true
            tvVideoStatus.text = getString(R.string.label_selected_video, "example-video.mp4")
            initialItems.add("Video selected: example-video.mp4")
            adapter.notifyDataSetChanged()
            updateStartButtonState()
        }

        btnStartProcessing.setOnClickListener {
            // TODO: Trigger processing: extract audio, transcribe offline, generate SRT
            // Show progress indicator during processing
            progress.visibility = ProgressBar.VISIBLE
            initialItems.add("Processing started…")
            adapter.notifyDataSetChanged()
            // TODO: Once processing completes or fails, hide progress and update status list
        }

        // Ensure start button is disabled initially until both selections are made
        updateStartButtonState()
    }

    // PUBLIC_INTERFACE
    /**
     * Updates the enabled state of the Start Processing button based on selections.
     */
    private fun updateStartButtonState() {
        btnStartProcessing.isEnabled = modelSelected && videoSelected
    }
}
