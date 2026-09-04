package com.aimonk.attendance.engine

import android.graphics.RectF
import com.aimonk.attendance.model.Detection
import com.aimonk.attendance.model.Track
import kotlin.math.hypot
import kotlin.math.max

class IoUTracker(
    private val iouThreshold: Float = 0.12f,
    private val maxAge: Int = 45
) {
    private var nextId = 101
    val tracks = mutableListOf<Track>()

    fun extrapolateMotion(): List<Track> {
        tracks.forEach { t ->
            t.age++
            t.timeSinceUpdate++
            val speed = hypot(t.vx, t.vy)
            if (t.timeSinceUpdate in 1..8 && speed >= 1.5f) {
                val dx = t.vx * 0.5f
                val dy = t.vy * 0.5f
                t.bbox = RectF(
                    t.bbox.left + dx,
                    t.bbox.top + dy,
                    t.bbox.right + dx,
                    t.bbox.bottom + dy
                )
            }
            t.vx *= 0.6f
            t.vy *= 0.6f
        }
        return tracks.filter { it.timeSinceUpdate <= 15 }
    }

    fun update(detections: List<Detection>): List<Track> {
        tracks.forEach { t ->
            t.age++
            t.timeSinceUpdate++
        }

        if (detections.isEmpty()) {
            tracks.removeAll { it.timeSinceUpdate > maxAge }
            return tracks.filter { it.timeSinceUpdate <= 15 }
        }

        // IoU + Proximity Matching
        val matchedTracks = mutableSetOf<Int>()
        val matchedDets = mutableSetOf<Int>()

        while (true) {
            var bestScore = -1f
            var bestTrackIdx = -1
            var bestDetIdx = -1

            for (tIdx in tracks.indices) {
                if (tIdx in matchedTracks) continue
                val t = tracks[tIdx]

                for (dIdx in detections.indices) {
                    if (dIdx in matchedDets) continue
                    val det = detections[dIdx]
                    val score = calculateScore(t.bbox, det.bbox)

                    if (score > bestScore) {
                        bestScore = score
                        bestTrackIdx = tIdx
                        bestDetIdx = dIdx
                    }
                }
            }

            if (bestScore < iouThreshold || bestTrackIdx == -1 || bestDetIdx == -1) {
                break
            }

            matchedTracks.add(bestTrackIdx)
            matchedDets.add(bestDetIdx)

            val track = tracks[bestTrackIdx]
            val det = detections[bestDetIdx]

            val oldW = track.bbox.width()
            val oldH = track.bbox.height()
            val newW = det.bbox.width()
            val newH = det.bbox.height()

            val oldCx = track.bbox.centerX()
            val oldCy = track.bbox.centerY()
            val newCx = det.bbox.centerX()
            val newCy = det.bbox.centerY()

            val centerShift = hypot(newCx - oldCx, newCy - oldCy)

            val smoothCx: Float
            val smoothCy: Float
            if (centerShift < 3.5f) {
                smoothCx = oldCx
                smoothCy = oldCy
                track.vx *= 0.5f
                track.vy *= 0.5f
            } else {
                smoothCx = 0.35f * newCx + 0.65f * oldCx
                smoothCy = 0.35f * newCy + 0.65f * oldCy
                val instVx = (newCx - oldCx) * 0.30f
                val instVy = (newCy - oldCy) * 0.30f
                track.vx = track.vx * 0.6f + instVx * 0.4f
                track.vy = track.vy * 0.6f + instVy * 0.4f
            }

            // Heavy dimension stabilization (80% previous size, 20% new detection)
            val smoothW = 0.20f * newW + 0.80f * oldW
            val smoothH = 0.20f * newH + 0.80f * oldH

            track.bbox = RectF(
                smoothCx - smoothW / 2f,
                smoothCy - smoothH / 2f,
                smoothCx + smoothW / 2f,
                smoothCy + smoothH / 2f
            )
            track.score = det.score
            track.hits++
            track.timeSinceUpdate = 0
        }

        // Spawn new tracks for unmatched detections
        for (dIdx in detections.indices) {
            if (dIdx !in matchedDets) {
                tracks.add(
                    Track(
                        trackId = nextId++,
                        bbox = RectF(detections[dIdx].bbox),
                        score = detections[dIdx].score
                    )
                )
            }
        }

        tracks.removeAll { it.timeSinceUpdate > maxAge }
        return tracks.filter { it.timeSinceUpdate <= 15 }
    }

    fun clear() {
        tracks.clear()
    }

    private fun calculateScore(r1: RectF, r2: RectF): Float {
        val interLeft = maxOf(r1.left, r2.left)
        val interTop = maxOf(r1.top, r2.top)
        val interRight = minOf(r1.right, r2.right)
        val interBottom = minOf(r1.bottom, r2.bottom)

        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        val area1 = r1.width() * r1.height()
        val area2 = r2.width() * r2.height()
        val iou = interArea / max(1f, area1 + area2 - interArea)

        val dist = hypot(r1.centerX() - r2.centerX(), r1.centerY() - r2.centerY())
        val avgSize = max(30f, (max(r1.width(), r1.height()) + max(r2.width(), r2.height())) / 2f)
        val prox = max(0f, 1f - (dist / (avgSize * 1.5f)))

        return iou * 0.65f + prox * 0.35f
    }
}
