package app.revanced.integrations.reddit.patches;

import android.view.View;
import android.view.ViewGroup;

import app.revanced.integrations.reddit.settings.SettingsEnum;
import app.revanced.integrations.reddit.utils.LogHelper;

@SuppressWarnings("unused")
public final class NavigationButtonsPatch {

    public static void hideNavigationButtons(ViewGroup viewGroup) {
        try {
            for (NavigationButton button : NavigationButton.values())
                if (button.enabled && viewGroup.getChildCount() > button.index)
                    viewGroup.getChildAt(button.index).setVisibility(View.GONE);
        } catch (Exception exception) {
            LogHelper.printException(() -> "Failed to remove button view", exception);
        }
    }

    private enum NavigationButton {
        CHAT(SettingsEnum.HIDE_CHAT_BUTTON.getBoolean(), 3),
        CREATE(SettingsEnum.HIDE_CREATE_BUTTON.getBoolean(), 2),
        DISCOVER(SettingsEnum.HIDE_DISCOVER_BUTTON.getBoolean(), 1);
        private final boolean enabled;
        private final int index;

        NavigationButton(final boolean enabled, final int index) {
            this.enabled = enabled;
            this.index = index;
        }
    }
}
