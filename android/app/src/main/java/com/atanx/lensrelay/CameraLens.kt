package com.atanx.lensrelay

import androidx.camera.core.CameraSelector

enum class CameraLens(val lensFacing: Int) {
    Back(CameraSelector.LENS_FACING_BACK),
    Front(CameraSelector.LENS_FACING_FRONT),
    ;

    fun opposite(): CameraLens = when (this) {
        Back -> Front
        Front -> Back
    }
}
