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

package android.app.compat.gms;

import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.gmscompat.GmsInfo;

/**
 * This class provides helpers for GMS ("Google Mobile Services") compatibility.
 * It allows the following apps to work as regular, unprivileged user apps:
 *     - GSF ("Google Services Framework")
 *     - GMS Core ("Google Play services")
 *     - Google Play Store
 *     - Apps that depend on the above
 *
 * All GMS compatibility hooks should call methods on GmsCompat. Hooks that are more complicated
 * than returning a simple constant value should also be implemented in GmsHooks to reduce
 * maintenance overhead.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class GmsCompat {
    private static final String TAG = "GmsCompat/Core";

    private static boolean isGmsCompatEnabled;
    private static boolean isGmsCore;
    private static boolean isPlayStore;

    // Static only
    private GmsCompat() { }

    public static boolean isEnabled() {
        return isGmsCompatEnabled;
    }

    /** @hide */
    public static boolean isGmsCore() {
        return isGmsCore;
    }

    /** @hide */
    public static boolean isPlayStore() {
        return isPlayStore;
    }

    private static Context appContext;

    /** @hide */
    public static Context appContext() {
        return appContext;
    }

    /**
     * Called before Application.onCreate()
     *
     * @hide
     */
    public static void maybeEnable(Application app) {
        if (!Process.isApplicationUid(Process.myUid())) {
            return;
        }
        appContext = app;
        ApplicationInfo appInfo = app.getApplicationInfo();

        if (isGmsApp(appInfo)) {
            isGmsCompatEnabled = true;
            String pkg = appInfo.packageName;
            isGmsCore = GmsInfo.PACKAGE_GMS_CORE.equals(pkg);
            isPlayStore = GmsInfo.PACKAGE_PLAY_STORE.equals(pkg);
        }
    }

    private static boolean validateCerts(Signature[] signatures) {
        for (Signature signature : signatures) {
            if (signature.toCharsString().equals(GmsInfo.SIGNING_CERT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether the given app is unprivileged and part of the Google Play Services family.
     * @hide
     */
    public static boolean isGmsApp(String packageName, Signature[] signatures,
                                   Signature[] pastSignatures, boolean isPrivileged, String sharedUserId) {
        // Privileged GMS doesn't need any compatibility changes
        if (isPrivileged) {
            return false;
        }

        if (GmsInfo.PACKAGE_GMS_CORE.equals(packageName) || GmsInfo.PACKAGE_GSF.equals(packageName)) {
            // Check the shared user ID to avoid affecting microG with a spoofed signature. This is a
            // reliable indicator because apps can't change their shared user ID after shipping with it.
            if (!GmsInfo.SHARED_USER_ID.equals(sharedUserId)) {
                return false;
            }
        } else if (!GmsInfo.PACKAGE_PLAY_STORE.equals(packageName)) {
            return false;
        }

        // Validate signature to avoid affecting apps like microG and Gcam Services Provider.
        // This isn't actually necessary from a security perspective because GMS doesn't get any
        // special privileges, but it's a failsafe to avoid unintentional compatibility issues.
        boolean validCert = validateCerts(signatures);

        if (!validCert && pastSignatures != null) {
            validCert = validateCerts(pastSignatures);
        }
        return validCert;
    }

    /** @hide */
    public static boolean isGmsApp(ApplicationInfo app) {
        String packageName = app.packageName;
        if (!(GmsInfo.PACKAGE_GMS_CORE.equals(packageName)
            || GmsInfo.PACKAGE_PLAY_STORE.equals(packageName)
            || GmsInfo.PACKAGE_GSF.equals(packageName))) {
            return false;
        }
        int userId = UserHandle.getUserId(app.uid);
        IPackageManager pm = ActivityThread.getPackageManager();

        // Fetch PackageInfo to get signing certificates
        PackageInfo pkg;
        long token = Binder.clearCallingIdentity();
        try {
            pkg = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return isGmsApp(pkg);
    }

    private static boolean isGmsApp(PackageInfo pkg) {
        ApplicationInfo app = pkg.applicationInfo;
        if (app == null) {
            return false;
        }
        SigningInfo si = pkg.signingInfo;
        return isGmsApp(app.packageName,
            si.getApkContentsSigners(), si.getSigningCertificateHistory(),
            app.isPrivilegedApp(), pkg.sharedUserId);
    }

    private static volatile boolean cachedIsClientOfGmsCore;

    /** @hide */
    public static boolean isClientOfGmsCore(Context ctx) {
        if (cachedIsClientOfGmsCore) {
            return true;
        }
        if (!Process.isApplicationUid(Process.myUid()) || isGmsCore()) {
            return false;
        }
        try {
            PackageInfo pkgInfo = ctx.getPackageManager()
                .getPackageInfo(GmsInfo.PACKAGE_GMS_CORE, PackageManager.GET_SIGNING_CERTIFICATES);
            if (isGmsApp(pkgInfo)) {
                cachedIsClientOfGmsCore = true;
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Ignored: normal - GMS Core not installed
        } catch (Exception e) {
            Log.e(TAG, "Failed to get GMS Core package info", e);
        }

        return false;
    }

    /** @hide */
    public static boolean hasPermission(String perm) {
        return appContext().checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
}
