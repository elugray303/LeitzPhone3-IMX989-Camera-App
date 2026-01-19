package com.example.imx989;

import android.app.Activity;
import android.graphics.Color;
import android.util.Range;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class ManualUIManager {

    // UI Elements
    private final LinearLayout manualPanel;
    private final LinearLayout sliderContainer;
    private final SeekBar sharedSlider;
    private final TextView btnAutoParam;
    private final LinearLayout btnParamISO, btnParamShutter, btnParamFocus;
    private final TextView txtValISO, txtValShutter, txtValFocus;

    // Constants (Request Ranges)
    private final int REQ_MIN_ISO = 50;
    private final int REQ_MAX_ISO = 25600;
    private final long REQ_MIN_SHUTTER_NS = 100_000L;        // 1/10000s
    private final long REQ_MAX_SHUTTER_NS = 30_000_000_000L; // 30s

    // Hardware Limits (From Camera)
    private Range<Integer> isoRange;
    private Range<Long> shutterRange;
    private float minFocusDist = 0.0f;

    // State
    public enum ParameterMode { NONE, ISO, SHUTTER, FOCUS }
    private ParameterMode currentParamMode = ParameterMode.NONE;
    private long lastSliderUpdateTime = 0;

    // Values (0 or 0.0f means AUTO)
    private int manualISO = 0;
    private long manualShutter = 0;
    private float manualFocus = 0.0f;

    // Auto Values (Spy from Preview)
    private int lastAutoISO = 100;
    private long lastAutoShutter = 10000000L;
    private float lastAutoFocus = 0.0f;

    // Callback to update preview
    private final Runnable updatePreviewCallback;

    public ManualUIManager(Activity activity, Runnable updatePreviewCallback) {
        this.updatePreviewCallback = updatePreviewCallback;

        // Binding Views
        manualPanel = activity.findViewById(R.id.manualPanel);
        sliderContainer = activity.findViewById(R.id.sliderContainer);
        sharedSlider = activity.findViewById(R.id.sharedSlider);
        btnAutoParam = activity.findViewById(R.id.btnAutoParam);

        btnParamISO = activity.findViewById(R.id.btnParamISO);
        btnParamShutter = activity.findViewById(R.id.btnParamShutter);
        btnParamFocus = activity.findViewById(R.id.btnParamFocus);

        txtValISO = activity.findViewById(R.id.txtValISO);
        txtValShutter = activity.findViewById(R.id.txtValShutter);
        txtValFocus = activity.findViewById(R.id.txtValFocus);

        setupListeners();
    }

    public void setHardwareRanges(Range<Integer> iso, Range<Long> shutter, float minFocus) {
        this.isoRange = iso;
        this.shutterRange = shutter;
        this.minFocusDist = minFocus;
    }

    public void updateAutoValues(int iso, long shutter, float focus) {
        this.lastAutoISO = iso;
        this.lastAutoShutter = shutter;
        this.lastAutoFocus = focus;
    }

    // Getters for CameraRequest
    public int getISO() { return manualISO > 0 ? manualISO : lastAutoISO; }
    public long getShutter() { return manualShutter > 0 ? manualShutter : lastAutoShutter; }
    public float getFocus() { return manualFocus; } // 0.0f will handle logic in Main

    public boolean isManualExposure() { return manualISO > 0 || manualShutter > 0; }
    public boolean isManualFocus() { return manualFocus > 0; }

    public void show() {
        manualPanel.setVisibility(View.VISIBLE);
        // Inherit Auto values visually but keep actual manual values as 0 (Auto) until touched
        updateDisplayTexts();
    }

    public void hide() {
        manualPanel.setVisibility(View.GONE);
        sliderContainer.setVisibility(View.GONE);
        currentParamMode = ParameterMode.NONE;
        // Reset manual overrides so Auto takes over
        manualISO = 0;
        manualShutter = 0;
        manualFocus = 0.0f;
        updateDisplayTexts();
    }

    private void updateDisplayTexts() {
        // Display Manual value if set, otherwise display Auto (Spy) value
        int displayISO = (manualISO > 0) ? manualISO : lastAutoISO;
        long displayShutter = (manualShutter > 0) ? manualShutter : lastAutoShutter;
        float displayFocus = (manualFocus > 0) ? manualFocus : lastAutoFocus;

        txtValISO.setText(manualISO > 0 ? String.valueOf(displayISO) : "AUTO");

        String shutterText;
        if (displayShutter >= 1000000000L) shutterText = String.format("%.1fs", displayShutter / 1e9);
        else shutterText = "1/" + (1000000000L / displayShutter);
        txtValShutter.setText(manualShutter > 0 ? shutterText : "AUTO");

        txtValFocus.setText(manualFocus > 0 ? String.format("%.1f", displayFocus) : "AUTO");
    }

    private void setupListeners() {
        btnParamISO.setOnClickListener(v -> selectParameter(ParameterMode.ISO));
        btnParamShutter.setOnClickListener(v -> selectParameter(ParameterMode.SHUTTER));
        btnParamFocus.setOnClickListener(v -> selectParameter(ParameterMode.FOCUS));
        btnAutoParam.setOnClickListener(v -> resetCurrentParameterToAuto());

        sharedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSliderUpdateTime < 30) return; // Throttle
                lastSliderUpdateTime = currentTime;

                handleSliderChange(progress);
                updatePreviewCallback.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void handleSliderChange(int progress) {
        switch (currentParamMode) {
            case ISO:
                if (isoRange == null) return;
                double isoRatio = (double) progress / 100.0;
                double logISO = Math.pow((double) REQ_MAX_ISO / REQ_MIN_ISO, isoRatio);
                int calcISO = (int) (REQ_MIN_ISO * logISO);
                manualISO = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), calcISO));
                break;
            case SHUTTER:
                if (shutterRange == null) return;
                double shutterRatio = (double) progress / 100.0;
                double logShutter = Math.pow((double) REQ_MAX_SHUTTER_NS / REQ_MIN_SHUTTER_NS, shutterRatio);
                long calcNs = (long) (REQ_MIN_SHUTTER_NS * logShutter);
                manualShutter = Math.max(shutterRange.getLower(), Math.min(shutterRange.getUpper(), calcNs));
                break;
            case FOCUS:
                manualFocus = 0.0f + (minFocusDist * (progress / 100.0f));
                break;
        }
        updateDisplayTexts();
    }

    private void selectParameter(ParameterMode mode) {
        currentParamMode = mode;
        sliderContainer.setVisibility(View.VISIBLE);

        // Reset UI Highlights
        btnParamISO.setBackgroundResource(R.drawable.bg_param_button);
        btnParamShutter.setBackgroundResource(R.drawable.bg_param_button);
        btnParamFocus.setBackgroundResource(R.drawable.bg_param_button);

        int progress = 0;
        switch (mode) {
            case ISO:
                btnParamISO.setBackgroundColor(Color.parseColor("#D32F2F"));
                int currentISO = (manualISO > 0) ? manualISO : lastAutoISO;
                currentISO = Math.max(REQ_MIN_ISO, Math.min(REQ_MAX_ISO, currentISO));
                progress = (int) ((Math.log((double) currentISO / REQ_MIN_ISO) / Math.log((double) REQ_MAX_ISO / REQ_MIN_ISO)) * 100);
                break;
            case SHUTTER:
                btnParamShutter.setBackgroundColor(Color.parseColor("#D32F2F"));
                long currentShutter = (manualShutter > 0) ? manualShutter : lastAutoShutter;
                currentShutter = Math.max(REQ_MIN_SHUTTER_NS, Math.min(REQ_MAX_SHUTTER_NS, currentShutter));
                progress = (int) ((Math.log((double) currentShutter / REQ_MIN_SHUTTER_NS) / Math.log((double) REQ_MAX_SHUTTER_NS / REQ_MIN_SHUTTER_NS)) * 100);
                break;
            case FOCUS:
                btnParamFocus.setBackgroundColor(Color.parseColor("#D32F2F"));
                float currentFocus = (manualFocus > 0) ? manualFocus : lastAutoFocus;
                progress = (int) ((currentFocus / minFocusDist) * 100);
                break;
        }
        sharedSlider.setProgress(progress);
    }

    private void resetCurrentParameterToAuto() {
        switch (currentParamMode) {
            case ISO: manualISO = 0; break;
            case SHUTTER: manualShutter = 0; break;
            case FOCUS: manualFocus = 0.0f; break;
        }
        sliderContainer.setVisibility(View.GONE);
        btnParamISO.setBackgroundResource(R.drawable.bg_param_button);
        btnParamShutter.setBackgroundResource(R.drawable.bg_param_button);
        btnParamFocus.setBackgroundResource(R.drawable.bg_param_button);
        currentParamMode = ParameterMode.NONE;

        updateDisplayTexts();
        updatePreviewCallback.run();
    }
}