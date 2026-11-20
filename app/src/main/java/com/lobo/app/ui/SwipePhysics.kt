package com.lobo.app.ui

import kotlin.math.abs

/**
 * 传输板信息条的滑动物理规则（纯函数，方便 JVM 单元测试）。
 *
 * 采用与 Compose `AnchoredDraggable` / RecyclerView 惯性滚动一致的「速度投影 + 最近锚点」模型：
 * 抬手后先用速度把当前位置向前投影，再吸附到最近的锚点，而不是只看固定的距离阈值。
 * 配合 [androidx.dynamicanimation.animation.SpringAnimation] 的弹簧收尾，滑动会跟手且有惯性。
 *
 * 约定：[fraction] = 内容位移 / 容器宽度，取值 0..1。
 * - 左滑：从右往左为 0 → 1，露出右侧动作按钮；
 * - 右滑：从左往右为 0 → 1，用于「删除记录」。
 */
object SwipePhysics {

    /** 抬手后的吸附目标。 */
    enum class Snap {
        /** 回弹回原状。 */
        CLOSED,

        /** 停在完全展开动作按钮的位置。 */
        REVEALED,

        /** 直接删除记录（无二次确认）。 */
        DELETE
    }

    /**
     * 左滑抬手：先按速度投影，再在「收起 / 完全展开 / 移除」三个锚点里取最近的一个。
     *
     * @param fraction 当前左滑位移比例（0..1）
     * @param velocityPxPerSec 抬手水平速度（px/s，正右负左）
     * @param widthPx 容器宽度（px）
     * @param revealFraction 动作按钮完全展开时的比例（按钮数 × 0.15）
     */
    fun resolveLeft(
        fraction: Float,
        velocityPxPerSec: Float,
        widthPx: Int,
        revealFraction: Float
    ): Snap {
        val f = fraction.coerceIn(0f, 1f)
        // 已经拖过删除阈值：不再回弹，直接删除
        if (f >= LEFT_DELETE_THRESHOLD) return Snap.DELETE

        val width = widthPx.coerceAtLeast(1)
        // 左滑位移为正，负速度会把它继续推大
        val projected = (f - velocityPxPerSec * PROJECTION_SECONDS / width).coerceIn(0f, 1f)
        val reveal = revealFraction.coerceIn(0f, 0.95f)

        val closedDistance = abs(projected)
        val revealDistance = abs(projected - reveal)
        val deleteDistance = abs(1f - projected)
        return when {
            deleteDistance <= revealDistance && deleteDistance <= closedDistance -> Snap.DELETE
            revealDistance <= closedDistance -> Snap.REVEALED
            else -> Snap.CLOSED
        }
    }

    /** 右滑抬手：速度投影后越过一半就删除，否则回弹。 */
    fun resolveRight(fraction: Float, velocityPxPerSec: Float, widthPx: Int): Snap {
        val f = fraction.coerceIn(0f, 1f)
        if (f >= RIGHT_DELETE_THRESHOLD) return Snap.DELETE

        val width = widthPx.coerceAtLeast(1)
        val projected = (f + velocityPxPerSec * PROJECTION_SECONDS / width).coerceIn(0f, 1f)
        return if (projected >= RIGHT_DELETE_THRESHOLD) Snap.DELETE else Snap.CLOSED
    }

    /**
     * 左滑越过「按钮总宽」之后的视觉参数。
     *
     * @param fraction 当前位移比例
     * @param revealFraction 按钮完全展开时的比例（按钮数 × 0.15）
     */
    fun overdrag(fraction: Float, revealFraction: Float, buttonCount: Int): Overdrag {
        val f = fraction.coerceIn(0f, 1f)
        val reveal = revealFraction.coerceIn(0f, 0.95f)
        val count = buttonCount.coerceAtLeast(1)
        if (f <= reveal) {
            return Overdrag(stretch = 0f, fadeOthers = 1f)
        }
        if (f <= 0.5f) {
            // 未超过一半：按钮平均拉伸，等用户手动回拉
            val stretch = (f - reveal) / (0.5f - reveal).coerceAtLeast(0.0001f)
            return Overdrag(stretch = stretch.coerceIn(0f, 1f), fadeOthers = 1f)
        }
        // 越过一半：从「完全拉伸时的布局」连续过渡到「居中放大的删除记录按钮」，避免在 0.5 处跳变
        val t = ((f - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val fade = 1f - t
        val startWidth = 0.5f / count
        val startCenter = 1f - startWidth / 2f
        val endWidth = BUTTON_WIDTH_FRACTION * 1.2f
        return Overdrag(
            stretch = 1f,
            fadeOthers = (fade * fade).coerceIn(0f, 1f),
            deleteCenter = startCenter + (0.5f - startCenter) * t,
            deleteWidth = startWidth + (endWidth - startWidth) * t,
            deleteIconScale = 1f + 0.25f * t
        )
    }

    data class Overdrag(
        /** 0..1，按钮被拉伸的程度（0=原始宽度）。 */
        val stretch: Float,
        /** 其它按钮的透明度，1=完全可见。 */
        val fadeOthers: Float,
        /** 删除记录按钮中心点位置（占容器宽度比例），null 表示保持原布局。 */
        val deleteCenter: Float? = null,
        /** 删除记录按钮宽度（占容器宽度比例），null 表示保持原布局。 */
        val deleteWidth: Float? = null,
        /** 删除记录按钮内部叉号的放大倍数（1~1.25）。 */
        val deleteIconScale: Float = 1f
    )

    /** 每个动作按钮占容器宽度的比例。 */
    const val BUTTON_WIDTH_FRACTION = 0.15f

    /** 抬手速度投影到未来的时间（秒）：类似 Compose AnchoredDraggable 的 velocity 吸附。 */
    const val PROJECTION_SECONDS = 0.18f

    /** 左滑拖过该比例直接删除，不再回弹。 */
    const val LEFT_DELETE_THRESHOLD = 0.65f

    /** 右滑拖过该比例直接删除。 */
    const val RIGHT_DELETE_THRESHOLD = 0.5f
}
