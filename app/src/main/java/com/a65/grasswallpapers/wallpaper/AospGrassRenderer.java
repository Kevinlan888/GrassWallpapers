package com.a65.grasswallpapers.wallpaper;

import android.content.Context;

import com.a65.grasswallpapers.R;

public final class AospGrassRenderer extends BaseGrassRenderer {
    public AospGrassRenderer(Context context) {
        super(context);
        loadBackgrounds(R.drawable.sky_day_aosp);
    }

    @Override
    protected int getBladeCount() {
        return 200;
    }
}
