package org.example.app.srt

import org.example.app.stt.VoskTranscriber.Segment
import java.io.File
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

/**
 * SrtGenerator provides utilities to convert recognized speech segments into .srt subtitle files.
 *
 * It supports:
 * - Formatting timecodes as HH:MM:SS,mmm
 * - Merging short/adjacent segments based on configurable heuristics
 * - Wrapping subtitle lines without breaking words (soft wrap) at a specified column width
 * - Writing UTF-8 with CRLF line endings and blank line separators per SRT spec
 */
object SrtGenerator {

    // PUBLIC_INTERFACE
    /**
     * Format a millisecond timestamp as SRT timecode "HH:MM:SS,mmm".
     *
     * @param ms Time in milliseconds (negative values are clamped to 0).
     * @return Time formatted as "HH:MM:SS,mmm".
     */
    @JvmStatic
    fun formatTimecode(ms: Long): String {
        val clamped = if (ms < 0) 0L else ms
        val hours = clamped / 3_600_000
        val minutes = (clamped % 3_600_000) / 60_000
        val seconds = (clamped % 60_000) / 1_000
        val millis = clamped % 1_000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * PUBLIC_INTERFACE
     * Data class representing a final SRT segment with index, start/end times in ms, and text.
     */
    data class SrtSegment(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    /**
     * PUBLIC_INTERFACE
     * Generate an SRT file from a list of speech segments.
     *
     * Parameters:
     * - segments: List of raw STT segments (startMs, endMs, text)
     * - outFile: Target file to write .srt to (parents will be created if needed)
     * - wrapAt: Soft wrap column; long lines are broken without splitting words (default 40)
     * - minMergeMs: Heuristic threshold (in ms) to consider a segment "very short" and merge into neighbors (default 1200)
     *
     * Heuristics:
     * - Sort segments by start time.
     * - Merge consecutive segments if:
     *   a) The gap between them is < 300 ms, or
     *   b) Either segment duration is < minMergeMs.
     * - Ensure end >= start for every output segment, clamp if needed.
     * - Wrap output lines at wrapAt characters without breaking words.
     * - Write UTF-8 with CRLF line endings and blank line separator between entries.
     *
     * Returns:
     * - Result containing the written path, segment count, and total covered duration (ms).
     */
    @JvmStatic
    fun generate(
        segments: List<Segment>,
        outFile: File,
        wrapAt: Int = 40,
        minMergeMs: Long = 1200L
    ): Result {
        val cleaned = segments
            .filter { it.text.isNotBlank() }
            .map { seg ->
                val start = max(0L, seg.startMs)
                val end = max(start, seg.endMs)
                Segment(start, end, seg.text.trim().replace(Regex("\\s+"), " "))
            }
            .sortedBy { it.startMs }

        // Merge heuristics
        val merged = mergeSegments(cleaned, minMergeMs = minMergeMs, shortGapMs = 300L)

        // Wrap lines and build SRT entries
        val srtLines = mutableListOf<String>()
        var idx = 1
        var minStart = Long.MAX_VALUE
        var maxEnd = 0L

        for (seg in merged) {
            val start = seg.startMs
            val end = max(seg.endMs, start) // ensure end >= start
            minStart = min(minStart, start)
            maxEnd = max(maxEnd, end)

            val header = "${formatTimecode(start)} --> ${formatTimecode(end)}"
            val wrapped = wrapText(seg.text, width = wrapAt)

            srtLines.add(idx.toString())
            srtLines.add(header)
            srtLines.addAll(wrapped)
            srtLines.add("") // blank separator

            idx++
        }

        // Ensure parents exist
        outFile.parentFile?.mkdirs()

        // Write with CRLF as per SRT conventional formatting
        val crlf = "\r\n"
        val content = srtLines.joinToString(separator = crlf)
        outFile.writeText(content, Charset.forName("UTF-8"))

        val count = merged.size
        val duration = if (count > 0 && minStart != Long.MAX_VALUE) max(0L, maxEnd - minStart) else 0L
        return Result(
            path = outFile.absolutePath,
            segments = count,
            durationMs = duration
        )
    }

    /**
     * Merge adjacent segments based on heuristics:
     * - If gap < shortGapMs (e.g., 300ms) -> merge.
     * - If either segment is "very short" (duration < minMergeMs) -> merge.
     */
    private fun mergeSegments(
        input: List<Segment>,
        minMergeMs: Long,
        shortGapMs: Long
    ): List<Segment> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<Segment>()
        var cur = input.first()

        fun dur(s: Segment) = max(0L, s.endMs - s.startMs)

        for (i in 1 until input.size) {
            val next = input[i]
            val gap = next.startMs - cur.endMs
            val shouldMerge = gap < shortGapMs || dur(cur) < minMergeMs || dur(next) < minMergeMs
            if (shouldMerge) {
                // Merge
                cur = Segment(
                    startMs = min(cur.startMs, next.startMs),
                    endMs = max(cur.endMs, next.endMs),
                    text = (cur.text + " " + next.text).replace(Regex("\\s+"), " ").trim()
                )
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    /**
     * Wrap text at a given width without breaking words. Preserves whitespace minimally,
     * condensing multiple spaces into singles and wrapping on spaces or punctuation
     * when possible.
     */
    private fun wrapText(text: String, width: Int): List<String> {
        if (text.length <= width) return listOf(text)
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        val sb = StringBuilder()

        for (w in words) {
            if (sb.isEmpty()) {
                sb.append(w)
            } else {
                if (sb.length + 1 + w.length <= width) {
                    sb.append(' ').append(w)
                } else {
                    lines.add(sb.toString())
                    sb.clear()
                    sb.append(w)
                }
            }
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())
        return lines
    }

    /**
     * PUBLIC_INTERFACE
     * Result object returned by generate(), providing output path and basic statistics.
     */
    data class Result(
        val path: String,
        val segments: Int,
        val durationMs: Long
    )
}
