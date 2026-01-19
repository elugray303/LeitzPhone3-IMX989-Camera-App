package com.example.imx989;

import android.Manifest;
import android.content.ContentUris;
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

import java.io.ByteArrayInputStream;
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

    // --- UI ---
    private TextureView textureView;
    private ImageButton btnCapture, btnSwitch, btnGallery, btnRatio, btnHighRes;
    private TextView txtZoomIndicator;
    private TextView modeVideo, modePhoto, modeManual, modeLeitz;
    private ConstraintLayout mainLayout;

    // --- MANAGERS ---
    private ManualUIManager manualManager;

    // --- CAMERA ---
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size previewSize;
    private Rect sensorArraySize;
    private Rect sensorArraySizeHighRes;
    private ImageReader imageReader;

    // --- THREAD ---
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // --- STATE ---
    private boolean isDeviceSupportHighRes = false;
    private boolean isHighResEnabled = false;
    private boolean isManualMode = false;
    private int currentZoomLevel = 1;
    private float currentCropFactor = 0.2f;
    private int currentRatioIndex = 0;

    private Uri lastPhotoUri = null;
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainLayout = findViewById(R.id.mainLayout);

        setupUIViews();
        // Khởi tạo Manual Manager và truyền vào hàm callback updatePreview
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

        // --- MỞ GOOGLE PHOTOS LOGIC ---
        btnGallery.setOnClickListener(v -> {
            if (lastPhotoUri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(lastPhotoUri, "image/*");
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // Ép mở Google Photos
                intent.setPackage("com.google.android.apps.photos");
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    // Nếu không có Google Photos, mở app mặc định
                    intent.setPackage(null);
                    try { startActivity(intent); }
                    catch (Exception ex) { Toast.makeText(this, "Không tìm thấy app xem ảnh!", Toast.LENGTH_SHORT).show(); }
                }
            } else {
                Toast.makeText(this, "Thư viện trống", Toast.LENGTH_SHORT).show();
            }
        });

        modeVideo.setOnClickListener(v -> switchMode(modeVideo, "VIDEO"));
        modePhoto.setOnClickListener(v -> switchMode(modePhoto, "PHOTO"));
        modeManual.setOnClickListener(v -> switchMode(modeManual, "MANUAL"));
        modeLeitz.setOnClickListener(v -> switchMode(modeLeitz, "LEITZ"));
    }

    private void switchMode(TextView selectedMode, String modeName) {
        modeVideo.setTextColor(Color.WHITE);
        modePhoto.setTextColor(Color.WHITE);
        modeManual.setTextColor(Color.WHITE);
        modeLeitz.setTextColor(Color.WHITE);
        selectedMode.setTextColor(Color.parseColor("#D32F2F"));

        if (modeName.equals("MANUAL")) {
            isManualMode = true;
            manualManager.show(); // Hiện Manual Panel

            // Hiện Gallery, ẩn Switch
            btnGallery.setVisibility(View.VISIBLE);
            btnSwitch.setVisibility(View.INVISIBLE);
        } else {
            isManualMode = false;
            manualManager.hide(); // Ẩn Manual Panel

            btnGallery.setVisibility(View.VISIBLE);
            btnSwitch.setVisibility(View.VISIBLE);
        }
        updatePreview();
    }

    private void updatePreview() {
        if (cameraDevice == null || captureRequestBuilder == null) return;
        try {
            if (isManualMode) {
                // MANUAL MODE LOGIC (Lấy giá trị từ ManualManager)
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                if (manualManager.isManualExposure()) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, manualManager.getISO());
                    captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualManager.getShutter());
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                }

                if (manualManager.isManualFocus()) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualManager.getFocus());
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
            } else {
                // AUTO MODE
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // --- CAPTURE CALLBACK (Spying Auto Values) ---
    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            // Gửi thông số Auto về cho ManualManager để hiển thị/đồng bộ
            if (!isManualMode) {
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Long shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

                if (manualManager != null) {
                    manualManager.updateAutoValues(
                            iso != null ? iso : 100,
                            shutter != null ? shutter : 10000000L,
                            focus != null ? focus : 0.0f
                    );
                }
            }
        }
    };

    // --- CAMERA CORE ---
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            // Gửi giới hạn phần cứng sang ManualManager
            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            manualManager.setHardwareRanges(isoRange, shutterRange, minFocus);

            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sensorArraySizeHighRes = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            }
            if (sensorArraySizeHighRes == null) sensorArraySizeHighRes = sensorArraySize;

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), 1440, 1080);

            Size targetSize = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            if (isHighResEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                StreamConfigurationMap highResMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (highResMap != null) {
                    targetSize = Collections.max(Arrays.asList(highResMap.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                    isDeviceSupportHighRes = true;
                }
            }

            imageReader = ImageReader.newInstance(targetSize.getWidth(), targetSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void takePicture() {
        if (null == cameraDevice) return;
        try {
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            boolean useHighRes = isHighResEnabled && isDeviceSupportHighRes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            if (useHighRes) {
                captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            }
            Rect activeArray = (useHighRes) ? sensorArraySizeHighRes : sensorArraySize;
            applyZoomToBuilder(captureBuilder, currentCropFactor, activeArray);

            if (isManualMode) {
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
                    super.onCaptureCompleted(session, request, result);
                    updatePreview();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đã lưu!", Toast.LENGTH_SHORT).show());
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // --- LISTENERS & HELPERS (Code cũ) ---
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) { openCamera(); }
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) { configureTransform(width, height); }
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) { cameraDevice = camera; createCameraPreview(); }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
    };

    private final ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                Uri savedUri = saveImageToGallery(bytes);
                lastPhotoUri = savedUri;
                Bitmap rotatedBitmap = loadBitmapFromBytes(bytes);
                if (rotatedBitmap != null) {
                    Bitmap circularBitmap = getCircularBitmap(rotatedBitmap);
                    runOnUiThread(() -> btnGallery.setImageBitmap(circularBitmap));
                }
            } catch (Exception e) { e.printStackTrace(); } finally { if (image != null) image.close(); }
        }
    };

    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (cameraDevice == null) return;
                    cameraCaptureSessions = cameraCaptureSession;
                    applyZoom(currentCropFactor);
                    runOnUiThread(() -> configureTransform(textureView.getWidth(), textureView.getHeight()));
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private Uri saveImageToGallery(byte[] bytes) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "LEITZ_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeitzCam");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try {
            if (uri != null) {
                OutputStream outputStream = getContentResolver().openOutputStream(uri);
                if(outputStream != null) { outputStream.write(bytes); outputStream.close(); return uri; }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    // --- OTHER HELPERS (Zoom, Transform, LoadImage...) ---
    // (Phần này giống hệt code cũ, đảm bảo không thiếu)
    private void toggleHighResMode() {
        isHighResEnabled = !isHighResEnabled;
        if (isHighResEnabled) { btnHighRes.setColorFilter(Color.parseColor("#FFD700")); btnHighRes.setAlpha(1.0f); Toast.makeText(this, "50MP ON", Toast.LENGTH_SHORT).show(); }
        else { btnHighRes.setColorFilter(Color.WHITE); btnHighRes.setAlpha(0.5f); Toast.makeText(this, "12MP ON", Toast.LENGTH_SHORT).show(); }
        closeCamera(); if (textureView.isAvailable()) openCamera(); else textureView.setSurfaceTextureListener(textureListener);
    }
    private void closeCamera() { if (cameraCaptureSessions != null) cameraCaptureSessions.close(); if (cameraDevice != null) cameraDevice.close(); if (imageReader != null) imageReader.close(); }
    private void applyZoom(float cropFactor) { if (cameraDevice != null && captureRequestBuilder != null) { applyZoomToBuilder(captureRequestBuilder, cropFactor, sensorArraySize); updatePreview(); } }
    private void applyZoomToBuilder(CaptureRequest.Builder builder, float cropFactor, Rect activeArraySize) {
        int minW = (int) (activeArraySize.width() * (1.0f - cropFactor));
        int minH = (int) (activeArraySize.height() * (1.0f - cropFactor));
        int cropX = (activeArraySize.width() - minW) / 2;
        int cropY = (activeArraySize.height() - minH) / 2;
        Rect cropRect = new Rect(activeArraySize.left + cropX, activeArraySize.top + cropY, activeArraySize.left + cropX + minW, activeArraySize.top + cropY + minH);
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect);
    }
    private void cycleZoom() {
        if (currentZoomLevel == 1) { currentZoomLevel = 2; txtZoomIndicator.setText("2x"); txtZoomIndicator.setTextColor(Color.parseColor("#FFD700")); currentCropFactor = 0.5f; }
        else if (currentZoomLevel == 2) { currentZoomLevel = 0; txtZoomIndicator.setText("0.7x"); txtZoomIndicator.setTextColor(Color.WHITE); currentCropFactor = 0.0f; }
        else { currentZoomLevel = 1; txtZoomIndicator.setText("1x"); txtZoomIndicator.setTextColor(Color.WHITE); currentCropFactor = 0.2f; }
        applyZoom(currentCropFactor);
    }
    private void toggleAspectRatio() {
        currentRatioIndex++; if (currentRatioIndex > 2) currentRatioIndex = 0;
        ConstraintSet set = new ConstraintSet(); set.clone(mainLayout);
        if (currentRatioIndex == 0) set.setDimensionRatio(textureView.getId(), "H,3:4");
        else if (currentRatioIndex == 1) set.setDimensionRatio(textureView.getId(), "H,1:1");
        else set.setDimensionRatio(textureView.getId(), "H,9:16");
        set.applyTo(mainLayout); textureView.post(() -> configureTransform(textureView.getWidth(), textureView.getHeight()));
    }
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == previewSize) return;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX(); float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) viewHeight / previewSize.getWidth(), (float) viewWidth / previewSize.getHeight());
        matrix.postScale(scale, scale, centerX, centerY);
        textureView.setTransform(matrix);
    }
    private void loadLastImage() {
        new Thread(() -> {
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.ImageColumns._ID}, null, null, MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    Uri imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)));
                    lastPhotoUri = imageUri;
                    Bitmap bitmap = loadBitmapFromUri(imageUri);
                    if (bitmap != null) runOnUiThread(() -> btnGallery.setImageBitmap(getCircularBitmap(bitmap)));
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }
    private Bitmap loadBitmapFromUri(Uri uri) { try { InputStream i = getContentResolver().openInputStream(uri); Bitmap b = BitmapFactory.decodeStream(i); i.close(); return b; } catch (Exception e) { return null; } }
    private Bitmap loadBitmapFromBytes(byte[] bytes) { return BitmapFactory.decodeByteArray(bytes, 0, bytes.length); }
    private Bitmap getCircularBitmap(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight(); int s = Math.min(w, h);
        Bitmap o = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(o); Paint p = new Paint(); p.setAntiAlias(true);
        c.drawCircle(s/2f, s/2f, s/2f, p); p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(bitmap, new Rect((w-s)/2, (h-s)/2, (w+s)/2, (h+s)/2), new Rect(0,0,s,s), p);
        return o;
    }
    private Size chooseOptimalSize(Size[] choices, int w, int h) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) { if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= w && option.getHeight() >= h) bigEnough.add(option); }
        if (bigEnough.size() > 0) return Collections.min(bigEnough, new CompareSizesByArea()); else return choices[0];
    }
    private void startBackgroundThread() { backgroundThread = new HandlerThread("CameraBackground"); backgroundThread.start(); backgroundHandler = new Handler(backgroundThread.getLooper()); }
    private void stopBackgroundThread() { if (backgroundThread != null) { backgroundThread.quitSafely(); try { backgroundThread.join(); backgroundThread = null; backgroundHandler = null; } catch (InterruptedException e) { e.printStackTrace(); } } }
    static class CompareSizesByArea implements Comparator<Size> { @Override public int compare(Size lhs, Size rhs) { return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight()); } }
    @Override protected void onResume() { super.onResume(); startBackgroundThread(); loadLastImage(); if (textureView.isAvailable()) openCamera(); else textureView.setSurfaceTextureListener(textureListener); }
    @Override protected void onPause() { stopBackgroundThread(); closeCamera(); super.onPause(); }
    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) { super.onRequestPermissionsResult(r, p, g); if (r == REQUEST_CAMERA_PERMISSION && g[0] != PackageManager.PERMISSION_GRANTED) finish(); }
}