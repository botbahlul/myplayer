package com.app.myplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import org.json.JSONArray;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class PlaybackService extends MediaSessionService {
    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "playback_channel";
    private static final int NOTIFICATION_ID = 101;
    private Notification startupNotification;

    private String streamUrl = "";
    private ExoPlayer player;
    private MediaSession mediaSession;
    private ExecutorService executor;
    private ArrayList<String> selectedFilesPath;
    private ArrayList<Uri> selectedFilesUri;

    private boolean streamRefreshInProgress = false;
    private boolean streamRefreshPending = false;
    private int streamRefreshAttempts = 0;

    private static final String ACTION_SELECT_FORMAT = "com.app.myplayer.SELECT_FORMAT";

    // Read by MainActivity (one process with service)
    public static volatile String sChoicesJson = "[]";
    public static volatile String sSelectedChoiceId = "auto";

    private volatile String defaultUrl = "";
    private volatile Map<String, String> defaultHeaders = new HashMap<>();

    @UnstableApi
    @Override
    public void onCreate() {
        super.onCreate();

        selectedFilesPath = new ArrayList<>();
        selectedFilesUri = new ArrayList<>();
        executor = Executors.newSingleThreadExecutor();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        player = new ExoPlayer.Builder(this, renderersFactory).build();
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(
                    EventTime eventTime,
                    String decoderName,
                    long initializedTimestampMs,
                    long initializationDurationMs) {
                android.util.Log.i(
                        "MyPlayer-AV1",
                        "Video decoder initialized: " + decoderName
                                + ", initDurationMs=" + initializationDurationMs
                );
            }
        });

        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                for (Tracks.Group g : tracks.getGroups()) {
                    for (int i = 0; i < g.length; i++) {
                        Log.i(TAG, "TRACK type=" + g.getType()
                                + " selected=" + g.isTrackSelected(i)
                                + " supported=" + g.isTrackSupported(i)
                                + " mime=" + g.getTrackFormat(i).sampleMimeType
                                + " codecs=" + g.getTrackFormat(i).codecs);
                    }
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (streamUrl == null
                        || streamUrl.trim().isEmpty()
                        || !streamUrl.startsWith("http")) {
                    return;
                }

                if (isYouTubeUrl(streamUrl)) {
                    return;
                }

                Throwable cause = error;
                boolean accessDenied = false;

                while (cause != null) {
                    if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException httpError =
                                (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause;

                        if (httpError.responseCode == 401 || httpError.responseCode == 403) {
                            accessDenied = true;
                            break;
                        }
                    }
                    cause = cause.getCause();
                }

                if (!accessDenied) {
                    Log.e(TAG, "Playback error is not an HTTP 401/403; no URL refresh.");
                    return;
                }

                if (streamRefreshInProgress) {
                    Log.w(TAG, "Stream refresh already in progress. Skipping duplicate request.");
                    return;
                }

                streamRefreshAttempts++;
                streamRefreshInProgress = true;
                streamRefreshPending = true;

                Log.w(TAG, "HTTP 401/403 detected. Refreshing stream URL, attempt "
                        + streamRefreshAttempts + "/2");

                getStreamUrlAndPlay();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    streamRefreshAttempts = 0;
                    Log.i(TAG, "Playback is active.");
                }
            }
        });

        mediaSession = new MediaSession.Builder(this, player).build();

        createNotificationChannel();

        startupNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("My Player")
                .setContentText("Preparing player...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, startupNotification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null
                && !ACTION_SELECT_FORMAT.equals(intent.getAction())
                && startupNotification != null) {
            startForeground(NOTIFICATION_ID, startupNotification);
        }

        if (intent != null && ACTION_SELECT_FORMAT.equals(intent.getAction())) {
            playChoice(intent.getStringExtra("format_id"));
            return super.onStartCommand(intent, flags, startId);
        }

        if (intent != null && intent.hasExtra("EXTRA_STREAMING_URL")) {
            streamUrl = intent.getStringExtra("EXTRA_STREAMING_URL");
            Log.i(TAG, "Receiving URL from Intent: " + streamUrl);
            getStreamUrlAndPlay();
        }

        if (intent != null && intent.hasExtra("EXTRA_SELECTED_FILES_URI")) {
            selectedFilesUri = intent.getParcelableArrayListExtra("EXTRA_SELECTED_FILES_URI");

            if (selectedFilesUri == null) {
                selectedFilesUri = new ArrayList<>();
            }

            Log.i(TAG, "Receiving selectedFilesUri from MainActivity");

            for (int i = 0; i < selectedFilesUri.size(); i++) {
                Log.i(TAG, "selectedFilesUri[" + i + "] = " + selectedFilesUri.get(i));
            }

            if (!selectedFilesUri.isEmpty()) {
                playLocalFiles(selectedFilesUri);
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private File getQuickJsRuntime() {
        File nativeLibraryDirectory = new File(getApplicationInfo().nativeLibraryDir);
        File qjsFile = new File(nativeLibraryDirectory, "libqjs.so");

        Log.i(TAG, "Native library directory: " + nativeLibraryDirectory.getAbsolutePath());
        Log.i(TAG, "QuickJS path: " + qjsFile.getAbsolutePath());
        Log.i(TAG, "QuickJS exists: " + qjsFile.isFile());
        Log.i(TAG, "QuickJS executable: " + qjsFile.canExecute());

        if (!qjsFile.isFile()) {
            throw new IllegalStateException(
                    "QuickJS binary not found: " + qjsFile.getAbsolutePath()
            );
        }

        if (!qjsFile.canExecute()) {
            throw new IllegalStateException(
                    "QuickJS binary is not executable: " + qjsFile.getAbsolutePath()
            );
        }

        return qjsFile;
    }

    private void getStreamUrlAndPlay() {
        if (streamUrl == null || streamUrl.trim().isEmpty()) {
            Log.e(TAG, "URL is empty, process cancelled.");
            sendError("URL is empty");
            return;
        }

        final String requestedUrl = streamUrl.trim();

        if (executor == null || executor.isShutdown()) {
            Log.e(TAG, "Executor is unavailable");
            sendError("Playback service executor is unavailable");
            return;
        }

        final boolean isYouTube = isYouTubeUrl(requestedUrl);

        executor.execute(() -> {
            try {
                File qjsFile = getQuickJsRuntime();

                Python python = Python.getInstance();
                PyObject module = python.getModule("stream_helper");

                module.callAttr("set_quickjs_path", qjsFile.getAbsolutePath());
                Log.i(TAG, "QuickJS path: " + qjsFile.getAbsolutePath());
                Log.i(TAG, "Resolving URL: " + requestedUrl);
                String resultText;

                if (isYouTube) {
                    Log.i(TAG, "YouTube detected. Using yt-dlp only.");
                    //PyObject result = module.callAttr("get_yt_url", requestedUrl);
                    //resultText = result.toString().trim();
                    PyObject result = module.callAttr("get_yt_stream_and_choices", requestedUrl);
                    resultText = result.toString().trim();
                } else {
                    Log.i(TAG, "Non-YouTube URL detected. Using yt-dlp -> Streamlink router with HTTP headers.");
                    PyObject result = module.callAttr("resolve_stream_data", requestedUrl);
                    resultText = result.toString().trim();
                }

                if (resultText.startsWith("COOKIE_AUTH_REQUIRED:")) {
                    Log.w(TAG, "Cookie authentication needs attention: " + requestedUrl);

                    sendCookieAuthRequired(requestedUrl, false, resultText);
                    return;
                }

                final String resolvedStreamUrl;
                final Map<String, String> streamHeaders = new HashMap<>();

                JSONObject streamData = new JSONObject(resultText);
                resolvedStreamUrl = streamData.getString("url");

                Map<String, String> parsedHeaders = jsonToMap(streamData.optJSONObject("headers"));
                if (!isYouTube) {
                    streamHeaders.putAll(parsedHeaders);   // perilaku lama tetap
                }

                JSONArray choices = streamData.optJSONArray("choices");
                sChoicesJson = (choices != null) ? choices.toString() : "[]";
                sSelectedChoiceId = "auto";
                defaultUrl = resolvedStreamUrl;
                defaultHeaders = parsedHeaders;

                if (resolvedStreamUrl == null
                        || resolvedStreamUrl.trim().isEmpty()
                        || "None".equalsIgnoreCase(resolvedStreamUrl.trim())) {
                    throw new IllegalStateException(
                            isYouTube
                                    ? "yt-dlp returned an empty stream URL"
                                    : "yt-dlp and Streamlink returned an empty stream URL"
                    );
                }

                Uri parsedUri = Uri.parse(resolvedStreamUrl);
                String scheme = parsedUri.getScheme();

                if (!"http".equalsIgnoreCase(scheme)
                        && !"https".equalsIgnoreCase(scheme)) {
                    throw new IllegalStateException(
                            "Resolved stream URL has an unsupported scheme: " + scheme
                    );
                }

                Log.i(TAG, "Stream URL resolved successfully.");
                Log.i(TAG, "Stream host: " + parsedUri.getHost());

                if (!isYouTube) {
                    Log.i(TAG, "HTTP headers received: " + streamHeaders.keySet());
                }

                final String finalStreamUrl = resolvedStreamUrl;

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (player == null) {
                        Log.e(TAG, "Player is unavailable.");
                        sendError("Player is unavailable");
                        return;
                    }

                    if (isYouTube) {
                        playYTStream(finalStreamUrl);
                    } else {
                        playStream(finalStreamUrl, streamHeaders);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Stream URL resolution failed", e);

                streamRefreshInProgress = false;
                streamRefreshPending = false;

                String errorMessage = e.getMessage();
                if (errorMessage == null || errorMessage.trim().isEmpty()) {
                    errorMessage = e.getClass().getSimpleName();
                }
                sendError(errorMessage);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Playback Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager = getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        if (player != null) {
            player.release();
            player = null;
        }

        super.onDestroy();
    }

    private void playLocalFiles(ArrayList<Uri> fileUris) {
        if (fileUris == null || fileUris.isEmpty()) {
            Log.e(TAG, "Local file Uri kosong");
            return;
        }

        ArrayList<MediaItem> mediaItems = new ArrayList<>();

        for (Uri fileUri : fileUris) {
            if (fileUri == null) {
                continue;
            }

            Log.i(TAG, "Adding local file: " + fileUri);
            mediaItems.add(MediaItem.fromUri(fileUri));
        }

        if (mediaItems.isEmpty()) {
            Log.e(TAG, "Tidak ada MediaItem");
            return;
        }

        if (player == null) {
            Log.e(TAG, "Player is unavailable");
            return;
        }

        sChoicesJson = "[]";
        sSelectedChoiceId = "auto";

        player.setMediaItems(mediaItems);
        player.prepare();
        player.play();

        Log.i(TAG, "Playing local playlist, count = " + mediaItems.size());
    }

    private void sendError(String message) {
        Intent intent = new Intent("com.app.myplayer.STREAMLINK_ERROR");
        intent.setPackage(getPackageName());
        intent.putExtra("error", message);
        sendBroadcast(intent);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playYTStream(String streamUrl) {
        if (player == null) {
            Log.e(TAG, "Cannot play stream: player is null");
            return;
        }

        DefaultHttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent(
                                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                                        + "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
                        );

        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpDataSourceFactory);
        player.setMediaSource(mediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl))));
        player.prepare();
        player.play();
        Log.i(TAG, "Playback started");
    }

    private boolean isYouTubeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String host = Uri.parse(url.trim()).getHost();

        if (host == null) {
            return false;
        }

        host = host.toLowerCase(Locale.ROOT);

        return host.equals("youtu.be")
                || host.equals("youtube.com")
                || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com")
                || host.endsWith(".youtube-nocookie.com");
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playStream(String streamUrl, Map<String, String> headers) {
        try {
            if (player == null) {
                Log.e(TAG, "Cannot play stream: player is null");
                sendError("Player is unavailable");
                return;
            }

            Map<String, String> requestHeaders = new HashMap<>();

            if (headers != null) {
                requestHeaders.putAll(headers);
            }

            String userAgent = null;

            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                if ("User-Agent".equalsIgnoreCase(entry.getKey())) {
                    userAgent = entry.getValue();
                    break;
                }
            }

            if (userAgent == null || userAgent.trim().isEmpty()) {
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:157.0) Gecko/20100101 Firefox/157.0";
                requestHeaders.put("User-Agent", userAgent);
            }

            Log.i(TAG, "========== PLAY STREAM DIAGNOSTICS ==========");
            Log.i(TAG, "Stream host: " + Uri.parse(streamUrl).getHost());
            Log.i(TAG, "Stream path: " + Uri.parse(streamUrl).getPath());
            Log.i(TAG, "Stream scheme: " + Uri.parse(streamUrl).getScheme());
            Log.i(TAG, "User-Agent: " + userAgent);
            Log.i(TAG, "Request header names: " + requestHeaders.keySet());

            DefaultHttpDataSource.Factory dataSourceFactory =
                    new DefaultHttpDataSource.Factory()
                            .setUserAgent(userAgent)
                            .setDefaultRequestProperties(requestHeaders)
                            .setConnectTimeoutMs(15000)
                            .setReadTimeoutMs(20000)
                            .setAllowCrossProtocolRedirects(true);

            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(streamUrl));

            String lowerUrl = streamUrl.toLowerCase(Locale.ROOT);

            if (lowerUrl.contains(".m3u8")  || lowerUrl.contains("vidio.com")) {
                mediaItemBuilder.setMimeType("application/x-mpegURL");
                Log.i(TAG, "Detected HLS stream");
            } else {
                Log.i(TAG, "Stream type will be detected by Media3");
            }

            MediaItem mediaItem = mediaItemBuilder.build();

            MediaSource mediaSource = new DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem);

            player.setMediaSource(mediaSource);

            if (streamRefreshPending) {
                Log.i(TAG, "Refreshing stream at the default playback position.");
                player.seekToDefaultPosition();
                streamRefreshPending = false;
            }

            streamRefreshInProgress = false;

            player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                @Override
                public void onLoadError(
                        androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime eventTime,
                        androidx.media3.exoplayer.source.LoadEventInfo loadEventInfo,
                        androidx.media3.exoplayer.source.MediaLoadData mediaLoadData,
                        java.io.IOException error,
                        boolean wasCanceled
                ) {
                    Uri errorUri = loadEventInfo.uri;

                    Log.e(TAG, "========== MEDIA3 LOAD ERROR ==========");
                    Log.e(TAG, "Load error host: " + errorUri.getHost());
                    Log.e(TAG, "Load error path: " + errorUri.getPath());
                    Log.e(TAG, "Load error type: " + mediaLoadData.dataType);
                    Log.e(TAG, "Load canceled: " + wasCanceled);
                    Log.e(TAG, "Load error message: " + error.getMessage());

                    Throwable cause = error;

                    while (cause != null) {
                        Log.e(TAG, "Error cause: "
                                + cause.getClass().getName()
                                + ": " + cause.getMessage());

                        if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                            androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException httpError =
                                    (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause;

                            Log.e(TAG, "HTTP status: " + httpError.responseCode);
                            Log.e(TAG, "HTTP response headers: " + httpError.headerFields);

                            byte[] responseBody = httpError.responseBody;

                            if (responseBody != null && responseBody.length > 0) {
                                String body = new String(
                                        responseBody,
                                        java.nio.charset.StandardCharsets.UTF_8
                                );

                                if (body.length() > 1000) {
                                    body = body.substring(0, 1000);
                                }

                                Log.e(TAG, "HTTP response body: " + body);
                            } else {
                                Log.e(TAG, "HTTP response body: <empty>");
                            }
                        }

                        cause = cause.getCause();
                    }
                }
            });

            player.prepare();
            player.play();

            Log.i(TAG, "Playing stream with HTTP headers: " + requestHeaders.keySet());
            Log.i(TAG, "========== END PLAY STREAM DIAGNOSTICS ==========");

        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare stream. Host: "
                    + Uri.parse(streamUrl).getHost(), e);

            Throwable cause = e;

            while (cause != null) {
                Log.e(TAG, "Exception cause: "
                        + cause.getClass().getName()
                        + ": " + cause.getMessage());

                if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException httpError =
                            (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause;

                    Log.e(TAG, "HTTP error code: " + httpError.responseCode);
                    Log.e(TAG, "HTTP response headers: " + httpError.headerFields);

                    byte[] responseBody = httpError.responseBody;

                    if (responseBody != null && responseBody.length > 0) {
                        String body = new String(
                                responseBody,
                                java.nio.charset.StandardCharsets.UTF_8
                        );

                        if (body.length() > 1000) {
                            body = body.substring(0, 1000);
                        }

                        Log.e(TAG, "HTTP error response body: " + body);
                    }

                    break;
                }

                cause = cause.getCause();
            }

            sendError("Failed to play stream: " + e.getMessage());
        }
    }

    private void sendCookieAuthRequired(String url, boolean av1Test, String details) {
        Intent intent = new Intent("com.app.myplayer.STREAMLINK_ERROR");
        intent.setPackage(getPackageName());
        intent.putExtra("cookie_auth_required", true);
        intent.putExtra("request_url", url);
        intent.putExtra("av1_test", av1Test);
        intent.putExtra("error", "Cookie authentication required. " + details);
        sendBroadcast(intent);
    }

    @androidx.annotation.OptIn(markerClass = UnstableApi.class)
    private void playAv1WithAudio(String videoUrl, String audioUrl, Map<String, String> videoHeaders, Map<String, String> audioHeaders) {
        try {
            if (player == null) {
                Log.e(TAG, "[AV1 TEST] Player is null");
                return;
            }

            DefaultHttpDataSource.Factory videoFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(videoHeaders)
                    .setAllowCrossProtocolRedirects(true);

            DefaultHttpDataSource.Factory audioFactory = new DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(audioHeaders)
                    .setAllowCrossProtocolRedirects(true);

            MediaItem videoItem = new MediaItem.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setMimeType("application/x-mpegURL")
                    .build();

            MediaItem audioItem = new MediaItem.Builder()
                    .setUri(Uri.parse(audioUrl))
                    .setMimeType("application/x-mpegURL")
                    .build();

            MediaSource videoSource = new DefaultMediaSourceFactory(videoFactory)
                    .createMediaSource(videoItem);

            MediaSource audioSource = new DefaultMediaSourceFactory(audioFactory)
                    .createMediaSource(audioItem);

            Log.i(TAG, "[AV1 TEST] Preparing AV1 video + separate audio");

            player.setMediaSource(new MergingMediaSource(videoSource, audioSource));
            player.prepare();
            player.play();

        } catch (Exception e) {
            Log.e(TAG, "[AV1 TEST] Failed to play merged audio/video", e);
        }
    }

    private static Map<String, String> jsonToMap(JSONObject json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            String v = json.optString(k, "");
            if (!v.isEmpty()) map.put(k, v);
        }
        return map;
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaSource buildSource(String url, Map<String, String> headers, boolean hls) {
        Map<String, String> h = new HashMap<>(headers);
        String ua = null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if ("User-Agent".equalsIgnoreCase(e.getKey())) { ua = e.getValue(); break; }
        }
        if (ua == null) {
            ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36";
        }

        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(h)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);

        return new DefaultMediaSourceFactory(factory).createMediaSource(item.build());
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playChoice(String id) {
        if (player == null || id == null) return;

        try {
            long pos = player.isCurrentMediaItemLive() ? C.TIME_UNSET : player.getCurrentPosition();
            boolean wasPlaying = player.getPlayWhenReady();

            MediaSource source;
            int maxHeight = 0;

            if ("auto".equals(id)) {
                if (defaultUrl.isEmpty()) return;
                source = buildSource(defaultUrl, defaultHeaders, defaultUrl.contains(".m3u8"));
            } else {
                JSONArray arr = new JSONArray(sChoicesJson);
                JSONObject choice = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.getJSONObject(i);
                    if (id.equals(c.optString("id"))) { choice = c; break; }
                }
                if (choice == null) {
                    Log.w(TAG, "Format not found: " + id);
                    return;
                }

                maxHeight = choice.optInt("max_height", 0);   // <-- baru
                JSONObject v = choice.getJSONObject("video");
                source = buildSource(v.getString("url"),
                        jsonToMap(v.optJSONObject("headers")), v.optBoolean("hls"));

                JSONObject a = choice.optJSONObject("audio");
                if (a != null) {
                    MediaSource audioSource = buildSource(a.getString("url"),
                            jsonToMap(a.optJSONObject("headers")), a.optBoolean("hls"));
                    source = new MergingMediaSource(source, audioSource);
                }
            }

            TrackSelectionParameters.Builder tsp = player.getTrackSelectionParameters().buildUpon();
            if (maxHeight > 0) {
                tsp.setMaxVideoSize(Integer.MAX_VALUE, maxHeight);
            } else {
                tsp.clearVideoSizeConstraints();
            }

            player.setTrackSelectionParameters(tsp.build());
            player.setMediaSource(source, pos);   // lanjut dari posisi terakhir
            player.prepare();
            player.setPlayWhenReady(wasPlaying);
            sSelectedChoiceId = id;
            Log.i(TAG, "Switched to format: " + id);

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch format", e);
            sendError("Failed to switch quality: " + e.getMessage());
        }
    }

    private Map<String, String> jsonToHeaders(JSONObject json) {
        Map<String, String> headers = new HashMap<>();

        if (json == null) {
            return headers;
        }

        Iterator<String> keys = json.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            String value = json.optString(key, "");

            if (!value.isEmpty()) {
                headers.put(key, value);
            }
        }

        return headers;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playAv1WithSeparateAudio(
            String videoUrl,
            String audioUrl,
            Map<String, String> videoHeaders,
            Map<String, String> audioHeaders) {
        try {
            DefaultHttpDataSource.Factory videoDataFactory =
                    new DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(videoHeaders)
                            .setConnectTimeoutMs(15000)
                            .setReadTimeoutMs(20000)
                            .setAllowCrossProtocolRedirects(true);

            DefaultHttpDataSource.Factory audioDataFactory =
                    new DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(audioHeaders)
                            .setConnectTimeoutMs(15000)
                            .setReadTimeoutMs(20000)
                            .setAllowCrossProtocolRedirects(true);

            MediaItem videoItem = new MediaItem.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build();

            MediaItem audioItem = new MediaItem.Builder()
                    .setUri(Uri.parse(audioUrl))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build();

            MediaSource videoSource = new DefaultMediaSourceFactory(videoDataFactory)
                    .createMediaSource(videoItem);

            MediaSource audioSource = new DefaultMediaSourceFactory(audioDataFactory)
                    .createMediaSource(audioItem);

            MergingMediaSource mergedSource = new MergingMediaSource(videoSource, audioSource);

            streamUrl = videoUrl;

            player.stop();
            player.setMediaSource(mergedSource);
            player.prepare();
            player.play();
            Log.i(TAG, "[AV1 TEST] Playing separate AV1 video + audio");

        } catch (Exception e) {
            Log.e(TAG, "[AV1 TEST] Failed to start merged playback", e);
            sendError("Failed to play Vimeo AV1 video/audio: " + e.getMessage());
        }
    }

}
