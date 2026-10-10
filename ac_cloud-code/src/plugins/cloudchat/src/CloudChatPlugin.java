package com.diegonmarcos.cloudcode.chat;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * cloud-code Chat's native half (Cordova service "CloudChat"). The web page (src/cloud/chat) draws
 * everything; this class does only what a WebView must not or cannot:
 *
 *  - the OpenRouter token: stored encrypted ({@link ChatSecrets}), shown masked, tested, and added
 *    to a request HERE, never handed to JavaScript (of the fleet Account's key, only whether it
 *    holds one is asked);
 *  - HTTP to the fleet's agent gateway (WireGuard, cleartext http on the mesh) and to OpenRouter,
 *    streamed back chunk by chunk (keepCallback) so the chat renders tokens as they arrive;
 *  - the model catalogue, through libs:model-catalogue ({@link CatalogueBridge});
 *  - attachments: the Android photo picker / Storage Access Framework (no storage permission), and a
 *    voice message recorded as 16 kHz mono WAV (RECORD_AUDIO, asked when first used).
 *
 * Nothing here logs: a request carries a key, a body carries the owner's words.
 */
public class CloudChatPlugin extends CordovaPlugin {
    private static final int REQ_PICK = 0xC0D1; // androidx: request codes must fit in 16 bits
    private static final int REQ_MIC = 0xC0D2;
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BODY_CHARS = 4 * 1024 * 1024;
    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_RECORD_SECONDS = 300;
    private static final String OPENROUTER_HOST = "openrouter.ai";

    private ChatSecrets secrets;
    private CatalogueBridge catalogue;
    private final Map<String, HttpURLConnection> live = new ConcurrentHashMap<>();
    private CallbackContext pickCallback;
    private CallbackContext micCallback;

    private volatile boolean recording;
    private AudioRecord recorder;
    private Thread recordThread;
    private ByteArrayOutputStream pcm;
    private long recordStarted;

    @Override
    protected void pluginInitialize() {
        secrets = new ChatSecrets(cordova.getContext());
        catalogue = new CatalogueBridge(cordova.getContext(), url -> {
            try {
                Response r = request(url, "GET", null, null, false, 20000, null);
                return r.status >= 200 && r.status < 300 ? r.body : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) {
        switch (action) {
            case "tokenSet":
            case "tokenClear":
            case "tokenStatus":
            case "tokenTest":
            case "catalogue":
            case "get":
            case "post":
                cordova.getThreadPool().execute(() -> run(action, args, cb));
                return true;
            case "stream":
                stream(args.optJSONObject(0), cb);
                return true;
            case "cancel":
                HttpURLConnection c = live.remove(args.optString(0));
                if (c != null) c.disconnect();
                cb.success();
                return true;
            case "pick":
                pick(args.optString(0, "file"), args.optBoolean(1, true), cb);
                return true;
            case "recordStart":
                if (cordova.hasPermission(Manifest.permission.RECORD_AUDIO)) startRecording(cb);
                else {
                    micCallback = cb;
                    cordova.requestPermission(this, REQ_MIC, Manifest.permission.RECORD_AUDIO);
                }
                return true;
            case "recordStop":
                cordova.getThreadPool().execute(() -> stopRecording(cb));
                return true;
            default:
                return false;
        }
    }

    private void run(String action, JSONArray args, CallbackContext cb) {
        try {
            switch (action) {
                case "tokenSet":
                    secrets.set(args.optString(0, ""));
                    cb.success(status());
                    break;
                case "tokenClear":
                    secrets.clear();
                    cb.success(status());
                    break;
                case "tokenStatus":
                    cb.success(status());
                    break;
                case "tokenTest":
                    cb.success(test());
                    break;
                case "catalogue":
                    cb.success(catalogue.shown(args.optJSONArray(0), args.optBoolean(1, false)));
                    break;
                case "get": {
                    Response r = request(args.getString(0), "GET", null, args.optJSONObject(1), "openrouter".equals(args.optString(2)), 30000, null);
                    cb.success(new JSONObject().put("status", r.status).put("body", r.body));
                    break;
                }
                case "post": {
                    Response r = request(args.getString(0), "POST", args.optString(1, "{}"), args.optJSONObject(2), false, 60000, null);
                    cb.success(new JSONObject().put("status", r.status).put("body", r.body));
                    break;
                }
                default:
                    cb.error("unknown action");
            }
        } catch (Exception e) {
            cb.error(reason(e));
        }
    }

    /** Never the token: whether one is set, its mask, and the fleet Account's key-free status. */
    private JSONObject status() throws Exception {
        String local = secrets.local();
        return new JSONObject()
            .put("set", local != null)
            .put("masked", ChatSecrets.mask(local))
            .put("account", secrets.account());
    }

    /** GET /api/v1/key with the token: whether OpenRouter accepts it, and its usage. The key's own label is not passed on. */
    private JSONObject test() throws Exception {
        Response r = request("https://openrouter.ai/api/v1/key", "GET", null, null, true, 20000, null);
        JSONObject out = new JSONObject().put("ok", r.status == 200).put("status", r.status);
        if (r.status == 200) {
            JSONObject d = new JSONObject(r.body).optJSONObject("data");
            if (d != null) {
                out.put("usage", d.opt("usage")).put("limit", d.opt("limit")).put("is_free_tier", d.optBoolean("is_free_tier"));
            }
        }
        return out;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private static final class Response {
        final int status;
        final String body;
        final String contentType;

        Response(int status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }
    }

    interface Sink {
        void head(int status, String contentType);
        void data(String text);
    }

    /**
     * One request. [openrouter] adds the Authorization header from {@link ChatSecrets}, and only to
     * https://openrouter.ai: the token cannot be sent anywhere else, whatever the page asks. A
     * header the page passes named Authorization is dropped for the same reason.
     */
    private Response request(String url, String method, String body, JSONObject headers, boolean openrouter, int timeoutMs, Sink sink) throws Exception {
        return request(url, method, body, headers, openrouter, timeoutMs, sink, null);
    }

    private Response request(String url, String method, String body, JSONObject headers, boolean openrouter, int timeoutMs, Sink sink, String liveId) throws Exception {
        URL u = new URL(url);
        String scheme = u.getProtocol();
        if (!"https".equals(scheme) && !"http".equals(scheme)) throw new IllegalArgumentException("not http(s): " + scheme);
        String token = null;
        if (openrouter) {
            if (!"https".equals(scheme) || !OPENROUTER_HOST.equals(u.getHost())) throw new SecurityException("the OpenRouter token is only sent to https://" + OPENROUTER_HOST);
            token = secrets.resolve();
            if (token == null) throw new IllegalStateException("No OpenRouter token: set one in Profile & Config");
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (liveId != null && !liveId.isEmpty()) live.put(liveId, c);
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json, text/event-stream");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if ("authorization".equalsIgnoreCase(k)) continue;
                    c.setRequestProperty(k, headers.optString(k));
                }
            }
            if (token != null) {
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("HTTP-Referer", "https://github.com/diegonmarcos/cloud-u-android");
                c.setRequestProperty("X-Title", "Cloud Code");
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            String type = c.getContentType();
            if (sink != null) sink.head(status, type);
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder all = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[2048];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        if (sink != null) sink.data(new String(buf, 0, n));
                        else if (all.length() < MAX_BODY_CHARS) all.append(buf, 0, n);
                    }
                }
            }
            return new Response(status, all.toString(), type);
        } finally {
            c.disconnect();
        }
    }

    /** {id, url, method, headers, body, auth: "openrouter"|null, timeout_ms}: head, data…, end (or error). */
    private void stream(JSONObject req, CallbackContext cb) {
        if (req == null) {
            cb.error("no request");
            return;
        }
        cordova.getThreadPool().execute(() -> {
            String id = req.optString("id");
            try {
                request(req.getString("url"), req.optString("method", "POST"), req.has("body") ? req.optString("body") : null,
                    req.optJSONObject("headers"), "openrouter".equals(req.optString("auth")), req.optInt("timeout_ms", 900000),
                    new Sink() {
                        @Override
                        public void head(int status, String contentType) {
                            emit(cb, true, "head", "status", status, "content_type", contentType == null ? "" : contentType);
                        }

                        @Override
                        public void data(String text) {
                            emit(cb, true, "data", "text", text, null, null);
                        }
                    }, id);
                emit(cb, false, "end", null, null, null, null);
            } catch (Exception e) {
                emit(cb, false, "error", "message", reason(e), null, null);
            } finally {
                live.remove(id);
            }
        });
    }

    private static void emit(CallbackContext cb, boolean more, String type, String k1, Object v1, String k2, Object v2) {
        try {
            JSONObject o = new JSONObject().put("type", type);
            if (k1 != null) o.put(k1, v1);
            if (k2 != null) o.put(k2, v2);
            PluginResult r = new PluginResult(PluginResult.Status.OK, o);
            r.setKeepCallback(more);
            cb.sendPluginResult(r);
        } catch (Exception ignored) {
            // a JSON put of a String/int cannot fail
        }
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    // ── attachments: photo picker / SAF, no storage permission ─────────

    private void pick(String kind, boolean multiple, CallbackContext cb) {
        Intent i;
        if ("media".equals(kind) && Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if (multiple) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
        } else if ("media".equals(kind)) {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        }
        pickCallback = cb;
        cordova.startActivityForResult(this, i, REQ_PICK);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK || pickCallback == null) return;
        CallbackContext cb = pickCallback;
        pickCallback = null;
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.success(new JSONArray());
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        cordova.getThreadPool().execute(() -> {
            JSONArray out = new JSONArray();
            ContentResolver cr = cordova.getContext().getContentResolver();
            for (Uri uri : uris) {
                try {
                    String name = "attachment";
                    long size = -1;
                    try (Cursor q = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                        if (q != null && q.moveToFirst()) {
                            name = q.getString(0);
                            size = q.isNull(1) ? -1 : q.getLong(1);
                        }
                    }
                    String mime = cr.getType(uri);
                    JSONObject f = new JSONObject().put("name", name).put("mime", mime == null ? "application/octet-stream" : mime).put("size", size);
                    if (size > MAX_FILE_BYTES) {
                        out.put(f.put("error", "larger than " + (MAX_FILE_BYTES / 1024 / 1024) + " MB"));
                        continue;
                    }
                    byte[] bytes = read(cr.openInputStream(uri));
                    if (bytes == null) {
                        out.put(f.put("error", "larger than " + (MAX_FILE_BYTES / 1024 / 1024) + " MB"));
                        continue;
                    }
                    out.put(f.put("size", bytes.length).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)));
                } catch (Exception e) {
                    try {
                        out.put(new JSONObject().put("name", String.valueOf(uri.getLastPathSegment())).put("error", reason(e)));
                    } catch (Exception ignored) {
                        // a JSON put of two strings cannot fail
                    }
                }
            }
            cb.success(out);
        });
    }

    private static byte[] read(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE_BYTES) return null;
            }
            return out.toByteArray();
        }
    }

    // ── voice message: 16 kHz mono PCM, sent as WAV ─────────────────────

    @Override
    public void onRequestPermissionResult(int code, String[] permissions, int[] results) {
        if (code != REQ_MIC || micCallback == null) return;
        CallbackContext cb = micCallback;
        micCallback = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRecording(cb);
        else cb.error("Microphone permission denied");
    }

    private synchronized void startRecording(CallbackContext cb) {
        if (recording) {
            cb.error("already recording");
            return;
        }
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE * 2));
            pcm = new ByteArrayOutputStream();
            recorder.startRecording();
            recording = true;
            recordStarted = System.currentTimeMillis();
            final AudioRecord rec = recorder;
            final ByteArrayOutputStream sinkPcm = pcm;
            recordThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                long cap = (long) SAMPLE_RATE * 2 * MAX_RECORD_SECONDS;
                while (recording && sinkPcm.size() < cap) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) sinkPcm.write(buf, 0, n);
                }
            }, "cloud-chat-mic");
            recordThread.start();
            cb.success(new JSONObject().put("recording", true).put("max_seconds", MAX_RECORD_SECONDS));
        } catch (Exception e) {
            recording = false;
            cb.error(reason(e));
        }
    }

    private void stopRecording(CallbackContext cb) {
        try {
            if (!recording && recorder == null) {
                cb.error("not recording");
                return;
            }
            recording = false;
            if (recordThread != null) recordThread.join(2000);
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) { /* already stopped */ }
                recorder.release();
            }
            recorder = null;
            byte[] data = pcm.toByteArray();
            byte[] wav = wav(data);
            cb.success(new JSONObject()
                .put("mime", "audio/wav").put("format", "wav").put("name", "voice.wav")
                .put("size", wav.length).put("duration_ms", System.currentTimeMillis() - recordStarted)
                .put("base64", Base64.encodeToString(wav, Base64.NO_WRAP)));
        } catch (Exception e) {
            cb.error(reason(e));
        }
    }

    /** A canonical 44-byte RIFF header over 16-bit mono PCM. */
    static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }

    @Override
    public void onDestroy() {
        recording = false;
        for (HttpURLConnection c : live.values()) c.disconnect();
        live.clear();
    }
}
