package com.junerver.videorecorder.gles

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL纹理渲染器
 * 支持旋转Camera纹理并渲染到目标Surface
 */
class TextureRenderer {

    // 顶点着色器 - 标准的全屏四边形
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    // 片段着色器 - 处理外部纹理（Camera输出）
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private var program = 0
    private var aPositionHandle = 0
    private var aTextureCoordHandle = 0
    private var uMVPMatrixHandle = 0
    private var uTexMatrixHandle = 0

    // MVP矩阵（用于旋转）
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    // 顶点坐标（全屏四边形）
    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,   // 左下
        1.0f, -1.0f,    // 右下
        -1.0f, 1.0f,    // 左上
        1.0f, 1.0f      // 右上
    )

    // 纹理坐标（会根据旋转角度动态调整）
    private var textureCoords = floatArrayOf(
        0.0f, 0.0f,     // 左下
        1.0f, 0.0f,     // 右下
        0.0f, 1.0f,     // 左上
        1.0f, 1.0f      // 右上
    )

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var textureBuffer: FloatBuffer

    private var textureId = -1
    private var rotation = 0 // 旋转角度（0, 90, 180, 270）
    private var mirrorHorizontal = false // 是否水平镜像

    init {
        setupBuffers()
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(texMatrix, 0)
    }

    private fun setupBuffers() {
        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexCoords)
        vertexBuffer.position(0)

        textureBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /**
     * 根据旋转角度更新纹理坐标
     * 这是实现画面旋转的关键 - 通过重新排列纹理坐标来旋转内容
     */
    private fun updateTextureCoords() {
        // 根据不同的旋转角度，重新排列纹理坐标顺序
        textureCoords = when (rotation) {
            0 -> floatArrayOf(
                0.0f, 0.0f,     // 左下
                1.0f, 0.0f,     // 右下
                0.0f, 1.0f,     // 左上
                1.0f, 1.0f      // 右上
            )
            90 -> floatArrayOf(
                0.0f, 1.0f,     // 逆时针90度：左上 -> 左下
                0.0f, 0.0f,     // 左下 -> 右下
                1.0f, 1.0f,     // 右上 -> 左上
                1.0f, 0.0f      // 右下 -> 右上
            )
            180 -> floatArrayOf(
                1.0f, 1.0f,     // 旋转180度：右上 -> 左下
                0.0f, 1.0f,     // 左上 -> 右下
                1.0f, 0.0f,     // 右下 -> 左上
                0.0f, 0.0f      // 左下 -> 右上
            )
            270 -> floatArrayOf(
                1.0f, 0.0f,     // 逆时针270度（顺时针90度）：右下 -> 左下
                1.0f, 1.0f,     // 右上 -> 右下
                0.0f, 0.0f,     // 左下 -> 左上
                0.0f, 1.0f      // 左上 -> 右上
            )
            else -> floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            )
        }

        // 如果需要水平镜像，翻转u坐标（x坐标）
        if (mirrorHorizontal) {
            for (i in textureCoords.indices step 2) {
                textureCoords[i] = 1.0f - textureCoords[i]  // u坐标翻转
            }
        }

        // 更新buffer
        textureBuffer.clear()
        textureBuffer.put(textureCoords)
        textureBuffer.position(0)

        Log.d(TAG, "纹理坐标已更新为${rotation}度旋转${if (mirrorHorizontal) "（水平镜像）" else ""}")
    }

    /**
     * 初始化OpenGL程序
     */
    fun surfaceCreated() {
        program = createProgram(vertexShaderCode, fragmentShaderCode)
        if (program == 0) {
            throw RuntimeException("Failed to create program")
        }

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (aPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }

        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (aTextureCoordHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }

        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (uMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get uniform location for uMVPMatrix")
        }

        uTexMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        checkGlError("glGetUniformLocation uTexMatrix")
        if (uTexMatrixHandle == -1) {
            throw RuntimeException("Could not get uniform location for uTexMatrix")
        }

        // 创建外部纹理
        textureId = createTextureObject()
        Log.d(TAG, "OpenGL程序初始化完成，textureId=$textureId")
    }

    /**
     * 创建外部纹理对象（用于Camera输出）
     */
    private fun createTextureObject(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlError("glGenTextures")

        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        checkGlError("glBindTexture $texId")

        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlError("glTexParameter")

        return texId
    }

    /**
     * 获取纹理ID（用于绑定到SurfaceTexture）
     */
    fun getTextureId(): Int = textureId

    /**
     * 设置旋转角度
     * @param degrees 旋转角度，必须是0, 90, 180, 270之一
     */
    fun setRotation(degrees: Int) {
        rotation = degrees % 360
        updateTextureCoords()  // 通过调整纹理坐标来旋转画面内容
        Log.d(TAG, "设置旋转角度: $rotation°")
    }

    /**
     * 设置是否水平镜像
     * @param mirror true=镜像，false=不镜像
     */
    fun setMirrorHorizontal(mirror: Boolean) {
        mirrorHorizontal = mirror
        updateTextureCoords()  // 重新应用纹理坐标
        Log.d(TAG, "设置水平镜像: $mirror")
    }

    /**
     * 绘制帧
     * @param texMatrix 纹理变换矩阵（从SurfaceTexture获取）
     */
    fun drawFrame(texMatrix: FloatArray) {
        checkGlError("drawFrame start")

        // 清除屏幕
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 使用program
        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        // 设置顶点坐标
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(
            aPositionHandle, 2, GLES20.GL_FLOAT, false,
            8, vertexBuffer
        )
        checkGlError("glVertexAttribPointer aPosition")

        // 设置纹理坐标
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(
            aTextureCoordHandle, 2, GLES20.GL_FLOAT, false,
            8, textureBuffer
        )
        checkGlError("glVertexAttribPointer aTextureCoord")

        // 设置MVP矩阵（单位矩阵，不做变换）
        // 旋转通过纹理坐标实现，而不是MVP变换
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
        checkGlError("glUniformMatrix4fv uMVPMatrix")

        // 设置纹理变换矩阵
        GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0)
        checkGlError("glUniformMatrix4fv uTexMatrix")

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        // 清理
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    /**
     * 释放资源
     */
    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != -1) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = -1
        }
        Log.d(TAG, "TextureRenderer释放完成")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) {
            return 0
        }
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            return 0
        }

        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            Log.e(TAG, "Could not create program")
        }

        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader vertex")
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError("glAttachShader fragment")
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ")
            Log.e(TAG, GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            program = 0
        }

        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $shaderType:")
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }

        return shader
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            val msg = op + ": glError 0x" + Integer.toHexString(error)
            Log.e(TAG, msg)
            throw RuntimeException(msg)
        }
    }

    companion object {
        private const val TAG = "TextureRenderer"
    }
}
