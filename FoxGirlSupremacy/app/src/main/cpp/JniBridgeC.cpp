/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include <jni.h>
#include "JniBridgeC.hpp"
#include "LAppMinimumDelegate.hpp"
#include "LAppMinimumLive2DManager.hpp"
#include "LAppMinimumModel.hpp"
#include "LAppPal.hpp"

using namespace Csm;

static JavaVM* g_JVM; // JavaVM is valid for all threads, so just save it globally
static jclass  g_JniBridgeJavaClass;
static jmethodID g_LoadFileMethodId;
static jmethodID g_MoveTaskToBackMethodId;

JNIEnv* GetEnv()
{
    JNIEnv* env = NULL;
    g_JVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

// The VM calls JNI_OnLoad when the native library is loaded
jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_JVM = vm;

    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/foxgirlsupremacy/companion/NativeLive2DBridge");
    g_JniBridgeJavaClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    g_LoadFileMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "LoadFile", "(Ljava/lang/String;)[B");
    g_MoveTaskToBackMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "MoveTaskToBack", "()V");

    return JNI_VERSION_1_6;
}

void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv *env = GetEnv();
    env->DeleteGlobalRef(g_JniBridgeJavaClass);
}

char* JniBridgeC::LoadFileAsBytesFromJava(const char* filePath, unsigned int* outSize)
{
    JNIEnv *env = GetEnv();

    // ファイルロード
    jbyteArray obj = (jbyteArray)env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_LoadFileMethodId, env->NewStringUTF(filePath));

    // ファイルが見つからなかったらnullが返ってくるためチェック
    if (!obj)
    {
        return NULL;
    }

    *outSize = static_cast<unsigned int>(env->GetArrayLength(obj));

    char* buffer = new char[*outSize];
    env->GetByteArrayRegion(obj, 0, *outSize, reinterpret_cast<jbyte *>(buffer));

    return buffer;
}

void JniBridgeC::MoveTaskToBack()
{
    JNIEnv *env = GetEnv();

    // アプリ終了
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_MoveTaskToBackMethodId, NULL);
}

extern "C"
{
    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnStart(JNIEnv *env, jclass type)
    {
        LAppMinimumDelegate::GetInstance()->OnStart();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnPause(JNIEnv *env, jclass type)
    {
        LAppMinimumDelegate::GetInstance()->OnPause();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnStop(JNIEnv *env, jclass type)
    {
        LAppMinimumDelegate::GetInstance()->OnStop();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnDestroy(JNIEnv *env, jclass type)
    {
        LAppMinimumDelegate::GetInstance()->OnDestroy();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnSurfaceCreated(JNIEnv *env, jclass type)
    {
        LAppMinimumDelegate::GetInstance()->OnSurfaceCreate();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnSurfaceChanged(JNIEnv *env, jclass type, jint width, jint height)
    {
        LAppMinimumDelegate::GetInstance()->OnSurfaceChanged(width, height);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnDrawFrame(JNIEnv *env, jclass type)
    {
        LAppMinimumDelegate::GetInstance()->Run();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeConfigureModel(JNIEnv *env, jclass type, jstring modelName, jstring assetDirectory)
    {
        const char* name = env->GetStringUTFChars(modelName, nullptr);
        const char* directory = env->GetStringUTFChars(assetDirectory, nullptr);
        LAppMinimumLive2DManager::ConfigureModel(name, directory);
        env->ReleaseStringUTFChars(modelName, name);
        env->ReleaseStringUTFChars(assetDirectory, directory);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativePlayMotion(JNIEnv *env, jclass type, jstring group, jint index)
    {
        const char* value = env->GetStringUTFChars(group, nullptr);
        auto* model = LAppMinimumLive2DManager::GetInstance()->GetModel();
        if (model) model->StartMotion(value, index, 3);
        env->ReleaseStringUTFChars(group, value);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeSetDisplayScale(JNIEnv *env, jclass type, jfloat scale)
    {
        LAppMinimumLive2DManager::SetDisplayScale(scale);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeSetFacing(JNIEnv *env, jclass type, jint facing)
    {
        LAppMinimumLive2DManager::SetFacing(facing);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeSetAnimationsEnabled(JNIEnv *env, jclass type, jboolean enabled)
    {
        auto* model = LAppMinimumLive2DManager::GetInstance()->GetModel();
        if (model) model->SetAnimationsEnabled(enabled == JNI_TRUE);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeSetExpression(JNIEnv *env, jclass type, jstring name)
    {
        const char* value = env->GetStringUTFChars(name, nullptr);
        auto* model = LAppMinimumLive2DManager::GetInstance()->GetModel();
        if (model) model->SetExpression(value);
        env->ReleaseStringUTFChars(name, value);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnTouchesBegan(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppMinimumDelegate::GetInstance()->OnTouchBegan(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnTouchesEnded(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppMinimumDelegate::GetInstance()->OnTouchEnded(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeLive2DBridge_nativeOnTouchesMoved(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppMinimumDelegate::GetInstance()->OnTouchMoved(pointX, pointY);
    }
}

