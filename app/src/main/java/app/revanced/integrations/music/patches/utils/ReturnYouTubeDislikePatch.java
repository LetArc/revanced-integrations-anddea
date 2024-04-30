package app.revanced.integrations.music.patches.utils;

import static app.revanced.integrations.music.returnyoutubedislike.ReturnYouTubeDislike.Vote;

import android.text.Spanned;

import androidx.annotation.Nullable;

import java.util.Objects;

import app.revanced.integrations.music.returnyoutubedislike.ReturnYouTubeDislike;
import app.revanced.integrations.music.returnyoutubedislike.requests.ReturnYouTubeDislikeApi;
import app.revanced.integrations.music.settings.SettingsEnum;
import app.revanced.integrations.music.utils.LogHelper;
import app.revanced.integrations.music.utils.ReVancedUtils;

/**
 * Handles all interaction of UI patch components.
 * <p>
 * Does not handle creating dislike spans or anything to do with {@link ReturnYouTubeDislikeApi}.
 */
@SuppressWarnings("unused")
public class ReturnYouTubeDislikePatch {
    @Nullable
    private static String currentVideoId;

    /**
     * Injection point
     * <p>
     * Called when a Shorts dislike Spannable is created
     */
    public static Spanned onComponentCreated(Spanned like) {
        return ReturnYouTubeDislike.onComponentCreated(like);
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(@Nullable String videoId) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) {
                return;
            }
            if (videoId == null || videoId.isEmpty()) {
                return;
            }
            if (Objects.equals(currentVideoId, videoId)) {
                return;
            }
            if (ReVancedUtils.isNetworkNotConnected()) {
                LogHelper.printDebug(() -> "Network not connected, ignoring video");
                return;
            }

            currentVideoId = videoId;
            ReturnYouTubeDislike.newVideoLoaded(videoId);
        } catch (Exception ex) {
            LogHelper.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the user likes or dislikes.
     *
     * @param vote int that matches {@link ReturnYouTubeDislike.Vote#value}
     */
    public static void sendVote(int vote) {
        try {
            if (!SettingsEnum.RYD_ENABLED.getBoolean()) {
                return;
            }

            for (Vote v : Vote.values()) {
                if (v.value == vote) {
                    ReturnYouTubeDislike.sendVote(v);
                    return;
                }
            }
            LogHelper.printException(() -> "Unknown vote type: " + vote);
        } catch (Exception ex) {
            LogHelper.printException(() -> "sendVote failure", ex);
        }
    }
}
