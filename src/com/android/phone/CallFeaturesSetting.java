/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.phone;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SlimSeekBarPreference;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.common.util.SettingsUtil;
import com.android.phone.settings.AccountSelectionPreference;
import com.android.phone.settings.PhoneAccountSettingsFragment;
import com.android.phone.settings.VoicemailSettingsActivity;
import com.android.phone.settings.fdn.FdnSetting;
import com.android.services.telephony.sip.SipUtil;

import org.cyanogenmod.internal.util.PackageManagerUtils;

import java.lang.String;
import java.util.ArrayList;
import java.util.List;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "Call settings" hierarchy available from the Phone
 * app; the settings here let you control various features related to phone calls (including
 * voicemail settings, the "Respond via SMS" feature, and others.)  It's used only on
 * voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "Mobile network settings" screen under the main Settings app,
 * See {@link MobileNetworkSettings}.
 *
 * @see com.android.phone.MobileNetworkSettings
 */
public class CallFeaturesSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    // TODO: Consider moving these strings to strings.xml, so that they are not duplicated here and
    // in the layout files. These strings need to be treated carefully; if the setting is
    // persistent, they are used as the key to store shared preferences and the name should not be
    // changed unless the settings are also migrated.
    private static final String VOICEMAIL_SETTING_SCREEN_PREF_KEY = "button_voicemail_category_key";
    private static final String BUTTON_FDN_KEY   = "button_fdn_key";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";

    private static final String PROX_AUTO_SPEAKER  = "prox_auto_speaker";
    private static final String PROX_AUTO_SPEAKER_DELAY  = "prox_auto_speaker_delay";
    private static final String PROX_AUTO_SPEAKER_INCALL_ONLY  = "prox_auto_speaker_incall_only";

    private static final String BUTTON_GSM_UMTS_OPTIONS = "button_gsm_more_expand_key";
    private static final String BUTTON_CDMA_OPTIONS = "button_cdma_more_expand_key";

    private static final String PHONE_ACCOUNT_SETTINGS_KEY =
            "phone_account_settings_preference_screen";

    private static final String ENABLE_VIDEO_CALLING_KEY = "button_enable_video_calling";
    private static final String BUTTON_PROXIMITY_KEY   = "button_proximity_key";

    private static final String FLIP_ACTION_KEY = "flip_action";

    private Phone mPhone;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private TelecomManager mTelecomManager;

    private SwitchPreference mButtonAutoRetry;
    private PreferenceScreen mVoicemailSettingsScreen;
    private SwitchPreference mButtonProximity;

    private SwitchPreference mEnableVideoCalling;
    
    // Call recording format
    private static final String CALL_RECORDING_FORMAT = "call_recording_format";
    private ListPreference mCallRecordingFormat;
    
    private ListPreference mFlipAction;

    private SwitchPreference mProxSpeaker;
    private SlimSeekBarPreference mProxSpeakerDelay;
    private SwitchPreference mProxSpeakerIncallOnly;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonAutoRetry) {
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        } else if (preference == mButtonProximity) {
            int checked = mButtonProximity.isChecked() ? 1 : 0;
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.IN_CALL_PROXIMITY_SENSOR, checked);
            if (checked == 1) {
                mButtonProximity.setSummary(R.string.proximity_on_summary);
            } else {
                mButtonProximity.setSummary(R.string.proximity_off_summary);
            }
            return true;
        } else if (preference == mProxSpeaker) {
            Settings.System.putInt(getContentResolver(), Settings.System.PROXIMITY_AUTO_SPEAKER,
                    mProxSpeaker.isChecked() ? 1 : 0);
        } else if (preference == mProxSpeakerIncallOnly) {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.PROXIMITY_AUTO_SPEAKER_INCALL_ONLY,
                    mProxSpeakerIncallOnly.isChecked() ? 1 : 0);
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
		
	ContentResolver cr = mPhone.getContext().getContentResolver();
		
        if (DBG) log("onPreferenceChange: \"" + preference + "\" changed to \"" + objValue + "\"");

        if (preference == mEnableVideoCalling) {
            if (ImsManager.isEnhanced4gLteModeSettingEnabledByUser(mPhone.getContext())) {
                PhoneGlobals.getInstance().phoneMgr.enableVideoCalling((boolean) objValue);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                DialogInterface.OnClickListener networkSettingsClickListener =
                        new Dialog.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                startActivity(new Intent(mPhone.getContext(),
                                        com.android.phone.MobileNetworkSettings.class));
                            }
                        };
                builder.setMessage(getResources().getString(
                                R.string.enable_video_calling_dialog_msg))
                        .setNeutralButton(getResources().getString(
                                R.string.enable_video_calling_dialog_settings),
                                networkSettingsClickListener)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return false;
            }
        } else if (preference == mCallRecordingFormat) {
            int value = Integer.valueOf((String) objValue);
            int index = mCallRecordingFormat.findIndexOfValue((String) objValue);
            Settings.System.putInt(cr, Settings.System.CALL_RECORDING_FORMAT, value);
            mCallRecordingFormat.setSummary(mCallRecordingFormat.getEntries()[index]);  
        } else if (preference == mFlipAction) {
            int index = mFlipAction.findIndexOfValue((String) objValue);
            Settings.System.putInt(getContentResolver(),
                Settings.System.CALL_FLIP_ACTION_KEY, index);
            updateFlipActionSummary(index);
        } else if (preference == mProxSpeakerDelay) {
            int delay = Integer.valueOf((String) objValue);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.PROXIMITY_AUTO_SPEAKER_DELAY, delay);
        }

        // Always let the preference setting proceed.
        return true;
    }

    private void updateFlipActionSummary(int value) {
        if (mFlipAction != null) {
            String[] summaries = getResources().getStringArray(R.array.flip_action_summary_entries);
            mFlipAction.setSummary(getString(R.string.flip_action_summary, summaries[value]));
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("onCreate: Intent is " + getIntent());

        PreferenceScreen preferenceScreen = getPreferenceScreen();

        // Make sure we are running as an admin user.
        if (!UserManager.get(this).isAdminUser()) {
            Toast.makeText(this, R.string.call_settings_admin_user_only,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        mTelecomManager = TelecomManager.from(this);

        if (mButtonProximity != null) {
            if (getResources().getBoolean(R.bool.config_proximity_enable)) {
                mButtonProximity.setOnPreferenceChangeListener(this);
            } else {
                getPreferenceScreen().removePreference(mButtonProximity);
                mButtonProximity = null;
            }
        }
    }

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DBG) log("PhoneStateListener onCallStateChanged: state is " + state);
            if (mEnableVideoCalling != null) {
                mEnableVideoCalling.setEnabled(state == TelephonyManager.CALL_STATE_IDLE);
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            preferenceScreen.removeAll();
        }

        addPreferencesFromResource(R.xml.call_feature_setting);

        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        Preference phoneAccountSettingsPreference = findPreference(PHONE_ACCOUNT_SETTINGS_KEY);
        if (telephonyManager.isMultiSimEnabled() || !SipUtil.isVoipSupported(mPhone.getContext())) {
            getPreferenceScreen().removePreference(phoneAccountSettingsPreference);
        }

        PreferenceScreen prefSet = getPreferenceScreen();
        mVoicemailSettingsScreen =
                (PreferenceScreen) findPreference(VOICEMAIL_SETTING_SCREEN_PREF_KEY);
        mVoicemailSettingsScreen.setIntent(mSubscriptionInfoHelper.getIntent(
                VoicemailSettingsActivity.class));

        mButtonAutoRetry = (SwitchPreference) findPreference(BUTTON_RETRY_KEY);

        mEnableVideoCalling = (SwitchPreference) findPreference(ENABLE_VIDEO_CALLING_KEY);

        mFlipAction = (ListPreference) findPreference(FLIP_ACTION_KEY);

        mProxSpeaker = (SwitchPreference) findPreference(PROX_AUTO_SPEAKER);
        mProxSpeakerIncallOnly = (SwitchPreference) findPreference(PROX_AUTO_SPEAKER_INCALL_ONLY);
        mProxSpeakerDelay = (SlimSeekBarPreference) findPreference(PROX_AUTO_SPEAKER_DELAY);
        if (mProxSpeakerDelay != null) {
            mProxSpeakerDelay.setDefault(100);
            mProxSpeakerDelay.isMilliseconds(true);
            mProxSpeakerDelay.setInterval(1);
            mProxSpeakerDelay.minimumValue(100);
            mProxSpeakerDelay.multiplyValue(100);
            mProxSpeakerDelay.setOnPreferenceChangeListener(this);
        }

        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());

        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_AUTO_RETRY_ENABLED_BOOL)) {
            mButtonAutoRetry.setOnPreferenceChangeListener(this);
            int autoretry = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        } else {
            prefSet.removePreference(mButtonAutoRetry);
            mButtonAutoRetry = null;
        }

        mButtonProximity = (SwitchPreference) findPreference(BUTTON_PROXIMITY_KEY);

        Preference cdmaOptions = prefSet.findPreference(BUTTON_CDMA_OPTIONS);
        Preference gsmOptions = prefSet.findPreference(BUTTON_GSM_UMTS_OPTIONS);
        Preference fdnButton = prefSet.findPreference(BUTTON_FDN_KEY);
        fdnButton.setIntent(mSubscriptionInfoHelper.getIntent(FdnSetting.class));

        final ContentResolver contentResolver = getContentResolver();

        if (mProxSpeaker != null) {
            PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
            if (pm.isWakeLockLevelSupported(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
                    && getResources().getBoolean(R.bool.config_enabled_speakerprox)) {
                mProxSpeaker.setChecked(Settings.System.getInt(contentResolver,
                        Settings.System.PROXIMITY_AUTO_SPEAKER, 0) == 1);
                if (mProxSpeakerIncallOnly != null) {
                    mProxSpeakerIncallOnly.setChecked(Settings.System.getInt(contentResolver,
                            Settings.System.PROXIMITY_AUTO_SPEAKER_INCALL_ONLY, 0) == 1);
                }
                if (mProxSpeakerDelay != null) {
                    final int proxDelay = Settings.System.getInt(getContentResolver(),
                            Settings.System.PROXIMITY_AUTO_SPEAKER_DELAY, 100);
                    // minimum 100 is 1 interval of the 100 multiplier
                    mProxSpeakerDelay.setDefault((proxDelay / 100) - 1);
                }
            } else {
                prefSet.removePreference(mProxSpeaker);
                mProxSpeaker = null;
                if (mProxSpeakerIncallOnly != null) {
                    prefSet.removePreference(mProxSpeakerIncallOnly);
                    mProxSpeakerIncallOnly = null;
                }
                if (mProxSpeakerDelay != null) {
                    prefSet.removePreference(mProxSpeakerDelay);
                    mProxSpeakerDelay = null;
                }
            }
        }

        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_PHONE_BOOL)) {
            cdmaOptions.setIntent(mSubscriptionInfoHelper.getIntent(CdmaCallOptions.class));
            gsmOptions.setIntent(mSubscriptionInfoHelper.getIntent(GsmUmtsCallOptions.class));
        } else {
            prefSet.removePreference(cdmaOptions);
            prefSet.removePreference(gsmOptions);

            int phoneType = mPhone.getPhoneType();
            if (carrierConfig.getBoolean(CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)) {
                prefSet.removePreference(fdnButton);
            } else {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    prefSet.removePreference(fdnButton);
                    addPreferencesFromResource(R.xml.cdma_call_privacy);

                    if (carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
                        CdmaVoicePrivacySwitchPreference prefPri = (CdmaVoicePrivacySwitchPreference)
                                prefSet.findPreference("button_voice_privacy_key");
                        if (prefPri != null) {
                             prefSet.removePreference(prefPri);
                        }
                    }

                    if (carrierConfig.getBoolean(
                                CarrierConfigManager.KEY_CDMA_CW_CF_ENABLED_BOOL)
                                && CdmaCallOptions.isCdmaCallWaitingActivityPresent(mPhone.getContext())) {
                        Log.d(LOG_TAG, "Enabled CW CF");
                        PreferenceScreen prefCW = (PreferenceScreen)
                                prefSet.findPreference("button_cw_key");
                        if (prefCW != null) {
                            prefCW.setOnPreferenceClickListener(
                                    new Preference.OnPreferenceClickListener() {
                                        @Override
                                        public boolean onPreferenceClick(Preference preference) {
                                            Intent intent = new Intent(CdmaCallOptions.CALL_WAITING_INTENT);
                                            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mPhone.getSubId());
                                            startActivity(intent);
                                            return true;
                                        }
                                    });
                        }
                        PreferenceScreen prefCF = (PreferenceScreen)
                                prefSet.findPreference("button_cf_expand_key");
                        if (prefCF != null) {
                            prefCF.setOnPreferenceClickListener(
                                    new Preference.OnPreferenceClickListener() {
                                        @Override
                                        public boolean onPreferenceClick(Preference preference) {
                                            Intent intent = new Intent(CdmaCallOptions.CALL_FORWARD_INTENT);
                                            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mPhone.getSubId());
                                            startActivity(intent);
                                            return true;
                                        }
                                    });
                        }
                    } else {
                        Log.d(LOG_TAG, "Disabled CW CF");
                        PreferenceScreen prefCW = (PreferenceScreen)
                                prefSet.findPreference("button_cw_key");
                        if (prefCW != null) {
                            prefSet.removePreference(prefCW);
                        }
                        PreferenceScreen prefCF = (PreferenceScreen)
                                prefSet.findPreference("button_cf_expand_key");
                        if (prefCF != null) {
                            prefSet.removePreference(prefCF);
                        }
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {

                    if (carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_ADDITIONAL_CALL_SETTING_BOOL)) {
                        addPreferencesFromResource(R.xml.gsm_umts_call_options);
                        GsmUmtsCallOptions.init(prefSet, mSubscriptionInfoHelper);
                    }
                } else {
                    throw new IllegalStateException("Unexpected phone type: " + phoneType);
                }
            }
        }

        if (ImsManager.isVtEnabledByPlatform(mPhone.getContext()) &&
                ImsManager.isVtProvisionedOnDevice(mPhone.getContext()) &&
                mPhone.mDcTracker.isDataEnabled(true)) {
            boolean currentValue =
                    ImsManager.isEnhanced4gLteModeSettingEnabledByUser(mPhone.getContext())
                    ? PhoneGlobals.getInstance().phoneMgr.isVideoCallingEnabled(
                            getOpPackageName()) : false;
            mEnableVideoCalling.setChecked(currentValue);
            mEnableVideoCalling.setOnPreferenceChangeListener(this);
        } else {
            prefSet.removePreference(mEnableVideoCalling);
        }

        if (ImsManager.isVolteEnabledByPlatform(this) &&
                !carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            /* tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE); */
        }

        if (!PackageManagerUtils.isAppInstalled(this, "com.qualcomm.qti.ims")) {
            prefSet.removePreference(findPreference("ims_settings_key"));
        }

        if (!PackageManagerUtils.isAppInstalled(this, "com.qualcomm.qti.phonefeature")) {
            prefSet.removePreference(findPreference("button_callbarring_expand_key"));
        }

        Preference wifiCallingSettings = findPreference(
                getResources().getString(R.string.wifi_calling_settings_key));

        final PhoneAccountHandle simCallManager = mTelecomManager.getSimCallManager();
        if (simCallManager != null) {
            Intent intent = PhoneAccountSettingsFragment.buildPhoneAccountConfigureIntent(
                    this, simCallManager);
            if (intent != null) {
                PackageManager pm = mPhone.getContext().getPackageManager();
                List<ResolveInfo> resolutions = pm.queryIntentActivities(intent, 0);
                if (!resolutions.isEmpty()) {
                    wifiCallingSettings.setTitle(resolutions.get(0).loadLabel(pm));
                    wifiCallingSettings.setSummary(null);
                    wifiCallingSettings.setIntent(intent);
                } else {
                    prefSet.removePreference(wifiCallingSettings);
                }
            } else {
                prefSet.removePreference(wifiCallingSettings);
            }
        } else if (!ImsManager.isWfcEnabledByPlatform(mPhone.getContext()) ||
                !ImsManager.isWfcProvisionedOnDevice(mPhone.getContext())) {
            prefSet.removePreference(wifiCallingSettings);
        } else {
            int resId = com.android.internal.R.string.wifi_calling_off_summary;
            if (ImsManager.isWfcEnabledByUser(mPhone.getContext())) {
                int wfcMode = ImsManager.getWfcMode(mPhone.getContext());
                switch (wfcMode) {
                    case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                        resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                        break;
                    case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_cellular_preferred_summary;
                        break;
                    case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                        break;
                    default:
                        if (DBG) log("Unexpected WFC mode value: " + wfcMode);
                }
            }
            wifiCallingSettings.setSummary(resId);
        }     
        // Call recording Format
        mCallRecordingFormat = (ListPreference) findPreference(CALL_RECORDING_FORMAT);
        if (mCallRecordingFormat != null) {
            if (isCallRecordingEnabled()) {
                int format = Settings.System.getInt(getContentResolver(),
                        Settings.System.CALL_RECORDING_FORMAT, 0);
                mCallRecordingFormat.setValue(String.valueOf(format));
                mCallRecordingFormat.setSummary(mCallRecordingFormat.getEntry());
                mCallRecordingFormat.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mCallRecordingFormat);
            }
        }

        if (mButtonProximity != null) {
            boolean checked = Settings.System.getInt(getContentResolver(),
                    Settings.System.IN_CALL_PROXIMITY_SENSOR, 1) == 1;
            mButtonProximity.setChecked(checked);
            mButtonProximity.setSummary(checked ? R.string.proximity_on_summary
                    : R.string.proximity_off_summary);
        }

        if (mFlipAction != null) {
            mFlipAction.setOnPreferenceChangeListener(this);
        }
        if (mFlipAction != null) {
            int flipAction = Settings.System.getInt(getContentResolver(),
                    Settings.System.CALL_FLIP_ACTION_KEY, 2);
            mFlipAction.setValue(String.valueOf(flipAction));
            updateFlipActionSummary(flipAction);
        }
    }

    @Override
    protected void onNewIntent(Intent newIntent) {
        setIntent(newIntent);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

     private boolean isCallRecordingEnabled() {
         boolean recordingEnabled = false;
         try {
             PackageManager pm = getPackageManager();
             String phonePackage = "com.android.dialer";
             Resources res;
             res = pm.getResourcesForApplication(phonePackage);
             int booleanID =
                     res.getIdentifier(phonePackage + ":bool/call_recording_enabled", null, null);
             recordingEnabled = res.getBoolean(booleanID);
         } catch (NameNotFoundException ex) {
             ex.printStackTrace();
         } catch (NotFoundException ex) {
             ex.printStackTrace();
         }
         return recordingEnabled;
     }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(
            Activity activity, SubscriptionInfoHelper subscriptionInfoHelper) {
        Intent intent = subscriptionInfoHelper.getIntent(CallFeaturesSetting.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }
}
