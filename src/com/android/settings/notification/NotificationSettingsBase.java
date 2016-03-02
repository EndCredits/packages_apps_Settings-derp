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
 * limitations under the License.
 */

package com.android.settings.notification;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.applications.AppInfoBase;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedPreference;
import com.android.settingslib.RestrictedSwitchPreference;
import com.android.settings.applications.LayoutPreference;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService.Ranking;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.Preference;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.util.ArrayList;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

abstract public class NotificationSettingsBase extends SettingsPreferenceFragment {
    private static final String TAG = "NotifiSettingsBase";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String TUNER_SETTING = "show_importance_slider";

    protected static final String KEY_BYPASS_DND = "bypass_dnd";
    protected static final String KEY_VISIBILITY_OVERRIDE = "visibility_override";
    protected static final String KEY_IMPORTANCE = "importance";
    protected static final String KEY_IMPORTANCE_TITLE = "importance_title";
    protected static final String KEY_IMPORTANCE_RESET = "importance_reset_button";
    protected static final String KEY_BLOCK = "block";
    protected static final String KEY_SILENT = "silent";

    protected PackageManager mPm;
    protected final NotificationBackend mBackend = new NotificationBackend();
    protected Context mContext;
    protected boolean mCreated;
    protected int mUid;
    protected int mUserId;
    protected String mPkg;
    protected PackageInfo mPkgInfo;
    protected ImportanceSeekBarPreference mImportance;
    protected RestrictedPreference mImportanceTitle;
    protected LayoutPreference mImportanceReset;
    protected RestrictedSwitchPreference mPriority;
    protected DropDownPreference mVisibilityOverride;
    protected RestrictedSwitchPreference mBlock;
    protected RestrictedSwitchPreference mSilent;
    protected EnforcedAdmin mSuspendedAppsAdmin;
    protected boolean mShowSlider = false;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (DEBUG) Log.d(TAG, "onActivityCreated mCreated=" + mCreated);
        if (mCreated) {
            Log.w(TAG, "onActivityCreated: ignoring duplicate call");
            return;
        }
        mCreated = true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getActivity();
        Intent intent = getActivity().getIntent();
        Bundle args = getArguments();
        if (DEBUG) Log.d(TAG, "onCreate getIntent()=" + intent);
        if (intent == null && args == null) {
            Log.w(TAG, "No intent");
            toastAndFinish();
            return;
        }

        mPm = getPackageManager();

        mPkg = args != null && args.containsKey(AppInfoBase.ARG_PACKAGE_NAME)
                ? args.getString(AppInfoBase.ARG_PACKAGE_NAME)
                : intent.getStringExtra(Settings.EXTRA_APP_PACKAGE);
        mUid = args != null && args.containsKey(AppInfoBase.ARG_PACKAGE_UID)
                ? args.getInt(AppInfoBase.ARG_PACKAGE_UID)
                : intent.getIntExtra(Settings.EXTRA_APP_UID, -1);
        if (mUid == -1 || TextUtils.isEmpty(mPkg)) {
            Log.w(TAG, "Missing extras: " + Settings.EXTRA_APP_PACKAGE + " was " + mPkg + ", "
                    + Settings.EXTRA_APP_UID + " was " + mUid);
            toastAndFinish();
            return;
        }
        mUserId = UserHandle.getUserId(mUid);

        if (DEBUG) Log.d(TAG, "Load details for pkg=" + mPkg + " uid=" + mUid);
        mPkgInfo = findPackageInfo(mPkg, mUid);
        if (mPkgInfo == null) {
            Log.w(TAG, "Failed to find package info: " + Settings.EXTRA_APP_PACKAGE + " was " + mPkg
                    + ", " + Settings.EXTRA_APP_UID + " was " + mUid);
            toastAndFinish();
            return;
        }

        mSuspendedAppsAdmin = RestrictedLockUtils.checkIfApplicationIsSuspended(
                mContext, mPkg, mUserId);
        mShowSlider = Settings.Secure.getInt(getContentResolver(), TUNER_SETTING, 0) == 1;
    }

    @Override
    public void onResume() {
        super.onResume();
        if ((mUid != -1 && getPackageManager().getPackagesForUid(mUid) == null)) {
            // App isn't around anymore, must have been removed.
            finish();
            return;
        }
        mSuspendedAppsAdmin = RestrictedLockUtils.checkIfApplicationIsSuspended(
                mContext, mPkg, mUserId);
        if (mImportance != null) {
            mImportance.setDisabledByAdmin(mSuspendedAppsAdmin);
        }
        if (mPriority != null) {
            mPriority.setDisabledByAdmin(mSuspendedAppsAdmin);
        }
        if (mImportanceTitle != null) {
            mImportanceTitle.setDisabledByAdmin(mSuspendedAppsAdmin);
        }
        if (mBlock != null) {
            mBlock.setDisabledByAdmin(mSuspendedAppsAdmin);
        }
        if (mSilent != null) {
            mSilent.setDisabledByAdmin(mSuspendedAppsAdmin);
        }
    }

    protected void setupImportancePrefs(boolean isSystemApp, int importance, boolean banned) {
        if (mShowSlider) {
            setVisible(mBlock, false);
            setVisible(mSilent, false);
            mImportance.setDisabledByAdmin(mSuspendedAppsAdmin);
            mImportanceTitle.setDisabledByAdmin(mSuspendedAppsAdmin);
            if (importance == Ranking.IMPORTANCE_UNSPECIFIED) {
                mImportance.setVisible(false);
                mImportanceReset.setVisible(false);
                mImportanceTitle.setOnPreferenceClickListener(showEditableImportance);
            } else {
                mImportanceTitle.setOnPreferenceClickListener(null);
            }

            mImportanceTitle.setSummary(getProgressSummary(importance));
            mImportance.setSystemApp(isSystemApp);
            mImportance.setMinimumProgress(
                    isSystemApp ? Ranking.IMPORTANCE_MIN : Ranking.IMPORTANCE_NONE);
            mImportance.setMax(Ranking.IMPORTANCE_MAX);
            mImportance.setProgress(importance);
            mImportance.setCallback(new ImportanceSeekBarPreference.Callback() {
                @Override
                public void onImportanceChanged(int progress) {
                    mBackend.setImportance(mPkg, mUid, progress);
                    mImportanceTitle.setSummary(getProgressSummary(progress));
                    updateDependents(progress);
                }
            });

            Button button = (Button) mImportanceReset.findViewById(R.id.left_button);
            button.setText(R.string.importance_reset);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mSuspendedAppsAdmin != null) {
                        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(
                                getActivity(), mSuspendedAppsAdmin);
                        return;
                    }

                    mBackend.setImportance(mPkg, mUid, Ranking.IMPORTANCE_UNSPECIFIED);
                    mImportanceReset.setVisible(false);
                    mImportance.setVisible(false);
                    mImportanceTitle.setOnPreferenceClickListener(showEditableImportance);
                    mImportanceTitle.setSummary(getProgressSummary(Ranking.IMPORTANCE_UNSPECIFIED));
                    updateDependents(Ranking.IMPORTANCE_UNSPECIFIED);
                }
            });
            mImportanceReset.findViewById(R.id.right_button).setVisibility(View.INVISIBLE);
        } else {
            setVisible(mImportance, false);
            setVisible(mImportanceReset, false);
            setVisible(mImportanceTitle, false);
            boolean blocked = importance == Ranking.IMPORTANCE_NONE || banned;
            mBlock.setChecked(blocked);
            mBlock.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final boolean blocked = (Boolean) newValue;
                    final int importance =
                            blocked ? Ranking.IMPORTANCE_NONE :Ranking.IMPORTANCE_UNSPECIFIED;
                    mBackend.setImportance(mPkgInfo.packageName, mUid, importance);
                    updateDependents(importance);
                    return true;
                }
            });

            mSilent.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final boolean silenced = (Boolean) newValue;
                    final int importance =
                            silenced ? Ranking.IMPORTANCE_LOW : Ranking.IMPORTANCE_UNSPECIFIED;
                    mBackend.setImportance(mPkgInfo.packageName, mUid, importance);
                    updateDependents(importance);
                    return true;
                }
            });
            updateDependents(banned ? Ranking.IMPORTANCE_NONE : importance);
        }
    }

    private String getProgressSummary(int progress) {
        switch (progress) {
            case Ranking.IMPORTANCE_NONE:
                return mContext.getString(R.string.notification_importance_blocked);
            case Ranking.IMPORTANCE_MIN:
                return mContext.getString(R.string.notification_importance_min);
            case Ranking.IMPORTANCE_LOW:
                return mContext.getString(R.string.notification_importance_low);
            case Ranking.IMPORTANCE_DEFAULT:
                return mContext.getString(R.string.notification_importance_default);
            case Ranking.IMPORTANCE_HIGH:
                return mContext.getString(R.string.notification_importance_high);
            case Ranking.IMPORTANCE_MAX:
                return mContext.getString(R.string.notification_importance_max);
            case Ranking.IMPORTANCE_UNSPECIFIED:
                return mContext.getString(R.string.notification_importance_none);
            default:
                return "";
        }
    }

    protected void setupPriorityPref(boolean priority) {
        mPriority.setDisabledByAdmin(mSuspendedAppsAdmin);
        mPriority.setChecked(priority);
        mPriority.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final boolean bypassZenMode = (Boolean) newValue;
                return mBackend.setBypassZenMode(mPkgInfo.packageName, mUid, bypassZenMode);
            }
        });
    }

    protected void setupVisOverridePref(int sensitive) {
        ArrayList<CharSequence> entries = new ArrayList<>();
        ArrayList<CharSequence> values = new ArrayList<>();

        if (getLockscreenNotificationsEnabled() && getLockscreenAllowPrivateNotifications()) {
            entries.add(getString(R.string.lock_screen_notifications_summary_show));
            values.add(Integer.toString(Ranking.VISIBILITY_NO_OVERRIDE));
        }

        entries.add(getString(R.string.lock_screen_notifications_summary_hide));
        values.add(Integer.toString(Notification.VISIBILITY_PRIVATE));
        entries.add(getString(R.string.lock_screen_notifications_summary_disable));
        values.add(Integer.toString(Notification.VISIBILITY_SECRET));
        mVisibilityOverride.setEntries(entries.toArray(new CharSequence[entries.size()]));
        mVisibilityOverride.setEntryValues(values.toArray(new CharSequence[values.size()]));

        if (sensitive == Ranking.VISIBILITY_NO_OVERRIDE) {
            mVisibilityOverride.setValue(Integer.toString(getGlobalVisibility()));
        } else {
            mVisibilityOverride.setValue(Integer.toString(sensitive));
        }
        mVisibilityOverride.setSummary("%s");

        mVisibilityOverride.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                int sensitive = Integer.parseInt((String) newValue);
                if (sensitive == getGlobalVisibility()) {
                    sensitive = Ranking.VISIBILITY_NO_OVERRIDE;
                }
                mBackend.setVisibilityOverride(mPkgInfo.packageName, mUid, sensitive);
                return true;
            }
        });
    }

    private int getGlobalVisibility() {
        int globalVis = Ranking.VISIBILITY_NO_OVERRIDE;
        if (!getLockscreenNotificationsEnabled()) {
            globalVis = Notification.VISIBILITY_SECRET;
        } else if (!getLockscreenAllowPrivateNotifications()) {
            globalVis = Notification.VISIBILITY_PRIVATE;
        }
        return globalVis;
    }

    protected boolean getLockscreenNotificationsEnabled() {
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0) != 0;
    }

    protected boolean getLockscreenAllowPrivateNotifications() {
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0) != 0;
    }

    abstract void updateDependents(int progress);

    protected void setVisible(Preference p, boolean visible) {
        final boolean isVisible = getPreferenceScreen().findPreference(p.getKey()) != null;
        if (isVisible == visible) return;
        if (visible) {
            getPreferenceScreen().addPreference(p);
        } else {
            getPreferenceScreen().removePreference(p);
        }
    }

    protected void toastAndFinish() {
        Toast.makeText(mContext, R.string.app_not_found_dlg_text, Toast.LENGTH_SHORT).show();
        getActivity().finish();
    }

    private PackageInfo findPackageInfo(String pkg, int uid) {
        final String[] packages = mPm.getPackagesForUid(uid);
        if (packages != null && pkg != null) {
            final int N = packages.length;
            for (int i = 0; i < N; i++) {
                final String p = packages[i];
                if (pkg.equals(p)) {
                    try {
                        return mPm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                    } catch (NameNotFoundException e) {
                        Log.w(TAG, "Failed to load package " + pkg, e);
                    }
                }
            }
        }
        return null;
    }

    private Preference.OnPreferenceClickListener showEditableImportance =
            new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    mBackend.setImportance(mPkg, mUid, Ranking.IMPORTANCE_DEFAULT);
                    mImportance.setProgress(Ranking.IMPORTANCE_DEFAULT);
                    mImportanceTitle.setSummary(getProgressSummary(Ranking.IMPORTANCE_DEFAULT));
                    mImportance.setVisible(true);
                    mImportanceReset.setVisible(true);
                    mImportanceTitle.setOnPreferenceClickListener(null);
                    return true;
                }
            };
}
