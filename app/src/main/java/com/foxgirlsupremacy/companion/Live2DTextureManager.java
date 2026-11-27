package com.foxgirlsupremacy.companion;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Live2DTextureManager {

    public static class TextureInfo {
        public int id;
        public int width;
        public int height;
        public String filePath;
    }

    private final AssetManager assetManager;
    private final List<TextureInfo> textures = new ArrayList<>();

    public Live2DTextureManager(Context context) {
        this.assetManager = context.getApplicationContext().getAssets();
    }

    public TextureInfo createTextureFromPngFile(String filePath) {
        for (TextureInfo info : textures) {
            if (info.filePath.equals(filePath)) {
                return info;
            }
        }

        Bitmap bitmap;
        try (InputStream stream = openStream(filePath)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPremultiplied = true;
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        } catch (IOException e) {
            throw new RuntimeException("Texture introuvable : " + filePath, e);
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        int[] textureId = new int[1];
        GLES20.glGenTextures(1, textureId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0]);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        TextureInfo info = new TextureInfo();
        info.filePath = filePath;
        info.width = bitmap.getWidth();
        info.height = bitmap.getHeight();
        info.id = textureId[0];
        textures.add(info);

        bitmap.recycle();
        return info;
    }

    private InputStream openStream(String path) throws IOException {
        if (path.startsWith("/")) {
            return new FileInputStream(new File(path));
        }
        return assetManager.open(path);
    }

    public void releaseInvalidTextures() {
        textures.clear();
    }
}
