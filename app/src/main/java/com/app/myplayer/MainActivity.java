package com.app.myplayer;

import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;
import static android.provider.MediaStore.AUTHORITY;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.Manifest;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.DefaultTimeBar;
import android.view.ViewGroup;
import androidx.media3.ui.TimeBar;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import android.widget.FrameLayout;

import android.view.inputmethod.InputMethodManager;
import android.content.Context;

@UnstableApi
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MyPlayer";

    private String STREAMING_URL = "";

    private PlayerView playerView;
    private EditText urlInput;
    private Button buttonEmbed;
    private Button buttonOpenLocalFiles;
    private TextView textViewSelectedFilePath;
    private Button buttonGrantStoragePermission;
    private Button buttonGrantManageAppAllFilesAccessPermission;
    private Button buttonGrantPersistedttreeUriPermission;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController mediaController;

    private ArrayList<Uri> selectedFilesUri;
    private ArrayList<String> selectedFilesPath;
    private ArrayList<String> selectedFilesDisplayName;
    private String selectedFolderPath;
    private Uri selectedFolderUri;
    private ArrayList<Uri> savedTreesUri;
    private boolean alreadySaved;
    int STORAGE_PERMISSION_CODE = 101;

    private ViewGroup playerParent;
    private ViewGroup.LayoutParams playerLayoutParams;
    private int playerIndex;
    private FrameLayout fullscreenContainer;
    private View root;
    private View rootLayout;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.url_input);
        playerView = findViewById(R.id.player_view);
        playerView = findViewById(R.id.player_view);
        setTimeBarColor(playerView);
        buttonEmbed = findViewById(R.id.button_embed);
        buttonOpenLocalFiles = findViewById(R.id.button_open_local_files);
        textViewSelectedFilePath = findViewById(R.id.textview_filePath);
        buttonGrantStoragePermission = findViewById(R.id.button_grant_storage_permission);
        buttonGrantManageAppAllFilesAccessPermission = findViewById(R.id.button_grant_manage_app_all_files_access_permission);
        buttonGrantPersistedttreeUriPermission = findViewById(R.id.button_grant_persisted_tree_uri_permission);
        root = findViewById(R.id.root);
        rootLayout = findViewById(R.id.root_layout);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        rootLayout.setFocusable(true);
        rootLayout.setFocusableInTouchMode(true);
        root.requestFocus();

        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                return;
            }
            Log.d(TAG, "urlInput got focus");
        });

        root.setOnClickListener(view -> {
            urlInput.clearFocus();
            root.requestFocus();
            Log.d(TAG, "root request focus");
        });

        rootLayout.setOnClickListener(view -> {
            urlInput.clearFocus();
            rootLayout.requestFocus();
            Log.d(TAG, "rootLayout request focus");
        });

        root.setOnFocusChangeListener((v, hasFocus) -> {
            Log.d(TAG, "root focus = " + hasFocus);
        });

        rootLayout.setOnFocusChangeListener((v, hasFocus) -> {
            Log.d(TAG, "rootLayout focus = " + hasFocus);
        });

        textViewSelectedFilePath.setOnFocusChangeListener((v, hasFocus) -> {
            Log.d(TAG, "textViewSelectedFilePath focus = " + hasFocus);
        });

        buttonEmbed.setOnClickListener(view -> {
            STREAMING_URL = urlInput.getText().toString().trim(); // Must be declare before check isEmpty()
            if (!STREAMING_URL.isEmpty()) {
                urlInput.clearFocus();
                buttonEmbed.requestFocus();
                Log.d(TAG, "buttonEmbed request focus");
                Log.i(TAG, "Starting PlaybackService");
                Log.i(TAG, "YouTube URL: " + STREAMING_URL);

                Intent intent = new Intent(MainActivity.this, PlaybackService.class);
                intent.putExtra("EXTRA_STREAMING_URL", STREAMING_URL);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } else {
                Toast.makeText(MainActivity.this, "URL can't be empty", Toast.LENGTH_SHORT).show();
            }
        });

        buttonOpenLocalFiles.setOnClickListener(view -> {
            urlInput.clearFocus();
            buttonOpenLocalFiles.requestFocus();
            Log.d(TAG, "buttonOpenLocalFiles request focus");
            selectedFilesUri = null;
            selectedFilesUri = new ArrayList<>();
            selectedFilesPath = null;
            selectedFilesPath = new ArrayList<>();
            selectedFilesDisplayName = null;
            selectedFilesDisplayName = new ArrayList<>();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            String[] mimeTypes = {"video/*", "audio/*"};
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startForBrowseFileActivity.launch(intent);
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.VISIBLE);
        } else {
            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.GONE);
        }

        buttonGrantStoragePermission.setOnClickListener(view -> {
            urlInput.clearFocus();
            buttonGrantStoragePermission.requestFocus();
            Log.d(TAG, "buttonGrantStoragePermission request focus");
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            buttonGrantManageAppAllFilesAccessPermission.setOnClickListener(view -> {
                urlInput.clearFocus();
                buttonGrantManageAppAllFilesAccessPermission.requestFocus();
                Log.d(TAG, "buttonGrantManageAppAllFilesAccessPermission request focus");
                if (!Environment.isExternalStorageManager()) {
                    try {
                        Uri uri = Uri.parse("package:${BuildConfig.LIBRARY_PACKAGE_NAME}");
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
                        intent.addCategory("android.intent.category.DEFAULT");
                        intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                        startForRequestManageAppAllFileAccessPermissionActivity.launch(intent);
                    }
                    catch (Exception e) {
                        if (e.getMessage() != null) {
                            Log.e("Exception: ", e.getMessage());
                            e.printStackTrace();
                        }
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                }
            });
        }

        buttonGrantPersistedttreeUriPermission.setOnClickListener(view -> {
            urlInput.clearFocus();
            buttonGrantPersistedttreeUriPermission.requestFocus();
            Log.d(TAG, "buttonGrantPersistedttreeUriPermission request focus");
            savedTreesUri = loadSavedTreeUrisFromSharedPreference();
            Log.d("onCreated", "savedTreesUri.size() = " + savedTreesUri.size());
            for (int i=0; i<savedTreesUri.size(); i++) {
                Log.d("onCreated", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
            }
            requestTreeUriPermissions();
        });

        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
        }

        savedTreesUri = loadSavedTreeUrisFromSharedPreference();
        Log.d("onCreated", "savedTreesUri.size() = " + savedTreesUri.size());
        for (int i=0; i<savedTreesUri.size(); i++) {
            Log.d("onCreated", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
        }
        if (savedTreesUri.size() == 0) {
            requestTreeUriPermissions();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Uri uri = Uri.parse("package:${BuildConfig.LIBRARY_PACKAGE_NAME}");
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startForRequestManageAppAllFileAccessPermissionActivity.launch(intent);
                }
                catch (Exception e) {
                    if (e.getMessage() != null) {
                        Log.e("Exception: ", e.getMessage());
                        e.printStackTrace();
                    }
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }

        // REMOVE BUTTONS IF PERMISSIONS HAVE ALREADY GRANTED BECAUSE THERE'S NO ANY WAY TO REVOKE PERMISSION PROGRAMMATICALLY
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            buttonGrantStoragePermission.setVisibility(View.GONE);
        } else {
            buttonGrantStoragePermission.setVisibility(View.VISIBLE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.GONE);
            }
            else {
                buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.VISIBLE);
            }
        }
        savedTreesUri = loadSavedTreeUrisFromSharedPreference();
        Log.d("onCreated", "savedTreesUri.size() = " + savedTreesUri.size());
        for (int i=0; i<savedTreesUri.size(); i++) {
            Log.d("onCreated", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
        }

        if (!isInternetAvailable()) {
            Toast.makeText(this, "It seems that you're not connected to internet, this app won't work without internet connection", Toast.LENGTH_SHORT).show();
        }

        try {
            Class.forName("dalvik.system.CloseGuard")
                    .getMethod("setEnabled", boolean.class)
                    .invoke(null, true);
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View currentFocus = getCurrentFocus();

            if (currentFocus == urlInput) {
                Rect rect = new Rect();
                urlInput.getGlobalVisibleRect(rect);

                if (!rect.contains((int) event.getRawX(), (int) event.getRawY())) {
                    urlInput.clearFocus();

                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
                    }
                }
            }
        }

        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantresults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantresults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantresults.length > 0 && grantresults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.v("onRequestPermissionsResult","Permission: " + permissions[0] + " was "+ grantresults[0]);

                buttonGrantStoragePermission.setVisibility(View.GONE);
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();

                savedTreesUri = loadSavedTreeUrisFromSharedPreference();
                Log.d("onRequestPermissionsResult", "savedTreesUri.size() = " + savedTreesUri.size());
                if (savedTreesUri.size() > 0) {
                    Toast.makeText(this, "Persisted tree uri permission currently is granted for folders :\n", Toast.LENGTH_SHORT).show();
                    for (int i=0; i<savedTreesUri.size(); i++) {
                        Toast.makeText(this, TreeUri2Path(savedTreesUri.get(i)) + "\n", Toast.LENGTH_SHORT).show();
                        Log.d("onRequestPermissionsResult", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
                        Log.d("onRequestPermissionsResult", "TreeUri2Path(savedTreesUri.get(" + i + ")) = " + TreeUri2Path(savedTreesUri.get(i)));
                    }
                    if (selectedFilesPath != null && selectedFilesPath.size()>0) {
                        if (isTreeUriPermissionGrantedForDirPathOfFilePath(selectedFilesPath.get(0))) {
                            Toast.makeText(this, "Persisted tree uri permission is granted for :\n" + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                            Log.d("onRequestPermissionsResult", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                        } else {
                            Toast.makeText(this, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                            Log.d("onRequestPermissionsResult", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                        }
                    } else {
                        //appendText(textview_output_messages, "All subtitle files will be saved into your selected folder.");
                    }
                }
                else {
                    Toast.makeText(this, "Persisted tree uri permission is not granted for any folders.\n", Toast.LENGTH_SHORT).show();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (Environment.isExternalStorageManager()) {
                            buttonGrantStoragePermission.setVisibility(View.GONE);
                            Toast.makeText(this, "Manage app all files access permission is granted", Toast.LENGTH_SHORT).show();
                        } else {
                            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.VISIBLE);
                            Toast.makeText(this, "Manage app all files access permission is not granted", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.GONE);
                    }
                }
            } else {
                buttonGrantStoragePermission.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Storage permission is not granted, this app won't work", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // CONNECT MAIN ACTIVITY TO PLAYBACK SERVICE
    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter("com.app.myplayer.STREAMLINK_ERROR");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamlinkErrorReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(streamlinkErrorReceiver, filter);
        }

        Log.i(TAG, "Connecting MediaController...");
        SessionToken sessionToken = new SessionToken(
                this,
                new ComponentName(
                        this,
                        PlaybackService.class
                )
        );

        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                Log.i(TAG, "MediaController connected");
                // Hubungkan PlayerView ke ExoPlayeryang berada di PlaybackService melalui MediaController
                playerView.setPlayer(mediaController);
                playerView.setFullscreenButtonClickListener(isFullscreen -> {
                    if (isFullscreen) {
                        enterFullscreen();
                        playerView.setShowSubtitleButton(true);
                    } else {
                        exitFullscreen();
                        playerView.setShowSubtitleButton(true);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect MediaController", e);
            }

        }, ContextCompat.getMainExecutor(this));
        playerView.setShowSubtitleButton(true);
    }

    // DISCONNECT FROM PLAYBACK SERVICE
    @Override
    protected void onStop() {
        unregisterReceiver(streamlinkErrorReceiver);
        // Don't release ExoPlayer here, ExoPlayer owned by PlaybackService
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        mediaController = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        finish();
    }

    ActivityResultLauncher<Intent> startForBrowseFileActivity = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult result) {
            if (result.getResultCode() == Activity.RESULT_OK) {
                Intent intent = result.getData();

                selectedFilesUri.clear();
                selectedFilesPath.clear();
                selectedFilesDisplayName.clear();
                selectedFolderPath = null;
                selectedFolderUri = null;

                if (intent != null && intent.getClipData() == null) {
                    Log.d("startForBrowseFileActivity", "Single file selected");

                    Uri fileUri = intent.getData();

                    if (fileUri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    fileUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (SecurityException e) {
                            Log.w(
                                    "startForBrowseFileActivity",
                                    "Tidak bisa mengambil persistable URI permission",
                                    e
                            );
                        }

                        selectedFilesUri.add(fileUri);

                        String selectedFilePath = Uri2Path(getApplicationContext(), fileUri);
                        selectedFilesPath.add(selectedFilePath);

                        String fileDisplayName = queryName(getApplicationContext(), fileUri);
                        selectedFilesDisplayName.add(fileDisplayName);

                        if (selectedFilePath != null) {
                            selectedFolderPath = new File(selectedFilePath).getParent();
                        }
                    }

                } else if (intent != null && intent.getClipData() != null) {
                    Log.d("startForBrowseFileActivity", "Multiple files selected");

                    ClipData clipData = intent.getClipData();

                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        Uri fileUri = clipData.getItemAt(i).getUri();

                        if (fileUri == null) {
                            continue;
                        }

                        try {
                            getContentResolver().takePersistableUriPermission(
                                    fileUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (SecurityException e) {
                            Log.w(
                                    "startForBrowseFileActivity",
                                    "Tidak bisa mengambil persistable URI permission: " + fileUri,
                                    e
                            );
                        }

                        selectedFilesUri.add(fileUri);

                        String selectedFilePath = Uri2Path(getApplicationContext(), fileUri);
                        selectedFilesPath.add(selectedFilePath);

                        String fileDisplayName = queryName(getApplicationContext(), fileUri);
                        selectedFilesDisplayName.add(fileDisplayName);

                        if (selectedFilePath != null && selectedFolderPath == null) {
                            selectedFolderPath = new File(selectedFilePath).getParent();
                        }

                        Log.d(
                                "startForBrowseFileActivity",
                                "selectedFilesUri[" + i + "] = " + fileUri
                        );
                    }
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(
                                MainActivity.this,
                                "Please select at least 1 media file",
                                Toast.LENGTH_SHORT
                        ).show();
                    });

                    return;
                }

                runOnUiThread(() -> {
                    textViewSelectedFilePath.setText("");

                    for (int i = 0; i < selectedFilesPath.size(); i++) {
                        String t2 = selectedFilesPath.get(i);
                        textViewSelectedFilePath.append(t2 + "\n");
                    }
                });

                Log.i(
                        TAG,
                        "Total selected files = " + selectedFilesUri.size()
                );

                Intent playbackIntent = new Intent(
                        MainActivity.this,
                        PlaybackService.class
                );

                playbackIntent.putParcelableArrayListExtra(
                        "EXTRA_SELECTED_FILES_URI",
                        selectedFilesUri
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(playbackIntent);
                } else {
                    startService(playbackIntent);
                }
            }
        }
    });

    ActivityResultLauncher<Intent> startForRequestManageAppAllFileAccessPermissionActivity = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (Environment.isExternalStorageManager()) {
                            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, "\"Manage app all files access permission is granted", Toast.LENGTH_SHORT).show();

                            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                buttonGrantStoragePermission.setVisibility(View.GONE);
                                Toast.makeText(MainActivity.this, "Storage permission is granted", Toast.LENGTH_SHORT).show();

                                savedTreesUri = loadSavedTreeUrisFromSharedPreference();
                                Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "savedTreesUri.size() = " + savedTreesUri.size());
                                if (savedTreesUri.size() > 0) {
                                    Toast.makeText(MainActivity.this, "Persisted tree uri permission currently is granted for folders :\n", Toast.LENGTH_SHORT).show();
                                    for (int i=0; i<savedTreesUri.size(); i++) {
                                        Toast.makeText(MainActivity.this, TreeUri2Path(savedTreesUri.get(i)) + "\n", Toast.LENGTH_SHORT).show();
                                        Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
                                        Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "TreeUri2Path(savedTreesUri.get(" + i + ")) = " + TreeUri2Path(savedTreesUri.get(i)));
                                    }
                                    if (selectedFilesPath.size()>0) {
                                        if (isTreeUriPermissionGrantedForDirPathOfFilePath(selectedFilesPath.get(0))) {
                                            Toast.makeText(MainActivity.this, "Persisted tree uri permission is granted for :\n" + new File(selectedFilesPath.get(0)).getParent(), Toast.LENGTH_SHORT).show();
                                            Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                            ////appendText(textview_output_messages, "All subtitle files will be saved into :\n" + new File(selectedFilesPath.get(0)).getParent() + "\n");
                                        } else {
                                            Toast.makeText(MainActivity.this, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                                            Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                            //Toast.makeText(MainActivity.this, "", Toast.LENGTH_SHORT).show();
                                        }
                                    } else {
                                        //Toast.makeText(MainActivity.this, "You can write to folders you've selected", Toast.LENGTH_SHORT).show();
                                    }

                                } else {
                                    Toast.makeText(MainActivity.this, "Persisted tree uri permission is not granted for any folders", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                buttonGrantStoragePermission.setVisibility(View.VISIBLE);
                                Toast.makeText(MainActivity.this, "Storage permission is not granted, this app won't work", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.VISIBLE);
                            Toast.makeText(MainActivity.this, "Manage all files permission is not granted", Toast.LENGTH_SHORT).show();

                            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                buttonGrantStoragePermission.setVisibility(View.GONE);
                                Toast.makeText(MainActivity.this, "Storage permission is granted", Toast.LENGTH_SHORT).show();

                                savedTreesUri = loadSavedTreeUrisFromSharedPreference();
                                Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "savedTreesUri.size() = " + savedTreesUri.size());
                                if (savedTreesUri.size() > 0) {
                                    Toast.makeText(MainActivity.this, "Persisted tree uri permission currently is granted for folders :\n", Toast.LENGTH_SHORT).show();
                                    for (int i=0; i<savedTreesUri.size(); i++) {
                                        Toast.makeText(MainActivity.this, TreeUri2Path(savedTreesUri.get(i)) + "\n", Toast.LENGTH_SHORT).show();
                                        Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
                                        Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "TreeUri2Path(savedTreesUri.get(" + i + ")) = " + TreeUri2Path(savedTreesUri.get(i)));
                                    }
                                    if (selectedFilesPath != null && selectedFilesPath.size()>0) {
                                        if (isTreeUriPermissionGrantedForDirPathOfFilePath(selectedFilesPath.get(0))) {
                                            Toast.makeText(MainActivity.this, "Persisted tree uri permission is granted for :\n" + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                                            Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                            //Toast.makeText(MainActivity.this, "", Toast.LENGTH_SHORT).show();
                                        }
                                        else {
                                            Toast.makeText(MainActivity.this, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                                            Log.d("startForRequestManageAppAllFileAccessPermissionActivity", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                            //Toast.makeText(MainActivity.this, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                    else {
                                        //Toast.makeText(MainActivity.this, "All subtitle files will be saved into your selected folder.", Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(MainActivity.this, "Persisted tree uri permission is not granted for any folders", Toast.LENGTH_SHORT).show();
                                    //Toast.makeText(MainActivity.this, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/", Toast.LENGTH_SHORT).show();
                                }
                            }
                            else {
                                buttonGrantStoragePermission.setVisibility(View.VISIBLE);
                                Toast.makeText(MainActivity.this, "Storage permission is not granted, this app won't work", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
            });

    ActivityResultLauncher<Intent> startForRequestPersistedTreeUriPermissionActivity = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Uri treeUri;
                        Intent intent = result.getData();
                        if (intent != null) {
                            treeUri = intent.getData();

                            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                buttonGrantStoragePermission.setVisibility(View.GONE);
                                ////setText(textview_output_messages, "Storage permission is granted.\n");
                                Toast.makeText(MainActivity.this, "Storage permission is granted", Toast.LENGTH_SHORT).show();
                                ////appendText(textview_output_messages, "Persisted tree uri permission is granted for :\n" + TreeUri2Path(treeUri) + "\n");
                                Toast.makeText(MainActivity.this, "Persisted tree uri permission is granted for :\n" + TreeUri2Path(treeUri) + "\n", Toast.LENGTH_SHORT).show();
                                DocumentFile dfSelectedDir = DocumentFile.fromTreeUri(MainActivity.this, treeUri);
                                DocumentFile dfFile;
                                if (dfSelectedDir != null) {
                                    dfFile = dfSelectedDir.createFile("*/*", "test.txt");
                                    if (dfFile != null && dfFile.canWrite()) {
                                        Uri uriFile = dfFile.getUri();
                                        Log.d("startForRequestPersistedTreeUriPermissionActivity", "uriFile = " + uriFile);
                                        try {
                                            testWrite(uriFile);
                                            if (dfFile.exists() && dfFile.delete()) {
                                                ////appendText(textview_output_messages, "Write test succeed.\n");
                                                Toast.makeText(MainActivity.this, "Write test succeed", Toast.LENGTH_SHORT).show();
                                                ////appendText(textview_output_messages, "All subtitle files will be saved into :\n" + TreeUri2Path(treeUri));
                                            }
                                        }
                                        catch (FileNotFoundException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                    else {
                                        Log.d("startForRequestPersistedTreeUriPermissionActivity", "File is not exist or cannot write dfFile");
                                        ////setText(textview_output_messages, "Write test error!");
                                        Toast.makeText(MainActivity.this, "Write test error!", Toast.LENGTH_SHORT).show();
                                    }
                                }

                                savedTreesUri = loadSavedTreeUrisFromSharedPreference();
                                alreadySaved = false;
                                for (int i = 0; i < savedTreesUri.size(); i++) {
                                    Log.d("startForRequestPersistedTreeUriPermissionActivity", "savedTreesUri.size() = " + savedTreesUri.size());
                                    Log.d("startForRequestPersistedTreeUriPermissionActivity", "savedTreesUri.get(i) = " + savedTreesUri.get(i));
                                    Log.d("startForRequestPersistedTreeUriPermissionActivity", "treeUri = " + treeUri);
                                    Log.d("startForRequestPersistedTreeUriPermissionActivity", "savedTreesUri.get(i).toString().equals(treeUri.toString()) = " + savedTreesUri.get(i).toString().equals(treeUri.toString()));
                                    if (savedTreesUri.get(i).toString().equals(treeUri.toString())) {
                                        alreadySaved = true;
                                        Log.d("startForRequestPersistedTreeUriPermissionActivity", "alreadySaved = true");
                                    }
                                }
                                if (!alreadySaved) {
                                    int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                                    savedTreesUri.add(treeUri);
                                    Log.d("startForRequestPersistedTreeUriPermissionActivity", "alreadySaved = false -> saveTreeUrisToSharedPreference");
                                    saveTreeUrisToSharedPreference(savedTreesUri);
                                }
                            }
                            else {
                                buttonGrantStoragePermission.setVisibility(View.VISIBLE);
                                ////setText(textview_output_messages, "Storage permission is not granted, this app won't work");
                                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
                            }
                        }
                    }
                    else {
                        Log.d("startForRequestPersistedTreeUriPermissionActivity", "result.getResultCode() != Activity.RESULT_OK");
                    }
                }
            });

    private void saveTreeUrisToSharedPreference(ArrayList<Uri> savedTreesUri) {
        SharedPreferences sp = getSharedPreferences("com.android.autosubtitle.prefs", 0);
        SharedPreferences.Editor mEdit1 = sp.edit();
        mEdit1.putInt("arrayListSize", savedTreesUri.size());
        for(int i=0;i<savedTreesUri.size();i++) {
            mEdit1.remove("arrayList_" + i);
            mEdit1.putString("arrayList_" + i, savedTreesUri.get(i).toString());
            Log.d("saveTreeUrisToSharedPreference", "arrayList_" + i + " = " + savedTreesUri.get(i).toString());
        }
        mEdit1.apply();
    }


    private ArrayList<Uri> loadSavedTreeUrisFromSharedPreference() {
        ArrayList<Uri> savedTreesUri = new ArrayList<>();
        SharedPreferences sp = getSharedPreferences("com.android.autosubtitle.prefs", 0);
        int size = sp.getInt("arrayListSize", 0);
        for(int i=0;i<size;i++) {
            Uri uri = Uri.parse(sp.getString("arrayList_" + i, null));
            savedTreesUri.add(uri);
        }
        return savedTreesUri;
    }

    private boolean isTreeUriPermissionGrantedForDirPathOfFilePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            Log.e(
                    "isTreeUriPermissionGrantedForDirPathOfFilePath",
                    "filePath == null or empty"
            );
            return false;
        }
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent == null) {
            Log.e(
                    "isTreeUriPermissionGrantedForDirPathOfFilePath",
                    "parent == null for: " + filePath
            );
            return false;
        }
        String dirName = parent.getName();
        Uri dirUri = getFolderUri(dirName);
        if (dirUri == null) {
            Log.e("isTreeUriPermissionGrantedForDirPathOfFilePath", "dirUri == null for directory: " + dirName);
            return false;
        }
        savedTreesUri = loadSavedTreeUrisFromSharedPreference();
        if (savedTreesUri == null || savedTreesUri.size() == 0) {
            return false;
        }
        String dirLastPathSegment = dirUri.getLastPathSegment();
        if (dirLastPathSegment == null) {
            Log.e("isTreeUriPermissionGrantedForDirPathOfFilePath", "dirUri.getLastPathSegment() == null");
            return false;
        }
        for (int j = 0; j < savedTreesUri.size(); j++) {
            if (savedTreesUri.get(j) == null) {
                continue;
            }
            Uri savedTreeUri = Uri.parse(savedTreesUri.get(j).toString());
            if (savedTreeUri == null) {
                continue;
            }
            String savedLastPathSegment = savedTreeUri.getLastPathSegment();
            Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "savedTreeUri = " + savedTreeUri);
            Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "savedLastPathSegment = " + savedLastPathSegment);
            Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "dirUri = " + dirUri);
            Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "dirLastPathSegment = " + dirLastPathSegment);
            if (savedLastPathSegment != null && savedLastPathSegment.contains(dirLastPathSegment)) {
                selectedFolderUri = savedTreeUri;
                Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "selectedFolderUri = " + selectedFolderUri);
                Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "alreadySaved = true");
                return true;
            }
        }
        Log.d("isTreeUriPermissionGrantedForDirPathOfFilePath", "alreadySaved = false");
        return false;
    }

    private static String queryName(Context context, Uri uri) {
        Cursor returnCursor = context.getContentResolver().query(uri, null, null, null, null);
        assert returnCursor != null;
        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        returnCursor.moveToFirst();
        String name = returnCursor.getString(nameIndex);
        returnCursor.close();
        return name;
    }

    public static Uri getFolderUri(String folderPath) {
        File folder = new File(folderPath);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    folder.getAbsolutePath().substring(1));
        }
        else {
            uri = Uri.fromFile(folder);
        }
        return uri;
    }

    private String Uri2Path(Context context, Uri uri) {
        Log.d("Uri2Path", "========================================");
        if (uri == null) {
            Log.e("Uri2Path", "URI IS NULL");
            return null;
        }
        Log.d("Uri2Path", "uri = " + uri);
        Log.d("Uri2Path", "scheme = " + uri.getScheme());
        Log.d("Uri2Path", "authority = " + uri.getAuthority());
        Log.d("Uri2Path", "path = " + uri.getPath());
        // ============================================================
        // FILE URI
        // ============================================================
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            String path = uri.getPath();
            Log.d("Uri2Path", "FILE URI path = " + path);
            return path;
        }
        // ============================================================
        // CONTENT URI
        // ============================================================
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Log.e("Uri2Path", "Unsupported URI scheme: " + uri.getScheme());
            return null;
        }
        String authority = uri.getAuthority();
        Log.d("Uri2Path", "CONTENT authority = " + authority);
        // ============================================================
        // DOCUMENT PROVIDER
        // ============================================================
        if (DocumentsContract.isDocumentUri(context, uri)) {
            String docId;
            try {
                docId = DocumentsContract.getDocumentId(uri);
            }
            catch (Exception e) {
                Log.e("Uri2Path", "getDocumentId() failed", e);
                return null;
            }
            Log.d("Uri2Path", "DocumentProvider docId = " + docId);
            // ========================================================
            // RAW DOCUMENT
            // Example:
            // raw:/storage/emulated/0/Download/AUTOSRT/file.mp4
            // ========================================================
            if (docId != null && docId.startsWith("raw:")) {
                String rawPath = docId.substring(4);
                Log.d("Uri2Path", "RAW path = " + rawPath);
                if (rawPath != null && !rawPath.isEmpty()) {
                    return rawPath;
                }
                return null;
            }
            // ========================================================
            // EXTERNAL STORAGE PROVIDER
            // Example:
            // primary:Download/AUTOSRT/file.mp4
            // ========================================================
            if ("com.android.externalstorage.documents".equals(authority)) {
                String[] split = new String[0];
                if (docId != null) {
                    split = docId.split(":", 2);
                }
                Log.d("Uri2Path", "ExternalStorage split = " + Arrays.toString(split));
                if (split.length == 2) {
                    String fullPath = getPathFromExtSD(split);
                    Log.d("Uri2Path", "ExternalStorage fullPath = " + fullPath);
                    if (fullPath != null && !fullPath.isEmpty()) {
                        return fullPath;
                    }
                }
                Log.e("Uri2Path", "Unable to resolve ExternalStorage document");
                return null;
            }
            // ========================================================
            // DOWNLOADS PROVIDER
            // ========================================================
            if ("com.android.providers.downloads.documents".equals(authority)) {
                Log.d("Uri2Path", "DownloadsProvider detected");
                // raw: sudah ditangani di atas.
                // Untuk document ID selain raw:, coba resolver/query melalui ContentResolver.
                try {
                    Cursor cursor = context.getContentResolver().query(uri, new String[]{
                                    MediaStore.Files.FileColumns.DATA
                            },
                            null,
                            null,
                            null
                    );
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                int index = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                                if (index >= 0) {
                                    String path = cursor.getString(index);
                                    Log.d("Uri2Path", "Downloads DATA = " + path);
                                    if (path != null && !path.isEmpty()) {
                                        return path;
                                    }
                                }
                            }
                        }
                        finally {
                            cursor.close();
                        }
                    }
                }
                catch (Exception e) {
                    Log.e("Uri2Path", "DownloadsProvider query failed", e);
                }
            }
        }
        // ============================================================
        // MEDIA PROVIDER / OTHER CONTENT PROVIDER
        // ============================================================
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    uri,
                    new String[]{
                            MediaStore.Files.FileColumns.DATA
                    },
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                if (index >= 0) {
                    String path = cursor.getString(index);
                    Log.d("Uri2Path", "Generic DATA = " + path);
                    if (path != null && !path.isEmpty()) {
                        return path;
                    }
                }
            }

        }
        catch (Exception e) {
            Log.e("Uri2Path", "Generic content resolver failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // ============================================================
        // LAST RESORT
        // ============================================================
        Log.e("Uri2Path", "FAILED TO RESOLVE URI TO FILESYSTEM PATH: " + uri);
        return null;
    }

    private String getPathFromExtSD(String[] pathData) {
        final String type = pathData[0];
        final String relativePath = File.separator + pathData[1];
        String fullPath = null;

        if ("primary".equalsIgnoreCase(type)) {
            Log.d("getPathFromExtSD", "PRIMARY");
            Log.d("getPathFromExtSD", "type = " + type);
            if (new File(Environment.getExternalStorageDirectory() + relativePath).exists()) {
                fullPath = Environment.getExternalStorageDirectory() + relativePath;
            }
        } else {
            // CHECK SECONDARY STORAGE
            if (new File("/storage/" + type + relativePath).exists()) {
                fullPath = "/storage/" + type + relativePath;
            }
        }
        Log.d("getPathFromExtSD", "fullPath = " + fullPath);
        return fullPath;
    }

    private void requestTreeUriPermissions() {
        // Choose a directory using the system's file picker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setMessage("Please select folder of your media files so this app can write subtitle files on same folder")
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        ////setText(textview_output_messages, "Persisted tree uri permission request is canceled.\n");
                        Toast.makeText(this, "Persisted tree uri permission request is canceled", Toast.LENGTH_SHORT).show();

                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                            savedTreesUri = loadSavedTreeUrisFromSharedPreference();
                            Log.d("requestTreeUriPermissions", "savedTreesUri.size() = " + savedTreesUri.size());
                            if (savedTreesUri.size() > 0) {
                                for (int i=0; i<savedTreesUri.size(); i++) {
                                    Log.d("requestTreeUriPermissions", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
                                    Log.d("requestTreeUriPermissions", "TreeUri2Path(savedTreesUri.get(" + i + ")) = " + TreeUri2Path(savedTreesUri.get(i)));
                                }
                                if (selectedFilesPath != null && selectedFilesPath.size()>0) {
                                    if (isTreeUriPermissionGrantedForDirPathOfFilePath(selectedFilesPath.get(0))) {
                                        Log.d("requestTreeUriPermissions", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                    } else {
                                        ////setText(textview_output_messages, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n");
                                        Toast.makeText(this, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                                        if (Environment.isExternalStorageManager()) {
                                            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.GONE);
                                            //textview_grant_manage_app_all_files_access_permission_notes.setVisibility(View.GONE);
                                            ////appendText(textview_output_messages, "Manage app all files access permission is granted.\n");
                                            Toast.makeText(this, "Manage app all files access permission is granted", Toast.LENGTH_SHORT).show();
                                            ////appendText(textview_output_messages, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/");
                                            //Toast.makeText(this, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/", Toast.LENGTH_SHORT).show();
                                        } else {
                                            buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.VISIBLE);
                                            //textview_grant_manage_app_all_files_access_permission_notes.setVisibility(View.VISIBLE);
                                            ////appendText(textview_output_messages, "Manage app all files access permission is not granted.\n");
                                            Toast.makeText(this, "Manage app all files access permission is not granted", Toast.LENGTH_SHORT).show();
                                            ////appendText(textview_output_messages, "All subtitle files will always be saved as new files into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/");
                                            //Toast.makeText(this, "All subtitle files will always be saved as new files into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                } else {
                                    ////appendText(textview_output_messages, "All subtitle files will be saved into your selected folder.");
                                    Toast.makeText(this, "All subtitle files will be saved into your selected folder", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                ////appendText(textview_output_messages, "Persisted tree uri permission is not granted for any folders.\n");
                                Toast.makeText(this, "\"Persisted tree uri permission is not granted for any folders", Toast.LENGTH_SHORT).show();
                                if (Environment.isExternalStorageManager()) {
                                    buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.GONE);
                                    ////appendText(textview_output_messages, "Manage app all files access permission is granted.\n");
                                    Toast.makeText(this, "Manage app all files access permission is granted", Toast.LENGTH_SHORT).show();
                                    ////appendText(textview_output_messages, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/");
                                    //Toast.makeText(this, "", Toast.LENGTH_SHORT).show();
                                } else {
                                    buttonGrantManageAppAllFilesAccessPermission.setVisibility(View.VISIBLE);
                                    //textview_grant_manage_app_all_files_access_permission_notes.setVisibility(View.VISIBLE);
                                    ////appendText(textview_output_messages, "Manage app all files access permission is not granted.\n");
                                    Toast.makeText(this, "Manage app all files access permission is not granted", Toast.LENGTH_SHORT).show();
                                    ////appendText(textview_output_messages, "All subtitle files will always be saved as new files into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/");
                                }
                            }
                        } else {
                            buttonGrantStoragePermission.setVisibility(View.VISIBLE);
                            ////setText(textview_output_messages, "Storage permission is not granted, this app won't work");
                            Toast.makeText(this, "Storage permission is not granted, this app won't work", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton("Ok", (dialog, which) -> {
                        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                        Intent intent = sm.getPrimaryStorageVolume().createOpenDocumentTreeIntent();
                        String startDir = "Documents";
                        Uri uri;
                        if (intent != null) {
                            uri = intent.getParcelableExtra("android.provider.extra.INITIAL_URI");
                            String scheme;
                            if (uri != null) {
                                scheme = uri.toString().replace("/root/", "/document/");
                                scheme += "%3A" + startDir;
                                uri = Uri.parse(scheme);
                                Uri rootUri = DocumentsContract.buildDocumentUri(AUTHORITY, uri.toString());
                                sm.getPrimaryStorageVolume().createOpenDocumentTreeIntent().putExtra(EXTRA_INITIAL_URI, rootUri);

                                // Optionally, specify a URI for the directory that should be opened in
                                // the system file picker when it loads.
                                Intent intent2 = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                intent2.addFlags(
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                                intent2.putExtra(EXTRA_INITIAL_URI, rootUri);
                                //startActivity(intent2);
                                startForRequestPersistedTreeUriPermissionActivity.launch(intent2);
                            }
                        }
                    })
                    .setCancelable(false)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setMessage("Please select folder of your media files so this app can write subtitle files on same folder")
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        //setText(textview_output_messages, "Persisted tree uri permission request is canceled.\n");
                        Toast.makeText(this, "Persisted tree uri permission request is canceled", Toast.LENGTH_SHORT).show();

                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                            buttonGrantStoragePermission.setVisibility(View.GONE);
                            //adjustOutputMessagesHeight();
                            //setText(textview_output_messages, "Storage permission is granted.\n");
                            Toast.makeText(this, "Storage permission is granted", Toast.LENGTH_SHORT).show();

                            savedTreesUri = loadSavedTreeUrisFromSharedPreference();
                            Log.d("requestTreeUriPermissions", "savedTreesUri.size() = " + savedTreesUri.size());
                            if (savedTreesUri.size() > 0) {
                                //appendText(textview_output_messages, "Persisted tree uri permission currently is granted for folders :\n");
                                Toast.makeText(this, "Persisted tree uri permission currently is granted for folders :\n", Toast.LENGTH_SHORT).show();
                                for (int i=0; i<savedTreesUri.size(); i++) {
                                    //appendText(textview_output_messages, TreeUri2Path(savedTreesUri.get(i)) + "\n");
                                    Toast.makeText(this, TreeUri2Path(savedTreesUri.get(i)) + "\n", Toast.LENGTH_SHORT).show();
                                    Log.d("requestTreeUriPermissions", "savedTreesUri.get(" + i + ") = " + savedTreesUri.get(i));
                                    Log.d("requestTreeUriPermissions", "TreeUri2Path(savedTreesUri.get(" + i + ")) = " + TreeUri2Path(savedTreesUri.get(i)));
                                }
                                if (selectedFilesPath != null && selectedFilesPath.size()>0) {
                                    if (isTreeUriPermissionGrantedForDirPathOfFilePath(selectedFilesPath.get(0))) {
                                        //setText(textview_output_messages, "Persisted tree uri permission is granted for :\n" + new File(selectedFilesPath.get(0)).getParent() + "\n");
                                        Toast.makeText(this, "Persisted tree uri permission is granted for :\n" + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                                        Log.d("requestTreeUriPermissions", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                        //appendText(textview_output_messages, "All subtitle files will be saved into :\n" + new File(selectedFilesPath.get(0)).getParent() + "\n");
                                    } else {
                                        //setText(textview_output_messages, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n");
                                        Toast.makeText(this, "Persisted tree uri permission request is not granted for " + new File(selectedFilesPath.get(0)).getParent() + "\n", Toast.LENGTH_SHORT).show();
                                        Log.d("requestTreeUriPermissions", "getParentFolderPath(selectedFilesPath.get(0)) = " + getParentFolderPath(selectedFilesPath.get(0)));
                                        //appendText(textview_output_messages, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/");
                                    }
                                } else {
                                    //appendText(textview_output_messages, "All subtitle files will be saved into your selected folder.");
                                    Toast.makeText(this, "All subtitle files will be saved into your selected folder", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                //appendText(textview_output_messages, "Persisted tree uri permission is not granted for any folders.\n");
                                Toast.makeText(this, "Persisted tree uri permission is not granted for any folders!", Toast.LENGTH_SHORT).show();
                                //appendText(textview_output_messages, "All subtitle files will be saved into :\n/storage/emulated/0/" + DIRECTORY_DOCUMENTS + "/com.android.autosrt/");
                            }
                        } else {
                            //setText(textview_output_messages, "Storage permission is not granted, this app won't work");
                            Toast.makeText(this, "Storage permission is not granted, this app won't work", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setPositiveButton("Ok", (dialog, which) -> {
                        //Intent intent = sm.getPrimaryStorageVolume().createAccessIntent(DIRECTORY_DOCUMENTS);
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

                        //startActivity(intent);
                        startForRequestPersistedTreeUriPermissionActivity.launch(intent);
                    })
                    .setCancelable(false)
                    .show();
        }
    }


    private String TreeUri2Path(Uri uri) {
        if (uri == null) {
            return null;
        }
        String docId = DocumentsContract.getTreeDocumentId(uri);
        Log.d("TreeUri2Path", "docId = " + docId);

        // Handle "raw:/absolute/path" docId (seen on some vendors/SD cards)
        if (docId.startsWith("raw:")) {
            String rawPath = docId.substring(4);
            Log.d("TreeUri2Path", "rawPath = " + rawPath);
            return (rawPath.isEmpty()) ? null : rawPath;
        }

        String[] split = docId.split(":");
        Log.d("TreeUri2Path", "split = " + Arrays.toString(split));

        if (split.length < 2) {
            Log.e("TreeUri2Path", "Unexpected docId format: " + docId);
            return null;
        }

        String fullPath = getPathFromExtSD(split);
        Log.d("TreeUri2Path", "fullPath = " + fullPath);

        // fullPath can be null, not just "", so check null first
        if (fullPath != null && !fullPath.isEmpty()) {
            return fullPath;
        }
        return null;
    }

    public String getParentFolderPath(String filePath) {
        File file = new File(filePath);
        File parent = file.getParentFile();

        if (parent != null) {
            return parent.getAbsolutePath();
        } else {
            return null;
        }
    }

    @SuppressLint("Recycle")
    private void testWrite(Uri uri) throws FileNotFoundException {
        @SuppressLint("Recycle")
        ParcelFileDescriptor parcelFileDescriptor;
        parcelFileDescriptor = getContentResolver().openFileDescriptor(uri, "w");
        try (FileOutputStream fos = new FileOutputStream(parcelFileDescriptor.getFileDescriptor())) {
            long currentTimeMillis = System.currentTimeMillis();
            fos.write(("String written at " + currentTimeMillis + "\n").getBytes());
            //Log.d("testWrite", "Write test succeed");
        }
        catch (IOException e) {
            Log.e("IOException: ", e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        try {
            parcelFileDescriptor.close();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isInternetAvailable() {
        try {
            InetAddress[] ipAddr = checkGoogleHost();
            return !Arrays.toString(ipAddr).equals("");
        }
        catch (Exception e) {
            return false;
        }
    }

    private InetAddress[] checkGoogleHost() {
        final InetAddress[] ipAddr = new InetAddress[1];
        Thread netThread = new Thread(() -> {
            try {
                ipAddr[0] = InetAddress.getByName("www.google.com");
            }
            catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
            Log.d("isInternetAvailable", "ipAddr = " + ipAddr[0]);
        });
        netThread.start();
        return ipAddr;
    }

    private void enterFullscreen() {
        if (fullscreenContainer != null) {
            return;
        }

        playerParent = (ViewGroup) playerView.getParent();
        playerIndex = playerParent.indexOfChild(playerView);
        playerLayoutParams = playerView.getLayoutParams();

        playerParent.removeView(playerView);

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(0xFF000000);

        FrameLayout.LayoutParams fullscreenParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );

        fullscreenContainer.addView(playerView, fullscreenParams);

        ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        decorView.addView(
                fullscreenContainer,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        //playerView.setFullscreenButtonState(true);
    }

    private void exitFullscreen() {
        if (fullscreenContainer == null) {
            return;
        }

        fullscreenContainer.removeView(playerView);
        ((ViewGroup) fullscreenContainer.getParent()).removeView(fullscreenContainer);

        playerParent.addView(
                playerView,
                playerIndex,
                playerLayoutParams
        );

        fullscreenContainer = null;

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        //playerView.setFullscreenButtonState(false);
    }

    private void setTimeBarColor(View view) {
        if (view instanceof TimeBar) {
            TimeBar timeBar = (TimeBar) view;

            if (timeBar instanceof DefaultTimeBar) {
                DefaultTimeBar defaultTimeBar = (DefaultTimeBar) timeBar;
                defaultTimeBar.setPlayedColor(Color.RED);
                defaultTimeBar.setScrubberColor(Color.RED);
            }

            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;

            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                setTimeBarColor(viewGroup.getChildAt(i));
            }
        }
    }

    private final BroadcastReceiver streamlinkErrorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String error = intent.getStringExtra("error");

            if (error == null || error.isEmpty()) {
                error = "Unknown Streamlink error";
            }

            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
        }
    };

}
