package app.revanced.integrations.music.sponsorblock.requests;

import static app.revanced.integrations.music.requests.Route.Method.GET;

import app.revanced.integrations.music.requests.Route;

class SBRoutes {
    static final Route GET_SEGMENTS = new Route(GET, "/api/skipSegments?videoID={video_id}&categories={categories}");

    private SBRoutes() {
    }
}