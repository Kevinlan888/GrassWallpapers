package com.a65.grasswallpapers.wallpaper;

public final class AospGrassWallpaperService extends BaseGrassWallpaperService {
    @Override
    protected BaseGrassRenderer createRenderer() {
        return new AospGrassRenderer(this);
    }
}
