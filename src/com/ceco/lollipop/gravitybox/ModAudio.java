/*
 * Copyright (C) 2013 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.lollipop.gravitybox;

import com.ceco.lollipop.gravitybox.ledcontrol.QuietHours;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.view.Surface;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModAudio {
    private static final String TAG = "GB:ModAudio";
    private static final String CLASS_AUDIO_SERVICE = "android.media.AudioService";
    private static final boolean DEBUG = false;

    private static final int STREAM_MUSIC = 3;
    private static final int DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS = 5000;

    private static boolean mVolForceMusicControl;
    private static boolean mSwapVolumeKeys;
    private static HandleChangeVolume mHandleChangeVolume;
    private static XSharedPreferences mQhPrefs;
    private static boolean mVolumesLinked;
    private static Object mAudioService;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) log("Broadcast received: " + intent.toString());
            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_VOL_FORCE_MUSIC_CONTROL_CHANGED)) {
                mVolForceMusicControl = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_VOL_FORCE_MUSIC_CONTROL, false);
                if (DEBUG) log("Force music volume control set to: " + mVolForceMusicControl);
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_VOL_SWAP_KEYS_CHANGED)) {
                mSwapVolumeKeys = intent.getBooleanExtra(GravityBoxSettings.EXTRA_VOL_SWAP_KEYS, false);
                if (DEBUG) log("Swap volume keys set to: " + mSwapVolumeKeys);
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_LINK_VOLUMES_CHANGED)) {
                mVolumesLinked = intent.getBooleanExtra(GravityBoxSettings.EXTRA_LINKED, true);
                if (DEBUG) log("mVolumesLinked set to: " + mVolumesLinked);
                updateStreamVolumeAlias();
            }
        }
    };

    public static void initZygote(final XSharedPreferences prefs) {
        try {
            final Class<?> classAudioService = XposedHelpers.findClass(CLASS_AUDIO_SERVICE, null);

            mQhPrefs = new XSharedPreferences(GravityBox.PACKAGE_NAME, "quiet_hours");
            mQhPrefs.makeWorldReadable();

            mSwapVolumeKeys = prefs.getBoolean(GravityBoxSettings.PREF_KEY_VOL_SWAP_KEYS, false);
            mVolumesLinked = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LINK_VOLUMES, true);

            XposedBridge.hookAllConstructors(classAudioService, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS, false)) {
                        int[] maxStreamVolume = (int[])
                                XposedHelpers.getStaticObjectField(classAudioService, "MAX_STREAM_VOLUME");
                        maxStreamVolume[STREAM_MUSIC] = prefs.getInt(
                                GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS_VALUE, 30);
                        if (DEBUG) log("MAX_STREAM_VOLUME for music stream set to " + maxStreamVolume[STREAM_MUSIC]);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mAudioService = param.thisObject;
                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    if (context != null) {
                        mHandleChangeVolume = new HandleChangeVolume(context);
                        XposedHelpers.findAndHookMethod(classAudioService, "adjustMasterVolume", 
                                int.class, int.class, String.class, mHandleChangeVolume);
                        XposedHelpers.findAndHookMethod(classAudioService, "adjustSuggestedStreamVolume", 
                                int.class, int.class, int.class, String.class, mHandleChangeVolume);

                        IntentFilter intentFilter = new IntentFilter();
                        intentFilter.addAction(GravityBoxSettings.ACTION_PREF_VOL_FORCE_MUSIC_CONTROL_CHANGED);
                        intentFilter.addAction(GravityBoxSettings.ACTION_PREF_VOL_SWAP_KEYS_CHANGED);
                        intentFilter.addAction(GravityBoxSettings.ACTION_PREF_LINK_VOLUMES_CHANGED);
                        context.registerReceiver(mBroadcastReceiver, intentFilter);
                        if (DEBUG) log("AudioService constructed. Broadcast receiver registered");
                    }
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS, false)) {
                        XposedHelpers.setIntField(param.thisObject, "mSafeMediaVolumeIndex", 150);
                        if (DEBUG) log("Default mSafeMediaVolumeIndex set to 150");
                    }
                }
            });

            if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_MUSIC_VOLUME_STEPS, false)) {
                XposedHelpers.findAndHookMethod(classAudioService, "onConfigureSafeVolume",
                        boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setObjectExtra("gbCurSafeMediaVolIndex",
                                XposedHelpers.getIntField(param.thisObject, "mSafeMediaVolumeIndex"));
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if ((Integer) param.getObjectExtra("gbCurSafeMediaVolIndex") !=
                                XposedHelpers.getIntField(param.thisObject, "mSafeMediaVolumeIndex")) {
                            int safeMediaVolIndex = XposedHelpers.getIntField(param.thisObject, "mSafeMediaVolumeIndex") * 2;
                            XposedHelpers.setIntField(param.thisObject, "mSafeMediaVolumeIndex", safeMediaVolIndex);
                            if (DEBUG) log("onConfigureSafeVolume: mSafeMediaVolumeIndex set to " + safeMediaVolIndex);
                        }
                    }
                });
            }

            mVolForceMusicControl = prefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_VOL_FORCE_MUSIC_CONTROL, false);
            XposedHelpers.findAndHookMethod(classAudioService, "getActiveStreamType",
                    int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (mVolForceMusicControl &&
                            (Integer) param.args[0] == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                        final boolean isInComm = (Boolean) XposedHelpers.callMethod(
                                param.thisObject, "isInCommunication");
                        final boolean activeMusic = (Boolean) XposedHelpers.callMethod(
                                param.thisObject, "isAfMusicActiveRecently",
                                DEFAULT_STREAM_TYPE_OVERRIDE_DELAY_MS);

                        if (!isInComm && !activeMusic) {
                            param.setResult(STREAM_MUSIC);
                            if (DEBUG) log("getActiveStreamType: Forcing music stream");
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(AudioManager.class, "querySoundEffectsEnabled",
                    int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    mQhPrefs.reload();
                    QuietHours qh = new QuietHours(mQhPrefs);
                    if (qh.isSystemSoundMuted(QuietHours.SystemSound.TOUCH)) {
                        param.setResult(false);
                    }
                } 
            });

            XposedHelpers.findAndHookMethod(classAudioService, "updateStreamVolumeAlias",
                    boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if ((Boolean) XposedHelpers.callMethod(param.thisObject, "isPlatformVoice")) {
                        int[] streamVolumeAlias = (int[]) XposedHelpers.getObjectField(param.thisObject, "mStreamVolumeAlias");
                        streamVolumeAlias[AudioManager.STREAM_NOTIFICATION] = mVolumesLinked ? 
                                AudioManager.STREAM_RING : AudioManager.STREAM_NOTIFICATION;
                        XposedHelpers.setObjectField(param.thisObject, "mStreamVolumeAlias", streamVolumeAlias);
                        if (DEBUG) log("AudioService mStreamVolumeAlias updated, STREAM_NOTIFICATION set to: " + 
                                streamVolumeAlias[AudioManager.STREAM_NOTIFICATION]);
                    }
                }
            });
        } catch(Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void updateStreamVolumeAlias() {
        if (mAudioService == null) {
            if (DEBUG) log("updateStreamVolumeAlias: AudioService is null");
            return;
        }

        try {
            XposedHelpers.callMethod(mAudioService, "updateStreamVolumeAlias", true);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static class HandleChangeVolume extends XC_MethodHook {
        private WindowManager mWm;

        public HandleChangeVolume(Context context) {
            mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (mSwapVolumeKeys) {
                try {
                    if ((Integer) param.args[0] != 0) {
                        if (DEBUG) log("Original direction = " + param.args[0]);
                        int orientation = getDirectionFromOrientation();
                        param.args[0] = orientation * (Integer) param.args[0];
                        if (DEBUG) log("Modified direction = " + param.args[0]);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        }

        private int getDirectionFromOrientation() {
            int rotation = mWm.getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_0:
                    if (DEBUG) log("Rotation = 0");
                    return 1;
                case Surface.ROTATION_90:
                    if (DEBUG) log("Rotation = 90");
                    return -1;
                case Surface.ROTATION_180:
                    if (DEBUG) log("Rotation = 180");
                    return -1;
                case Surface.ROTATION_270:
                default:
                    if (DEBUG) log("Rotation = 270");
                    return 1;
            }
        }
    }
}
