package com.saeed.headunithook

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class HighPerfRoadView(context: Context) : GLSurfaceView(context) {
    // Change this from 'private val' to 'val' so LauncherHook can see it
    val renderer = RoadRenderer(context)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)

        // Use the public renderer
        setRenderer(renderer)

        // This is important for transparency layering
        setZOrderMediaOverlay(true)

        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun setSpeed(speed: Float) {
        renderer.updateSpeed(speed)
    }
}