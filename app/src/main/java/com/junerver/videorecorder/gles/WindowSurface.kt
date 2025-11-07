package com.junerver.videorecorder.gles

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.view.Surface

/**
 * EGL Window Surface封装
 * 简化Surface的创建、使用和销毁
 */
class WindowSurface(
    private val eglCore: EglCore,
    surface: Any,
    private val releaseSurface: Boolean = false
) {
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var width = -1
    private var height = -1

    init {
        if (surface is Surface) {
            this.surface = surface
        } else if (surface is SurfaceTexture) {
            this.surfaceTexture = surface
        } else {
            throw IllegalArgumentException("Invalid surface: $surface")
        }

        eglSurface = eglCore.createWindowSurface(surface)
        width = eglCore.querySurface(eglSurface, android.opengl.EGL14.EGL_WIDTH)
        height = eglCore.querySurface(eglSurface, android.opengl.EGL14.EGL_HEIGHT)
    }

    /**
     * 使当前Surface成为渲染目标
     */
    fun makeCurrent() {
        eglCore.makeCurrent(eglSurface)
    }

    /**
     * 交换缓冲区
     */
    fun swapBuffers(): Boolean {
        return eglCore.swapBuffers(eglSurface)
    }

    /**
     * 设置显示时间戳（用于视频录制）
     */
    fun setPresentationTime(nsecs: Long) {
        eglCore.setPresentationTime(eglSurface, nsecs)
    }

    /**
     * 获取Surface宽度
     */
    fun getWidth(): Int = width

    /**
     * 获取Surface高度
     */
    fun getHeight(): Int = height

    /**
     * 释放资源
     */
    fun release() {
        eglCore.destroySurface(eglSurface)
        eglSurface = android.opengl.EGL14.EGL_NO_SURFACE

        if (releaseSurface) {
            surface?.release()
            surfaceTexture?.release()
        }

        surface = null
        surfaceTexture = null
        width = -1
        height = -1
    }
}
