package com.a65.grasswallpapers;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

public final class SingleWallpaperActivity extends Activity {
    private static final String META_WALLPAPER_SERVICE =
            "com.a65.grasswallpapers.SINGLE_WALLPAPER_SERVICE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        setContentView(R.layout.activity_single);

        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();

        String serviceClass = readWallpaperServiceClass();
        boolean mtk = serviceClass.endsWith(".MtkGrassWallpaperService");
        int variant = mtk ? 1 : 0;

        bindWallpaperDetails(mtk);
        WallpaperPreviewView preview = findViewById(R.id.preview_wallpaper);
        preview.setGrassVariant(variant);
        preview.setContentDescription(getString(mtk
                ? R.string.mtk_wallpaper_name
                : R.string.aosp_wallpaper_name));

        findViewById(R.id.button_wallpaper).setOnClickListener(
                view -> openWallpaperPreview(serviceClass));
    }

    private void bindWallpaperDetails(boolean mtk) {
        setText(R.id.single_hero_subtitle, mtk
                ? R.string.split_mtk_hero_subtitle
                : R.string.split_aosp_hero_subtitle);
        setText(R.id.single_card_number, mtk
                ? R.string.mtk_card_number
                : R.string.aosp_card_number);
        setText(R.id.single_card_badge, mtk
                ? R.string.mtk_card_badge
                : R.string.aosp_card_badge);
        setText(R.id.single_card_title, mtk
                ? R.string.mtk_card_title
                : R.string.aosp_card_title);
        setText(R.id.single_card_description, mtk
                ? R.string.mtk_card_description
                : R.string.aosp_card_description);
        setText(R.id.single_tag_1, mtk
                ? R.string.tag_100_blades
                : R.string.tag_200_blades);
        setText(R.id.single_tag_2, mtk
                ? R.string.tag_tap_particles
                : R.string.tag_time_of_day);
    }

    private void setText(int viewId, int stringId) {
        TextView textView = findViewById(viewId);
        textView.setText(stringId);
    }

    private String readWallpaperServiceClass() {
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(
                    getComponentName(), PackageManager.GET_META_DATA);
            if (info.metaData != null) {
                String serviceClass = info.metaData.getString(META_WALLPAPER_SERVICE);
                if (serviceClass != null && !serviceClass.isEmpty()) {
                    return serviceClass;
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Fall through to the configuration error below.
        }
        throw new IllegalStateException("Wallpaper service is not configured in the manifest");
    }

    private void configureEdgeToEdge() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars, lightBars);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
    }

    private void openWallpaperPreview(String serviceClassName) {
        ComponentName component = new ComponentName(this, serviceClassName);
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
        try {
            startActivity(preview);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Some launchers expose only the general wallpaper picker.
        }

        try {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Try the platform wallpaper picker below.
        }

        try {
            startActivity(Intent.createChooser(
                    new Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.set_wallpaper)));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this, R.string.wallpaper_picker_missing, Toast.LENGTH_LONG).show();
        }
    }
}
