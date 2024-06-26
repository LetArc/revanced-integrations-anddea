package app.revanced.integrations.youtube.swipecontrols.controller

import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import app.revanced.integrations.youtube.settings.SettingsEnum
import app.revanced.integrations.youtube.swipecontrols.misc.Rectangle
import app.revanced.integrations.youtube.swipecontrols.misc.applyDimension
import app.revanced.integrations.youtube.utils.ReVancedUtils
import app.revanced.integrations.youtube.utils.ResourceType
import app.revanced.integrations.youtube.utils.ResourceUtils
import app.revanced.integrations.youtube.utils.StringRef.str
import kotlin.math.min

/**
 * Y- Axis:
 * -------- 0
 *        ^
 * dead   | 40dp
 *        v
 * -------- yDeadTop
 *        ^
 * swipe  |
 *        v
 * -------- yDeadBtm
 *        ^
 * dead   | 80dp
 *        v
 * -------- screenHeight
 *
 * X- Axis:
 *  0    xBrigStart    xBrigEnd    xVolStart     xVolEnd   screenWidth
 *  |          |            |          |            |          |
 *  |   20dp   |    3/8     |    2/8   |    3/8     |   20dp   |
 *  | <------> |  <------>  | <------> |  <------>  | <------> |
 *  |   dead   | brightness |   dead   |   volume   |   dead   |
 *             | <--------------------------------> |
 *                              1/1
 */
@Suppress("PrivatePropertyName")
class SwipeZonesController(
    private val host: Activity,
    private val fallbackScreenRect: () -> Rectangle
) {
    /**
     * rect size for the overlay
     */
    private val MAXIMUM_OVERLAY_RECT_SIZE = 50

    private var overlayRectSize = SettingsEnum.SWIPE_OVERLAY_RECT.int
        set(value) {
            field = value
            validateOverlayRectSize()
        }

    init {
        validateOverlayRectSize()
    }

    /**
     * 20dp, in pixels
     */
    private val _20dp = 20.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP)

    /**
     * 40dp, in pixels
     */
    private val _40dp = 40.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP)

    /**
     * 80dp, in pixels
     */
    private val _80dp = 80.applyDimension(host, TypedValue.COMPLEX_UNIT_DIP)

    /**
     * id for R.id.player_view
     */
    private val playerViewId = ResourceUtils.identifier("player_view", ResourceType.ID, host)

    /**
     * current bounding rectangle of the player
     */
    private var playerRect: Rectangle? = null

    private fun validateOverlayRectSize(): Int {
        if (overlayRectSize <= 0) {
            throw IllegalArgumentException("Overlay rectangle size must be greater than 0.")
        }

        if (overlayRectSize > MAXIMUM_OVERLAY_RECT_SIZE) {
            ReVancedUtils.showToastLong(str("revanced_swipe_overlay_rect_warning", MAXIMUM_OVERLAY_RECT_SIZE.toString()))
            SettingsEnum.SWIPE_OVERLAY_RECT.resetToDefault()
            return SettingsEnum.SWIPE_OVERLAY_RECT.int // Return the default value
        }

        return overlayRectSize
    }

    /**
     * rectangle of the area that is effectively usable for swipe controls
     */
    private val effectiveSwipeRect: Rectangle
        get() {
            maybeAttachPlayerBoundsListener()
            val p = if (playerRect != null) playerRect!! else fallbackScreenRect()
            return Rectangle(
                p.x + _20dp,
                p.y + _40dp,
                p.width - _20dp,
                p.height - _20dp - _80dp
            )
        }

    /**
     * the rectangle of the volume control zone
     */
    val volume: Rectangle
        get() {
            val zoneWidth = effectiveSwipeRect.width * overlayRectSize / 100
            return Rectangle(
                effectiveSwipeRect.right - zoneWidth,
                effectiveSwipeRect.top,
                zoneWidth,
                effectiveSwipeRect.height
            )
        }

    /**
     * the rectangle of the screen brightness control zone
     */
    val brightness: Rectangle
        get() {
            val zoneWidth = effectiveSwipeRect.width * overlayRectSize / 100
            return Rectangle(
                effectiveSwipeRect.left,
                effectiveSwipeRect.top,
                zoneWidth,
                effectiveSwipeRect.height
            )
        }

    /**
     * try to attach a listener to the player_view and update the player rectangle.
     * once a listener is attached, this function does nothing
     */
    private fun maybeAttachPlayerBoundsListener() {
        if (playerRect != null) return
        host.findViewById<ViewGroup>(playerViewId)?.let {
            onPlayerViewLayout(it)
            it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                onPlayerViewLayout(it)
            }
        }
    }

    /**
     * update the player rectangle on player_view layout
     *
     * @param playerView the player view
     */
    private fun onPlayerViewLayout(playerView: ViewGroup) {
        playerView.getChildAt(0)?.let { playerSurface ->
            // the player surface is centered in the player view
            // figure out the width of the surface including the padding (same on the left and right side)
            // and use that width for the player rectangle size
            // this automatically excludes any engagement panel from the rect
            val playerWidthWithPadding = playerSurface.width + (playerSurface.x.toInt() * 2)
            playerRect = Rectangle(
                playerView.x.toInt(),
                playerView.y.toInt(),
                min(playerView.width, playerWidthWithPadding),
                playerView.height
            )
        }
    }
}