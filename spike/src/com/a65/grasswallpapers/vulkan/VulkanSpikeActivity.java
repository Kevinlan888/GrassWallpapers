package com.a65.grasswallpapers.vulkan;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class VulkanSpikeActivity extends Activity {
    private static final int BACKGROUND_TOP = Color.rgb(7, 31, 30);
    private static final int BACKGROUND_BOTTOM = Color.rgb(15, 70, 68);
    private static final int TEXT_PRIMARY = Color.rgb(242, 250, 246);
    private static final int TEXT_SECONDARY = Color.rgb(184, 210, 202);
    private static final int ACCENT = Color.rgb(199, 245, 139);
    private static final int ACCENT_DARK = Color.rgb(14, 48, 31);
    private static final int[] ROTATION_SECONDS = {30, 60, 300, 900};
    private static final int[] ROTATION_LABELS = {
            R.string.scene_rotation_30_seconds,
            R.string.scene_rotation_1_minute,
            R.string.scene_rotation_5_minutes,
            R.string.scene_rotation_15_minutes,
    };

    private TextView wallpaperStateView;
    private Switch syncSwitch;
    private TextView syncSummaryView;
    private LinearLayout rotationPanel;
    private final Button[] rotationButtons = new Button[ROTATION_SECONDS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        configureWindow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWallpaperState();
    }

    private View createContent() {
        int basePadding = dp(24);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(basePadding, basePadding, basePadding, basePadding);

        TextView badge = createText(R.string.setup_badge, 10.5f, ACCENT,
                Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(0.12f);
        badge.setPadding(dp(12), dp(6), dp(12), dp(6));
        badge.setBackground(rounded(0x1FFFFFFF, 0, 0));
        content.addView(badge, wrapContent());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(108), dp(108));
        iconParams.topMargin = dp(20);
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(icon, iconParams);

        TextView title = createText(R.string.setup_title, 30.0f,
                TEXT_PRIMARY, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = wrapContent();
        titleParams.topMargin = dp(16);
        content.addView(title, titleParams);

        TextView subtitle = createText(R.string.setup_subtitle, 15.0f,
                TEXT_SECONDARY, Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(0.0f, 1.22f);
        LinearLayout.LayoutParams subtitleParams = matchWidth();
        subtitleParams.topMargin = dp(12);
        content.addView(subtitle, subtitleParams);

        LinearLayout featureCard = new LinearLayout(this);
        featureCard.setOrientation(LinearLayout.VERTICAL);
        featureCard.setBackground(rounded(0x18FFFFFF, 0x2AFFFFFF, 1));
        featureCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams cardParams = matchWidth();
        cardParams.topMargin = dp(24);
        content.addView(featureCard, cardParams);

        TextView features = createText(R.string.feature_summary, 14.0f,
                TEXT_PRIMARY, Typeface.NORMAL);
        features.setLineSpacing(0.0f, 1.32f);
        featureCard.addView(features, matchWidth());

        LinearLayout settingsCard = createSceneSettingsCard();
        LinearLayout.LayoutParams settingsParams = matchWidth();
        settingsParams.topMargin = dp(12);
        content.addView(settingsCard, settingsParams);

        Button setWallpaperButton = createPrimaryButton();
        setWallpaperButton.setOnClickListener(view -> openLiveWallpaperPreview());
        LinearLayout.LayoutParams primaryParams = matchWidth();
        primaryParams.topMargin = dp(24);
        content.addView(setWallpaperButton, primaryParams);

        Button pickerButton = createSecondaryButton();
        pickerButton.setOnClickListener(view -> openSystemWallpaperPicker());
        LinearLayout.LayoutParams pickerParams = matchWidth();
        pickerParams.topMargin = dp(10);
        content.addView(pickerButton, pickerParams);

        wallpaperStateView = createText(R.string.wallpaper_state_not_applied,
                13.0f, TEXT_SECONDARY, Typeface.BOLD);
        wallpaperStateView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stateParams = matchWidth();
        stateParams.topMargin = dp(22);
        content.addView(wallpaperStateView, stateParams);

        TextView hint = createText(R.string.wallpaper_preview_hint, 12.0f,
                TEXT_SECONDARY, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0.0f, 1.22f);
        LinearLayout.LayoutParams hintParams = matchWidth();
        hintParams.topMargin = dp(10);
        content.addView(hint, hintParams);

        TextView footer = createText(R.string.setup_footer, 11.0f,
                Color.rgb(132, 166, 158), Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = matchWidth();
        footerParams.topMargin = dp(28);
        content.addView(footer, footerParams);

        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        root.setClipToPadding(false);
        root.setVerticalScrollBarEnabled(false);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {BACKGROUND_TOP, BACKGROUND_BOTTOM}));
        root.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(basePadding + left, basePadding + top,
                    basePadding + right, basePadding + bottom);
            return insets;
        });
        root.requestApplyInsets();
        return root;
    }

    private LinearLayout createSceneSettingsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0x18FFFFFF, 0x2AFFFFFF, 1));
        card.setPadding(dp(18), dp(16), dp(18), dp(18));

        TextView sectionTitle = createText(R.string.scene_settings_title,
                12.0f, ACCENT, Typeface.BOLD);
        sectionTitle.setLetterSpacing(0.08f);
        card.addView(sectionTitle, matchWidth());

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams syncRowParams = matchWidth();
        syncRowParams.topMargin = dp(12);
        card.addView(syncRow, syncRowParams);

        LinearLayout syncLabels = new LinearLayout(this);
        syncLabels.setOrientation(LinearLayout.VERTICAL);
        syncRow.addView(syncLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView syncTitle = createText(R.string.scene_sync_title,
                14.5f, TEXT_PRIMARY, Typeface.BOLD);
        syncLabels.addView(syncTitle, matchWidth());

        syncSummaryView = createText(R.string.scene_sync_summary_on,
                12.0f, TEXT_SECONDARY, Typeface.NORMAL);
        syncSummaryView.setLineSpacing(0.0f, 1.18f);
        LinearLayout.LayoutParams summaryParams = matchWidth();
        summaryParams.topMargin = dp(3);
        syncLabels.addView(syncSummaryView, summaryParams);

        syncSwitch = new Switch(this);
        syncSwitch.setChecked(WallpaperSettings.isSyncWithLocalTime(this));
        syncSwitch.setContentDescription(getString(R.string.scene_sync_title));
        syncSwitch.setThumbTintList(ColorStateList.valueOf(ACCENT));
        syncSwitch.setTrackTintList(ColorStateList.valueOf(0x55204030));
        syncSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            WallpaperSettings.setSyncWithLocalTime(this, isChecked);
            updateSceneModeControls();
        });
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.leftMargin = dp(12);
        syncRow.addView(syncSwitch, switchParams);

        rotationPanel = new LinearLayout(this);
        rotationPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rotationParams = matchWidth();
        rotationParams.topMargin = dp(14);
        card.addView(rotationPanel, rotationParams);

        TextView intervalLabel = createText(R.string.scene_rotation_interval,
                12.0f, TEXT_SECONDARY, Typeface.BOLD);
        rotationPanel.addView(intervalLabel, matchWidth());

        LinearLayout intervalRow = new LinearLayout(this);
        intervalRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams intervalRowParams = matchWidth();
        intervalRowParams.topMargin = dp(8);
        rotationPanel.addView(intervalRow, intervalRowParams);

        for (int index = 0; index < ROTATION_SECONDS.length; index++) {
            final int seconds = ROTATION_SECONDS[index];
            Button intervalButton = createIntervalButton(ROTATION_LABELS[index]);
            intervalButton.setOnClickListener(view -> {
                WallpaperSettings.setRotationSeconds(this, seconds);
                updateSceneModeControls();
            });
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    0, dp(38), 1.0f);
            if (index > 0) {
                buttonParams.leftMargin = dp(6);
            }
            rotationButtons[index] = intervalButton;
            intervalRow.addView(intervalButton, buttonParams);
        }

        TextView hint = createText(R.string.scene_settings_hint,
                11.0f, TEXT_SECONDARY, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWidth();
        hintParams.topMargin = dp(10);
        card.addView(hint, hintParams);

        updateSceneModeControls();
        return card;
    }

    private Button createIntervalButton(int textResource) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setTextSize(11.5f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        return button;
    }

    private void updateSceneModeControls() {
        if (syncSwitch == null || syncSummaryView == null
                || rotationPanel == null) {
            return;
        }
        boolean syncWithLocalTime = syncSwitch.isChecked();
        syncSummaryView.setText(syncWithLocalTime
                ? R.string.scene_sync_summary_on
                : R.string.scene_sync_summary_off);
        rotationPanel.setVisibility(syncWithLocalTime ? View.GONE : View.VISIBLE);

        int selectedSeconds = WallpaperSettings.getRotationSeconds(this);
        for (int index = 0; index < rotationButtons.length; index++) {
            boolean selected = ROTATION_SECONDS[index] == selectedSeconds;
            rotationButtons[index].setTextColor(selected ? ACCENT_DARK : TEXT_PRIMARY);
            rotationButtons[index].setBackground(selected
                    ? rounded(ACCENT, 0, 0)
                    : rounded(0x10FFFFFF, 0x55FFFFFF, 1));
        }
    }

    private Button createPrimaryButton() {
        Button button = new Button(this);
        button.setText(R.string.set_live_wallpaper);
        button.setTextSize(16.0f);
        button.setTextColor(ACCENT_DARK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(rounded(ACCENT, 0, 0));
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return button;
    }

    private Button createSecondaryButton() {
        Button button = new Button(this);
        button.setText(R.string.open_system_picker);
        button.setTextSize(14.0f);
        button.setTextColor(TEXT_PRIMARY);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(rounded(0x10FFFFFF, 0x55FFFFFF, 1));
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        return button;
    }

    private TextView createText(int textResource, float sizeSp, int color,
            int style) {
        TextView textView = new TextView(this);
        textView.setText(textResource);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.create("sans-serif", style));
        return textView;
    }

    private GradientDrawable rounded(int fillColor, int strokeColor,
            int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(18));
        if (strokeWidthDp > 0) {
            drawable.setStroke(dp(strokeWidthDp), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView()
                    .getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
    }

    private void updateWallpaperState() {
        if (wallpaperStateView == null) {
            return;
        }
        boolean applied = false;
        try {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            WallpaperInfo info = manager.getWallpaperInfo();
            ComponentName component = new ComponentName(
                    this, VulkanGrassWallpaperService.class);
            applied = info != null && component.equals(info.getComponent());
        } catch (RuntimeException ignored) {
            // Keep the default "not applied" state if the platform query fails.
        }
        wallpaperStateView.setText(applied
                ? R.string.wallpaper_state_applied
                : R.string.wallpaper_state_not_applied);
        wallpaperStateView.setTextColor(applied ? ACCENT : TEXT_SECONDARY);
    }

    private void openLiveWallpaperPreview() {
        ComponentName component = new ComponentName(
                this, VulkanGrassWallpaperService.class);
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        if (tryStartActivity(preview)) {
            return;
        }
        openSystemWallpaperPicker();
    }

    private void openSystemWallpaperPicker() {
        Intent livePicker = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
        if (tryStartActivity(livePicker)) {
            return;
        }
        Intent chooser = Intent.createChooser(
                new Intent(Intent.ACTION_SET_WALLPAPER),
                getString(R.string.open_system_picker));
        if (!tryStartActivity(chooser)) {
            Toast.makeText(this, R.string.wallpaper_picker_missing,
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryStartActivity(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
