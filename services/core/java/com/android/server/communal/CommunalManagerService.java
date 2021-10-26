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

package com.android.server.communal;

import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;

import static com.android.server.wm.ActivityInterceptorCallback.COMMUNAL_MODE_ORDERED_ID;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.communal.ICommunalManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.Overridable;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LaunchAfterAuthenticationActivity;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System service for handling Communal Mode state.
 */
public final class CommunalManagerService extends SystemService {
    private static final String TAG = CommunalManagerService.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String DELIMITER = ",";
    private final Context mContext;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final KeyguardManager mKeyguardManager;
    private final AtomicBoolean mCommunalViewIsShowing = new AtomicBoolean(false);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<String> mEnabledApps = new HashSet<>();
    private final SettingsObserver mSettingsObserver;
    private final BinderService mBinderService;

    /**
     * This change id is used to annotate packages which are allowed to run in communal mode.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT = 200324021L;

    /**
     * This change id is used to annotate packages which can run in communal mode by default,
     * without requiring user opt-in.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long ALLOW_COMMUNAL_MODE_BY_DEFAULT = 203673428L;

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public Intent intercept(ActivityInterceptorInfo info) {
                    if (!shouldIntercept(info.aInfo)) {
                        return null;
                    }

                    final IIntentSender target = mAtmInternal.getIntentSender(
                            INTENT_SENDER_ACTIVITY,
                            info.callingPackage,
                            info.callingFeatureId,
                            info.callingUid,
                            info.userId,
                            /* token= */null,
                            /* resultWho= */ null,
                            /* requestCode= */ 0,
                            new Intent[]{info.intent},
                            new String[]{info.resolvedType},
                            PendingIntent.FLAG_IMMUTABLE,
                            /* bOptions= */ null);

                    return LaunchAfterAuthenticationActivity.createLaunchAfterAuthenticationIntent(
                            new IntentSender(target));

                }
            };

    public CommunalManagerService(Context context) {
        super(context);
        mContext = context;
        mSettingsObserver = new SettingsObserver();
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
        mBinderService = new BinderService();
    }

    @VisibleForTesting
    BinderService getBinderServiceInstance() {
        return mBinderService;
    }

    @VisibleForTesting
    void publishBinderServices() {
        publishBinderService(Context.COMMUNAL_MANAGER_SERVICE, mBinderService);
    }

    @Override
    public void onStart() {
        publishBinderServices();
        mAtmInternal.registerActivityStartInterceptor(COMMUNAL_MODE_ORDERED_ID,
                mActivityInterceptorCallback);

        updateSelectedApps();
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.COMMUNAL_MODE_PACKAGES), false, mSettingsObserver,
                UserHandle.USER_SYSTEM);
    }

    @VisibleForTesting
    void updateSelectedApps() {
        final String encodedApps = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.COMMUNAL_MODE_PACKAGES,
                UserHandle.USER_SYSTEM);

        mEnabledApps.clear();

        if (!TextUtils.isEmpty(encodedApps)) {
            mEnabledApps.addAll(Arrays.asList(encodedApps.split(DELIMITER)));
        }
    }

    private boolean isAppAllowed(ApplicationInfo appInfo) {
        if (isChangeEnabled(ALLOW_COMMUNAL_MODE_BY_DEFAULT, appInfo)) {
            return true;
        }

        if (appInfo.isSystemApp() || appInfo.isUpdatedSystemApp()) {
            if (DEBUG) Slog.d(TAG, "Allowlisted as system app: " + appInfo.packageName);
            return isAppEnabledByUser(appInfo);
        }

        return isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, appInfo)
                && isAppEnabledByUser(appInfo);
    }

    private boolean isAppEnabledByUser(ApplicationInfo appInfo) {
        return mEnabledApps.contains(appInfo.packageName);
    }

    private boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        return CompatChanges.isChangeEnabled(changeId, appInfo.packageName, UserHandle.SYSTEM);
    }

    private boolean shouldIntercept(ActivityInfo activityInfo) {
        if (!mCommunalViewIsShowing.get() || !mKeyguardManager.isKeyguardLocked()) return false;
        return !isAppAllowed(activityInfo.applicationInfo);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(mHandler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mContext.getMainExecutor().execute(CommunalManagerService.this::updateSelectedApps);
        }
    }

    private final class BinderService extends ICommunalManager.Stub {
        /**
         * Sets whether or not we are in communal mode.
         */
        @RequiresPermission(Manifest.permission.WRITE_COMMUNAL_STATE)
        @Override
        public void setCommunalViewShowing(boolean isShowing) {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_COMMUNAL_STATE,
                    Manifest.permission.WRITE_COMMUNAL_STATE
                            + "permission required to modify communal state.");
            mCommunalViewIsShowing.set(isShowing);
        }
    }
}
