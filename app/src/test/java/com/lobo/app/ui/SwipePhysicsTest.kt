package com.lobo.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipePhysicsTest {

    // ---------------- 左滑抬手（速度投影 + 最近锚点） ----------------

    private val width = 1000

    @Test
    fun resolveLeft_slowNearStart_closes() {
        assertEquals(SwipePhysics.Snap.CLOSED, SwipePhysics.resolveLeft(0f, 0f, width, 0.30f))
        assertEquals(SwipePhysics.Snap.CLOSED, SwipePhysics.resolveLeft(0.10f, 0f, width, 0.30f))
    }

    @Test
    fun resolveLeft_pastHalfwayToReveal_optsRevealed() {
        assertEquals(SwipePhysics.Snap.REVEALED, SwipePhysics.resolveLeft(0.20f, 0f, width, 0.30f))
        assertEquals(SwipePhysics.Snap.REVEALED, SwipePhysics.resolveLeft(0.44f, 0f, width, 0.30f))
        assertEquals(SwipePhysics.Snap.REVEALED, SwipePhysics.resolveLeft(0.60f, 0f, width, 0.30f))
    }

    @Test
    fun resolveLeft_dragPastDeleteThreshold_deletes() {
        assertEquals(SwipePhysics.Snap.DELETE, SwipePhysics.resolveLeft(0.65f, 0f, width, 0.30f))
        assertEquals(SwipePhysics.Snap.DELETE, SwipePhysics.resolveLeft(1f, 0f, width, 0.30f))
    }

    @Test
    fun resolveLeft_fastFling_carriesToNextAnchor() {
        // 只滑了一点点，但快速左甩：动量足以展开
        assertEquals(SwipePhysics.Snap.REVEALED, SwipePhysics.resolveLeft(0.08f, -2000f, width, 0.30f))
        // 已经过半再快速左甩：直接删除
        assertEquals(SwipePhysics.Snap.DELETE, SwipePhysics.resolveLeft(0.40f, -2000f, width, 0.30f))
    }

    @Test
    fun resolveLeft_fastFlingBack_closes() {
        assertEquals(SwipePhysics.Snap.CLOSED, SwipePhysics.resolveLeft(0.50f, 2000f, width, 0.30f))
    }

    // ---------------- 右滑抬手 ----------------

    @Test
    fun resolveRight_halfOrLess_closes() {
        assertEquals(SwipePhysics.Snap.CLOSED, SwipePhysics.resolveRight(0f, 0f, width))
        assertEquals(SwipePhysics.Snap.CLOSED, SwipePhysics.resolveRight(0.49f, 0f, width))
    }

    @Test
    fun resolveRight_moreThanHalf_deletes() {
        assertEquals(SwipePhysics.Snap.DELETE, SwipePhysics.resolveRight(0.50f, 0f, width))
        assertEquals(SwipePhysics.Snap.DELETE, SwipePhysics.resolveRight(1f, 0f, width))
    }

    @Test
    fun resolveRight_momentumDecidesThreshold() {
        // 只滑了 30%，但快速右甩：投影超过一半，删除
        assertEquals(SwipePhysics.Snap.DELETE, SwipePhysics.resolveRight(0.30f, 1500f, width))
        // 快速向左回拉：回弹
        assertEquals(SwipePhysics.Snap.CLOSED, SwipePhysics.resolveRight(0.30f, -1500f, width))
    }

    // ---------------- 超过按钮总宽后的视觉参数 ----------------

    /** 两个按钮时完全展开 = 0.30 */
    private val reveal = 0.30f
    private val count = 2

    @Test
    fun overdrag_withinReveal_keepsLayout() {
        val over = SwipePhysics.overdrag(0.10f, reveal, count)
        assertEquals(1f, over.fadeOthers, 0.0001f)
        assertNull(over.deleteCenter)
        assertNull(over.deleteWidth)
    }

    @Test
    fun overdrag_beforeHalf_stretchesWithoutFading() {
        val over = SwipePhysics.overdrag(0.40f, reveal, count)
        assertEquals(1f, over.fadeOthers, 0.0001f)
        assertNull(over.deleteCenter)
        assertTrue(over.stretch in 0.01f..0.99f)
    }

    @Test
    fun overdrag_afterHalf_fadesOthersAndCentersDelete() {
        val over = SwipePhysics.overdrag(1f, reveal, count)
        assertEquals(0f, over.fadeOthers, 0.0001f)
        assertEquals(1.25f, over.deleteIconScale, 0.0001f)
        assertEquals(0.5f, over.deleteCenter!!, 0.0001f)
        assertEquals(SwipePhysics.BUTTON_WIDTH_FRACTION * 1.2f, over.deleteWidth!!, 0.0001f)
    }

    @Test
    fun overdrag_atHalfBoundary_continuesFromStretchedLayout() {
        // 越过 0.5 的瞬间：删除按钮应接续「完全拉伸时的最后一格」，不能在 0.5 处跳变
        val over = SwipePhysics.overdrag(0.5001f, reveal, count)
        val expectedWidth = 0.5f / count
        val expectedCenter = 1f - expectedWidth / 2f
        assertEquals(expectedWidth, over.deleteWidth!!, 0.01f)
        assertEquals(expectedCenter, over.deleteCenter!!, 0.01f)
    }

    @Test
    fun overdrag_deleteMovesInwardSmoothly() {
        var previousCenter = SwipePhysics.overdrag(0.5001f, reveal, count).deleteCenter!!
        var previousWidth = SwipePhysics.overdrag(0.5001f, reveal, count).deleteWidth!!
        for (step in 1..10) {
            val f = 0.5f + 0.05f * step
            val over = SwipePhysics.overdrag(f, reveal, count)
            val center = over.deleteCenter!!
            val widthValue = over.deleteWidth!!
            assertTrue(center <= previousCenter + 0.0001f)
            assertTrue(widthValue <= previousWidth + 0.0001f)
            previousCenter = center
            previousWidth = widthValue
        }
        assertEquals(0.5f, previousCenter, 0.0001f)
    }

    @Test
    fun overdrag_othersFadeFasterThanLinear() {
        // 0.75 时线性淡出应为 0.5，这里应更快（小于 0.3）
        val over = SwipePhysics.overdrag(0.75f, reveal, count)
        assertTrue(over.fadeOthers < 0.3f)
    }
}
