package com.diegonmarcos.superapp.image.mlkit;

import android.os.ParcelFileDescriptor;

/**
 * The image-scan engine's wire (Cloud-Lib-Ml-L-Image-Mlkit.apk). The engine runs in its own
 * uid and cannot open a caller's private file or an ungranted content Uri, so the CALLER
 * opens the image and hands over the descriptor; the engine reads it to the end.
 */
interface IImageScanEngine {
    /** [method] over the image behind [image]; JSON back, or {"error": …}. Never throws. */
    String scan(String method, in ParcelFileDescriptor image);

    /** Method names this engine answers, so a caller can degrade knowingly. */
    String[] methods();
    /**
     * #772 contract 2: [method] over the image with a JSON [request] (recognize: route, model,
     * thresholds — RecognitionConfig.request); models takes no image (null). Appended, so
     * contract-1 callers are unaffected.
     */
    String scanWith(String method, String request, in ParcelFileDescriptor image);
}
