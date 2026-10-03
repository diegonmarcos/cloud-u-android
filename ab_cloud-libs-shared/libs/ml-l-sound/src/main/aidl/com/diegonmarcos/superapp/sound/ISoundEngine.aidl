package com.diegonmarcos.superapp.sound;

import android.os.ParcelFileDescriptor;

/**
 * #798 the sound engine's wire (Cloud-Lib-Ml-L-Sound-Yamnet.apk). The engine runs in its own uid
 * and cannot open a caller's private file, so the CALLER opens the clip (a RIFF/WAVE file) and
 * hands over the descriptor; the engine reads it to the end. Append-only: a method that changes
 * shape ships under a new name and a higher CONTRACT.
 */
interface ISoundEngine {
    /** [method] over the clip behind [audio] (null for info) with a JSON [request]; JSON back, or {"error": …}. Never throws. */
    String call(String method, String request, in ParcelFileDescriptor audio);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
}
