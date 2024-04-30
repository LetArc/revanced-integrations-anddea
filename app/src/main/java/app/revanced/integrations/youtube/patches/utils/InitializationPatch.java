package app.revanced.integrations.youtube.patches.utils;

import static app.revanced.integrations.youtube.settings.SettingsUtils.showRestartDialog;
import static app.revanced.integrations.youtube.utils.ReVancedUtils.runOnMainThreadDelayed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import app.revanced.integrations.youtube.settings.SettingsEnum;
import app.revanced.integrations.youtube.utils.ReVancedHelper;

@SuppressWarnings("unused")
public class InitializationPatch {

    /**
     * The new layout is not loaded when the app is first installed.
     * (Also reproduced on unPatched YouTube)
     * <p>
     * Side effects when new layout is not loaded:
     * - 8X zoom not working in fullscreen
     * <p>
     * To fix this, show the reboot dialog when the app is installed for the first time.
     * <p>
     * The version of the current integrations is saved to YouTube's SharedPreferences to identify if the app was first installed.
     */
    public static void initializeReVancedSettings(@NonNull Context context) {
        ReVancedHelper.setPlayerFlyoutPanelAdditionalSettings();
        if (SettingsEnum.INITIALIZED.getBoolean())
            return;

        // show dialog
        if (!(context instanceof Activity mActivity))
            return;

        runOnMainThreadDelayed(() -> showRestartDialog(mActivity, "revanced_restart_first_run", 500), 500);
        runOnMainThreadDelayed(() ->
                {
                    // set initialize value
                    SettingsEnum.INITIALIZED.saveValue(true);

                    // set save playback speed default value
                    SettingsEnum.ENABLE_SAVE_PLAYBACK_SPEED.saveValue(PatchStatus.DefaultPlaybackSpeed());
                }, 1000
        );
    }

    public static void setDeviceInformation(@NonNull Context context) {
        ReVancedHelper.setPackageName(context);
        ReVancedHelper.setApplicationLabel(context);
        ReVancedHelper.setIsTablet(context);
        ReVancedHelper.setVersionName(context);
    }
}