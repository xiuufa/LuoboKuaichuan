package com.lobo.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.customview.widget.ViewDragHelper
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.lobo.app.R
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 传输板信息条的滑动容器。
 *
 * 手势识别交给 AndroidX [ViewDragHelper]；抬手后的吸附使用 AndroidX DynamicAnimation 的
 * [SpringAnimation]：先按抬手速度投影、选择最近锚点，再以弹簧带速度收尾。相比固定阈值 + Scroller，
 * 更接近通知中心/Compose AnchoredDraggable 的跟手与惯性手感。
 *
 * 锚点只有三个业务状态：
 * - CLOSED：收起；
 * - REVEALED：动作按钮完全展开；
 * - DELETE：越过阈值后整条甩出并删除记录。
 */
class SwipeActionLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** 左滑越过删除阈值或右滑超过一半时抬手：直接删除记录（无二次确认）。 */
        fun onSwipeDelete()
    }

    var swipeListener: Listener? = null
    var swipeEnabled: Boolean = true

    private var content: View? = null
    private var actions: LinearLayout? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** 内容左边缘位置：0=收起，负数=左滑露出按钮，正数=右滑准备删除。 */
    private var offsetX = 0f
    private var dragging = false

    private var spring: SpringAnimation? = null
    private var pendingTarget: Float? = null
    private var pendingVelocity = 0f
    private var pendingDelete = false

    /** 让 SpringAnimation 直接驱动 offsetX，并在每一帧重新摆放内容与动作按钮。 */
    private val offsetProperty = object : FloatPropertyCompat<SwipeActionLayout>("offsetX") {
        override fun getValue(view: SwipeActionLayout): Float = view.offsetX

        override fun setValue(view: SwipeActionLayout, value: Float) {
            view.offsetX = value
            view.applyOffset()
        }
    }

    private val dragHelper = ViewDragHelper.create(this, 1f, object : ViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int): Boolean =
            swipeEnabled && child === content

        override fun getViewHorizontalDragRange(child: View): Int = width.coerceAtLeast(1)

        override fun getViewVerticalDragRange(child: View): Int = 0

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            val containerWidth = width.toFloat().coerceAtLeast(1f)
            return left.coerceIn(
                -containerWidth.toInt(),
                (containerWidth * 0.7f).toInt()
            )
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int = 0

        override fun onViewPositionChanged(
            changedView: View,
            left: Int,
            top: Int,
            dx: Int,
            dy: Int
        ) {
            offsetX = left.toFloat()
            layoutActions(width, height)
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            finishDrag(xvel)
        }

        override fun onViewDragStateChanged(state: Int) {
            when (state) {
                ViewDragHelper.STATE_DRAGGING -> {
                    dragging = true
                    cancelSpring()
                    closeOtherInstances()
                }

                ViewDragHelper.STATE_SETTLING -> dragging = false

                ViewDragHelper.STATE_IDLE -> {
                    dragging = false
                    // 等 ViewDragHelper 彻底释放被捕获的视图后再启动弹簧，避免两套动画抢同一视图
                    val target = pendingTarget ?: return
                    val velocity = pendingVelocity
                    val delete = pendingDelete
                    pendingTarget = null
                    pendingVelocity = 0f
                    pendingDelete = false
                    startSpring(target, velocity, delete)
                }
            }
        }
    })

    init {
        dragHelper.setEdgeTrackingEnabled(0)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.cardContent)
        actions = findViewById(R.id.actionsContainer)
        // 正文盖在动作按钮之上，动作区域只在左滑让位后露出
        content?.bringToFront()
        // 已展开时点正文只收起，不触发「打开文件」
        content?.setOnTouchListener { _, event ->
            if (offsetX == 0f) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    closeActions()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    override fun onDetachedFromWindow() {
        cancelSpring()
        runCatching { dragHelper.cancel() }
        if (openInstance?.get() === this) openInstance = null
        super.onDetachedFromWindow()
    }

    // ==================== 对外控制 ====================

    fun isActionsOpen(): Boolean = offsetX < -1f

    fun isSwiping(): Boolean = dragging || spring?.isRunning == true

    /** 立即收起（绑定新数据时调用）。 */
    fun closeActionsImmediately() {
        resetImmediately()
    }

    /** 弹簧收回动作按钮。 */
    fun closeActions() {
        if (offsetX == 0f) return
        startSpring(0f, 0f, delete = false)
    }

    private fun resetImmediately() {
        cancelSpring()
        pendingTarget = null
        pendingDelete = false
        pendingVelocity = 0f
        offsetX = 0f
        runCatching { dragHelper.cancel() }
        dragging = false
        clearOpened()
        content?.let { view ->
            val w = width
            val h = height
            if (w > 0 && h > 0) view.layout(0, 0, w, h)
        }
        layoutActions(width, height)
    }

    // ==================== 布局 ====================

    private fun visibleActions(): List<View> {
        val container = actions ?: return emptyList()
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
    }

    private fun revealWidth(): Float {
        val count = visibleActions().size
        return if (count == 0) 0f else SwipePhysics.BUTTON_WIDTH_FRACTION * width * count
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        // 先让正文按内容测一遍（长文件名等多行会把它撑高）
        val available = if (heightMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else heightSize
        content?.measure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        var target = (content?.measuredHeight ?: 0).coerceAtLeast(suggestedMinimumHeight)
        if (heightMode == MeasureSpec.AT_MOST) target = target.coerceAtMost(heightSize)
        if (heightMode == MeasureSpec.EXACTLY) target = heightSize
        target = target.coerceAtLeast(1)
        content?.measure(widthSpec, MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY))
        val buttonWidth = (SwipePhysics.BUTTON_WIDTH_FRACTION * width).toInt().coerceAtLeast(1)
        actions?.let { container ->
            for (i in 0 until container.childCount) {
                container.getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(buttonWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY)
                )
            }
        }
        setMeasuredDimension(width, target)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        if (width <= 0 || height <= 0) return
        content?.layout(0, 0, width, height)
        actions?.layout(0, 0, width, height)
        val offset = offsetX.toInt()
        if (offset != 0) content?.offsetLeftAndRight(offset)
        layoutActions(width, height)
    }

    private fun layoutActions(width: Int, height: Int) {
        val buttons = visibleActions()
        val buttonCount = buttons.size
        if (buttonCount == 0) return
        val baseUnit = SwipePhysics.BUTTON_WIDTH_FRACTION * width
        val revealFraction = SwipePhysics.BUTTON_WIDTH_FRACTION * buttonCount
        val leftFraction = if (offsetX < 0f) (-offsetX / width).coerceIn(0f, 1f) else 0f

        val totalWidth = when {
            leftFraction <= revealFraction -> revealFraction * width
            leftFraction <= 0.5f -> leftFraction * width
            else -> 0.5f * width
        }
        // 右滑时动作按钮可能从内容右侧露出来，这里直接隐藏（右滑只有透明滑动，不显示按钮）
        if (offsetX > 0f) {
            buttons.forEach { it.alpha = 0f }
        }

        val over = SwipePhysics.overdrag(leftFraction, revealFraction, buttonCount)

        if (leftFraction > 0.5f) {
            layoutOverdrag(buttons, width, height, baseUnit, totalWidth, over)
            return
        }

        // 正常展开 / 平均拉伸：所有按钮均分已露出的宽度
        val each = totalWidth / buttonCount
        var cursor = width - totalWidth
        for (button in buttons) {
            button.alpha = if (offsetX > 0f) 0f else 1f
            button.scaleX = 1f
            button.scaleY = 1f
            button.layout(cursor.toInt(), 0, (cursor + each).toInt(), height)
            cursor += each
        }
    }

    /** 超过一半：其它按钮淡出，删除记录按钮（最后一个）向中间移动并放大。 */
    private fun layoutOverdrag(
        buttons: List<View>,
        width: Int,
        height: Int,
        baseUnit: Float,
        totalWidth: Float,
        over: SwipePhysics.Overdrag
    ) {
        val deleteButton = buttons.last()
        val others = buttons.dropLast(1)
        val centerX = (over.deleteCenter ?: (1f - baseUnit * buttons.size / 2f)) * width
        val deleteWidth = (over.deleteWidth ?: baseUnit) * width
        deleteButton.alpha = 1f
        deleteButton.scaleX = over.deleteIconScale
        deleteButton.scaleY = over.deleteIconScale
        val deleteLeft = centerX - deleteWidth / 2f
        deleteButton.layout(deleteLeft.toInt(), 0, (deleteLeft + deleteWidth).toInt(), height)

        if (others.isEmpty()) return
        val othersTotal = (totalWidth - deleteWidth).coerceAtLeast(0f)
        val each = othersTotal / others.size
        var cursor = width - totalWidth
        for (button in others) {
            button.alpha = over.fadeOthers
            button.scaleX = 1f
            button.scaleY = 1f
            button.layout(cursor.toInt(), 0, (cursor + each).toInt(), height)
            cursor += each
        }
    }

    // ==================== 手势 ====================

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSpring()
                downX = ev.x
                downY = ev.y
                return dragHelper.shouldInterceptTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 只接管明确以横向为主的手势，纵向滚动仍交给 RecyclerView
                if (abs(dx) <= touchSlop || abs(dx) <= abs(dy) * 1.2f) return false
                return dragHelper.shouldInterceptTouchEvent(ev)
            }

            else -> return dragHelper.shouldInterceptTouchEvent(ev)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        dragHelper.processTouchEvent(ev)
        return true
    }

    private fun finishDrag(velocityX: Float) {
        val containerWidth = width.toFloat().coerceAtLeast(1f)
        val fraction = abs(offsetX) / containerWidth
        val widthPx = width.coerceAtLeast(1)
        val snap = if (offsetX < 0f) {
            SwipePhysics.resolveLeft(
                fraction = fraction,
                velocityPxPerSec = velocityX,
                widthPx = widthPx,
                revealFraction = revealWidth() / containerWidth
            )
        } else {
            SwipePhysics.resolveRight(fraction, velocityX, widthPx)
        }

        when (snap) {
            SwipePhysics.Snap.CLOSED -> queueSpring(0f, velocityX, delete = false)
            SwipePhysics.Snap.REVEALED -> queueSpring(-revealWidth(), velocityX, delete = false)
            SwipePhysics.Snap.DELETE -> queueSpring(
                if (offsetX < 0f) -containerWidth else containerWidth * 0.9f,
                velocityX,
                delete = true
            )
        }
    }

    /** 先记录目标，等 ViewDragHelper 进入 IDLE 释放视图后再真正启动弹簧。 */
    private fun queueSpring(target: Float, velocity: Float, delete: Boolean) {
        pendingTarget = target
        pendingVelocity = velocity
        pendingDelete = delete
    }

    private fun startSpring(target: Float, startVelocity: Float, delete: Boolean) {
        cancelSpring()
        val force = SpringForce(target).apply {
            // 删除用临界阻尼直接飞出；展开/收起保留极小回弹，手感更自然
            dampingRatio = if (delete) SpringForce.DAMPING_RATIO_NO_BOUNCY else 0.9f
            stiffness = if (delete) 1200f else 900f
        }
        val animation = SpringAnimation(this, offsetProperty, target)
        animation.spring = force
        animation.setStartVelocity(startVelocity)
        animation.addEndListener { anim, canceled, _, _ ->
            if (canceled) return@addEndListener
            if (spring === anim) spring = null
            if (delete) {
                resetImmediately()
                swipeListener?.onSwipeDelete()
            } else if (target == 0f) {
                clearOpened()
            } else {
                notifyOpened()
            }
        }
        spring = animation
        animation.start()
    }

    private fun cancelSpring() {
        spring?.cancel()
        spring = null
    }

    private fun applyOffset() {
        val view = content ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val offset = offsetX.toInt()
        view.layout(offset, 0, w + offset, h)
        layoutActions(w, h)
    }

    // ==================== 单条展开 ====================

    private fun notifyOpened() {
        val previous = openInstance?.get()
        if (previous != null && previous !== this) previous.closeActions()
        openInstance = WeakReference(this)
    }

    private fun clearOpened() {
        if (openInstance?.get() === this) openInstance = null
    }

    private fun closeOtherInstances() {
        val previous = openInstance?.get() ?: return
        if (previous !== this) previous.closeActions()
    }

    companion object {
        /** 同一列表里只保留一条展开的滑动条目。 */
        private var openInstance: WeakReference<SwipeActionLayout>? = null
    }
}
