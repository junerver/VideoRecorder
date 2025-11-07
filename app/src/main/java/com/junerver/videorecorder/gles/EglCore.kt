package com.junerver.videorecorder.gles

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * EGL环境管理核心类
 * 负责创建和管理OpenGL ES上下文
 */
class EglCore(sharedContext: EGLContext? = null, flags: Int = 0) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    init {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("EGL already set up")
        }

        val sharedCtx = sharedContext ?: EGL14.EGL_NO_CONTEXT

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("unable to initialize EGL14")
        }

        // 配置EGL
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, attribList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        eglConfig = configs[0]

        // 创建EGL上下文
        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, sharedCtx,
            attrib_list, 0
        )

        checkEglError("eglCreateContext")
        if (eglContext === EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("null context")
        }
    }

    /**
     * 创建窗口Surface
     */
    fun createWindowSurface(surface: Any): EGLSurface {
        if (surface !is Surface && surface !is android.graphics.SurfaceTexture) {
            throw RuntimeException("invalid surface: $surface")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface,
            surfaceAttribs, 0
        )
        checkEglError("eglCreateWindowSurface")
        if (eglSurface == null) {
            throw RuntimeException("surface was null")
        }
        return eglSurface
    }

    /**
     * 创建离屏渲染Surface
     */
    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            surfaceAttribs, 0
        )
        checkEglError("eglCreatePbufferSurface")
        if (eglSurface == null) {
            throw RuntimeException("surface was null")
        }
        return eglSurface
    }

    /**
     * 使当前Surface成为活动渲染目标
     */
    fun makeCurrent(eglSurface: EGLSurface) {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "Note: makeCurrent w/o display")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * 使当前Surface成为活动渲染目标（分离draw和read surface）
     */
    fun makeCurrent(drawSurface: EGLSurface, readSurface: EGLSurface) {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            Log.d(TAG, "Note: makeCurrent w/o display")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, drawSurface, readSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent(draw,read) failed")
        }
    }

    /**
     * 清除当前上下文
     */
    fun makeNothingCurrent(): Boolean {
        return EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
    }

    /**
     * 交换缓冲区，显示渲染结果
     */
    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * 设置显示时间戳
     */
    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    /**
     * 查询Surface属性
     */
    fun querySurface(eglSurface: EGLSurface, what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, what, value, 0)
        return value[0]
    }

    /**
     * 销毁Surface
     */
    fun destroySurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    /**
     * 释放资源
     */
    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    /**
     * 检查EGL错误
     */
    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("$msg: EGL error: 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        private const val TAG = "EglCore"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
