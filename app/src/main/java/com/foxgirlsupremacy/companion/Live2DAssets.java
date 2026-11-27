package com.foxgirlsupremacy.companion;

import android.content.Context;
import android.util.Log;

import com.live2d.sdk.cubism.core.ICubismLogger;
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Live2DAssets {
    private static final String TAG = "FGS-Live2D";

    private static Context appContext;
    private static double lastNano;
    private static double deltaNano;

    private Live2DAssets() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        lastNano = System.nanoTime();
    }

    public static void updateTime() {
        double now = System.nanoTime();
        deltaNano = now - lastNano;
        lastNano = now;
    }

    public static float getDeltaTime() {
        return (float) (deltaNano / 1_000_000_000.0);
    }

    public static byte[] loadFileAsBytes(String path) {
        try (InputStream is = openStream(path)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            int read = 0;
            while (read < size) {
                int r = is.read(buffer, read, size - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
            return buffer;
        } catch (IOException e) {
            Log.e(TAG, "Échec lecture asset : " + path, e);
            return new byte[0];
        }
    }

    private static InputStream openStream(String path) throws IOException {
        if (path.startsWith("/")) {
            return new FileInputStream(new File(path));
        }
        return appContext.getAssets().open(path);
    }

    public static final class LoadFile implements ICubismLoadFileFunction {
        @Override
        public byte[] load(String path) {
            return loadFileAsBytes(path);
        }
    }

    public static final class Logger implements ICubismLogger {
        @Override
        public void print(String message) {
            Log.d(TAG, message);
        }
    }
}
