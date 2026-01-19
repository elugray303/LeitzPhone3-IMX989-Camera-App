package com.example.imx989;

import android.Manifest;
import android.content.ContentUris; // Đã bổ sung import này
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.app.ActivityCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextureView textureView;
    private ImageButton btnCapture, btnGallery, btnRatio, btnSwitch;
    private TextView btnHighRes, btnRaw;
    private TextView txtZoomIndicator, modeVideo, modePhoto, modeManual, modeLeitz;
    private ConstraintLayout mainLayout;

    private ManualUIManager manualManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCharacteristics mCharacteristics;

    private Size previewSize;
    private Rect sensorArraySize;        // 12MP Array
    private Rect sensorArraySizeHighRes; // 50MP Array

    private ImageReader imageReaderNormal;  // Reader 12MP
    private ImageReader imageReaderHighRes; // Reader 50MP
    private ImageReader rawImageReader;     // Reader RAW (Max)

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isDeviceSupportHighRes = false;
    private boolean isHighResEnabled = false;
    private boolean isRawEnabled = false;
    private boolean isManualMode = false;

    private int currentZoomLevel = 1;
    private float currentCropFactor = 0.2f;
    private int currentRatioIndex = 0;

    private Uri lastPhotoUri = null;
    private final Object mRawLock = new Object();
    private Image mPendingRawImage;
    private TotalCaptureResult mPendingCaptureResult;

    private static final int REQUEST_PERMISSION_CODE = 200;
    private static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainLayout = findViewById(R.id.mainLayout);
        setupUIViews();
        manualManager = new ManualUIManager(this, this::updatePreview);
        setupListeners();
    }

    private void setupUIViews() {
        textureView = findViewById(R.id.textureView);
        btnCapture = findViewById(R.id.btnCapture);
        btnSwitch = findViewById(R.id.btnSwitch);
        btnGallery = findViewById(R.id.btnGallery);
        btnRatio = findViewById(R.id.btnRatio);
        btnHighRes = findViewById(R.id.btnHighRes);
        btnRaw = findViewById(R.id.btnRaw);
        txtZoomIndicator = findViewById(R.id.txtZoomIndicator);
        modeVideo = findViewById(R.id.modeVideo);
        modePhoto = findViewById(R.id.modePhoto);
        modeManual = findViewById(R.id.modeManual);
        modeLeitz = findViewById(R.id.modeLeitz);
    }

    private void setupListeners() {
        btnCapture.setOnClickListener(v -> takePicture());
        txtZoomIndicator.setOnClickListener(v -> cycleZoom());
        btnRatio.setOnClickListener(v -> toggleAspectRatio());

        btnHighRes.setOnClickListener(v -> toggleHighResMode());
        btnRaw.setOnClickListener(v -> toggleRawMode());

        btnGallery.setOnClickListener(v -> {
            if (lastPhotoUri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(lastPhotoUri, "image/*");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setPackage("com.google.android.apps.photos");
                try { startActivity(intent); } catch (Exception e) {}
            }
        });

        modeVideo.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, VideoActivity.class);
            startActivity(intent);
            overridePendingTransition(0, 0);
        });

        modeManual.setOnClickListener(v -> switchMode(modeManual, true));
        modePhoto.setOnClickListener(v -> switchMode(modePhoto, false));
    }

    private void switchMode(TextView selectedMode, boolean manual) {
        modePhoto.setTextColor(Color.WHITE);
        modeManual.setTextColor(Color.WHITE);
        selectedMode.setTextColor(Color.parseColor("#D32F2F"));
        isManualMode = manual;
        if(manual) manualManager.show(); else manualManager.hide();
        updatePreview();
    }

    // --- LOGIC CHECK CHẾ ĐỘ ---
    private boolean useHighResMode() {
        // Kích hoạt chế độ HighRes khi: Bật nút 50MP HOẶC Bật nút RAW
        return (isHighResEnabled || isRawEnabled)
                && isDeviceSupportHighRes
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            mCharacteristics = manager.getCameraCharacteristics(cameraId);

            Range<Integer> isoRange = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> shutterRange = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            float minFocus = mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            manualManager.setHardwareRanges(isoRange, shutterRange, minFocus);

            sensorArraySize = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sensorArraySizeHighRes = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            }
            if (sensorArraySizeHighRes == null) sensorArraySizeHighRes = sensorArraySize;

            StreamConfigurationMap mapNormal = mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StreamConfigurationMap mapHighRes = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mapHighRes = mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                isDeviceSupportHighRes = (mapHighRes != null);
            }

            previewSize = chooseOptimalSize(mapNormal.getOutputSizes(SurfaceTexture.class), 1440, 1080);

            // 1. SETUP NORMAL READER
            Size sizeNormal = Collections.max(Arrays.asList(mapNormal.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            imageReaderNormal = ImageReader.newInstance(sizeNormal.getWidth(), sizeNormal.getHeight(), ImageFormat.JPEG, 2);
            imageReaderNormal.setOnImageAvailableListener(jpegListener, backgroundHandler);

            // 2. SETUP HIGH RES READER
            if (isDeviceSupportHighRes && mapHighRes != null) {
                Size sizeHighRes = Collections.max(Arrays.asList(mapHighRes.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                imageReaderHighRes = ImageReader.newInstance(sizeHighRes.getWidth(), sizeHighRes.getHeight(), ImageFormat.JPEG, 2);
                imageReaderHighRes.setOnImageAvailableListener(jpegListener, backgroundHandler);
            }

            // 3. SETUP RAW READER
            if (contains(mCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES), CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                StreamConfigurationMap mapRaw = (isDeviceSupportHighRes && mapHighRes != null) ? mapHighRes : mapNormal;
                Size[] rawSizes = mapRaw.getOutputSizes(ImageFormat.RAW_SENSOR);
                if (rawSizes != null && rawSizes.length > 0) {
                    Size sizeRaw = Collections.max(Arrays.asList(rawSizes), new CompareSizesByArea());
                    rawImageReader = ImageReader.newInstance(sizeRaw.getWidth(), sizeRaw.getHeight(), ImageFormat.RAW_SENSOR, 2);
                    rawImageReader.setOnImageAvailableListener(rawListener, backgroundHandler);
                }
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSION_CODE);
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            List<Surface> targets = new ArrayList<>();
            targets.add(surface);

            // CHỌN READER PHÙ HỢP VÀO SESSION
            if (useHighResMode() && imageReaderHighRes != null) {
                targets.add(imageReaderHighRes.getSurface());
            } else {
                targets.add(imageReaderNormal.getSurface());
            }

            if (rawImageReader != null) targets.add(rawImageReader.getSurface());

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                    if (cameraDevice == null) return;
                    cameraCaptureSessions = s;
                    applyZoom(currentCropFactor);
                    updatePreview();
                    runOnUiThread(() -> configureTransform(textureView.getWidth(), textureView.getHeight()));
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void updatePreview() {
        if (cameraDevice == null || captureRequestBuilder == null) return;
        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            if (isManualMode) {
                if (manualManager.isManualExposure()) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualManager.getISO());
                    captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualManager.getShutter());
                } else { captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON); }

                if (manualManager.isManualFocus()) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualManager.getFocus());
                } else { captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE); }
            } else {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void takePicture() {
        if (null == cameraDevice) return;
        try {
            synchronized (mRawLock) {
                if (mPendingRawImage != null) { mPendingRawImage.close(); mPendingRawImage = null; }
                mPendingCaptureResult = null;
            }

            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // LOGIC CHỌN TARGET VÀ SENSOR MODE
            // Nếu dùng HighRes Mode (Do bật 50MP hoặc RAW) -> Dùng HighRes Reader & Max Res Mode
            boolean highResActive = useHighResMode() && imageReaderHighRes != null;

            if (highResActive) {
                captureBuilder.addTarget(imageReaderHighRes.getSurface());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                }
                applyZoomToBuilder(captureBuilder, currentCropFactor, sensorArraySizeHighRes);
            } else {
                captureBuilder.addTarget(imageReaderNormal.getSurface());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT);
                }
                applyZoomToBuilder(captureBuilder, currentCropFactor, sensorArraySize);
            }

            // RAW
            boolean captureRaw = isRawEnabled && (rawImageReader != null);
            if (captureRaw) {
                captureBuilder.addTarget(rawImageReader.getSurface());
            }

            // Manual Settings
            if (isManualMode) {
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                if (manualManager.isManualExposure()) {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                    captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualManager.getISO());
                    captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualManager.getShutter());
                } else { captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON); }
                if (manualManager.isManualFocus()) {
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualManager.getFocus());
                } else { captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE); }
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);
            MediaActionSound sound = new MediaActionSound();
            sound.play(MediaActionSound.SHUTTER_CLICK);

            cameraCaptureSessions.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    processRawCapture(null, result);
                    updatePreview();

                    String modeInfo = highResActive ? "50MP" : "12MP";
                    String rawInfo = captureRaw ? " + RAW" : "";
                    String msg = "Đã lưu: " + modeInfo + rawInfo;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void processRawCapture(Image image, TotalCaptureResult result) {
        synchronized (mRawLock) {
            if (image != null) {
                if (mPendingRawImage != null) mPendingRawImage.close();
                mPendingRawImage = image;
            }
            if (result != null) mPendingCaptureResult = result;
            if (mPendingRawImage != null && mPendingCaptureResult != null) {
                if (mCharacteristics != null) {
                    DngCreator dngCreator = new DngCreator(mCharacteristics, mPendingCaptureResult);
                    saveRawDng(dngCreator, mPendingRawImage);
                }
                mPendingRawImage.close(); mPendingRawImage = null; mPendingCaptureResult = null;
            }
        }
    }

    private void toggleHighResMode() {
        if (!isDeviceSupportHighRes) {
            Toast.makeText(this, "Thiết bị không hỗ trợ HighRes", Toast.LENGTH_SHORT).show();
            return;
        }
        isHighResEnabled = !isHighResEnabled;
        if (isHighResEnabled) {
            btnHighRes.setTextColor(Color.parseColor("#FFD700")); btnHighRes.setAlpha(1.0f);
        } else {
            btnHighRes.setTextColor(Color.WHITE); btnHighRes.setAlpha(0.5f);
        }
        closeCamera();
        if (textureView.isAvailable()) openCamera(); else textureView.setSurfaceTextureListener(textureListener);
    }

    private void toggleRawMode() {
        isRawEnabled = !isRawEnabled;
        if (isRawEnabled) {
            btnRaw.setTextColor(Color.parseColor("#FFD700")); btnRaw.setAlpha(1.0f);
        } else {
            btnRaw.setTextColor(Color.WHITE); btnRaw.setAlpha(0.5f);
        }
        closeCamera();
        if (textureView.isAvailable()) openCamera(); else textureView.setSurfaceTextureListener(textureListener);
    }

    private void closeCamera() {
        if (cameraCaptureSessions != null) { cameraCaptureSessions.close(); cameraCaptureSessions = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (imageReaderNormal != null) { imageReaderNormal.close(); imageReaderNormal = null; }
        if (imageReaderHighRes != null) { imageReaderHighRes.close(); imageReaderHighRes = null; }
        if (rawImageReader != null) { rawImageReader.close(); rawImageReader = null; }
        synchronized (mRawLock) { if (mPendingRawImage != null) { mPendingRawImage.close(); mPendingRawImage = null; } }
    }

    private final ImageReader.OnImageAvailableListener jpegListener = r -> {
        Image i = r.acquireLatestImage(); if (i==null) return;
        ByteBuffer b = i.getPlanes()[0].getBuffer(); byte[] d = new byte[b.capacity()]; b.get(d); i.close();
        saveImageToGallery(d);
    };

    private final ImageReader.OnImageAvailableListener rawListener = r -> {
        Image i = r.acquireNextImage(); if (i != null) processRawCapture(i, null);
    };

    private void saveRawDng(DngCreator d, Image i) { ContentValues v = new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME, "RAW_" + System.currentTimeMillis() + ".dng"); v.put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng"); v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeitzCam"); Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v); try { OutputStream o = getContentResolver().openOutputStream(u); if(o!=null) { d.writeImage(o, i); o.close(); d.close(); } } catch (IOException e) { e.printStackTrace(); } }
    private Uri saveImageToGallery(byte[] bytes) { ContentValues v = new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + System.currentTimeMillis() + ".jpg"); v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeitzCam"); Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v); try { OutputStream o = getContentResolver().openOutputStream(u); if(o!=null){o.write(bytes);o.close();return u;} } catch(Exception e){} return null; }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() { @Override public void onOpened(@NonNull CameraDevice c) { cameraDevice = c; createCameraPreview(); } @Override public void onDisconnected(@NonNull CameraDevice c) { c.close(); } @Override public void onError(@NonNull CameraDevice c, int e) { c.close(); cameraDevice = null; } };
    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() { @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); } @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) { configureTransform(w, h); } @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return false; } @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {} };
    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() { @Override public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) { super.onCaptureCompleted(session, request, result); if (!isManualMode) { Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY); Long shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME); Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE); if (manualManager != null) { manualManager.updateAutoValues(iso != null ? iso : 100, shutter != null ? shutter : 10000000L, focus != null ? focus : 0.0f); } } } };

    private void startBackgroundThread() { backgroundThread = new HandlerThread("CameraBackground"); backgroundThread.start(); backgroundHandler = new Handler(backgroundThread.getLooper()); }
    private void stopBackgroundThread() { if (backgroundThread != null) { backgroundThread.quitSafely(); try { backgroundThread.join(); } catch (InterruptedException e) {} backgroundThread = null; backgroundHandler = null; } }
    private boolean contains(int[] m, int v) { if(m==null)return false; for(int i:m)if(i==v)return true; return false; }
    private Size chooseOptimalSize(Size[] c, int w, int h) { List<Size> b = new ArrayList<>(); for (Size o : c) { if (o.getHeight() == o.getWidth() * h / w && o.getWidth() >= w && o.getHeight() >= h) b.add(o); } if (b.size() > 0) return Collections.min(b, new CompareSizesByArea()); else return c[0]; }
    static class CompareSizesByArea implements Comparator<Size> { @Override public int compare(Size lhs, Size rhs) { return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight()); } }
    private void applyZoom(float cropFactor) { if (cameraDevice != null && captureRequestBuilder != null) {
        Rect active = (useHighResMode()) ? sensorArraySizeHighRes : sensorArraySize;
        applyZoomToBuilder(captureRequestBuilder, cropFactor, active); updatePreview();
    }}
    private void applyZoomToBuilder(CaptureRequest.Builder builder, float cropFactor, Rect activeArraySize) { int minW = (int) (activeArraySize.width() * (1.0f - cropFactor)); int minH = (int) (activeArraySize.height() * (1.0f - cropFactor)); int cropX = (activeArraySize.width() - minW) / 2; int cropY = (activeArraySize.height() - minH) / 2; Rect cropRect = new Rect(activeArraySize.left + cropX, activeArraySize.top + cropY, activeArraySize.left + cropX + minW, activeArraySize.top + cropY + minH); builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect); }
    private void cycleZoom() { if (currentZoomLevel == 1) { currentZoomLevel = 2; txtZoomIndicator.setText("2x"); txtZoomIndicator.setTextColor(Color.parseColor("#FFD700")); currentCropFactor = 0.5f; } else if (currentZoomLevel == 2) { currentZoomLevel = 0; txtZoomIndicator.setText("0.7x"); txtZoomIndicator.setTextColor(Color.WHITE); currentCropFactor = 0.0f; } else { currentZoomLevel = 1; txtZoomIndicator.setText("1x"); txtZoomIndicator.setTextColor(Color.WHITE); currentCropFactor = 0.2f; } applyZoom(currentCropFactor); }
    private void toggleAspectRatio() { currentRatioIndex++; if (currentRatioIndex > 2) currentRatioIndex = 0; ConstraintSet set = new ConstraintSet(); set.clone(mainLayout); if (currentRatioIndex == 0) set.setDimensionRatio(textureView.getId(), "H,3:4"); else if (currentRatioIndex == 1) set.setDimensionRatio(textureView.getId(), "H,1:1"); else set.setDimensionRatio(textureView.getId(), "H,9:16"); set.applyTo(mainLayout); textureView.post(() -> configureTransform(textureView.getWidth(), textureView.getHeight())); }
    private void configureTransform(int w, int h) { if (null == textureView || null == previewSize) return; Matrix m = new Matrix(); RectF v = new RectF(0, 0, w, h); RectF b = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth()); float cx = v.centerX(); float cy = v.centerY(); b.offset(cx - b.centerX(), cy - b.centerY()); m.setRectToRect(v, b, Matrix.ScaleToFit.FILL); float s = Math.max((float) h / previewSize.getWidth(), (float) w / previewSize.getHeight()); m.postScale(s, s, cx, cy); textureView.setTransform(m); }
    private void loadLastImage() { new Thread(() -> { try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.ImageColumns._ID}, null, null, MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC")) { if (c != null && c.moveToFirst()) { Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID))); lastPhotoUri = u; Bitmap b = loadBitmapFromUri(u); if (b != null) runOnUiThread(() -> btnGallery.setImageBitmap(getCircularBitmap(b))); } } catch (Exception e) {} }).start(); }
    private Bitmap loadBitmapFromUri(Uri u) { try { InputStream i = getContentResolver().openInputStream(u); Bitmap b = BitmapFactory.decodeStream(i); i.close(); return b; } catch (Exception e) { return null; } }
    private Bitmap loadBitmapFromBytes(byte[] b) { return BitmapFactory.decodeByteArray(b, 0, b.length); }
    private Bitmap getCircularBitmap(Bitmap b) { int w = b.getWidth(), h = b.getHeight(); int s = Math.min(w, h); Bitmap o = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(o); Paint p = new Paint(); p.setAntiAlias(true); c.drawCircle(s/2f, s/2f, s/2f, p); p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN)); c.drawBitmap(b, new Rect((w-s)/2, (h-s)/2, (w+s)/2, (h+s)/2), new Rect(0,0,s,s), p); return o; }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) { super.onRequestPermissionsResult(r, p, g); if (r == REQUEST_PERMISSION_CODE) { if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) { if(textureView.isAvailable()) openCamera(); } else { Toast.makeText(this, "Cần quyền!", Toast.LENGTH_SHORT).show(); }}}
    @Override protected void onResume() { super.onResume(); startBackgroundThread(); loadLastImage(); if (textureView.isAvailable()) openCamera(); else textureView.setSurfaceTextureListener(textureListener); }
    @Override protected void onPause() { stopBackgroundThread(); closeCamera(); super.onPause(); }
}