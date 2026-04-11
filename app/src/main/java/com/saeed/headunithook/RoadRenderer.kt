package com.saeed.headunithook

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.graphics.BitmapFactory
import android.opengl.GLUtils

class RoadRenderer(private val context: Context) : GLSurfaceView.Renderer {



    // ==========================================
    // ⚙️ CALIBRATION & STYLE
    // ==========================================
    private var roadGapWidth = 200f
    private var flowToHorizon = false
    private var cameraHorizontalShift = -10.0f

    private var bgProgram: Int = 0
    private var bgTextureId: Int = 0
    private lateinit var bgVertexBuffer: FloatBuffer

    private val bgVertexShader = """
    attribute vec2 aPosition;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = vec4(aPosition, 0.0, 1.0);
        vTexCoord = aPosition * 0.5 + 0.5;
        vTexCoord.y = 1.0 - vTexCoord.y;
    }
""".trimIndent()

    private val bgFragmentShader = """
    precision mediump float;
    uniform sampler2D uTexture;
    varying vec2 vTexCoord;
    void main() {
        gl_FragColor = texture2D(uTexture, vTexCoord);
    }
""".trimIndent()

    // Use this to shift the lines left/right specifically to match the car
    // Try -5.0f or -15.0f if -10.0f isn't enough
    private var lineCenterNudge = -12.0f

    // Line Geometry Settings
    private val dashCount = 20
    private val dashLength = 60f
    private val gapLength = 100f
    private val dashWidth = 4f
    private val lineZCycle = dashLength + gapLength
    // ==========================================

    private var program: Int = 0
    private var lineProgram: Int = 0
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var lineVertexBuffer: FloatBuffer

    private var vertexCount = 0

    var gridCols = 75
        set(value) { field = value; needsRebuild = true }
    var gridRows = 200
        set(value) { field = value; needsRebuild = true }
    private var needsRebuild = false

    private val gridWidth = 75 * 30.0f

    private var dRainbowStart = 0
    private var dRainbowEnd   = 0
    private var dAurora = Array(6) { 0 }


    var dotSizeScale = 1.0f // Default scale


    var patternMode = 0f
    var colorMode = 0f

    var rainbowStart = 0f
    var rainbowEnd   = 360f
    var auroraColors = Array(6) { i ->
        when(i) {
            0 -> floatArrayOf(0f, 0.1f, 0.05f)
            1 -> floatArrayOf(0f, 1f,   0.53f)
            2 -> floatArrayOf(0f, 0.8f, 1f)
            3 -> floatArrayOf(0.47f, 0f, 1f)
            4 -> floatArrayOf(0f, 1f,   0.8f)
            else -> floatArrayOf(0f, 0.27f, 1f)
        }
    }


    private var mSpeedTotal = 0f
    private var mTime = 0f

    private val scrollSensitivity = 0.08f   // How fast the landscape scrolls
    private val shimmerSensitivity = 0.005f // How fast the waves bob up/down

    private var currentSpeed = 0f
    private var smoothedSpeed = 0f // For elegant transitions




    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.08f, 1.0f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = createProgram(Shaders.vertexShaderCode, Shaders.fragmentShaderCode)
        lineProgram = createProgram(Shaders.lineVertexShader, Shaders.lineFragmentShader)

        generateGrid()
        generateLines()
        setupBackground()
        dRainbowStart = GLES20.glGetUniformLocation(program, "uRainbowStart")
        dRainbowEnd   = GLES20.glGetUniformLocation(program, "uRainbowEnd")
        for (i in 0..5) dAurora[i] = GLES20.glGetUniformLocation(program, "uAurora$i")
    }

    private fun setupBackground() {
        // Fullscreen quad — two triangles covering the entire screen
        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f, -1f,
            1f,  1f,
            -1f,  1f
        )
        bgVertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }

        // Load texture
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        bgTextureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val hookContext = try {
            context.createPackageContext(
                "com.saeed.headunithook",
                Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (e: Exception) {
            android.util.Log.e("SHADER", "Failed to get hook context: ${e.message}")
            null
        }

        val bitmap = if (hookContext != null) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(hookContext.resources, R.drawable.bg, options)
            var scale = 1
            while (options.outWidth / scale > 1920 || options.outHeight / scale > 1200) scale *= 2
            BitmapFactory.decodeResource(hookContext.resources, R.drawable.bg,
                BitmapFactory.Options().apply {
                    inSampleSize = scale
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                })
        } else null

        if (bitmap != null) {
            android.util.Log.e("SHADER", "bg loaded: ${bitmap.width}x${bitmap.height}")
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        } else {
            android.util.Log.e("SHADER", "bg bitmap null")
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 1, 1, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE,
                ByteBuffer.wrap(byteArrayOf(18, 18, 20)))
        }

        bgProgram = createProgram(bgVertexShader, bgFragmentShader)
    }

    private fun drawBackground() {
        GLES20.glUseProgram(bgProgram)
        val posHandle = GLES20.glGetAttribLocation(bgProgram, "aPosition")
        val texHandle = GLES20.glGetUniformLocation(bgProgram, "uTexture")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
        GLES20.glUniform1i(texHandle, 0)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, bgVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(posHandle)
    }


    private fun generateLines() {
        val vertices = FloatArray(dashCount * 6 * 3)
        for (i in 0 until dashCount) {
            val zStart = -i * lineZCycle
            val zEnd = zStart - dashLength
            val xL = -dashWidth / 2f
            val xR = dashWidth / 2f
            val y = -59.8f // Tiny bit above the road floor

            val offset = i * 18
            vertices[offset + 0] = xL; vertices[offset + 1] = y; vertices[offset + 2] = zStart
            vertices[offset + 3] = xR; vertices[offset + 4] = y; vertices[offset + 5] = zStart
            vertices[offset + 6] = xL; vertices[offset + 7] = y; vertices[offset + 8] = zEnd
            vertices[offset + 9] = xR;  vertices[offset + 10] = y; vertices[offset + 11] = zStart
            vertices[offset + 12] = xR; vertices[offset + 13] = y; vertices[offset + 14] = zEnd
            vertices[offset + 15] = xL; vertices[offset + 16] = y; vertices[offset + 17] = zEnd
        }
        lineVertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
    }


    private fun generateGrid() {
        val cellSize = 30.0f
        val vertices = FloatArray(gridCols * gridRows * 3)
        vertexCount = gridCols * gridRows

        for (i in 0 until gridCols) {
            for (j in 0 until gridRows) {
                val idx = (i * gridRows + j) * 3
                vertices[idx + 0] = i * cellSize
                vertices[idx + 1] = 0f
                vertices[idx + 2] = -j * cellSize
            }
        }
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawBackground()

        if (needsRebuild) {
            needsRebuild = false
            generateGrid()
        }

        if (program == 0) return
        GLES20.glUseProgram(program)

        // 1. SMOOTHING: Prevents the "teleporting" look when speed changes
        // This eases the speed change over multiple frames
        smoothedSpeed += (currentSpeed - smoothedSpeed) * 0.05f

        // 2. STALL CHECK: If car is basically stopped, don't move at all
        mSpeedTotal += smoothedSpeed * 0.5f
        mTime += 0.008f + (smoothedSpeed * 0.0003f)

        // 3. SEND TO SHADER
        val timeHandle = GLES20.glGetUniformLocation(program, "uTime")
        val speedHandle = GLES20.glGetUniformLocation(program, "uSpeedTotal")
        GLES20.glUniform1f(timeHandle, mTime)
        GLES20.glUniform1f(speedHandle, mSpeedTotal)


        val smoothScrollZ = mSpeedTotal % lineZCycle

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.setLookAtM(viewMatrix, 0,
            cameraHorizontalShift, 100f, 50f,
            cameraHorizontalShift, 0f, -600f,
            0f, 1f, 0f
        )
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)


        drawWaves(mvpMatrix)
        drawRoadLines(mvpMatrix, smoothScrollZ)
    }

    // Inside RoadRenderer.kt

    private fun drawWaves(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)

        // Get the locations (handles)
        val matrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val speedHandle = GLES20.glGetUniformLocation(program, "uSpeedTotal")
        val timeHandle = GLES20.glGetUniformLocation(program, "uTime")
        val gapHandle = GLES20.glGetUniformLocation(program, "uRoadGap")
        val dirHandle = GLES20.glGetUniformLocation(program, "uFlowDir")
        val sideHandle = GLES20.glGetUniformLocation(program, "uSideOffset")

        // --- ADD THESE HANDLES ---
        val sizeHandle = GLES20.glGetUniformLocation(program, "uPointSizeScale")
        val patternHandle = GLES20.glGetUniformLocation(program, "uPattern")
        val colorHandle = GLES20.glGetUniformLocation(program, "uColor")

        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")

        // Upload common uniforms
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(speedHandle, mSpeedTotal)
        GLES20.glUniform1f(timeHandle, mTime)
        GLES20.glUniform1f(gapHandle, roadGapWidth)
        GLES20.glUniform1f(dirHandle, if (flowToHorizon) 1.0f else -1.0f)

        // --- UPLOAD THE PATTERN AND COLOR MODE ---
        GLES20.glUniform1f(sizeHandle, dotSizeScale)
        GLES20.glUniform1f(patternHandle, patternMode)
        GLES20.glUniform1f(colorHandle, colorMode)

        GLES20.glUniform1f(dRainbowStart, rainbowStart)
        GLES20.glUniform1f(dRainbowEnd,   rainbowEnd)
        for (i in 0..5) GLES20.glUniform3fv(dAurora[i], 1, auroraColors[i], 0)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glUniform1f(sideHandle, -(roadGapWidth / 2f) - gridWidth)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)

        GLES20.glUniform1f(sideHandle, (roadGapWidth / 2f))
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
    }

    private fun drawRoadLines(mvpMatrix: FloatArray, scrollZ: Float) {
        GLES20.glUseProgram(lineProgram)
        val matrixHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val scrollHandle = GLES20.glGetUniformLocation(lineProgram, "uScrollZ")
        val sideHandle = GLES20.glGetUniformLocation(lineProgram, "uSideOffset")
        val posHandle = GLES20.glGetAttribLocation(lineProgram, "aPosition")

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(scrollHandle, scrollZ)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, lineVertexBuffer)

        // Draw Left line (centered around the nudge)
        GLES20.glUniform1f(sideHandle, lineCenterNudge - 65f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, dashCount * 6)

        // Draw Right line
        GLES20.glUniform1f(sideHandle, lineCenterNudge + 65f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, dashCount * 6)
    }


    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 1f, 4000f)
    }

    fun updateSpeed(speed: Float) {
        this.currentSpeed = if (speed < 0f) 0f else speed
    }

    private fun createProgram(vCode: String, fCode: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vCode)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fCode)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }



}