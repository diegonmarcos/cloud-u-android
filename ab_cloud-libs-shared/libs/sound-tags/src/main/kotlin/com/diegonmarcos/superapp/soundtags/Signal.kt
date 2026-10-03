package com.diegonmarcos.superapp.soundtags

/** #798 getting a clip into the shape the model reads: its rate, then its fixed-size windows. */
object Signal {
    /**
     * [x] at [from] Hz resampled to [to] Hz. Downsampling averages each output sample's span of
     * input (a box filter, so a 48 kHz recording does not alias into the 0–8 kHz the model hears);
     * upsampling interpolates linearly.
     */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        require(from > 0 && to > 0) { "sample rates must be positive ($from -> $to)" }
        if (from == to || x.isEmpty()) return x.copyOf()
        val n = (x.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val step = from.toDouble() / to
        if (from > to) return FloatArray(n) { i ->
            val s = (i * step).toInt()
            val e = minOf(x.size, maxOf(s + 1, ((i + 1) * step).toInt()))
            var acc = 0f
            for (k in s until e) acc += x[k]
            acc / (e - s)
        }
        val last = x.size - 1
        return FloatArray(n) { i ->
            val t = i * step
            val j = minOf(t.toInt(), last)
            val a = x[j]
            a + (x[minOf(j + 1, last)] - a) * (t - j).toFloat()
        }
    }

    /**
     * Windows of [size] samples every [hop] samples. A window is taken while at least half of it is
     * real audio, the rest zero-padded, so a clip shorter than one window is still one window.
     */
    fun windows(x: FloatArray, size: Int, hop: Int): List<FloatArray> {
        require(size > 0 && hop > 0) { "window $size and hop $hop must be positive" }
        val out = ArrayList<FloatArray>()
        var start = 0
        do {
            val from = start
            out.add(FloatArray(size) { i -> if (from + i < x.size) x[from + i] else 0f })
            start += hop
        } while (start + size / 2 < x.size)
        return out
    }

    /** The clip's peak absolute amplitude, so a caller can tell a silent capture from a quiet room. */
    fun peak(x: FloatArray): Float = x.fold(0f) { m, v -> maxOf(m, kotlin.math.abs(v)) }
}
