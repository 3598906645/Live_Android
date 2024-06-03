package com.tencent.mlvb.customvideocapture.helper.render.opengl;

import android.opengl.GLES20;

import com.tencent.mlvb.customvideocapture.helper.basic.FrameBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.tencent.mlvb.customvideocapture.helper.render.opengl.OpenGlUtils.CUBE;


public class GPUImageFilterGroup extends GPUImageFilter {
    protected final List<GPUImageFilter> mFilters;
    protected final List<GPUImageFilter> mMergedFilters;
    private final   FrameBuffer[]        mFrameBuffers = new FrameBuffer[2];
    private final   FloatBuffer          mGLCubeBuffer;
    private final   FloatBuffer          mGLTextureBuffer;
    private final   FloatBuffer          mGLTextureFlipBuffer;

    /**
     * Constructor of GPUImageFilterGroup.
     */
    public GPUImageFilterGroup() {
        mGLCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLCubeBuffer.put(CUBE).position(0);

        mGLTextureBuffer = ByteBuffer.allocateDirect(TextureRotationUtils.TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLTextureBuffer.put(TextureRotationUtils.TEXTURE_NO_ROTATION).position(0);

        float[] flipTexture = TextureRotationUtils.getRotation(Rotation.NORMAL, false, true);
        mGLTextureFlipBuffer = ByteBuffer.allocateDirect(flipTexture.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLTextureFlipBuffer.put(flipTexture).position(0);

        mFilters = new ArrayList<>();
        mMergedFilters = new ArrayList<>();
    }

    public void addFilter(GPUImageFilter filter) {
        if (filter == null) {
            return;
        }
        mFilters.add(filter);
        updateMergedFilters();
    }

    public List<GPUImageFilter> getMergedFilters() {
        return mMergedFilters;
    }

    /**
     * Updated GPU image filters.
     */
    public void updateMergedFilters() {
        if (mFilters == null) {
            return;
        }

        mMergedFilters.clear();

        List<GPUImageFilter> filters;
        for (GPUImageFilter filter : mFilters) {
            if (filter instanceof GPUImageFilterGroup) {
                ((GPUImageFilterGroup) filter).updateMergedFilters();
                filters = ((GPUImageFilterGroup) filter).getMergedFilters();
                if (filters != null && !filters.isEmpty()) {
                    mMergedFilters.addAll(filters);
                }
            } else {
                mMergedFilters.add(filter);
            }
        }
    }

    @Override
    protected void onInit() {
        super.onInit();
        for (int i = 0; i < mMergedFilters.size(); ++i) {
            mMergedFilters.get(i).init();
        }
    }

    @Override
    protected void onUninit() {
        destroyFramebuffers();
        for (GPUImageFilter filter : mMergedFilters) {
            filter.destroy();
        }
        super.onUninit();
    }

    private void destroyFramebuffers() {
        for (int i = 0; i < mFrameBuffers.length; ++i) {
            if (mFrameBuffers[i] != null) {
                mFrameBuffers[i].uninitialize();
                mFrameBuffers[i] = null;
            }
        }
    }

    @Override
    public void onOutputSizeChanged(final int width, final int height) {
        super.onOutputSizeChanged(width, height);
        destroyFramebuffers();

        List<GPUImageFilter> renderFilters = getRenderFilters();
        int                  size          = renderFilters.size();
        for (int i = 0; i < size; i++) {
            renderFilters.get(i).onOutputSizeChanged(width, height);
        }

        if (size > 0) {
            for (int i = 0; i < mFrameBuffers.length; i++) {
                mFrameBuffers[i] = new FrameBuffer(width, height);
                mFrameBuffers[i].initialize();
            }
        }
    }

    public List<GPUImageFilter> getRenderFilters() {
        return mMergedFilters;
    }

    @Override
    public void onDraw(final int textureId, final FloatBuffer cubeBuffer, final FloatBuffer textureBuffer) {
        throw new RuntimeException("this method should not been call!");
    }

    /**
     * Draw the current special effect
     *
     * @param textureId image input
     * @param outFrameBufferId Where to draw, if it is -1, it means it needs to be drawn to the screen
     * @param cubeBuffer matrix drawn
     * @param textureBuffer Which part of the image input needs to be used
     */
    public void draw(final int textureId,
                     final int outFrameBufferId,
                     final FloatBuffer cubeBuffer,
                     final FloatBuffer textureBuffer) {
        runPendingOnDrawTasks();
        if (!isInitialized() || null == getRenderFilters()) {
            return;
        }

        if (textureId == OpenGlUtils.NO_TEXTURE) {
            return;
        }

        List<GPUImageFilter> filters         = getRenderFilters();
        int                  size            = filters.size();
        int                  previousTexture = textureId;
        for (int i = 0; i < size; i++) {
            GPUImageFilter filter    = filters.get(i);
            boolean        isNotLast = i < size - 1;
            if (isNotLast) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[i % 2].getFrameBufferId());
                GLES20.glClearColor(0, 0, 0, 0);
            } else if (OpenGlUtils.NO_TEXTURE != outFrameBufferId) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFrameBufferId);
                GLES20.glClearColor(0, 0, 0, 0);
            }

            if (i == 0) {
                filter.onDraw(previousTexture, cubeBuffer, textureBuffer);
            } else if (i == size - 1) {
                filter.onDraw(previousTexture, mGLCubeBuffer,
                        (size % 2 == 0) ? mGLTextureFlipBuffer : mGLTextureBuffer);
            } else {
                filter.onDraw(previousTexture, mGLCubeBuffer, mGLTextureBuffer);
            }

            if (isNotLast) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                previousTexture = mFrameBuffers[i % 2].getTextureId();
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }
        }
    }
}
