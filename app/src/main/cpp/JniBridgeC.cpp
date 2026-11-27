/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "JniBridgeC.hpp"
#include <algorithm>
#include <mutex>
#include <jni.h>
#include "LAppDelegate.hpp"
#include "LAppPal.hpp"
#include "LAppLive2DManager.hpp"
#include "LAppModel.hpp"
#include "LAppDefine.hpp"

using namespace Csm;

static JavaVM* g_JVM; // JavaVM is valid for all threads, so just save it globally
static jclass  g_JniBridgeJavaClass;
static jmethodID g_GetAssetsMethodId;
static jmethodID g_LoadFileMethodId;
static jmethodID g_MoveTaskToBackMethodId;
static jmethodID g_GetModelDirectoryMethodId;
static jmethodID g_GetModelJsonFileMethodId;
static JniBridgeC::FaceTrackingData g_faceTracking = {false, 0, 0, 0, 1, 1, 0, 0, 0, 0};
static std::mutex g_faceTrackingMutex;

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

    jclass clazz = env->FindClass("com/foxgirlsupremacy/companion/NativeCubismBridge");
    g_JniBridgeJavaClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    g_GetAssetsMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "getAssetList", "(Ljava/lang/String;)[Ljava/lang/String;");
    g_LoadFileMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "loadFile", "(Ljava/lang/String;)[B");
    g_MoveTaskToBackMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "moveTaskToBack", "()V");

    g_GetModelDirectoryMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "getModelDirectory", "()Ljava/lang/String;");
    g_GetModelJsonFileMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "getModelJsonFile", "()Ljava/lang/String;");
    return JNI_VERSION_1_6;
}

void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv *env = GetEnv();
    env->DeleteGlobalRef(g_JniBridgeJavaClass);
}

Csm::csmVector<Csm::csmString>JniBridgeC::GetAssetList(const Csm::csmString& path)
{
    JNIEnv *env = GetEnv();
    jobjectArray obj = reinterpret_cast<jobjectArray>(env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_GetAssetsMethodId, env->NewStringUTF(path.GetRawString())));
    unsigned int size = static_cast<unsigned int>(env->GetArrayLength(obj));
    Csm::csmVector<Csm::csmString> list(size);
    for (unsigned int i = 0; i < size; i++)
    {
        jstring jstr = reinterpret_cast<jstring>(env->GetObjectArrayElement(obj, i));
        const char* chars = env->GetStringUTFChars(jstr, nullptr);
        list.PushBack(Csm::csmString(chars));
        env->ReleaseStringUTFChars(jstr, chars);
        env->DeleteLocalRef(jstr);
    }
    return list;
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


Csm::csmString JniBridgeC::GetModelDirectory()
{
    JNIEnv* env = GetEnv();
    jstring value = static_cast<jstring>(env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_GetModelDirectoryMethodId));
    const char* chars = env->GetStringUTFChars(value, nullptr);
    Csm::csmString result(chars);
    env->ReleaseStringUTFChars(value, chars);
    env->DeleteLocalRef(value);
    return result;
}

Csm::csmString JniBridgeC::GetModelJsonFile()
{
    JNIEnv* env = GetEnv();
    jstring value = static_cast<jstring>(env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_GetModelJsonFileMethodId));
    const char* chars = env->GetStringUTFChars(value, nullptr);
    Csm::csmString result(chars);
    env->ReleaseStringUTFChars(value, chars);
    env->DeleteLocalRef(value);
    return result;
}

JniBridgeC::FaceTrackingData JniBridgeC::GetFaceTrackingData()
{
    std::lock_guard<std::mutex> lock(g_faceTrackingMutex);
    return g_faceTracking;
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
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnStart(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnStart();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnPause(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnPause();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnStop(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnStop();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnDestroy(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnDestroy();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnSurfaceCreated(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnSurfaceCreate();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnSurfaceChanged(JNIEnv *env, jclass type, jint width, jint height)
    {
        LAppDelegate::GetInstance()->OnSurfaceChanged(width, height);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnDrawFrame(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->Run();
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnTouchesBegan(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchBegan(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnTouchesEnded(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchEnded(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeOnTouchesMoved(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchMoved(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeSetFaceTrackingData(JNIEnv *env, jclass type, jboolean enabled,
        jfloat angleX, jfloat angleY, jfloat angleZ, jfloat eyeLOpen, jfloat eyeROpen, jfloat mouthForm, jfloat mouthOpen, jfloat faceX, jfloat faceY)
    {
        std::lock_guard<std::mutex> lock(g_faceTrackingMutex);
        g_faceTracking.enabled = enabled == JNI_TRUE;
        g_faceTracking.angleX = angleX; g_faceTracking.angleY = angleY; g_faceTracking.angleZ = angleZ;
        g_faceTracking.eyeLOpen = eyeLOpen; g_faceTracking.eyeROpen = eyeROpen; g_faceTracking.mouthForm = mouthForm; g_faceTracking.mouthOpen = mouthOpen;
        g_faceTracking.faceX = faceX; g_faceTracking.faceY = faceY;
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativePlayMotion(JNIEnv *env, jclass type, jstring group, jint index)
    {
        const char* chars = env->GetStringUTFChars(group, nullptr);
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model != nullptr) model->StartMotion(chars, index, LAppDefine::PriorityForce);
        env->ReleaseStringUTFChars(group, chars);
    }

    JNIEXPORT void JNICALL
    Java_com_foxgirlsupremacy_companion_NativeCubismBridge_nativeSetExpression(JNIEnv *env, jclass type, jstring expression)
    {
        const char* chars = env->GetStringUTFChars(expression, nullptr);
        LAppModel* model = LAppLive2DManager::GetInstance()->GetModel(0);
        if (model != nullptr) model->SetExpression(chars);
        env->ReleaseStringUTFChars(expression, chars);
    }
}
