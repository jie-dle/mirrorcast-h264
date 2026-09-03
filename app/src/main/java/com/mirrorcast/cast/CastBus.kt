package com.mirrorcast.cast

import android.media.projection.MediaProjection

/** 跨组件共享句柄（同进程，Activity ↔ Service / Session 传递 MediaProjection token） */
object CastBus {
    @Volatile var projection: MediaProjection? = null
}
