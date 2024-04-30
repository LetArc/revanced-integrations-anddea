package app.revanced.integrations.youtube.patches.video;

import static app.revanced.integrations.youtube.utils.StringRef.str;
import static app.revanced.integrations.youtube.utils.VideoHelpers.getCurrentQuality;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import app.revanced.integrations.youtube.settings.SettingsEnum;
import app.revanced.integrations.youtube.utils.LogHelper;
import app.revanced.integrations.youtube.utils.ReVancedUtils;

@SuppressWarnings("unused")
public class VideoQualityPatch {
    private static final SettingsEnum mobileQualitySetting = SettingsEnum.DEFAULT_VIDEO_QUALITY_MOBILE;
    private static final SettingsEnum wifiQualitySetting = SettingsEnum.DEFAULT_VIDEO_QUALITY_WIFI;

    private static Boolean useCustomQuality = false;
    @Nullable
    private static int customQuality;

    /**
     * Injection point.
     *
     * @param ignoredVideoId id of the current video
     */
    public static void initialize(@NonNull String ignoredVideoId) {
        useCustomQuality = false;
        customQuality = 0;
    }

    /**
     * The available qualities of the current video in human readable form: [1080, 720, 480]
     */
    @Nullable
    private static List<Integer> videoQualities;

    private static void changeDefaultQuality(final int defaultQuality) {
        // When user manual change the quality, force this quality until video ended.
        overrideCustomQuality(defaultQuality);

        if (!SettingsEnum.ENABLE_SAVE_VIDEO_QUALITY.getBoolean())
            return;

        final ReVancedUtils.NetworkType networkType = ReVancedUtils.getNetworkType();

        switch (networkType) {
            case NONE -> {
                ReVancedUtils.showToastShort(str("revanced_save_video_quality_none"));
                return;
            }
            case MOBILE -> mobileQualitySetting.saveValue(defaultQuality);
            default -> wifiQualitySetting.saveValue(defaultQuality);
        }

        ReVancedUtils.showToastShort(str("revanced_save_video_quality_" + networkType.getName(), defaultQuality + "p"));
    }

    public static void overrideCustomQuality(final int defaultQuality) {
        customQuality = defaultQuality;
        useCustomQuality = true;
        overrideQuality(defaultQuality);
    }
    
    public static void overideDefaultVideoQuality() {
        final int preferredQuality =
                ReVancedUtils.getNetworkType() == ReVancedUtils.NetworkType.MOBILE
                        ? mobileQualitySetting.getInt()
                        : wifiQualitySetting.getInt();

        if (preferredQuality == -2)
            return;

        ReVancedUtils.runOnMainThreadDelayed(() -> 
            setVideoQuality((useCustomQuality) ? customQuality 
                                               : preferredQuality)
        , 300);
    }

    /**
     * Injection point.
     * 
     * The remaining code will be implemented by patch.
     */
    public static void overrideQuality(final int qualityValue) {
        LogHelper.printDebug(() -> "Quality changed to: " + qualityValue);
    }

    /**
     * Injection point.
     *
     * @param qualities Video qualities available, ordered from largest to smallest, with index 0 being the 'automatic' value of -2
     */
    public static void setVideoQualityList(Object[] qualities) {
        try {
            if (videoQualities == null || videoQualities.size() != qualities.length) {
                videoQualities = new ArrayList<>(qualities.length);
                for (Object streamQuality : qualities) {
                    for (Field field : streamQuality.getClass().getFields()) {
                        if (field.getType().isAssignableFrom(Integer.TYPE)
                                && field.getName().length() <= 2) {
                            videoQualities.add(field.getInt(streamQuality));
                        }
                    }
                }
                LogHelper.printDebug(() -> "videoQualities: " + videoQualities);
            }
        } catch (Exception ex) {
            LogHelper.printException(() -> "Failed to set quality list", ex);
        }
    }

    private static void setVideoQuality(int preferredQuality) {
        if (videoQualities != null) {
            int qualityToUse = videoQualities.get(0); // first element is automatic mode
            for (Integer quality : videoQualities) {
                if (quality <= preferredQuality && qualityToUse < quality) {
                    qualityToUse = quality;
                }
            }
            preferredQuality = qualityToUse;
        }
        overrideQuality(preferredQuality);
    }

    /**
     * Injection point. New quality menu.
     *
     * @param selectedQuality user selected quality
     */
    public static void userChangedQuality(final int selectedQuality) {
        ReVancedUtils.runOnMainThreadDelayed(() ->
                        changeDefaultQuality(getCurrentQuality(selectedQuality)),
                300
        );
    }

    /**
     * Injection point. Old quality menu.
     *
     * @param selectedQualityIndex user selected quality index
     */
    public static void userChangedQualityIndex(final int selectedQualityIndex) {
        if (videoQualities == null)
            return;

        changeDefaultQuality(videoQualities.get(selectedQualityIndex));
    }
}
