package com.everwall

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

class LockableBottomSheetBehavior<V : View>(ctx: Context, attrs: AttributeSet) :
    BottomSheetBehavior<V>(ctx, attrs) {

    var locked = false

    override fun onInterceptTouchEvent(parent: CoordinatorLayout, child: V, ev: MotionEvent): Boolean {
        if (locked) return false
        return super.onInterceptTouchEvent(parent, child, ev)
    }

    override fun onTouchEvent(parent: CoordinatorLayout, child: V, ev: MotionEvent): Boolean {
        if (locked) return false
        return super.onTouchEvent(parent, child, ev)
    }

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout, child: V,
        directTargetChild: View, target: View, axes: Int, type: Int
    ): Boolean {
        if (locked) return false
        return super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, axes, type)
    }

    override fun onNestedPreScroll(
        coordinatorLayout: CoordinatorLayout, child: V,
        target: View, dx: Int, dy: Int, consumed: IntArray, type: Int
    ) {
        if (locked) return
        super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type)
    }

    override fun onNestedFling(
        coordinatorLayout: CoordinatorLayout, child: V,
        target: View, velocityX: Float, velocityY: Float, consumed: Boolean
    ): Boolean {
        if (locked) return false
        return super.onNestedFling(coordinatorLayout, child, target, velocityX, velocityY, consumed)
    }
}
