package com.a65.grasswallpapers.wallpaper;

public final class MtkGrassWallpaperService extends BaseGrassWallpaperService {
    @Override
    protected BaseGrassRenderer createRenderer() {
        return new MtkGrassRenderer(this);
    }

    @Override
    protected boolean supportsTouch() {
        return true;
    }
}
