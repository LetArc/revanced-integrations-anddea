package app.revanced.integrations.youtube.patches.misc;

import app.revanced.integrations.youtube.settings.SettingsEnum;
import app.revanced.integrations.youtube.utils.LogHelper;

@SuppressWarnings("unused")
public class LayoutOverridePatch {
    /**
     * Context is overridden when trying to play a YouTube video from the Google Play Store,
     * Which is speculated to affect LayoutOverridePatch
     */
    public static boolean enableTabletLayout() {
        try {
            return SettingsEnum.ENABLE_TABLET_LAYOUT.getBoolean();
        } catch (Exception ex) {
            LogHelper.printException(() -> "enableTabletLayout failed", ex);
        }
        return false;
    }

    public static int getLayoutOverride(int original) {
        try {
            return SettingsEnum.ENABLE_PHONE_LAYOUT.getBoolean() ? 480 : original;
        } catch (Exception ex) {
            LogHelper.printException(() -> "getLayoutOverride failed", ex);
        }
        return original;
    }
}
