package com.example.imx989;

// --- CÁC IMPORT BẮT BUỘC (ĐÃ KIỂM TRA ĐẦY ĐỦ) ---
import android.Manifest;
import android.content.ContentUris; // Sửa lỗi ContentUris
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat; // Sửa lỗi ImageFormat
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF; // Sửa lỗi RectF
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
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

public class MainActivity extends AppCompatActivity {

    // --- UI VARIABLES ---
    private TextureView textureView;
    private ImageButton btnCapture, btnSwitch, btnGallery, btnRatio, btnHighRes;
    private TextView txtZoomIndicator;
    private TextView modeVideo, modePhoto, modeManual, modeLeitz;
    private ConstraintLayout mainLayout;

    // --- MANUAL UI VARIABLES ---
    private LinearLayout manualPanel, sliderContainer;
    private SeekBar sharedSlider;
    private TextView btnAutoParam;
    private LinearLayout btnParamISO, btnParamShutter, btnParamFocus;
    private TextView txtValISO, txtValShutter, txtValFocus;

    // --- CAMERA VARIABLES ---
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;

    // --- SENSOR INFO ---
    private Rect sensorArraySize;
    private Rect sensorArraySizeHighRes;
    private Size previewSize = new Size(1440, 1080);

    // --- MANUAL RANGES (Hardware Limits) ---
    private Range<Integer> isoRange;
    private Range<Long> shutterRange;
    private float minFocusDist = 0.0f;

    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // --- STATE FLAGS ---
    private boolean isDeviceSupportHighRes = false;
    private boolean isHighResEnabled = false;
    private boolean isManualMode = false;

    private int currentZoomLevel = 1;
    private float currentCropFactor = 0.2f;
    private int currentRatioIndex = 0;

    // --- MANUAL VALUES (0 means Auto) ---
    private int manualISO = 0;
    private long manualShutter = 0;
    private float manualFocus = 0.0f;

    // --- AUTO VALUES STORAGE ---
    private int lastAutoISO = 100;
    private long lastAutoShutter = 10000000L; // 1/100s default
    private float lastAutoFocus = 0.0f;

    // --- SLIDER THROTTLE ---
    private long lastSliderUpdateTime = 0;

    private enum ParameterMode { NONE, ISO, SHUTTER, FOCUS }
    private ParameterMode currentParamMode = ParameterMode.NONE;

    // --- UI REQUESTED RANGES ---
    private final int REQ_MIN_ISO = 50;
    private final int REQ_MAX_ISO = 25600;
    private final long REQ_MIN_SHUTTER_NS = 100_000L;        // 1/10000s
    private final long REQ_MAX_SHUTTER_NS = 30_000_000_000L; // 30s

    private Uri lastPhotoUri = null;
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainLayout = findViewById(R.id.mainLayout);

        setupUIViews();
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

        manualPanel = findViewById(R.id.manualPanel);
        sliderContainer = findViewById(R.id.sliderContainer);
        sharedSlider = findViewById(R.id.sharedSlider);
        btnAutoParam = findViewById(R.id.btnAutoParam);

        btnParamISO = findViewById(R.id.btnParamISO);
        btnParamShutter = findViewById(R.id.btnParamShutter);
        btnParamFocus = findViewById(R.id.btnParamFocus);

        txtValISO = findViewById(R.id.txtValISO);
        txtValShutter = findViewById(R.id.txtValShutter);
        txtValFocus = findViewById(R.id.txtValFocus);
    }

    private void setupListeners() {
        btnCapture.setOnClickListener(v -> takePicture());
        txtZoomIndicator.setOnClickListener(v -> cycleZoom());
        btnRatio.setOnClickListener(v -> toggleAspectRatio());
        btnHighRes.setOnClickListener(v -> toggleHighResMode());

        btnGallery.setOnClickListener(v -> {
            if (lastPhotoUri != null) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(lastPhotoUri, "image/*");
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Không tìm thấy app xem ảnh!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        modeVideo.setOnClickListener(v -> switchMode(modeVideo, "VIDEO"));
        modePhoto.setOnClickListener(v -> switchMode(modePhoto, "PHOTO"));
        modeManual.setOnClickListener(v -> switchMode(modeManual, "MANUAL"));
        modeLeitz.setOnClickListener(v -> switchMode(modeLeitz, "LEITZ"));

        // --- MANUAL CONTROLS LISTENERS ---
        btnParamISO.setOnClickListener(v -> selectParameter(ParameterMode.ISO));
        btnParamShutter.setOnClickListener(v -> selectParameter(ParameterMode.SHUTTER));
        btnParamFocus.setOnClickListener(v -> selectParameter(ParameterMode.FOCUS));
        btnAutoParam.setOnClickListener(v -> resetCurrentParameterToAuto());

        sharedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return; // Chỉ xử lý khi người dùng kéo

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSliderUpdateTime < 30) return; // Throttle 30ms
                lastSliderUpdateTime = currentTime;

                switch (currentParamMode) {
                    case ISO:
                        if (isoRange == null) return;
                        // Logarithmic mapping for ISO
                        double isoRatio = (double) progress / 100.0;
                        double logISO = Math.pow((double) REQ_MAX_ISO / REQ_MIN_ISO, isoRatio);
                        int calcISO = (int) (REQ_MIN_ISO * logISO);

                        manualISO = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), calcISO));
                        txtValISO.setText(String.valueOf(manualISO));
                        break;

                    case SHUTTER:
                        if (shutterRange == null) return;
                        // Logarithmic mapping for Shutter
                        double shutterRatio = (double) progress / 100.0;
                        double logShutter = Math.pow((double) REQ_MAX_SHUTTER_NS / REQ_MIN_SHUTTER_NS, shutterRatio);
                        long calcNs = (long) (REQ_MIN_SHUTTER_NS * logShutter);

                        manualShutter = Math.max(shutterRange.getLower(), Math.min(shutterRange.getUpper(), calcNs));

                        String display;
                        if (manualShutter >= 1000000000L) {
                            display = String.format("%.1fs", manualShutter / 1e9);
                        } else {
                            long denominator = 1000000000L / manualShutter;
                            display = "1/" + denominator;
                        }
                        txtValShutter.setText(display);
                        break;

                    case FOCUS:
                        // Linear mapping for Focus
                        manualFocus = 0.0f + (minFocusDist * (progress / 100.0f));
                        txtValFocus.setText(String.format("%.1f", manualFocus));
                        break;
                }
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    // --- CAPTURE CALLBACK (Spy on Auto values) ---
    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if (!isManualMode) {
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Long shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

                if (iso != null) lastAutoISO = iso;
                if (shutter != null) lastAutoShutter = shutter;
                if (focus != null) lastAutoFocus = focus;
            }
        }
    };

    // --- UI LOGIC ---
    private void selectParameter(ParameterMode mode) {
        currentParamMode = mode;
        sliderContainer.setVisibility(View.VISIBLE);

        btnParamISO.setBackgroundResource(R.drawable.bg_param_button);
        btnParamShutter.setBackgroundResource(R.drawable.bg_param_button);
        btnParamFocus.setBackgroundResource(R.drawable.bg_param_button);

        switch (mode) {
            case ISO:
                btnParamISO.setBackgroundColor(Color.parseColor("#D32F2F"));
                int currentISO = (manualISO > 0) ? manualISO : lastAutoISO;
                currentISO = Math.max(REQ_MIN_ISO, Math.min(REQ_MAX_ISO, currentISO));

                // Inverse Logarithmic calculation for Slider position
                double isoLogVal = Math.log((double) currentISO / REQ_MIN_ISO);
                double isoLogRange = Math.log((double) REQ_MAX_ISO / REQ_MIN_ISO);
                int isoProgress = (int) ((isoLogVal / isoLogRange) * 100);

                sharedSlider.setProgress(isoProgress);
                break;

            case SHUTTER:
                btnParamShutter.setBackgroundColor(Color.parseColor("#D32F2F"));
                long currentShutter = (manualShutter > 0) ? manualShutter : lastAutoShutter;
                currentShutter = Math.max(REQ_MIN_SHUTTER_NS, Math.min(REQ_MAX_SHUTTER_NS, currentShutter));

                // Inverse Logarithmic calculation
                double shutterLogVal = Math.log((double) currentShutter / REQ_MIN_SHUTTER_NS);
                double shutterLogRange = Math.log((double) REQ_MAX_SHUTTER_NS / REQ_MIN_SHUTTER_NS);
                int shutterProgress = (int) ((shutterLogVal / shutterLogRange) * 100);

                sharedSlider.setProgress(shutterProgress);
                break;

            case FOCUS:
                btnParamFocus.setBackgroundColor(Color.parseColor("#D32F2F"));
                float currentFocus = (manualFocus > 0) ? manualFocus : lastAutoFocus;
                int focusProgress = (int) ((currentFocus / minFocusDist) * 100);
                sharedSlider.setProgress(focusProgress);
                break;
        }
    }

    private void switchMode(TextView selectedMode, String modeName) {
        modeVideo.setTextColor(Color.WHITE);
        modePhoto.setTextColor(Color.WHITE);
        modeManual.setTextColor(Color.WHITE);
        modeLeitz.setTextColor(Color.WHITE);
        selectedMode.setTextColor(Color.parseColor("#D32F2F"));

        if (modeName.equals("MANUAL")) {
            isManualMode = true;
            manualPanel.setVisibility(View.VISIBLE);
            btnGallery.setVisibility(View.INVISIBLE);
            btnSwitch.setVisibility(View.INVISIBLE);

            // Inherit Auto values
            if (manualISO == 0) manualISO = lastAutoISO;
            if (manualShutter == 0) manualShutter = lastAutoShutter;
            if (manualFocus == 0) manualFocus = lastAutoFocus;

            txtValISO.setText(String.valueOf(manualISO));
            String shutterText = manualShutter >= 1000000000L ? String.format("%.1fs", manualShutter/1e9) : String.format("1/%d", 1000000000L/manualShutter);
            txtValShutter.setText(shutterText);
            txtValFocus.setText(String.format("%.1f", manualFocus));

            sliderContainer.setVisibility(View.GONE);
            currentParamMode = ParameterMode.NONE;

            btnParamISO.setBackgroundResource(R.drawable.bg_param_button);
            btnParamShutter.setBackgroundResource(R.drawable.bg_param_button);
            btnParamFocus.setBackgroundResource(R.drawable.bg_param_button);

            updatePreview();
        } else {
            isManualMode = false;
            manualPanel.setVisibility(View.GONE);
            btnGallery.setVisibility(View.VISIBLE);
            btnSwitch.setVisibility(View.VISIBLE);

            txtValISO.setText("AUTO");
            txtValShutter.setText("AUTO");
            txtValFocus.setText("AUTO");

            updatePreview();
        }
    }

    private void resetCurrentParameterToAuto() {
        switch (currentParamMode) {
            case ISO:
                manualISO = 0;
                txtValISO.setText("AUTO");
                break;
            case SHUTTER:
                manualShutter = 0;
                txtValShutter.setText("AUTO");
                break;
            case FOCUS:
                manualFocus = 0.0f;
                txtValFocus.setText("AUTO");
                break;
        }
        sliderContainer.setVisibility(View.GONE);
        btnParamISO.setBackgroundResource(R.drawable.bg_param_button);
        btnParamShutter.setBackgroundResource(R.drawable.bg_param_button);
        btnParamFocus.setBackgroundResource(R.drawable.bg_param_button);
        currentParamMode = ParameterMode.NONE;
        updatePreview();
    }

    private void updatePreview() {
        if (cameraDevice == null || captureRequestBuilder == null) return;
        try {
            if (isManualMode) {
                // Manual Mode Logic
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                boolean manualExposure = (manualISO > 0 || manualShutter > 0);
                boolean manualFocusMode = (manualFocus > 0);

                if (manualExposure) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                    int targetISO = (manualISO > 0) ? manualISO : lastAutoISO;
                    long targetShutter = (manualShutter > 0) ? manualShutter : lastAutoShutter;
                    captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, targetISO);
                    captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutter);
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                }

                if (manualFocusMode) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus);
                } else {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
            } else {
                // Auto Mode
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // --- CAMERA LIFECYCLE & CORE ---

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sensorArraySizeHighRes = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            }
            if (sensorArraySizeHighRes == null) sensorArraySizeHighRes = sensorArraySize;

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StreamConfigurationMap highResMap = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                highResMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            }
            isDeviceSupportHighRes = (highResMap != null);

            Size targetSize;
            if (isHighResEnabled && isDeviceSupportHighRes) {
                targetSize = Collections.max(Arrays.asList(highResMap.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            } else {
                targetSize = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
            }

            imageReader = ImageReader.newInstance(targetSize.getWidth(), targetSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
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

            Rect activeArray = useHighRes ? sensorArraySizeHighRes : sensorArraySize;
            applyZoomToBuilder(captureBuilder, currentCropFactor, activeArray);

            // Apply Manual Settings to Capture
            if (isManualMode) {
                boolean manualExposure = (manualISO > 0 || manualShutter > 0);
                boolean manualFocusMode = (manualFocus > 0);

                if (manualExposure) {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                    int targetISO = (manualISO > 0) ? manualISO : lastAutoISO;
                    long targetShutter = (manualShutter > 0) ? manualShutter : lastAutoShutter;
                    captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, targetISO);
                    captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutter);
                } else {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                }

                if (manualFocusMode) {
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                    captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus);
                } else {
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
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

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void toggleHighResMode() {
        isHighResEnabled = !isHighResEnabled;
        if (isHighResEnabled) {
            btnHighRes.setColorFilter(Color.parseColor("#FFD700"));
            btnHighRes.setAlpha(1.0f);
            Toast.makeText(this, "Chế độ 50MP: BẬT", Toast.LENGTH_SHORT).show();
        } else {
            btnHighRes.setColorFilter(Color.parseColor("#FFFFFF"));
            btnHighRes.setAlpha(0.5f);
            Toast.makeText(this, "Chế độ 12MP: BẬT", Toast.LENGTH_SHORT).show();
        }
        closeCamera();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    private void closeCamera() {
        if (null != cameraCaptureSessions) {
            cameraCaptureSessions.close();
            cameraCaptureSessions = null;
        }
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void applyZoom(float cropFactor) {
        if (cameraDevice == null || captureRequestBuilder == null) return;
        applyZoomToBuilder(captureRequestBuilder, cropFactor, sensorArraySize);
        updatePreview();
    }

    private void applyZoomToBuilder(CaptureRequest.Builder builder, float cropFactor, Rect activeArraySize) {
        if (activeArraySize == null) return;
        int minW = (int) (activeArraySize.width() * (1.0f - cropFactor));
        int minH = (int) (activeArraySize.height() * (1.0f - cropFactor));
        int cropX = (activeArraySize.width() - minW) / 2;
        int cropY = (activeArraySize.height() - minH) / 2;
        int currentLeft = activeArraySize.left + cropX;
        int currentTop = activeArraySize.top + cropY;
        Rect cropRect = new Rect(currentLeft, currentTop, currentLeft + minW, currentTop + minH);
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect);
    }

    private void cycleZoom() {
        if (currentZoomLevel == 1) {
            currentZoomLevel = 2;
            txtZoomIndicator.setText("2x");
            txtZoomIndicator.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
            currentCropFactor = 0.5f;
        } else if (currentZoomLevel == 2) {
            currentZoomLevel = 0;
            txtZoomIndicator.setText("0.7x");
            txtZoomIndicator.setTextColor(getResources().getColor(android.R.color.white));
            currentCropFactor = 0.0f;
        } else {
            currentZoomLevel = 1;
            txtZoomIndicator.setText("1x");
            txtZoomIndicator.setTextColor(getResources().getColor(android.R.color.white));
            currentCropFactor = 0.2f;
        }
        applyZoom(currentCropFactor);
    }

    private void toggleAspectRatio() {
        currentRatioIndex++;
        if (currentRatioIndex > 2) currentRatioIndex = 0;
        ConstraintSet set = new ConstraintSet();
        set.clone(mainLayout);
        if (currentRatioIndex == 0) {
            set.setDimensionRatio(textureView.getId(), "H,3:4");
            Toast.makeText(this, "4:3", Toast.LENGTH_SHORT).show();
        } else if (currentRatioIndex == 1) {
            set.setDimensionRatio(textureView.getId(), "H,1:1");
            Toast.makeText(this, "1:1", Toast.LENGTH_SHORT).show();
        } else {
            set.setDimensionRatio(textureView.getId(), "H,9:16");
            Toast.makeText(this, "16:9", Toast.LENGTH_SHORT).show();
        }
        set.applyTo(mainLayout);
        textureView.post(() -> configureTransform(textureView.getWidth(), textureView.getHeight()));
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == previewSize) return;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float) viewHeight / previewSize.getWidth(), (float) viewWidth / previewSize.getHeight());
        matrix.postScale(scale, scale, centerX, centerY);
        textureView.setTransform(matrix);
    }

    // --- GALLERY ---
    private void loadLastImage() {
        new Thread(() -> {
            String selection = null;
            String[] selectionArgs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                selectionArgs = new String[]{"%Pictures/LeitzCam%"};
            }
            String sortOrder = MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC";
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.ImageColumns._ID},
                    selection, selectionArgs, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID);
                    long id = cursor.getLong(idColumn);
                    Uri imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    lastPhotoUri = imageUri;
                    Bitmap bitmap = loadBitmapFromUri(imageUri);
                    if (bitmap != null) {
                        Bitmap circularBitmap = getCircularBitmap(bitmap);
                        runOnUiThread(() -> btnGallery.setImageBitmap(circularBitmap));
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private Bitmap loadBitmapFromUri(Uri uri) {
        try {
            InputStream input = getContentResolver().openInputStream(uri);
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            input.close();
            input = getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 16;
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            input.close();
            return rotateBitmapIfRequired(bitmap, orientation);
        } catch (Exception e) { return null; }
    }

    private Bitmap loadBitmapFromBytes(byte[] bytes) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            ExifInterface exif = new ExifInterface(inputStream);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 16;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            return rotateBitmapIfRequired(bitmap, orientation);
        } catch (IOException e) { return null; }
    }

    private Bitmap rotateBitmapIfRequired(Bitmap img, int orientation) {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return img;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
        }
        return Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
    }

    private Bitmap getCircularBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int outputSize = Math.min(width, height);
        Bitmap output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, outputSize, outputSize);
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(outputSize / 2f, outputSize / 2f, outputSize / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int left = (width - outputSize) / 2;
        int top = (height - outputSize) / 2;
        Rect srcRect = new Rect(left, top, left + outputSize, top + outputSize);
        canvas.drawBitmap(bitmap, srcRect, rect, paint);
        return output;
    }

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

    private Uri saveImageToGallery(byte[] bytes) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "LEITZ_" + (isHighResEnabled?"HR_":"") + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LeitzCam");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        try {
            if (uri != null) {
                OutputStream outputStream = getContentResolver().openOutputStream(uri);
                outputStream.write(bytes);
                outputStream.close();
                return uri;
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        loadLastImage();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        closeCamera();
        super.onPause();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if(backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

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

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_DENIED) finish();
    }
}