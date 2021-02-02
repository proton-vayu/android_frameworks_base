/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.screenshot;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * CropView has top and bottom draggable crop handles, with a scrim to darken the areas being
 * cropped out.
 */
public class CropView extends View {
    private enum CropBoundary {
        NONE, TOP, BOTTOM
    }

    private final float mCropTouchMargin;
    private final Paint mShadePaint;
    private final Paint mHandlePaint;

    // Top and bottom crops are stored as floats [0, 1], representing the top and bottom of the
    // view, respectively.
    private float mTopCrop = 0f;
    private float mBottomCrop = 1f;

    private CropBoundary mCurrentDraggingBoundary = CropBoundary.NONE;
    private float mLastY;

    public CropView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray t = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CropView, 0, 0);
        mShadePaint = new Paint();
        mShadePaint.setColor(t.getColor(R.styleable.CropView_scrimColor, Color.TRANSPARENT));
        mHandlePaint = new Paint();
        mHandlePaint.setColor(t.getColor(R.styleable.CropView_handleColor, Color.BLACK));
        mHandlePaint.setStrokeWidth(
                t.getDimensionPixelSize(R.styleable.CropView_handleThickness, 20));
        t.recycle();
        // 48 dp touchable region around each handle.
        mCropTouchMargin = 24 * getResources().getDisplayMetrics().density;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawShade(canvas, 0, mTopCrop);
        drawShade(canvas, mBottomCrop, 1f);
        drawHandle(canvas, mTopCrop);
        drawHandle(canvas, mBottomCrop);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int topPx = fractionToPixels(mTopCrop);
        int bottomPx = fractionToPixels(mBottomCrop);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mCurrentDraggingBoundary = nearestBoundary(event, topPx, bottomPx);
            if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                mLastY = event.getY();
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE
                && mCurrentDraggingBoundary != CropBoundary.NONE) {
            float delta = event.getY() - mLastY;
            if (mCurrentDraggingBoundary == CropBoundary.TOP) {
                mTopCrop = pixelsToFraction((int) MathUtils.constrain(topPx + delta, 0,
                        bottomPx - 2 * mCropTouchMargin));
            } else {  // Bottom
                mBottomCrop = pixelsToFraction((int) MathUtils.constrain(bottomPx + delta,
                        topPx + 2 * mCropTouchMargin, getMeasuredHeight()));
            }
            mLastY = event.getY();
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * @return value [0,1] representing the position of the top crop boundary.
     */
    public float getTopBoundary() {
        return mTopCrop;
    }

    /**
     * @return value [0,1] representing the position of the bottom crop boundary.
     */
    public float getBottomBoundary() {
        return mBottomCrop;
    }

    private void drawShade(Canvas canvas, float fracStart, float fracEnd) {
        canvas.drawRect(0, fractionToPixels(fracStart), getMeasuredWidth(),
                fractionToPixels(fracEnd), mShadePaint);
    }

    private void drawHandle(Canvas canvas, float frac) {
        int y = fractionToPixels(frac);
        canvas.drawLine(0, y, getMeasuredWidth(), y, mHandlePaint);
    }

    private int fractionToPixels(float frac) {
        return (int) (frac * getMeasuredHeight());
    }

    private float pixelsToFraction(int px) {
        return px / (float) getMeasuredHeight();
    }

    private CropBoundary nearestBoundary(MotionEvent event, int topPx, int bottomPx) {
        if (Math.abs(event.getY() - topPx) < mCropTouchMargin) {
            return CropBoundary.TOP;
        }
        if (Math.abs(event.getY() - bottomPx) < mCropTouchMargin) {
            return CropBoundary.BOTTOM;
        }
        return CropBoundary.NONE;
    }
}
