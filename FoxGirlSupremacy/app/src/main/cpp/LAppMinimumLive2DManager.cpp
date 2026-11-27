/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppMinimumLive2DManager.hpp"
#include <string>
#include <GLES2/gl2.h>
#include <Rendering/CubismRenderer.hpp>
#include <Rendering/OpenGL/CubismOffscreenManager_OpenGLES2.hpp>
#include "LAppPal.hpp"
#include "LAppDefine.hpp"
#include "LAppMinimumDelegate.hpp"
#include "LAppMinimumModel.hpp"
#include "LAppMinimumView.hpp"

using namespace Csm;
using namespace LAppDefine;
using namespace std;

namespace {
    LAppMinimumLive2DManager* s_instance = nullptr;
    std::string s_modelName = "Hiyori";
    std::string s_assetDirectory = "live2d/hiyori/";
    float s_displayScale = 1.0f;
    int s_facing = 1;
}

LAppMinimumLive2DManager* LAppMinimumLive2DManager::GetInstance()
{
    if (!s_instance)
    {
        s_instance = new LAppMinimumLive2DManager();
    }

    return s_instance;
}

void LAppMinimumLive2DManager::ConfigureModel(const std::string& modelName, const std::string& assetDirectory)
{
    s_modelName = modelName;
    s_assetDirectory = assetDirectory;
}

void LAppMinimumLive2DManager::SetDisplayScale(float scale)
{
    s_displayScale = scale < 0.25f ? 0.25f : (scale > 3.0f ? 3.0f : scale);
}

void LAppMinimumLive2DManager::SetFacing(int facing)
{
    s_facing = facing < 0 ? -1 : 1;
}

void LAppMinimumLive2DManager::ReleaseInstance()
{
    if (s_instance)
    {
        delete s_instance;
    }

    s_instance = nullptr;
}

LAppMinimumLive2DManager::LAppMinimumLive2DManager()
{
    _viewMatrix = new CubismMatrix44();
    LoadModel(s_modelName);
}

LAppMinimumLive2DManager::~LAppMinimumLive2DManager()
{
    ReleaseModel();
    delete _viewMatrix;
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::ReleaseInstance();
}

void LAppMinimumLive2DManager::ReleaseModel()
{
    delete _model;
}

LAppMinimumModel* LAppMinimumLive2DManager::GetModel() const
{
    return _model;
}

void LAppMinimumLive2DManager::SetRenderTargetSize(csmUint32 width, csmUint32 height)
{
    if (_model)
    {
        _model->SetRenderTargetSize(width, height);
    }
}

void LAppMinimumLive2DManager::OnDrag(csmFloat32 x, csmFloat32 y) const
{
    LAppMinimumModel* model = GetModel();
    if (model)
    {
        model->SetDragging(x, y);
    }
}

void LAppMinimumLive2DManager::OnUpdate() const
{
    int width = LAppMinimumDelegate::GetInstance()->GetWindowWidth();
    int height = LAppMinimumDelegate::GetInstance()->GetWindowHeight();

    LAppMinimumModel* model = GetModel();
    if (!model || !model->GetModel() || width <= 0 || height <= 0)
    {
        return;
    }

    // モデルで使用するオフスクリーン管理の開始処理
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->BeginFrameProcess();

    CubismMatrix44 projection;

    float displayRatio = static_cast<float>(height) / static_cast<float>(width);
    float canvasRatio = model->GetModel()->GetCanvasHeight() / model->GetModel()->GetCanvasWidth();
    if (canvasRatio < displayRatio)
    {
        // 横に長いモデルを縦長ウィンドウに表示する際モデルの横サイズでscaleを算出する
        model->GetModelMatrix()->SetWidth(2.0f);
        projection.Scale(1.0f, static_cast<float>(width) / static_cast<float>(height));
    }
    else
    {
        model->GetModelMatrix()->SetHeight(2.0f);
        projection.Scale(static_cast<float>(height) / static_cast<float>(width), 1.0f);
    }

    // 必要があればここで乗算
    model->GetModelMatrix()->ScaleRelative(s_displayScale * static_cast<float>(s_facing), s_displayScale);

    if (_viewMatrix)
    {
        projection.MultiplyByMatrix(_viewMatrix);
    }

    // モデル1体描画前コール
    LAppMinimumDelegate::GetInstance()->GetView()->PreModelDraw(*model);

    model->Update();
    model->Draw(projection);///< 参照渡しなのでprojectionは変質する

    // モデル1体描画前コール
    LAppMinimumDelegate::GetInstance()->GetView()->PostModelDraw(*model);

    // モデルで使用するオフスクリーン管理の終了処理
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->EndFrameProcess();
    // もし余っているオフスクリーンのリソースを解放したい場合行う処理
    Csm::Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->ReleaseStaleRenderTextures();
}

void LAppMinimumLive2DManager::SetAssetDirectory(const std::string &path)
{
    _currentModelDirectory = path;
}

void LAppMinimumLive2DManager::LoadModel(const std::string modelDirectoryName)
{
    // モデルのディレクトリを指定
    SetAssetDirectory(s_assetDirectory);

    // モデルデータの新規生成
    _model = new LAppMinimumModel(modelDirectoryName, _currentModelDirectory);

    // モデルデータの読み込み及び生成とセットアップを行う
    static_cast<LAppMinimumModel*>(_model)->SetupModel();
}


