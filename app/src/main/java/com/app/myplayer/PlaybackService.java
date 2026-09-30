package com.app.myplayer; // Sesuaikan dengan package Anda

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.File;

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
        // Initialize Player & MediaSession
        player = new ExoPlayer.Builder(this).build();
        mediaSession = new MediaSession.Builder(this, player).build();
        // Make Notification Channel
        createNotificationChannel();

        // Running Foreground Service with initial notification
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
            //if (streamUrl.isEmpty()) {
            //    streamUrl = STREAMING_URL.STRING;
            //}
            getStreamUrlAndPlay();
        }

        //if (intent != null && intent.hasExtra("EXTRA_SELECTED_FILES_PATH")) {
        if (intent != null && intent.hasExtra("EXTRA_SELECTED_FILES_URI")) {
            //selectedFilesPath = intent.getStringArrayListExtra("EXTRA_SELECTED_FILES_PATH");
            //if (selectedFilesPath == null) {
            //    selectedFilesPath = new ArrayList<>();
            //}
            //Log.i(TAG, "Receiving selectedFilesPath from MainActivity");
            //for (int i = 0; i < selectedFilesPath.size(); i++) {
            //    Log.i(TAG, "selectedFilesPath [" + i + "] = " + selectedFilesPath.get(i));
            //}
            //if (!selectedFilesPath.isEmpty()) {
            //    playLocalFile(selectedFilesPath.get(0));
            //}

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

    private void getStreamUrlAndPlay() {
        if (streamUrl == null || streamUrl.isEmpty()) {
            Log.e(TAG, "URL is empty, process cancelled.");
            return;
        }
        executor.execute(() -> {
            try {
                Log.i(TAG, "Starting Streamlink...");
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(this));
                }
                Python python = Python.getInstance();
                PyObject module = python.getModule("streamlink_helper");
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
                //new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> playHls(streamUrl));
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> playStream(streamUrl));
            } catch (Exception e) {
                Log.e(TAG, "Streamlink failed", e);
                sendStreamlinkError(e.getMessage() != null ? e.getMessage() : "Streamlink error");
            }
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playHls(String streamUrl) {
        DefaultHttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36");

        HlsMediaSource hlsMediaSource =
                new HlsMediaSource.Factory(httpDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));

        player.setMediaSource(hlsMediaSource);
        player.prepare();
        player.play();
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
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    /*
    private void playLocalFile(String filePath) {

        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "Local file path kosong");
            return;
        }

        File file = new File(filePath);

        Log.i(TAG, "Local file path: " + filePath);
        Log.i(TAG, "Local file exists: " + file.exists());
        Log.i(TAG, "Local file readable: " + file.canRead());
        Log.i(TAG, "Local file length: " + file.length());

        if (!file.exists()) {
            Log.e(TAG, "Local file tidak ditemukan");
            return;
        }

        MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(file));

        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();

        Log.i(TAG, "Playing local file: " + filePath);
    }
    */

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
        player.setMediaItems(mediaItems);
        player.prepare();
        player.play();
        Log.i(TAG, "Playing local playlist, count = " + mediaItems.size());
    }

    private void sendStreamlinkError(String message) {
        Intent intent = new Intent("com.app.myplayer.STREAMLINK_ERROR");
        intent.putExtra("error", message);
        sendBroadcast(intent);
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
