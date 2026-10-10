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
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends MediaSessionService {
    private static final String TAG = "PlaybackService";
    private static final String CHANNEL_ID = "playback_channel";
    private static final int NOTIFICATION_ID = 101;

    private String streamUrl = "";
    private ExoPlayer player;
    private MediaSession mediaSession;
    private ExecutorService executor;
    private ArrayList<String> selectedFilesPath;
    private ArrayList<Uri> selectedFilesUri;

    @Override
    public void onCreate() {
        super.onCreate();

        selectedFilesPath = new ArrayList<>();
        selectedFilesUri = new ArrayList<>();
        executor = Executors.newSingleThreadExecutor();

        player = new ExoPlayer.Builder(this).build();
        mediaSession = new MediaSession.Builder(this, player).build();

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("My Player")
                .setContentText("Preparing player...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
            sendStreamlinkError("URL is empty");
            return;
        }

        final String requestedUrl = streamUrl;

        if (executor == null || executor.isShutdown()) {
            Log.e(TAG, "Executor is unavailable");
            sendStreamlinkError("Playback service executor is unavailable");
            return;
        }

        if (isYouTubeUrl(streamUrl)) {
            // UNTUK YOUTUBE: yt-dlp + QuickJS
            executor.execute(() -> {
                try {
                    File qjsFile = getQuickJsRuntime();

                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(getApplicationContext()));
                    }

                    Python python = Python.getInstance();
                    PyObject module = python.getModule("stream_helper");

                    module.callAttr("set_quickjs_path", qjsFile.getAbsolutePath());

                    Log.i(TAG, "QuickJS path passed to Python: " + qjsFile.getAbsolutePath());
                    Log.i(TAG, "Resolving stream URL: " + requestedUrl);


                    PyObject result = module.callAttr("get_yt_url", requestedUrl);
                    String resolvedStreamUrl = result.toString();

                    if (resolvedStreamUrl.startsWith("COOKIE_AUTH_REQUIRED:")) {
                        Log.w(TAG, "YouTube cookie authentication needs attention");

                        Intent authIntent = new Intent("com.app.myplayer.STREAMLINK_ERROR");
                        authIntent.setPackage(getPackageName());
                        authIntent.putExtra("error", "YouTube requires cookie re-import.");
                        authIntent.putExtra("cookie_auth_required", true);
                        authIntent.putExtra("youtube_url", requestedUrl);
                        sendBroadcast(authIntent);
                        return;
                    }

                    if (resolvedStreamUrl.trim().isEmpty()
                            || "None".equals(resolvedStreamUrl)) {
                        throw new IllegalStateException(
                                "yt-dlp returned an empty stream URL"
                        );
                    }

                    Uri parsedUri = Uri.parse(resolvedStreamUrl);

                    if (!"http".equalsIgnoreCase(parsedUri.getScheme())
                            && !"https".equalsIgnoreCase(parsedUri.getScheme())) {
                        throw new IllegalStateException(
                                "yt-dlp returned an unsupported stream URL scheme"
                        );
                    }

                    Log.i(TAG, "Stream URL resolved successfully");

                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (player != null) {
                            playYTStream(resolvedStreamUrl);
                        } else {
                            Log.e(TAG, "Player is unavailable");
                            sendStreamlinkError("Player is unavailable");
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Stream URL resolution failed", e);

                    String errorMessage = e.getMessage();

                    if (errorMessage == null || errorMessage.trim().isEmpty()) {
                        errorMessage = e.getClass().getSimpleName();
                    }

                    sendStreamlinkError(errorMessage);
                }
            });

        } else {

            executor.execute(() -> {
                try {
                    Log.i(TAG, "Starting Streamlink...");
                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(this));
                    }
                    Python python = Python.getInstance();
                    PyObject module = python.getModule("stream_helper");
                    Log.i(TAG, "Checking Streamlink URL: " + streamUrl);
                    PyObject validResult = module.callAttr("is_streaming_url", streamUrl);
                    boolean valid = validResult.toBoolean();
                    if (!valid) {
                        Log.e(TAG, "Streamlink: no stream found");
                        sendStreamlinkError("URL is not a valid Streamlink streaming URL");
                        return;
                    }
                    Log.i(TAG, "Streamlink URL is valid");
                    PyObject result = module.callAttr("get_stream_url", streamUrl);
                    String streamUrl = result.toString();
                    Log.i(TAG, "Stream URL: " + streamUrl);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> playStream(streamUrl));
                } catch (Exception e) {
                    Log.e(TAG, "Streamlink failed", e);
                    sendStreamlinkError(e.getMessage() != null ? e.getMessage() : "Streamlink error");
                }
            });
        }
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

        player.setMediaItems(mediaItems);
        player.prepare();
        player.play();

        Log.i(TAG, "Playing local playlist, count = " + mediaItems.size());
    }

    private void sendStreamlinkError(String message) {
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

        DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(httpDataSourceFactory);

        player.setMediaSource(
                mediaSourceFactory.createMediaSource(
                        MediaItem.fromUri(Uri.parse(streamUrl))
                )
        );

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
    private void playStream(String streamUrl) {

        DefaultHttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36");

        androidx.media3.exoplayer.source.DefaultMediaSourceFactory mediaSourceFactory =
                new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpDataSourceFactory);

        player.setMediaSource(
                mediaSourceFactory.createMediaSource(
                        MediaItem.fromUri(Uri.parse(streamUrl))
                )
        );

        player.prepare();
        player.play();
    }

}
