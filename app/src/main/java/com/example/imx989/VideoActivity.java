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
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class VideoActivity extends AppCompatActivity {

    private TextureView textureView;
    private ImageButton btnRecord;
    private TextView btnBackToPhoto, txtTimer;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureBuilder;
    private MediaRecorder mediaRecorder;
    private Size videoSize;
    private boolean isRecording = false;

    // Biến để lưu file video qua MediaStore
    private Uri videoUri;
    private ParcelFileDescriptor pfd;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private long startTime = 0;
    private final Handler timerHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);

        textureView = findViewById(R.id.textureView);
        btnRecord = findViewById(R.id.btnRecord);
        btnBackToPhoto = findViewById(R.id.btnSwitchToPhoto);
        txtTimer = findViewById(R.id.txtTimer);

        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });

        btnBackToPhoto.setOnClickListener(v -> finish());
    }

    private void startRecording() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            closePreviewSession();
            // Setup Recorder TRƯỚC khi tạo Session
            if (!setupMediaRecorder()) {
                Toast.makeText(this, "Lỗi khởi tạo file video!", Toast.LENGTH_SHORT).show();
                return;
            }

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recorderSurface);

            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureBuilder.addTarget(previewSurface);
            captureBuilder.addTarget(recorderSurface);
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        // Start Repeating Request
                        captureSession.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler);

                        // Start Recording
                        mediaRecorder.start();
                        isRecording = true;

                        runOnUiThread(() -> {
                            btnRecord.setImageResource(android.R.drawable.ic_media_pause);
                            startTime = SystemClock.uptimeMillis();
                            timerHandler.postDelayed(timerRunnable, 0);
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        stopRecording(); // Stop nếu lỗi start
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(VideoActivity.this, "Lỗi cấu hình Camera", Toast.LENGTH_SHORT).show();
                }
            }, backgroundHandler);

        } catch (Exception e) { e.printStackTrace(); }
    }

    private void stopRecording() {
        if (!isRecording) return;
        try {
            // 1. Dừng Camera Session trước
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }

            // 2. Dừng MediaRecorder
            mediaRecorder.stop();
            mediaRecorder.reset();

            // 3. Đóng File Descriptor (Quan trọng để file được lưu hoàn tất)
            if (pfd != null) {
                pfd.close();
                pfd = null;
            }

            isRecording = false;

            runOnUiThread(() -> {
                btnRecord.setImageResource(0); // Reset icon (hoặc set lại drawable đỏ)
                timerHandler.removeCallbacks(timerRunnable);
                txtTimer.setText("00:00");
                Toast.makeText(this, "Đã lưu vào Gallery!", Toast.LENGTH_SHORT).show();
            });

            // 4. Khởi động lại Preview để quay tiếp
            startPreview();

        } catch (Exception e) {
            e.printStackTrace();
            // Đôi khi stop fail nếu video quá ngắn, vẫn cần reset
            isRecording = false;
            startPreview();
        }
    }

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable()) return;
        try {
            closePreviewSession();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface surface = new Surface(texture);
            captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try { captureSession.setRepeatingRequest(captureBuilder.build(), null, backgroundHandler); }
                    catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // --- SETUP RECORDER VỚI MEDIASTORE (FIX LỖI KHÔNG LƯU) ---
    private boolean setupMediaRecorder() {
        try {
            mediaRecorder = new MediaRecorder();

            // 1. Cấu hình Source
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // 2. Tạo File qua MediaStore
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "LEITZ_VID_" + System.currentTimeMillis());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/LeitzCam");

            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) return false;

            // 3. Lấy FileDescriptor để ghi
            pfd = getContentResolver().openFileDescriptor(videoUri, "w");
            if (pfd == null) return false;

            mediaRecorder.setOutputFile(pfd.getFileDescriptor());

            // 4. Cấu hình Encoder
            mediaRecorder.setVideoEncodingBitRate(30000000); // 30Mbps
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOrientationHint(90);

            mediaRecorder.prepare();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
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
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private Size chooseVideoSize(Size[] choices) {
        // Ưu tiên Full HD hoặc 4K, tránh các size lạ
        for (Size size : choices) {
            if (size.getWidth() == 1920 && size.getHeight() == 1080) return size;
        }
        for (Size size : choices) {
            if (size.getWidth() == 3840 && size.getHeight() == 2160) return size;
        }
        return choices[0];
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
    @Override protected void onPause() {
        if (isRecording) stopRecording();
        stopBackgroundThread();
        if(cameraDevice!=null) cameraDevice.close();
        super.onPause();
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return false; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
    };
}