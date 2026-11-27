package com.foxgirlsupremacy.companion;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;

/** JNI bridge to Live2D Cubism Native. Asset IO stays on Android's AssetManager. */
public final class NativeLive2DBridge {
    private static Context appContext;

    static { System.loadLibrary("foxgirl_live2d"); }

    private NativeLive2DBridge() {}

    public static void initialize(Context context) {
        appContext = context.getApplicationContext();
    }

    public static byte[] LoadFile(String path) {
        if (appContext == null) return null;
        try {
            if (new File(path).isFile()) return java.nio.file.Files.readAllBytes(new File(path).toPath());
            try (InputStream in = appContext.getAssets().open(path);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                return out.toByteArray();
            }
        } catch (IOException e) {
            android.util.Log.e("FoxGirlLive2D", "Asset not found: " + path, e);
            return null;
        }
    }

    public static void MoveTaskToBack() { /* The companion is hosted by OverlayService. */ }

    public static native void nativeConfigureModel(String modelName, String assetDirectory);
    public static native void nativeOnStart();
    public static native void nativeOnPause();
    public static native void nativeOnStop();
    public static native void nativeOnDestroy();
    public static native void nativeOnSurfaceCreated();
    public static native void nativeOnSurfaceChanged(int width, int height);
    public static native void nativeOnDrawFrame();
    public static native void nativeOnTouchesBegan(float x, float y);
    public static native void nativeOnTouchesEnded(float x, float y);
    public static native void nativeOnTouchesMoved(float x, float y);
    public static native void nativePlayMotion(String group, int index);
    public static native void nativeSetExpression(String name);
    public static native void nativeSetAnimationsEnabled(boolean enabled);
    public static native void nativeSetDisplayScale(float scale);
    public static native void nativeSetFacing(int facing);
}
