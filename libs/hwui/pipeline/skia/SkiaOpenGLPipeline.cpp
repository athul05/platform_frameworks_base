/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "SkiaOpenGLPipeline.h"

#include "DeferredLayerUpdater.h"
#include "LayerDrawable.h"
#include "SkiaPipeline.h"
#include "SkiaProfileRenderer.h"
#include "hwui/Bitmap.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "renderthread/Frame.h"
#include "utils/GLUtils.h"
#include "utils/TraceUtils.h"

#include <GLES3/gl3.h>

#include <GrBackendSurface.h>
#include <SkBlendMode.h>
#include <SkImageInfo.h>

#include <cutils/properties.h>
#include <strings.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaOpenGLPipeline::SkiaOpenGLPipeline(RenderThread& thread)
        : SkiaPipeline(thread), mEglManager(thread.eglManager()) {}

MakeCurrentResult SkiaOpenGLPipeline::makeCurrent() {
    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    EGLint error = 0;
    if (!mEglManager.makeCurrent(mEglSurface, &error)) {
        return MakeCurrentResult::AlreadyCurrent;
    }
    return error ? MakeCurrentResult::Failed : MakeCurrentResult::Succeeded;
}

Frame SkiaOpenGLPipeline::getFrame() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
                        "drawRenderNode called on a context with no surface!");
    return mEglManager.beginFrame(mEglSurface);
}

bool SkiaOpenGLPipeline::draw(const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
                              const LightGeometry& lightGeometry,
                              LayerUpdateQueue* layerUpdateQueue, const Rect& contentDrawBounds,
                              bool opaque, bool wideColorGamut, const LightInfo& lightInfo,
                              const std::vector<sp<RenderNode>>& renderNodes,
                              FrameInfoVisualizer* profiler) {
    mEglManager.damageFrame(frame, dirty);

    SkColorType colorType;
    // setup surface for fbo0
    GrGLFramebufferInfo fboInfo;
    fboInfo.fFBOID = 0;
    if (wideColorGamut) {
        fboInfo.fFormat = GL_RGBA16F;
        colorType = kRGBA_F16_SkColorType;
    } else {
        fboInfo.fFormat = GL_RGBA8;
        colorType = kN32_SkColorType;
    }

    GrBackendRenderTarget backendRT(frame.width(), frame.height(), 0, STENCIL_BUFFER_SIZE, fboInfo);

    SkSurfaceProps props(0, kUnknown_SkPixelGeometry);

    SkASSERT(mRenderThread.getGrContext() != nullptr);
    sk_sp<SkSurface> surface(SkSurface::MakeFromBackendRenderTarget(
            mRenderThread.getGrContext(), backendRT, kBottomLeft_GrSurfaceOrigin, colorType,
            nullptr, &props));

    SkiaPipeline::updateLighting(lightGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, wideColorGamut, contentDrawBounds,
                surface);
    layerUpdateQueue->clear();

    // Draw visual debugging features
    if (CC_UNLIKELY(Properties::showDirtyRegions ||
                    ProfileType::None != Properties::getProfileType())) {
        SkCanvas* profileCanvas = surface->getCanvas();
        SkiaProfileRenderer profileRenderer(profileCanvas);
        profiler->draw(profileRenderer);
        profileCanvas->flush();
    }

    // Log memory statistics
    if (CC_UNLIKELY(Properties::debugLevel != kDebugDisabled)) {
        dumpResourceCacheUsage();
    }

    return true;
}

bool SkiaOpenGLPipeline::swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                                     FrameInfo* currentFrameInfo, bool* requireSwap) {
    GL_CHECKPOINT(LOW);

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    *requireSwap = drew || mEglManager.damageRequiresSwap();

    if (*requireSwap && (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty)))) {
        return false;
    }

    return *requireSwap;
}

DeferredLayerUpdater* SkiaOpenGLPipeline::createTextureLayer() {
    mRenderThread.requireGlContext();
    return new DeferredLayerUpdater(mRenderThread.renderState());
}

void SkiaOpenGLPipeline::onStop() {
    if (mEglManager.isCurrent(mEglSurface)) {
        mEglManager.makeCurrent(EGL_NO_SURFACE);
    }
}

bool SkiaOpenGLPipeline::setSurface(Surface* surface, SwapBehavior swapBehavior,
                                    ColorMode colorMode) {
    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (surface) {
        mRenderThread.requireGlContext();
        const bool wideColorGamut = colorMode == ColorMode::WideColorGamut;
        mEglSurface = mEglManager.createSurface(surface, wideColorGamut);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (swapBehavior != SwapBehavior::kSwap_discardBuffer);
        mBufferPreserved = mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        return true;
    }

    return false;
}

bool SkiaOpenGLPipeline::isSurfaceReady() {
    return CC_UNLIKELY(mEglSurface != EGL_NO_SURFACE);
}

bool SkiaOpenGLPipeline::isContextReady() {
    return CC_LIKELY(mEglManager.hasEglContext());
}

void SkiaOpenGLPipeline::invokeFunctor(const RenderThread& thread, Functor* functor) {
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        mode = DrawGlInfo::kModeProcess;
    }

    (*functor)(mode, nullptr);

    // If there's no context we don't need to reset as there's no gl state to save/restore
    if (mode != DrawGlInfo::kModeProcessNoContext) {
        thread.getGrContext()->resetContext();
    }
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
