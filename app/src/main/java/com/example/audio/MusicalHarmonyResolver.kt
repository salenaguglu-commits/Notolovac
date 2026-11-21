package com.example.audio

import com.example.model.DetectedNote
import kotlin.math.abs
import kotlin.math.ln

data class PitchCandidate(
    val midi: Int,
    val score: Float
)

data class NoteAcousticEvidence(
    val rawNote: DetectedNote,
    val candidateScores: Map<Int, Float>,
    val peakConfidence: Float,
    val maxClippingRatio: Float
)

data class DetectedMusicalKey(
    val rootPitchClass: Int, // 0=C, 1=C#, ..., 11=B
    val isMajor: Boolean,
    val serbianName: String,
    val fitConfidence: Float
)

data class HarmonyResolutionResult(
    val resolvedNotes: List<DetectedNote>,
    val detectedKey: DetectedMusicalKey?,
    val correctedCount: Int
)

/**
 * Smart On-Device Musical Inference Engine (Key/Solfege Scale Detection + Viterbi Melody Matcher).
 *
 * When the microphone is close to a loud piano or encounters brief clipping/inharmonicity,
 * raw per-frame FFT can occasionally hesitate between the true note and a disharmonic partial.
 * This resolver uses:
 * 1) Krumhansl-Schmuckler tonal key profiles across all 24 Major & Minor scales (weighted by
 *    high-confidence, unclipped notes) to identify the melody's active key/scale.
 * 2) Viterbi dynamic programming combining:
 *    - Actual acoustic spectral support from each note's FFT candidate histogram
 *    - Solfege / diatonic scale harmony fit
 *    - Melodic voice-leading continuity (penalizing isolated wild leaps / octave glitches)
 * 3) Strict protection of high-confidence, unclipped notes so intentional chromatic accidentals
 *    are NEVER erased.
 */
object MusicalHarmonyResolver {

    // Major scale pitch-class intervals (Ionian: Do Re Mi Fa Sol La Si)
    private val MAJOR_SCALE = setOf(0, 2, 4, 5, 7, 9, 11)
    // Minor scale (Natural + raised 7th harmonic leading tone for classical/children's piano pieces)
    private val NATURAL_MINOR_SCALE = setOf(0, 2, 3, 5, 7, 8, 10)
    private val HARMONIC_MINOR_LEADING = 11

    // Krumhansl-Schmuckler tonal hierarchy weights (tonic, dominant, mediant strongest)
    private val MAJOR_WEIGHTS = floatArrayOf(
        1.00f, 0.08f, 0.56f, 0.10f, 0.72f, 0.64f, 0.10f, 0.86f, 0.10f, 0.58f, 0.10f, 0.48f
    )
    private val MINOR_WEIGHTS = floatArrayOf(
        1.00f, 0.08f, 0.54f, 0.72f, 0.10f, 0.62f, 0.10f, 0.84f, 0.56f, 0.14f, 0.46f, 0.44f
    )

    private val MAJOR_NAMES_SR = arrayOf(
        "C-dur", "Db-dur", "D-dur", "Eb-dur", "E-dur", "F-dur",
        "F#-dur", "G-dur", "Ab-dur", "A-dur", "Bb-dur", "H-dur"
    )
    private val MINOR_NAMES_SR = arrayOf(
        "C-mol", "C#-mol", "D-mol", "Eb-mol", "E-mol", "F-mol",
        "F#-mol", "G-mol", "G#-mol", "A-mol", "Bb-mol", "H-mol"
    )

    // Mild tie-breaking prior for common beginner piano keys (never overrides clear acoustic evidence)
    private val COMMON_MAJOR_PRIOR = setOf(0, 5, 7, 2) // C, F, G, D major
    private val COMMON_MINOR_PRIOR = setOf(9, 2, 4)    // A, D, E minor

    fun resolve(evidences: List<NoteAcousticEvidence>): HarmonyResolutionResult {
        if (evidences.isEmpty()) {
            return HarmonyResolutionResult(emptyList(), null, 0)
        }
        if (evidences.size < 3) {
            return HarmonyResolutionResult(evidences.map { it.rawNote }, null, 0)
        }

        val detectedKey = inferKey(evidences)

        // Build per-step candidate states (each state must have real acoustic support OR be a
        // plausible semitone/octave correction ONLY when the note was uncertain/clipped)
        val stateLists = evidences.map { buildCandidateStates(it, detectedKey) }

        val n = evidences.size
        val dp = Array(n) { i -> FloatArray(stateLists[i].size) { Float.NEGATIVE_INFINITY } }
        val backptr = Array(n) { i -> IntArray(stateLists[i].size) { 0 } }

        // Initialize first note
        for (s in stateLists[0].indices) {
            val cand = stateLists[0][s]
            var score = emissionScore(evidences[0], cand, detectedKey, isEdgeNote = true)
            // Slight preference for starting on tonic/mediant/dominant of inferred key
            if (detectedKey != null && isTonicTriad(cand.midi, detectedKey)) {
                score += 0.20f
            }
            dp[0][s] = score
        }

        // Viterbi forward pass
        for (i in 1 until n) {
            val isLast = (i == n - 1)
            for (curIdx in stateLists[i].indices) {
                val curCand = stateLists[i][curIdx]
                var emit = emissionScore(evidences[i], curCand, detectedKey, isEdgeNote = isLast)
                if (isLast && detectedKey != null && isTonic(curCand.midi, detectedKey)) {
                    emit += 0.28f
                }

                var bestPrevScore = Float.NEGATIVE_INFINITY
                var bestPrevIdx = 0

                for (prevIdx in stateLists[i - 1].indices) {
                    val prevCand = stateLists[i - 1][prevIdx]
                    val trans = transitionScore(
                        prevMidi = prevCand.midi,
                        curMidi = curCand.midi,
                        curEvidence = evidences[i]
                    )
                    val total = dp[i - 1][prevIdx] + trans + emit
                    if (total > bestPrevScore) {
                        bestPrevScore = total
                        bestPrevIdx = prevIdx
                    }
                }
                dp[i][curIdx] = bestPrevScore
                backptr[i][curIdx] = bestPrevIdx
            }
        }

        // Backtrack optimal melodic path
        var bestFinalIdx = 0
        var bestFinalScore = Float.NEGATIVE_INFINITY
        for (s in stateLists[n - 1].indices) {
            if (dp[n - 1][s] > bestFinalScore) {
                bestFinalScore = dp[n - 1][s]
                bestFinalIdx = s
            }
        }

        val chosenMidi = IntArray(n)
        var idx = bestFinalIdx
        for (i in n - 1 downTo 0) {
            chosenMidi[i] = stateLists[i][idx].midi
            idx = backptr[i][idx]
        }

        var corrected = 0
        val resolved = evidences.mapIndexed { i, ev ->
            val newMidi = chosenMidi[i]
            if (newMidi != ev.rawNote.midiNote) {
                corrected++
                ev.rawNote.copy(midiNote = newMidi)
            } else {
                ev.rawNote
            }
        }

        return HarmonyResolutionResult(
            resolvedNotes = resolved,
            detectedKey = detectedKey,
            correctedCount = corrected
        )
    }

    fun inferKey(evidences: List<NoteAcousticEvidence>): DetectedMusicalKey? {
        if (evidences.size < 3) return null

        val pcHistogram = FloatArray(12)
        var totalWeight = 0f

        for ((idx, ev) in evidences.withIndex()) {
            val midi = ev.rawNote.midiNote
            if (midi !in 21..108) continue
            val pc = ((midi % 12) + 12) % 12
            val clipPenalty = (1f - ev.maxClippingRatio * 3.0f).coerceIn(0.25f, 1f)
            val durWeight = (ev.rawNote.durationMs / 450f).coerceIn(0.4f, 2.2f)
            val edgeBoost = if (idx == 0 || idx == evidences.lastIndex) 1.35f else 1.0f
            val w = ev.peakConfidence.coerceIn(0.2f, 1f) * clipPenalty * durWeight * edgeBoost
            pcHistogram[pc] += w
            totalWeight += w
        }

        if (totalWeight <= 0.1f) return null

        val firstPc = ((evidences.first().rawNote.midiNote % 12) + 12) % 12
        val lastPc = ((evidences.last().rawNote.midiNote % 12) + 12) % 12

        var bestRoot = 0
        var bestIsMajor = true
        var bestScore = Float.NEGATIVE_INFINITY
        var secondBestScore = Float.NEGATIVE_INFINITY

        for (root in 0 until 12) {
            for (isMajor in booleanArrayOf(true, false)) {
                val weights = if (isMajor) MAJOR_WEIGHTS else MINOR_WEIGHTS
                var score = 0f
                var inScaleWeight = 0f
                for (pc in 0 until 12) {
                    val rel = (pc - root + 12) % 12
                    score += pcHistogram[pc] * weights[rel]
                    if (isInScale(rel, isMajor)) {
                        inScaleWeight += pcHistogram[pc]
                    }
                }
                val inScaleRatio = inScaleWeight / totalWeight
                score = (score / totalWeight) + inScaleRatio * 0.95f

                // Musical cadence priors (melodies strongly favor ending on tonic or starting on tonic/5th)
                if (lastPc == root) score += 0.24f
                if (firstPc == root || firstPc == (root + 7) % 12) score += 0.10f

                // Gentle tie-break for standard beginner piano keys
                if (isMajor && root in COMMON_MAJOR_PRIOR) score += 0.05f
                if (!isMajor && root in COMMON_MINOR_PRIOR) score += 0.03f
                if (isMajor) score += 0.02f

                if (score > bestScore) {
                    secondBestScore = bestScore
                    bestScore = score
                    bestRoot = root
                    bestIsMajor = isMajor
                } else if (score > secondBestScore) {
                    secondBestScore = score
                }
            }
        }

        // Compute how well the melody actually fits the winning key
        var inScaleCount = 0
        for (ev in evidences) {
            val rel = (((ev.rawNote.midiNote % 12) + 12) % 12 - bestRoot + 12) % 12
            if (isInScale(rel, bestIsMajor)) inScaleCount++
        }
        val fitRatio = inScaleCount.toFloat() / evidences.size.toFloat()
        if (fitRatio < 0.55f) return null

        val margin = (bestScore - secondBestScore).coerceIn(0f, 1f)
        val fitConfidence = (fitRatio * 0.75f + margin * 0.25f).coerceIn(0f, 1f)
        val name = if (bestIsMajor) MAJOR_NAMES_SR[bestRoot] else MINOR_NAMES_SR[bestRoot]

        return DetectedMusicalKey(
            rootPitchClass = bestRoot,
            isMajor = bestIsMajor,
            serbianName = name,
            fitConfidence = fitConfidence
        )
    }

    private data class CandidateState(
        val midi: Int,
        val normalizedAcoustic: Float, // 0..1 relative to best acoustic candidate for this note
        val isRawWinner: Boolean
    )

    private fun buildCandidateStates(
        ev: NoteAcousticEvidence,
        key: DetectedMusicalKey?
    ): List<CandidateState> {
        val rawMidi = ev.rawNote.midiNote
        val rawScores = ev.candidateScores.ifEmpty { mapOf(rawMidi to 1.0f) }
        val maxAcoustic = rawScores.values.maxOrNull()?.coerceAtLeast(0.001f) ?: 1.0f

        // Protect crisp, unclipped, high-confidence notes!
        val sortedScores = rawScores.entries.sortedByDescending { it.value }
        val top1 = sortedScores.firstOrNull()?.value ?: maxAcoustic
        val top2 = sortedScores.getOrNull(1)?.value ?: 0f
        val acousticMargin = ((top1 - top2) / top1.coerceAtLeast(0.001f)).coerceIn(0f, 1f)

        val isRockSolidNote = ev.peakConfidence >= 0.78f &&
            ev.maxClippingRatio < 0.015f &&
            acousticMargin >= 0.26f

        if (isRockSolidNote) {
            return listOf(CandidateState(rawMidi, 1.0f, isRawWinner = true))
        }

        val candidates = linkedMapOf<Int, CandidateState>()
        // Include top acoustic candidates from the actual FFT histogram
        for ((midi, score) in sortedScores.take(5)) {
            val norm = (score / maxAcoustic).coerceIn(0f, 1f)
            if (midi == rawMidi || norm >= 0.28f) {
                candidates[midi] = CandidateState(
                    midi = midi,
                    normalizedAcoustic = norm,
                    isRawWinner = (midi == rawMidi)
                )
            }
        }
        if (rawMidi !in candidates) {
            candidates[rawMidi] = CandidateState(rawMidi, 1.0f, isRawWinner = true)
        }

        // If the note was clipped OR uncertain AND currently out of scale, also consider
        // +/- 1 semitone or +/- 12 octave in-scale neighbors with modest prior (only if they have some support
        // or the frame was clipped and out-of-key)
        val rawOutOfScale = key != null && !isMidiInKey(rawMidi, key)
        if (key != null && rawOutOfScale && (ev.maxClippingRatio >= 0.02f || ev.peakConfidence < 0.65f)) {
            val neighbors = intArrayOf(rawMidi - 1, rawMidi + 1, rawMidi - 12, rawMidi + 12)
            for (nb in neighbors) {
                if (nb in 48..84 && nb !in candidates && isMidiInKey(nb, key)) {
                    val histNorm = ((rawScores[nb] ?: 0f) / maxAcoustic).coerceIn(0f, 1f)
                    if (histNorm >= 0.14f || ev.maxClippingRatio >= 0.03f) {
                        candidates[nb] = CandidateState(
                            midi = nb,
                            normalizedAcoustic = maxOf(histNorm, 0.22f),
                            isRawWinner = false
                        )
                    }
                }
            }
        }

        return candidates.values.toList()
    }

    private fun emissionScore(
        ev: NoteAcousticEvidence,
        cand: CandidateState,
        key: DetectedMusicalKey?,
        isEdgeNote: Boolean
    ): Float {
        // How much do we trust raw acoustics on this note? Clipped/uncertain notes rely more on musical context!
        val clipTrust = (1f - ev.maxClippingRatio * 4.5f).coerceIn(0.32f, 1.0f)
        val acousticWeight = (1.35f + ev.peakConfidence * 1.45f) * clipTrust
        var score = acousticWeight * ln(cand.normalizedAcoustic.coerceAtLeast(0.08f) + 0.35f)
        if (cand.isRawWinner) {
            score += 0.28f * ev.peakConfidence * clipTrust
        }

        // Musical Scale / Solfege Harmony fit
        if (key != null) {
            val rel = (((cand.midi % 12) + 12) % 12 - key.rootPitchClass + 12) % 12
            val inScale = isInScale(rel, key.isMajor)
            val harmonyStrength = key.fitConfidence.coerceIn(0.45f, 1.0f)
            if (inScale) {
                val tonalWeight = if (key.isMajor) MAJOR_WEIGHTS[rel] else MINOR_WEIGHTS[rel]
                score += (0.72f + tonalWeight * 0.28f) * harmonyStrength
                if (isEdgeNote && rel == 0) score += 0.16f * harmonyStrength
            } else {
                // Out-of-key penalty — strong when uncertain/clipped, lighter when acoustically clean
                val penalty = if (ev.maxClippingRatio >= 0.02f || ev.peakConfidence < 0.65f) 1.05f else 0.55f
                score -= penalty * harmonyStrength
            }
        }

        return score
    }

    private fun transitionScore(
        prevMidi: Int,
        curMidi: Int,
        curEvidence: NoteAcousticEvidence
    ): Float {
        val interval = abs(curMidi - prevMidi)
        // Melodic voice-leading prior (children's & classical melodies move mostly by steps/thirds/fifths)
        val base = when {
            interval == 0 -> 0.18f       // Repeated note (Do-Do)
            interval in 1..2 -> 0.30f    // Stepwise motion (most common in melodies!)
            interval in 3..5 -> 0.24f    // Third / Fourth
            interval == 7 -> 0.20f       // Perfect Fifth (e.g. C -> G in Twinkle Twinkle!)
            interval in 6..9 -> 0.05f    // Tritone / Sixth
            interval == 12 -> -0.05f     // Clean Octave leap
            interval in 10..11 -> -0.35f // Awkward 7th leap
            else -> -0.75f - (interval - 12) * 0.06f // Wild multi-octave jump (classic clipping/harmonic glitch!)
        }
        // When acoustics are heavily clipped or uncertain, lean slightly more on melodic continuity
        val scale = if (curEvidence.maxClippingRatio >= 0.02f || curEvidence.peakConfidence < 0.65f) 1.35f else 0.85f
        return base * scale
    }

    private fun isInScale(relPitchClass: Int, isMajor: Boolean): Boolean {
        return if (isMajor) {
            relPitchClass in MAJOR_SCALE
        } else {
            relPitchClass in NATURAL_MINOR_SCALE || relPitchClass == HARMONIC_MINOR_LEADING
        }
    }

    private fun isMidiInKey(midi: Int, key: DetectedMusicalKey): Boolean {
        val rel = (((midi % 12) + 12) % 12 - key.rootPitchClass + 12) % 12
        return isInScale(rel, key.isMajor)
    }

    private fun isTonic(midi: Int, key: DetectedMusicalKey): Boolean {
        return (((midi % 12) + 12) % 12) == key.rootPitchClass
    }

    private fun isTonicTriad(midi: Int, key: DetectedMusicalKey): Boolean {
        val rel = (((midi % 12) + 12) % 12 - key.rootPitchClass + 12) % 12
        return rel == 0 || rel == 7 || rel == (if (key.isMajor) 4 else 3)
    }
}
