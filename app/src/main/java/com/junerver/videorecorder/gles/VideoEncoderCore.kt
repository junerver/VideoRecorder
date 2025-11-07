package com.junerver.videorecorder.gles

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.lang.ref.WeakReference

/**
 * 视频编码器核心
 * 负责从Camera接收帧，通过OpenGL旋转，然后送入MediaCodec
 */
class VideoEncoderCore(
    private val encoderSurface: Surface,
    private val rotation: Int,
    private val inputWidth: Int,
    private val inputHeight: Int
) {
    private var eglCore: EglCore? = null
    private var windowSurface: WindowSurface? = null
    private var textureRenderer: TextureRenderer? = null
    private var cameraSurfaceTexture: SurfaceTexture? = null

    // 渲染线程（拥有EGL上下文）
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var isReady = false
    private val texMatrix = FloatArray(16)
    private var frameCount = 0

    /**
     * 初始化OpenGL环境
     */
    fun prepare() {
        Log.d(TAG, "准备OpenGL环境，旋转角度=$rotation°")

        // 创建专门的渲染线程
        renderThread = HandlerThread("VideoEncoderThread").apply {
            start()
        }
        renderHandler = Handler(renderThread!!.looper)

        // 在渲染线程中初始化OpenGL
        val initLatch = java.util.concurrent.CountDownLatch(1)
        renderHandler!!.post {
            try {
                // 创建EGL环境
                eglCore = EglCore(null, 0)

                // 创建编码器窗口Surface（MediaCodec的InputSurface）
                windowSurface = WindowSurface(eglCore!!, encoderSurface, false)
                windowSurface!!.makeCurrent()

                // 创建纹理渲染器
                textureRenderer = TextureRenderer()
                textureRenderer!!.surfaceCreated()

                // 根据测试结果：
                // 后置摄像头（orientationHint=90）：需要镜像 + 90度旋转
                // 前置摄像头（orientationHint=270）：不需要镜像 + 270度旋转
                val useMirror: Boolean
                val actualRotation: Int

                when(rotation) {
                    90 -> {
                        // 后置摄像头：镜像 + 90度 = ↑ 正确 ✅
                        useMirror = true
                        actualRotation = 90
                    }
                    270 -> {
                        // 前置摄像头：尝试不镜像 + 90度
                        // 前置摄像头可能需要与后置不同的处理
                        useMirror = false
                        actualRotation = 90
                    }
                    else -> {
                        useMirror = false
                        actualRotation = rotation
                    }
                }

                textureRenderer!!.setRotation(actualRotation)
                textureRenderer!!.setMirrorHorizontal(useMirror)

                Log.d(TAG, "orientationHint=$rotation, 摄像头=${if (rotation == 90) "后置" else "前置"}, OpenGL旋转=$actualRotation°, 镜像=$useMirror")

                // 创建SurfaceTexture用于接收Camera输出
                val textureId = textureRenderer!!.getTextureId()
                cameraSurfaceTexture = SurfaceTexture(textureId)

                // 关键修复1：设置SurfaceTexture的buffer size为Camera输入尺寸
                // 这确保不会因为尺寸不匹配导致画质降低
                cameraSurfaceTexture!!.setDefaultBufferSize(inputWidth, inputHeight)
                Log.d(TAG, "SurfaceTexture buffer size: ${inputWidth}x${inputHeight}")

                // 关键修复2：设置OpenGL viewport为编码器输出尺寸（旋转后的尺寸）
                // 90°/270°旋转时，宽高需要交换
                val viewportWidth = if (rotation == 90 || rotation == 270) inputHeight else inputWidth
                val viewportHeight = if (rotation == 90 || rotation == 270) inputWidth else inputHeight
                android.opengl.GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                Log.d(TAG, "OpenGL viewport: ${viewportWidth}x${viewportHeight}")

                // 关键：在渲染线程的Handler上接收帧回调
                cameraSurfaceTexture!!.setOnFrameAvailableListener(
                    OnFrameAvailableListener(this@VideoEncoderCore),
                    renderHandler
                )

                isReady = true
                Log.d(TAG, "OpenGL环境准备完成（渲染线程：${Thread.currentThread().name}）")
            } catch (e: Exception) {
                Log.e(TAG, "OpenGL初始化失败", e)
            } finally {
                initLatch.countDown()
            }
        }

        // 等待初始化完成
        initLatch.await()

        if (!isReady) {
            throw RuntimeException("OpenGL环境初始化失败")
        }
    }

    /**
     * 获取用于Camera输出的SurfaceTexture
     */
    fun getCameraSurfaceTexture(): SurfaceTexture {
        return cameraSurfaceTexture ?: throw IllegalStateException("未初始化")
    }

    /**
     * 当新帧可用时调用（在渲染线程中）
     */
    private fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (!isReady) return

        try {
            frameCount++

            // 更新纹理图像（现在在正确的线程中）
            surfaceTexture.updateTexImage()

            // 不使用Camera的texMatrix（它可能包含额外的旋转）
            // 使用单位矩阵，完全由纹理坐标控制旋转
            android.opengl.Matrix.setIdentityM(texMatrix, 0)

            // 设置时间戳
            val timestamp = surfaceTexture.timestamp
            windowSurface?.setPresentationTime(timestamp)

            // 渲染帧（仅通过纹理坐标旋转）
            textureRenderer?.drawFrame(texMatrix)

            // 交换缓冲区（送入MediaCodec）
            windowSurface?.swapBuffers()

            // 前几帧打印日志
            if (frameCount <= 5) {
                Log.d(TAG, "✅ 帧$frameCount 渲染成功，时间戳=$timestamp")
            }
        } catch (e: Exception) {
            Log.e(TAG, "渲染帧失败", e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "释放VideoEncoderCore资源，总帧数=$frameCount")
        isReady = false

        // 在渲染线程中清理OpenGL资源
        renderHandler?.post {
            cameraSurfaceTexture?.setOnFrameAvailableListener(null)
            cameraSurfaceTexture?.release()
            cameraSurfaceTexture = null

            textureRenderer?.release()
            textureRenderer = null

            windowSurface?.release()
            windowSurface = null

            eglCore?.makeNothingCurrent()
            eglCore?.release()
            eglCore = null

            Log.d(TAG, "OpenGL资源清理完成")
        }

        // 停止渲染线程
        renderThread?.quitSafely()
        try {
            renderThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "等待渲染线程结束超时", e)
        }
        renderThread = null
        renderHandler = null

        Log.d(TAG, "VideoEncoderCore资源释放完成")
    }

    /**
     * 帧可用监听器（使用弱引用避免内存泄漏）
     */
    private class OnFrameAvailableListener(core: VideoEncoderCore) :
        SurfaceTexture.OnFrameAvailableListener {

        private val weakCore = WeakReference(core)

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            weakCore.get()?.onFrameAvailable(surfaceTexture)
        }
    }

    companion object {
        private const val TAG = "VideoEncoderCore"
    }
}
