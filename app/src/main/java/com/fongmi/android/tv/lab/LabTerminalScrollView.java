package com.fongmi.android.tv.lab;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

/**
 * 终端日志容器：让「自动换行」开关真的能换行。
 *
 * <p>原来的写法（{@code TextView.setHorizontallyScrolling(false)} + width=MATCH_PARENT）
 * 在 HorizontalScrollView 里是没用的——HorizontalScrollView 量孩子时给的是
 * <b>UNSPECIFIED</b> 宽度，TextView 于是按"最长那行"撑开，永远不折行。
 *
 * <p>所以换行开启时，这里改成按父容器宽度（EXACTLY）去量子视图，TextView 才会按屏宽折行；
 * 换行关闭时走父类原生逻辑（UNSPECIFIED → 按最长行撑开），保持可横向拖动。
 */
public class LabTerminalScrollView extends HorizontalScrollView {

    /** 默认开启，与 LabTerminalPrefs.autoWrap() 的默认值一致。 */
    private boolean wrapEnabled = true;

    public LabTerminalScrollView(Context context) {
        super(context);
    }

    public LabTerminalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LabTerminalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public boolean isWrapEnabled() {
        return wrapEnabled;
    }

    public void setWrapEnabled(boolean enabled) {
        if (wrapEnabled == enabled) return;
        wrapEnabled = enabled;
        requestLayout();
    }

    @Override
    protected void measureChild(View child, int parentWidthMeasureSpec, int parentHeightMeasureSpec) {
        if (!wrapEnabled) {
            super.measureChild(child, parentWidthMeasureSpec, parentHeightMeasureSpec);
            return;
        }
        int available = MeasureSpec.getSize(parentWidthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(Math.max(0, available), MeasureSpec.EXACTLY);
        int childHeightSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                getPaddingTop() + getPaddingBottom(), child.getLayoutParams().height);
        child.measure(childWidthSpec, childHeightSpec);
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
                                           int parentHeightMeasureSpec, int heightUsed) {
        if (!wrapEnabled) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed);
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        int available = MeasureSpec.getSize(parentWidthMeasureSpec) - getPaddingLeft() - getPaddingRight()
                - lp.leftMargin - lp.rightMargin - widthUsed;
        int childWidthSpec = MeasureSpec.makeMeasureSpec(Math.max(0, available), MeasureSpec.EXACTLY);
        int childHeightSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                getPaddingTop() + getPaddingBottom() + lp.topMargin + lp.bottomMargin + heightUsed, lp.height);
        child.measure(childWidthSpec, childHeightSpec);
    }
}
