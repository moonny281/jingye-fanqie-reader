package io.legado.app.utils

import androidx.annotation.IdRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet

@Suppress("unused")
fun ConstraintLayout.modifyBegin(withAnim: Boolean = false): ConstraintModify.ConstraintBegin {
    return ConstraintModify(this).begin
}

@Suppress("MemberVisibilityCanBePrivate", "unused")
class ConstraintModify(private val constraintLayout: ConstraintLayout) {

    val begin: ConstraintBegin by lazy {
        applyConstraintSet.clone(constraintLayout)
        ConstraintBegin(constraintLayout, applyConstraintSet)
    }
    private val applyConstraintSet = ConstraintSet()
    private val resetConstraintSet = ConstraintSet()

    init {
        resetConstraintSet.clone(constraintLayout)
    }

    /**
     * 墨水屏模式禁用过渡动画
     */
    fun beginWithAnim(): ConstraintBegin {
        return begin
    }

    /**
     * 重置
     */
    fun reSet() {
        resetConstraintSet.applyTo(constraintLayout)
    }

    /**
     * 墨水屏模式禁用过渡动画重置
     */
    fun reSetWidthAnim() {
        resetConstraintSet.applyTo(constraintLayout)
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    class ConstraintBegin(
        private val constraintLayout: ConstraintLayout,
        private val applyConstraintSet: ConstraintSet
    ) {

        fun clear(viewId: Int): ConstraintBegin {
            applyConstraintSet.clear(viewId)
            return this
        }

        fun clear(viewId: Int, anchor: Anchor): ConstraintBegin {
            applyConstraintSet.clear(viewId, anchor.toInt())
            return this
        }

        fun setHorizontalWeight(viewId: Int, weight: Float): ConstraintBegin {
            applyConstraintSet.setHorizontalWeight(viewId, weight)
            return this
        }

        fun setVerticalWeight(viewId: Int, weight: Float): ConstraintBegin {
            applyConstraintSet.setVerticalWeight(viewId, weight)
            return this
        }

        fun setMargin(
            @IdRes viewId: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ): ConstraintBegin {
            setMarginLeft(viewId, left)
            setMarginTop(viewId, top)
            setMarginRight(viewId, right)
            setMarginBottom(viewId, bottom)
            return this
        }

        fun setMarginLeft(@IdRes viewId: Int, left: Int): ConstraintBegin {
            applyConstraintSet.setMargin(viewId, ConstraintSet.LEFT, left)
            return this
        }

        fun setMarginRight(@IdRes viewId: Int, right: Int): ConstraintBegin {
            applyConstraintSet.setMargin(viewId, ConstraintSet.RIGHT, right)
            return this
        }

        fun setMarginTop(@IdRes viewId: Int, top: Int): ConstraintBegin {
            applyConstraintSet.setMargin(viewId, ConstraintSet.TOP, top)
            return this
        }

        fun setMarginBottom(@IdRes viewId: Int, bottom: Int): ConstraintBegin {
            applyConstraintSet.setMargin(viewId, ConstraintSet.BOTTOM, bottom)
            return this
        }

        fun leftToLeftOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.LEFT, endId, ConstraintSet.LEFT)
            return this
        }

        fun leftToRightOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.LEFT, endId, ConstraintSet.RIGHT)
            return this
        }

        fun topToTopOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.TOP, endId, ConstraintSet.TOP)
            return this
        }

        fun topToBottomOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.TOP, endId, ConstraintSet.BOTTOM)
            return this
        }

        fun rightToLeftOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.RIGHT, endId, ConstraintSet.LEFT)
            return this
        }

        fun rightToRightOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.RIGHT, endId, ConstraintSet.RIGHT)
            return this
        }

        fun bottomToBottomOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.BOTTOM, endId, ConstraintSet.BOTTOM)
            return this
        }

        fun bottomToTopOf(@IdRes startId: Int, @IdRes endId: Int): ConstraintBegin {
            applyConstraintSet.connect(startId, ConstraintSet.BOTTOM, endId, ConstraintSet.TOP)
            return this
        }

        fun setWidth(@IdRes viewId: Int, width: Int): ConstraintBegin {
            applyConstraintSet.constrainWidth(viewId, width)
            return this
        }

        fun setHeight(@IdRes viewId: Int, height: Int): ConstraintBegin {
            applyConstraintSet.constrainHeight(viewId, height)
            return this
        }

        fun commit() {
            constraintLayout.post {
                applyConstraintSet.applyTo(constraintLayout)
            }
        }
    }

    enum class Anchor {
        LEFT, RIGHT, TOP, BOTTOM, BASELINE, START, END, CIRCLE_REFERENCE;

        fun toInt(): Int {
            return when (this) {
                LEFT -> ConstraintSet.LEFT
                RIGHT -> ConstraintSet.RIGHT
                TOP -> ConstraintSet.TOP
                BOTTOM -> ConstraintSet.BOTTOM
                BASELINE -> ConstraintSet.BASELINE
                START -> ConstraintSet.START
                END -> ConstraintSet.END
                CIRCLE_REFERENCE -> ConstraintSet.CIRCLE_REFERENCE
            }
        }

    }

}
