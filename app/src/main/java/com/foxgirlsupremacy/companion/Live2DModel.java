package com.foxgirlsupremacy.companion;

import com.live2d.sdk.cubism.framework.CubismDefaultParameterId.ParameterId;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.effect.CubismBreath;
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.ACubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismBreathUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismEyeBlinkUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater;
import com.live2d.sdk.cubism.framework.motion.IBooleanSupplier;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Live2DModel extends CubismUserModel {

    private static final boolean MOC_CONSISTENCY = true;
    private static final int PRIORITY_IDLE = 1;
    private static final int PRIORITY_FORCE = 3;

    private Live2DTextureManager textureManager;
    private ICubismModelSetting modelSetting;
    private String modelHomeDirectory;
    private String idleGroup;

    private final Map<String, ACubismMotion> motions = new HashMap<>();
    private final Map<String, ACubismMotion> expressions = new HashMap<>();

    public Live2DModel(Live2DTextureManager textureManager) {
        this.textureManager = textureManager;
    }

    public void loadAssets(String dir, String fileName, int width, int height) {
        modelHomeDirectory = dir;
        setupModel(modelHomeDirectory + fileName);

        CubismRenderer renderer = CubismRendererAndroid.create(width, height);
        setupRenderer(renderer);
        setupTextures();
    }

    public void reloadRenderer(Live2DTextureManager textureManager, int width, int height) {
        this.textureManager = textureManager;
        deleteRenderer();

        CubismRenderer renderer = CubismRendererAndroid.create(width, height);
        setupRenderer(renderer);
        setupTextures();
    }

    public void update() {
        final float dt = Live2DAssets.getDeltaTime();

        model.loadParameters();
        if (motionManager.isFinished() && idleGroup != null) {
            startMotion(idleGroup, 0, PRIORITY_IDLE);
        } else {
            motionManager.updateMotion(model, dt);
        }
        model.saveParameters();

        updateScheduler.onLateUpdate(model, dt);

        model.update();
    }

    public void draw(CubismMatrix44 matrix) {
        if (model == null) {
            return;
        }
        CubismMatrix44.multiply(modelMatrix.getArray(), matrix.getArray(), matrix.getArray());

        CubismRendererAndroid renderer = (CubismRendererAndroid) getRenderer();
        renderer.setMvpMatrix(matrix);
        renderer.drawModel();
    }

    public int startMotion(String group, int number, int priority) {
        if (priority == PRIORITY_FORCE) {
            motionManager.setReservationPriority(priority);
        } else if (!motionManager.reserveMotion(priority)) {
            return -1;
        }

        ACubismMotion motion = motions.get(group + "_" + number);
        if (motion == null) {
            return -1;
        }
        return motionManager.startMotionPriority(motion, priority);
    }

    public void setExpression(String name) {
        ACubismMotion motion = expressions.get(name);
        if (motion != null) {
            expressionManager.startMotionPriority(motion, PRIORITY_FORCE);
        }
    }

    public void setExpressionFrom(String[] candidates) {
        for (String name : candidates) {
            ACubismMotion motion = expressions.get(name);
            if (motion != null) {
                expressionManager.startMotionPriority(motion, PRIORITY_FORCE);
                return;
            }
        }
    }

    public float canvasRatio() {
        return getModel().getCanvasHeight() / getModel().getCanvasWidth();
    }

    public void fitWidth() {
        getModelMatrix().setWidth(2.0f);
    }

    public void fitHeight() {
        getModelMatrix().setHeight(2.0f);
    }

    private void setupModel(String model3JsonPath) {
        byte[] json = Live2DAssets.loadFileAsBytes(model3JsonPath);
        modelSetting = new CubismModelSettingJson(json);

        String mocPath = modelSetting.getModelFileName();
        if (!mocPath.isEmpty()) {
            loadModel(Live2DAssets.loadFileAsBytes(modelHomeDirectory + mocPath), MOC_CONSISTENCY);
        }

        int expressionCount = modelSetting.getExpressionCount();
        for (int i = 0; i < expressionCount; i++) {
            String name = modelSetting.getExpressionName(i);
            byte[] buffer = Live2DAssets.loadFileAsBytes(modelHomeDirectory + modelSetting.getExpressionFileName(i));
            CubismExpressionMotion expression = loadExpression(buffer);
            if (expression != null) {
                expressions.put(name, expression);
            }
        }
        if (expressionCount > 0) {
            updateScheduler.addUpdatableList(new CubismExpressionUpdater(expressionManager));
        }

        String physicsPath = modelSetting.getPhysicsFileName();
        if (!physicsPath.isEmpty()) {
            loadPhysics(Live2DAssets.loadFileAsBytes(modelHomeDirectory + physicsPath));
        }
        if (physics != null) {
            updateScheduler.addUpdatableList(new CubismPhysicsUpdater(physics));
        }

        eyeBlink = CubismEyeBlink.create(modelSetting);
        if (eyeBlink != null) {
            updateScheduler.addUpdatableList(new CubismEyeBlinkUpdater(new IBooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return false;
                }
            }, eyeBlink));
        }

        breath = CubismBreath.create();
        CubismIdManager idm = CubismFramework.getIdManager();
        List<CubismBreath.BreathParameterData> breathParams = new ArrayList<>();
        breathParams.add(new CubismBreath.BreathParameterData(idm.getId(ParameterId.ANGLE_X.getId()), 0f, 15f, 6.5345f, 0.5f));
        breathParams.add(new CubismBreath.BreathParameterData(idm.getId(ParameterId.ANGLE_Y.getId()), 0f, 8f, 3.5345f, 0.5f));
        breathParams.add(new CubismBreath.BreathParameterData(idm.getId(ParameterId.ANGLE_Z.getId()), 0f, 10f, 5.5345f, 0.5f));
        breathParams.add(new CubismBreath.BreathParameterData(idm.getId(ParameterId.BODY_ANGLE_X.getId()), 0f, 4f, 15.5345f, 0.5f));
        breathParams.add(new CubismBreath.BreathParameterData(idm.getId(ParameterId.BREATH.getId()), 0.5f, 0.5f, 3.2345f, 0.5f));
        breath.setParameters(breathParams);
        updateScheduler.addUpdatableList(new CubismBreathUpdater(breath));

        updateScheduler.sortUpdatableList();

        Map<String, Float> layout = new HashMap<>();
        if (modelSetting.getLayoutMap(layout)) {
            modelMatrix.setupFromLayout(layout);
        }

        model.saveParameters();

        int groupCount = modelSetting.getMotionGroupCount();
        for (int i = 0; i < groupCount; i++) {
            preLoadMotionGroup(modelSetting.getMotionGroupName(i));
        }
        if (groupCount > 0) {
            idleGroup = modelSetting.getMotionGroupName(0);
        }
        motionManager.stopAllMotions();
    }

    private void preLoadMotionGroup(String group) {
        int count = modelSetting.getMotionCount(group);
        for (int i = 0; i < count; i++) {
            String path = modelSetting.getMotionFileName(group, i);
            if (path.isEmpty()) {
                continue;
            }
            CubismMotion motion = loadMotion(Live2DAssets.loadFileAsBytes(modelHomeDirectory + path));
            if (motion == null) {
                continue;
            }
            float fadeIn = modelSetting.getMotionFadeInTimeValue(group, i);
            if (fadeIn != -1.0f) {
                motion.setFadeInTime(fadeIn);
            }
            float fadeOut = modelSetting.getMotionFadeOutTimeValue(group, i);
            if (fadeOut != -1.0f) {
                motion.setFadeOutTime(fadeOut);
            }
            motions.put(group + "_" + i, motion);
        }
    }

    private void setupTextures() {
        for (int i = 0; i < modelSetting.getTextureCount(); i++) {
            String texturePath = modelSetting.getTextureFileName(i);
            if (texturePath.isEmpty()) {
                continue;
            }
            Live2DTextureManager.TextureInfo texture =
                textureManager.createTextureFromPngFile(modelHomeDirectory + texturePath);

            CubismRendererAndroid renderer = (CubismRendererAndroid) getRenderer();
            renderer.bindTexture(i, texture.id);
            renderer.isPremultipliedAlpha(true);
        }
    }
}
