/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

public class DefaultDialerCache {
    private static final String LOG_TAG = "DefaultDialerCache";
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("DDC.oR");
            try {
                String packageName;
                if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) {
                    packageName = intent.getData().getSchemeSpecificPart();
                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    packageName = intent.getData().getSchemeSpecificPart();
                } else if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    packageName = null;
                } else {
                    return;
                }

                synchronized (mLock) {
                    refreshCachesForUsersWithPackage(packageName);
                }

            } finally {
                Log.endSession();
            }
        }
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mDefaultDialerObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            Log.startSession("DDC.oC");
            try {
                // We don't get the user ID of the user that changed here, so we'll have to
                // refresh all of the users.
                synchronized (mLock) {
                    refreshCachesForUsersWithPackage(null);
                }
            } finally {
                Log.endSession();
            }
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }
    };

    private final Context mContext;
    private final TelecomServiceImpl.DefaultDialerManagerAdapter mDefaultDialerManagerAdapter;
    private final TelecomSystem.SyncRoot mLock;
    private final String mSystemDialerName;
    private SparseArray<String> mCurrentDefaultDialerPerUser = new SparseArray<>();

    public DefaultDialerCache(Context context,
            TelecomServiceImpl.DefaultDialerManagerAdapter defaultDialerManagerAdapter,
            TelecomSystem.SyncRoot lock) {
        mContext = context;
        mDefaultDialerManagerAdapter = defaultDialerManagerAdapter;
        mLock = lock;
        mSystemDialerName = mContext.getResources().getString(R.string.ui_default_package);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addDataScheme("package");
        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, intentFilter, null, null);

        Uri defaultDialerSetting =
                Settings.Secure.getUriFor(Settings.Secure.DIALER_DEFAULT_APPLICATION);
        context.getContentResolver()
                .registerContentObserver(defaultDialerSetting, false, mDefaultDialerObserver,
                        UserHandle.USER_ALL);
    }

    public String getDefaultDialerApplication(int userId) {
        if (userId == UserHandle.USER_CURRENT) {
            userId = ActivityManager.getCurrentUser();
        }

        if (userId < 0) {
            Log.w(LOG_TAG, "Attempting to get default dialer for a meta-user %d", userId);
            return null;
        }

        synchronized (mLock) {
            String defaultDialer = mCurrentDefaultDialerPerUser.get(userId);
            if (defaultDialer != null) {
                return defaultDialer;
            }
        }
        return refreshCacheForUser(userId);
    }

    public String getDefaultDialerApplication() {
        return getDefaultDialerApplication(mContext.getUserId());
    }

    public boolean isDefaultOrSystemDialer(Context context, String packageName) {
        String defaultDialer = getDefaultDialerApplication(context.getUserId());
        return Objects.equals(packageName, defaultDialer)
                || Objects.equals(packageName, mSystemDialerName);
    }

    private String refreshCacheForUser(int userId) {
        String currentDefaultDialer =
                mDefaultDialerManagerAdapter.getDefaultDialerApplication(mContext, userId);
        synchronized (mLock) {
            mCurrentDefaultDialerPerUser.put(userId, currentDefaultDialer);
        }
        return currentDefaultDialer;
    }

    /**
     * Refreshes the cache for users that currently have packageName as their cached default dialer.
     * If packageName is null, refresh all caches.
     * @param packageName Name of the affected package.
     */
    private void refreshCachesForUsersWithPackage(String packageName) {
        for (int i = 0; i < mCurrentDefaultDialerPerUser.size(); i++) {
            int userId = mCurrentDefaultDialerPerUser.keyAt(i);
            if (packageName == null ||
                    Objects.equals(packageName, mCurrentDefaultDialerPerUser.get(userId))) {
                String newDefaultDialer = refreshCacheForUser(userId);
                Log.i(LOG_TAG, "Refreshing default dialer for user %d: now %s",
                        userId, newDefaultDialer);
            }
        }
    }

    /**
     * registerContentObserver is really hard to mock out, so here is a getter method for the
     * content observer for testing instead.
     * @return The content observer
     */
    @VisibleForTesting
    public ContentObserver getContentObserver() {
        return mDefaultDialerObserver;
    }
}