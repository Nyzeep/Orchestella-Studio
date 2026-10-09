package io.github.nyzeep.orchestella;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.ArrayList;
import java.util.List;

public final class FakeSurfaceHolder implements SurfaceHolder {
    public final List<Callback> callbacks = new ArrayList<>();
    public int width = 0;
    public int height = 0;
    public int format = 0;
    public boolean creating = false;

    @Override
    public void addCallback(Callback callback) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        callbacks.remove(callback);
    }

    @Override
    public boolean isCreating() {
        return creating;
    }

    @Override
    public void setType(int type) {}

    @Override
    public void setFixedSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void setSizeFromLayout() {}

    @Override
    public void setFormat(int format) {
        this.format = format;
    }

    @Override
    public void setKeepScreenOn(boolean screenOn) {}

    @Override
    public Canvas lockCanvas() {
        return null;
    }

    @Override
    public Canvas lockCanvas(Rect dirty) {
        return null;
    }

    @Override
    public Canvas lockHardwareCanvas() {
        return null;
    }

    @Override
    public void unlockCanvasAndPost(Canvas canvas) {}

    @Override
    public Rect getSurfaceFrame() {
        return new Rect(0, 0, width, height);
    }

    @Override
    public Surface getSurface() {
        return null;
    }
}
