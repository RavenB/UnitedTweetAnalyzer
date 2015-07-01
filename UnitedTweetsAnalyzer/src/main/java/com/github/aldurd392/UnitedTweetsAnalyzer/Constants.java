package com.github.aldurd392.UnitedTweetsAnalyzer;

import org.geotools.geometry.Envelope2D;

/**
 * This class contains the constants we need.
 *
 * Specifically, the bounding box we use to bias the stream.
 */
class Constants {
    private final static double southWestLong = -125.1088;
    private final static double southWestLat = 23.2193;

    private final static double northEastLong = -66.595;
    private final static double northEastLat = 47.4416;

    /**
     * The bounding box containing the USA.
     */
    public final static double[][] boundingBox = {
            {southWestLong, southWestLat}, {northEastLong, northEastLat}
    };

    /**
     * A bounding box containing the whole world.
     * Useful to retrieve only tweets with an attached location.
     */
    public final static double[][] worldWideBox = {
            {-180, -90}, {180, 90}
    };

    /**
     * The same bounding box as en EnvelopeBox,
     * used by the JTS system.
     */
    public final static Envelope2D envelopeBox = new Envelope2D();
    static {
        envelopeBox.include(northEastLong, northEastLat);
        envelopeBox.include(southWestLong, southWestLat);
    }

    /**
     * This intent will let us add the user ID and build a URL to the user profile.
     */
    public final static String twitter_user_intent = "=HYPERLINK(\"https://twitter.com/intent/user?user_id=%d\")";

    /**
     * Limit the number of unlabeled instances we classify.
     */
    public final static int classification_limit = 200;
}
