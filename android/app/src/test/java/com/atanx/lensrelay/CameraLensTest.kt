package com.atanx.lensrelay

import androidx.camera.core.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLensTest {
    @Test
    fun oppositeTogglesBetweenBackAndFront() {
        assertEquals(CameraLens.Front, CameraLens.Back.opposite())
        assertEquals(CameraLens.Back, CameraLens.Front.opposite())
    }

    @Test
    fun cameraXSelectorsMatchEachLens() {
        assertEquals(CameraSelector.LENS_FACING_BACK, CameraLens.Back.lensFacing)
        assertEquals(CameraSelector.LENS_FACING_FRONT, CameraLens.Front.lensFacing)
    }
}
