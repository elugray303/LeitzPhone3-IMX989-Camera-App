package com.example.imx989;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VideoActivity extends AppCompatActivity {

    private TextureView textureView;
    private ImageButton btnRecord;
    private TextView btnSwitchToPhoto, txtTimer;
    private Spinner spinnerResolution;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureBuilder;
    private MediaRecorder mediaRecorder;

    private boolean isRecording = false;
    private Uri videoUri;
    private ParcelFileDescriptor pfd;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private long startTime = 0;
    private final Handler timerHandler = new Handler();

    private VideoConfig currentConfig;
    private List<VideoConfig> supportedConfigs;

    private static class VideoConfig {
        String label;
        int width, height, fps, bitrate;
        public VideoConfig(String label, int w, int h, int fps, int bitrate) {
            this.label = label; this.width = w; this.height = h; this.fps = fps; this.bitrate = bitrate;
        }
        @Override public String toString() { return label; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);

        textureView = findViewById(R.id.textureView);
        btnRecord = findViewById(R.id.btnRecord);
        btnSwitchToPhoto = findViewById(R.id.btnSwitchToPhoto);
        txtTimer = findViewById(R.id.txtTimer);
        spinnerResolution = findViewById(R.id.spinnerResolution);

        setupVideoConfigs();
        setupSpinner();

        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });

        btnSwitchToPhoto.setOnClickListener(v -> {
            if (!isRecording) {
                finish();
                overridePendingTransition(0, 0);
            }
        });
    }

    private void setupVideoConfigs() {
        supportedConfigs = new ArrayList<>();
        supportedConfigs.add(new VideoConfig("8K 24FPS", 7680, 4320, 24, 100_000_000));
        supportedConfigs.add(new VideoConfig("4K 60FPS", 3840, 2160, 60, 60_000_000));
        supportedConfigs.add(new VideoConfig("4K 30FPS", 3840, 2160, 30, 50_000_000));
        supportedConfigs.add(new VideoConfig("FHD 120FPS", 1920, 1080, 120, 40_000_000));
        supportedConfigs.add(new VideoConfig("FHD 60FPS", 1920, 1080, 60, 30_000_000));
        supportedConfigs.add(new VideoConfig("FHD 30FPS", 1920, 1080, 30, 20_000_000));
        currentConfig = supportedConfigs.get(2);
    }

    // --- CẬP NHẬT GIAO DIỆN SPINNER ĐẸP ---
    private void setupSpinner() {
        ArrayAdapter<VideoConfig> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_selected, supportedConfigs);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        spinnerResolution.setAdapter(adapter);
        spinnerResolution.setSelection(2);

        spinnerResolution.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentConfig = supportedConfigs.get(position);
                if (cameraDevice != null && !isRecording) startPreview();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void startRecording() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            spinnerResolution.setEnabled(false);
            spinnerResolution.setAlpha(0.5f);
            btnSwitchToPhoto.setVisibility(View.INVISIBLE);

            closePreviewSession();
            if (!setupMediaRecorder()) {
                Toast.makeText(this, "Lỗi tạo file!", Toast.LENGTH_SHORT).show();
                spinnerResolution.setEnabled(true);
                return;
            }

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(currentConfig.width, currentConfig.height);
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recorderSurface);

            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureBuilder.addTarget(previewSurface);
            captureBuilder.addTarget(recorderSurface);
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            Range<Integer> fpsRange = new Range<>(currentConfig.fps, currentConfig.fps);
            captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler);
                        mediaRecorder.start();
                        isRecording = true;
                        runOnUiThread(() -> {
                            btnRecord.setImageResource(android.R.drawable.ic_media_pause);
                            startTime = SystemClock.uptimeMillis();
                            timerHandler.postDelayed(timerRunnable, 0);
                        });
                    } catch (Exception e) { e.printStackTrace(); stopRecording(); }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    runOnUiThread(() -> {
                        Toast.makeText(VideoActivity.this, "Lỗi cấu hình", Toast.LENGTH_SHORT).show();
                        stopRecording();
                    });
                }
            }, backgroundHandler);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void stopRecording() {
        if (!isRecording) return;
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
            mediaRecorder.stop();
            mediaRecorder.reset();
            if (pfd != null) { pfd.close(); pfd = null; }
            isRecording = false;

            runOnUiThread(() -> {
                spinnerResolution.setEnabled(true);
                spinnerResolution.setAlpha(1.0f);
                btnSwitchToPhoto.setVisibility(View.VISIBLE);
                btnRecord.setImageResource(0);
                timerHandler.removeCallbacks(timerRunnable);
                txtTimer.setText("00:00");
                Toast.makeText(this, "Đã lưu: " + currentConfig.label, Toast.LENGTH_SHORT).show();
            });
            startPreview();
        } catch (Exception e) {
            e.printStackTrace();
            isRecording = false;
            startPreview();
            runOnUiThread(() -> {
                spinnerResolution.setEnabled(true);
                btnSwitchToPhoto.setVisibility(View.VISIBLE);
            });
        }
    }

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            closePreviewSession();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(currentConfig.width, currentConfig.height);
            Surface surface = new Surface(texture);
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try { captureSession.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler); }
                    catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private boolean setupMediaRecorder() {
        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "LEITZ_" + currentConfig.label.replace(" ", "_") + "_" + System.currentTimeMillis());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/LeitzCam");

            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) return false;
            pfd = getContentResolver().openFileDescriptor(videoUri, "w");
            if (pfd == null) return false;
            mediaRecorder.setOutputFile(pfd.getFileDescriptor());

            mediaRecorder.setVideoEncodingBitRate(currentConfig.bitrate);
            mediaRecorder.setVideoFrameRate(currentConfig.fps);
            mediaRecorder.setVideoSize(currentConfig.width, currentConfig.height);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOrientationHint(90);
            mediaRecorder.prepare();
            return true;
        } catch (IOException e) { e.printStackTrace(); return false; }
    }

    private Runnable timerRunnable = new Runnable() {
        public void run() {
            long millis = SystemClock.uptimeMillis() - startTime;
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            seconds = seconds % 60;
            txtTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
            timerHandler.postDelayed(this, 500);
        }
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void closePreviewSession() { if (captureSession != null) { captureSession.close(); captureSession = null; } }
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice camera) { cameraDevice = camera; startPreview(); }
        @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); cameraDevice = null; }
        @Override public void onError(@NonNull CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
    };
    private void startBackgroundThread() { backgroundThread = new HandlerThread("VideoBG"); backgroundThread.start(); backgroundHandler = new Handler(backgroundThread.getLooper()); }
    private void stopBackgroundThread() { if(backgroundThread!=null){backgroundThread.quitSafely();try{backgroundThread.join();}catch(Exception e){}backgroundThread=null;backgroundHandler=null;}}

    @Override protected void onResume() { super.onResume(); startBackgroundThread(); if (textureView.isAvailable()) openCamera(); else textureView.setSurfaceTextureListener(textureListener); }
    @Override protected void onPause() { if (isRecording) stopRecording(); stopBackgroundThread(); if(cameraDevice!=null) cameraDevice.close(); super.onPause(); }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return false; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
    };
}