package io.github.nyzeep.orchestella.spike.w405bench;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import io.github.nyzeep.orchestella.NativeGlesSurfacePresenter;

public final class BenchActivity extends Activity implements SurfaceHolder.Callback {
    private BenchRun run;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        setContentView(view);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (run != null) return;
        String mode = getIntent().getStringExtra("mode");
        String load = getIntent().getStringExtra("load");
        String runId = getIntent().getStringExtra("run_id");
        int seconds = getIntent().getIntExtra("seconds", 20);
        if (mode == null || !mode.matches("raw|zrle|ivshmem")
                || load == null || !load.matches("static|terminal|drag")
                || runId == null || !runId.matches("[a-z0-9]{1,24}")
                || seconds < 5 || seconds > 30 || !RfbSource.supports(mode)) {
            Log.e(BenchRun.TAG, "invalid benchmark configuration");
            finish();
            return;
        }
        NativeGlesSurfacePresenter presenter = new NativeGlesSurfacePresenter(holder.getSurface());
        presenter.onSurfaceChanged(width, height);
        run = new BenchRun(this, presenter, mode, load, runId, seconds, width, height);
        new Thread(() -> {
            try {
                run.execute();
            } finally {
                runOnUiThread(this::finish);
            }
        }, "W405BenchRun").start();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (run != null) run.abort();
    }
}
