package com.meetingnotes.transcription

import kotlin.math.*

/**
 * Pure-Kotlin log-mel spectrogram extractor tuned for NeMo Parakeet-TDT.
 *
 * Parameters match the NeMo FastConformer preprocessor defaults:
 *   sample_rate=16000, n_fft=512, win_length=400 (25 ms), hop_length=160 (10 ms),
 *   n_mels=128, normalize="per_feature" (mean/std per mel band).
 *
 * Output shape: [n_mels, n_frames]  (column-major time axis, matches ONNX model expectation).
 */
class MelSpectrogramExtractor(
    private val sampleRate: Int = 16_000,
    private val nFft: Int = 512,
    private val winLength: Int = 400,
    private val hopLength: Int = 160,
    val nMels: Int = 128,
    private val fMin: Float = 0f,
    private val fMax: Float = 8_000f,
) {
    // Pre-compute Hann window and mel filterbank at construction time.
    private val window = FloatArray(winLength) { n ->
        (0.5f * (1.0 - cos(2.0 * PI * n / (winLength - 1)))).toFloat()
    }
    private val melFilters: Array<FloatArray> = buildMelFilters()

    /**
     * Extract a normalised log-mel spectrogram from raw 16-bit PCM samples in [-1, 1].
     *
     * @return [n_mels × n_frames] array; may be empty if the input is shorter than one window.
     */
    fun extract(samples: FloatArray): Array<FloatArray> {
        val nFrames = if (samples.size >= winLength)
            (samples.size - winLength) / hopLength + 1 else 0
        if (nFrames == 0) return Array(nMels) { FloatArray(0) }

        val output = Array(nMels) { FloatArray(nFrames) }
        val frame = FloatArray(nFft)

        for (t in 0 until nFrames) {
            val start = t * hopLength
            frame.fill(0f)
            for (i in 0 until winLength) {
                if (start + i < samples.size) frame[i] = samples[start + i] * window[i]
            }

            val power = fftPowerSpectrum(frame)

            for (m in 0 until nMels) {
                var energy = 0f
                for (k in melFilters[m].indices) energy += power[k] * melFilters[m][k]
                output[m][t] = ln(energy.coerceAtLeast(1e-5f))
            }
        }

        // Per-feature (per mel band) mean/variance normalisation — NeMo default.
        for (m in 0 until nMels) {
            val mean = output[m].average().toFloat()
            var variance = 0f
            for (v in output[m]) variance += (v - mean) * (v - mean)
            val std = sqrt(variance / nFrames + 1e-5f)
            for (t in 0 until nFrames) output[m][t] = (output[m][t] - mean) / std
        }

        return output
    }

    // ── DSP helpers ───────────────────────────────────────────────────────────

    private fun buildMelFilters(): Array<FloatArray> {
        val nBins = nFft / 2 + 1

        fun hzToMel(hz: Float) = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float) = 700f * (10f.pow(mel / 2595f) - 1f)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val centers = FloatArray(nMels + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (nMels + 1))
        }
        // Convert Hz centres to FFT bin indices.
        val bins = FloatArray(nMels + 2) { i -> (nFft + 1) * centers[i] / sampleRate }

        return Array(nMels) { m ->
            FloatArray(nBins) { k ->
                val kf = k.toFloat()
                when {
                    kf < bins[m]     -> 0f
                    kf <= bins[m + 1] -> (kf - bins[m]) / (bins[m + 1] - bins[m])
                    kf <= bins[m + 2] -> (bins[m + 2] - kf) / (bins[m + 2] - bins[m + 1])
                    else             -> 0f
                }
            }
        }
    }

    /** Cooley–Tukey radix-2 DIT FFT, returns one-sided power spectrum of length nFft/2+1. */
    private fun fftPowerSpectrum(x: FloatArray): FloatArray {
        val n = x.size  // must be a power of two
        val re = DoubleArray(n) { x[it].toDouble() }
        val im = DoubleArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while (j and bit != 0) { j = j xor bit; bit = bit ushr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val step = -2.0 * PI / len
            val wRe = cos(step); val wIm = sin(step)
            var k = 0
            while (k < n) {
                var curRe = 1.0; var curIm = 0.0
                for (p in 0 until len / 2) {
                    val uRe = re[k + p]; val uIm = im[k + p]
                    val vRe = re[k + p + len / 2] * curRe - im[k + p + len / 2] * curIm
                    val vIm = re[k + p + len / 2] * curIm + im[k + p + len / 2] * curRe
                    re[k + p] = uRe + vRe; im[k + p] = uIm + vIm
                    re[k + p + len / 2] = uRe - vRe; im[k + p + len / 2] = uIm - vIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = newRe
                }
                k += len
            }
            len = len shl 1
        }

        return FloatArray(n / 2 + 1) { i -> (re[i] * re[i] + im[i] * im[i]).toFloat() }
    }
}
