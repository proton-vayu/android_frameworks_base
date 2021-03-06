/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.drawable.IconCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;

/**
 * Base dialog for media output UI
 */
public abstract class MediaOutputBaseDialog extends SystemUIDialog implements
        MediaOutputController.Callback, Window.Callback {

    private static final String TAG = "MediaOutputDialog";
    private static final String EMPTY_TITLE = " ";

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());
    private final RecyclerView.LayoutManager mLayoutManager;

    final Context mContext;
    final MediaOutputController mMediaOutputController;

    @VisibleForTesting
    View mDialogView;
    private TextView mHeaderTitle;
    private TextView mHeaderSubtitle;
    private RecyclerView mDevicesRecyclerView;
    private LinearLayout mDeviceListLayout;
    private Button mDoneButton;
    private Button mStopButton;
    private int mListMaxHeight;

    protected ImageView mHeaderIcon;

    MediaOutputBaseAdapter mAdapter;

    private final ViewTreeObserver.OnGlobalLayoutListener mDeviceListLayoutListener = () -> {
        // Set max height for list
        if (mDeviceListLayout.getHeight() > mListMaxHeight) {
            ViewGroup.LayoutParams params = mDeviceListLayout.getLayoutParams();
            params.height = mListMaxHeight;
            mDeviceListLayout.setLayoutParams(params);
        }
    };

    public MediaOutputBaseDialog(Context context, MediaOutputController mediaOutputController,
            SystemUIDialogManager dialogManager) {
        super(context, dialogManager);

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mMediaOutputController = mediaOutputController;
        mLayoutManager = new LinearLayoutManager(mContext);
        mListMaxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_max_height);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.media_output_dialog, null);
        final Window window = getWindow();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.CENTER;
        // Config insets to make sure the layout is above the navigation bar
        lp.setFitInsetsTypes(statusBars() | navigationBars());
        lp.setFitInsetsSides(WindowInsets.Side.all());
        lp.setFitInsetsIgnoringVisibility(true);
        window.setAttributes(lp);
        window.setContentView(mDialogView);
        // Sets window to a blank string to avoid talkback announce app label first when pop up,
        // which doesn't make sense.
        window.setTitle(EMPTY_TITLE);

        mHeaderTitle = mDialogView.requireViewById(R.id.header_title);
        mHeaderSubtitle = mDialogView.requireViewById(R.id.header_subtitle);
        mHeaderIcon = mDialogView.requireViewById(R.id.header_icon);
        mDevicesRecyclerView = mDialogView.requireViewById(R.id.list_result);
        mDeviceListLayout = mDialogView.requireViewById(R.id.device_list);
        mDoneButton = mDialogView.requireViewById(R.id.done);
        mStopButton = mDialogView.requireViewById(R.id.stop);

        mDeviceListLayout.getViewTreeObserver().addOnGlobalLayoutListener(
                mDeviceListLayoutListener);
        // Init device list
        mDevicesRecyclerView.setLayoutManager(mLayoutManager);
        mDevicesRecyclerView.setAdapter(mAdapter);
        // Init header icon
        mHeaderIcon.setOnClickListener(v -> onHeaderIconClick());
        // Init bottom buttons
        mDoneButton.setOnClickListener(v -> dismiss());
        mStopButton.setOnClickListener(v -> {
            mMediaOutputController.releaseSession();
            dismiss();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        mMediaOutputController.start(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        mMediaOutputController.stop();
    }

    void refresh() {
        refresh(false);
    }

    void refresh(boolean deviceSetChanged) {
        // Update header icon
        final int iconRes = getHeaderIconRes();
        final IconCompat iconCompat = getHeaderIcon();
        if (iconRes != 0) {
            mHeaderIcon.setVisibility(View.VISIBLE);
            mHeaderIcon.setImageResource(iconRes);
        } else if (iconCompat != null) {
            mHeaderIcon.setVisibility(View.VISIBLE);
            mHeaderIcon.setImageIcon(iconCompat.toIcon(mContext));
        } else {
            mHeaderIcon.setVisibility(View.GONE);
        }
        // Update title and subtitle
        mHeaderTitle.setText(getHeaderText());
        final CharSequence subTitle = getHeaderSubtitle();
        if (TextUtils.isEmpty(subTitle)) {
            mHeaderSubtitle.setVisibility(View.GONE);
            mHeaderTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        } else {
            mHeaderSubtitle.setVisibility(View.VISIBLE);
            mHeaderSubtitle.setText(subTitle);
            mHeaderTitle.setGravity(Gravity.NO_GRAVITY);
        }
        if (!mAdapter.isDragging() && !mAdapter.isAnimating()) {
            int currentActivePosition = mAdapter.getCurrentActivePosition();
            if (!deviceSetChanged && currentActivePosition >= 0
                    && currentActivePosition < mAdapter.getItemCount()) {
                mAdapter.notifyItemChanged(currentActivePosition);
            } else {
                mAdapter.notifyDataSetChanged();
            }
        }
        // Show when remote media session is available
        mStopButton.setVisibility(getStopButtonVisibility());
    }

    abstract int getHeaderIconRes();

    abstract IconCompat getHeaderIcon();

    abstract int getHeaderIconSize();

    abstract CharSequence getHeaderText();

    abstract CharSequence getHeaderSubtitle();

    abstract int getStopButtonVisibility();

    @Override
    public void onMediaChanged() {
        mMainThreadHandler.post(() -> refresh());
    }

    @Override
    public void onMediaStoppedOrPaused() {
        if (isShowing()) {
            dismiss();
        }
    }

    @Override
    public void onRouteChanged() {
        mMainThreadHandler.post(() -> refresh());
    }

    @Override
    public void onDeviceListChanged() {
        mMainThreadHandler.post(() -> refresh(true));
    }

    @Override
    public void dismissDialog() {
        dismiss();
    }

    void onHeaderIconClick() {
    }

    View getDialogView() {
        return mDialogView;
    }
}
