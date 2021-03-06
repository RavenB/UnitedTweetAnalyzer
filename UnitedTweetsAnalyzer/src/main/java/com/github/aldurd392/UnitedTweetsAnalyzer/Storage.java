package com.github.aldurd392.UnitedTweetsAnalyzer;

import com.vividsolutions.jts.geom.Coordinate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sqlite.SQLiteErrorCode;
import twitter4j.GeoLocation;
import twitter4j.Status;
import twitter4j.User;
import weka.core.stemmers.SnowballStemmer;

import java.io.File;
import java.sql.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Handle the interaction with the storage (an SQLite database).
 */
class Storage {
    private final static String JDBC_PREFIX = "jdbc:sqlite:";
    private final static String JDBC_DRIVER = "org.sqlite.JDBC";

    private final static String CLASSIFICATION_VIEW = "classification_view";

    /**
     * Load from the DB all the labeled instances.
     * i.e. users who have at least a tweet labeled
     * with an associated country.
     */
    public final static String TRAINING_QUERY = String.format(
            "SELECT %s.%s, %s.%s, %s.%s, %s.%s, %s.%s " +
                    "FROM %s, %s " +
                    "WHERE %s.%s = %s.%s",
            Storage.TABLE_USER, Storage.LANG,
            Storage.TABLE_USER, Storage.LOCATION,
            Storage.TABLE_USER, Storage.UTC_OFFSET,
            Storage.TABLE_USER, Storage.TIMEZONE,
            Storage.TABLE_TWEET, Storage.COUNTRY,
            Storage.TABLE_USER, Storage.TABLE_TWEET,
            Storage.TABLE_USER, Storage.ID, Storage.TABLE_TWEET, Storage.USER_ID);

    /**
     * Load from the DB both the training and the unlabeled instances.
     * Those instances will be used for the unsupervised
     * machine learning task.
     * <p>
     * We take a sample of the unlabeled data.
     * <p>
     * We need to load all those data because some classifiers need
     * to know the whole universe of nominal data.
     * In addition, we load the data with the ID, in order to
     * manually check the classification.
     * Nonetheless, we don't wanna use ID while classifying:
     * we thus filter the IDs away from the training data.
     */
    public final static String CLASSIFICATION_QUERY = "SELECT * FROM " + Storage.CLASSIFICATION_VIEW;

    public final static String ID = "ID";

    private final static String USERNAME = "USERNAME";
    public final static String LANG = "LANG";
    public final static String LOCATION = "LOCATION";
    public final static String UTC_OFFSET = "UTC_OFFSET";
    public final static String TIMEZONE = "TIMEZONE";

    private final static String LAT = "LAT";
    private final static String LON = "LON";
    private final static String USER_ID = "USER_ID";

    private final static String TABLE_USER = "USER";
    private final static String TABLE_TWEET = "TWEET";

    public final static String COUNTRY = "COUNTRY";  // This will also be the classifier class.

    private final static Logger logger = LogManager.getLogger(Storage.class.getSimpleName());

    /**
     * Stem the users' locations.
     * We use the default stemmer, set up for english.
     */
    private final static SnowballStemmer stemmer = new SnowballStemmer();

    /**
     * Compile the regexps we need to cache and optimize.
     */
    private final static Pattern re_letters = Pattern.compile("[^\\p{L}\\p{Z}]");
    private final static Pattern re_spaces = Pattern.compile("\\s+");

    /**
     * The {@link Geography} instances used to assign a country to each tweet.
     */
    private final Geography geography;

    /**
     * Keeps the connection to the DB.
     */
    private Connection c = null;

    /**
     * Avoid shutting down the DB while someone is writing it.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Create the storage.
     *
     * @param geography     a Geometry object to assign a country to stored tweets.
     * @param database_path the database path.
     */
    public Storage(Geography geography, String database_path) {
        assert (geography != null);
        assert (database_path != null);

        this.geography = geography;
        this.connect(database_path);
    }

    /**
     * Connect the Storage to the SQLite DB.
     *
     * @param dbPath the path to the DB.
     */
    private void connect(String dbPath) {
        /**
         * The connection creates the DB.
         * Before connecting check for existence.
         */
        boolean exists = this.checkDataBase(dbPath);

        try {
            /**
             * Class.forName() dynamically loads the class
             * in order to execute static blocks only once.
             */
            Class.forName(JDBC_DRIVER);
            this.c = DriverManager.getConnection(JDBC_PREFIX + dbPath);

            logger.debug("Database successfully opened.");
            if (!exists) {
                this.initDatabase();
                this.prepareClassificationView();
            }
        } catch (SQLException | ClassNotFoundException e) {
            logger.fatal("Error while connecting to / initializing the database.", e);
            System.exit(1);
        }
    }

    /**
     * Check if the DB (or any file) exists at db_path.
     *
     * @param db_path the path of the DB.
     * @return true is a file exists.
     */
    private boolean checkDataBase(String db_path) {
        return new File(db_path).exists();
    }

    /**
     * Create tables in the DB.
     * We'll create two different tables, respectively containing Users and Tweets.
     * <p>
     * The User's table contains (LOC):
     * - ID (PK) (We won't use it while learning)
     * - Username (We won't use it while learning)
     * - Lang
     * - Location
     * - UTC Offset
     * - Timezone
     * <p>
     * The Tweet's table contains:
     * - ID (PK)
     * - LAT / LON
     * - ID of the user (FK)
     * - Country
     *
     * @throws SQLException on table creation error.
     */
    private void initDatabase() throws SQLException {
        try (Statement stmt = this.c.createStatement()) {
            final String userTable = "CREATE TABLE " + TABLE_USER +
                    String.format(" (%s UNSIGNED BIG INT PRIMARY KEY NOT NULL," +
                                    " %s TEXT NOT NULL," +
                                    " %s VARCHAR(10)," +
                                    " %s VARCHAR(100)," +
                                    " %s INT," +
                                    " %s VARCHAR(50))",
                            ID, USERNAME, LANG, LOCATION, UTC_OFFSET, TIMEZONE);
            stmt.executeUpdate(userTable);

            final String tweetTable = "CREATE TABLE " + TABLE_TWEET +
                    String.format(" (%s UNSIGNED BIG INT PRIMARY KEY NOT NULL," +
                                    " %s FLOAT NOT NULL," +
                                    " %s FLOAT NOT NULL," +
                                    " %s VARCHAR(50)," +
                                    " %s UNSIGNED BIG INT," +
                                    " FOREIGN KEY(%s) REFERENCES %s(%s))",
                            ID, LAT, LON, COUNTRY, USER_ID, USER_ID, TABLE_USER, ID);
            stmt.executeUpdate(tweetTable);

            logger.debug("Tables successfully created.");
        }
    }

    /**
     * We're creating a View (a virtual table inside the database),
     * that we'll use to retrieve the union of
     * training instances and unlabeled data.
     * <p>
     * We need to do this because otherwise SQLite would
     * lose the column type.
     *
     * @throws SQLException in case of exception while executing the query.
     */
    private void prepareClassificationView() throws SQLException {
        try (Statement stmt = this.c.createStatement()) {
            final String classificationView = String.format(
                    "CREATE VIEW %s AS " +
                            "SELECT * " +
                            "FROM (SELECT " +
                            "%s.%s, " +
                            "%s.%s, %s.%s, " +
                            "%s.%s, %s.%s, " +
                            "NULL as %s " +
                            "FROM %s " +
                            "WHERE %s.%s not in " +
                            "(SELECT %s.%s FROM %s) " +
                            "ORDER BY RANDOM() LIMIT 0, %s) " +
                            "UNION " +
                            "SELECT %s.%s, %s.%s, %s.%s, " +
                            "%s.%s, %s.%s, " +
                            "%s.%s " +
                            "FROM %s, %s " +
                            "WHERE %s.%s = %s.%s",
                    Storage.CLASSIFICATION_VIEW,
                    Storage.TABLE_USER, Storage.ID,
                    Storage.TABLE_USER, Storage.LANG, Storage.TABLE_USER, Storage.LOCATION,
                    Storage.TABLE_USER, Storage.UTC_OFFSET, Storage.TABLE_USER, Storage.TIMEZONE,
                    Storage.COUNTRY,
                    Storage.TABLE_USER,
                    Storage.TABLE_USER, Storage.ID,
                    Storage.TABLE_TWEET, Storage.USER_ID, Storage.TABLE_TWEET,
                    Constants.classification_limit,
                    Storage.TABLE_USER, Storage.ID,
                    Storage.TABLE_USER, Storage.LANG,
                    Storage.TABLE_USER, Storage.LOCATION,
                    Storage.TABLE_USER, Storage.UTC_OFFSET,
                    Storage.TABLE_USER, Storage.TIMEZONE,
                    Storage.TABLE_TWEET, Storage.COUNTRY,
                    Storage.TABLE_USER, Storage.TABLE_TWEET,
                    Storage.TABLE_USER, Storage.ID, Storage.TABLE_TWEET, Storage.USER_ID
            );
            stmt.executeUpdate(classificationView);
        }
    }

    /**
     * We stem the user Location field.
     * It is a user-inserted string, that could differ from user to user.
     * It is not parseable and it won't probably help us during classification.
     * But its cost is low, so better try it.
     *
     * @param location string to be stemmed
     * @return a stemmed string
     */
    public static String stemLocation(String location) {
        if (location == null || location.length() == 0) {
            return null;
        }

        /* Remove characters that aren't letter in any language. */
        location = re_letters.matcher(location).replaceAll(" ");
        /* Replace multiple whitespaces with a single one. */
        location = re_spaces.matcher(location).replaceAll(" ");

        location = location.toLowerCase();
        location = location.trim();

        if (location.length() == 0) {
            return null;
        }

        final StringBuilder stringBuffer = new StringBuilder();
        for (String w : location.split(" ")) {
            stringBuffer.append(stemmer.stem(w));
            stringBuffer.append(" ");
        }

        return stringBuffer.toString().trim();
    }

    /**
     * Insert a user in the Storage.
     * Skip already existing users (no update).
     *
     * @param user the user to be inserted.
     * @throws SQLException on user insert error.
     */
    private void insertUser(User user) throws SQLException {
        if (user == null) {
            logger.error("Trying to add NULL user to the DB.");
            return;
        }

        /**
         * If the user already exists in the DB, we have nothing to do here.
         * Please note that, as a performance optimization,
         * we could skip this step and catch an eventual SQL exception regarding the user's PK.
         */
        /* Disabled due to performance optimization, we check the error code below.
        String select = String.format(
                "SELECT %s FROM %s WHERE %s = ?;",
                ID, TABLE_USER, ID
        );

        try (PreparedStatement stmt = this.c.prepareStatement(select)) {
            stmt.setLong(1, user.getId());

            if (stmt.executeQuery().next()) {
                logger.debug("User {} - {} already exists in DB.", user.getId(), user.getName());
                return;
            }
        } catch (SQLException e) {
            logger.error("Error while selecting user {}", user.getId(), e);
            throw e;
        }
        */

        final String insert = String.format(
                "INSERT INTO %s " +
                        "(%s, %s, %s, %s, %s, %s) " +
                        "VALUES (?, ?, ?, ?, ?, ?);",
                TABLE_USER,
                ID, USERNAME, LANG, LOCATION, UTC_OFFSET, TIMEZONE);
        try (PreparedStatement stmt = this.c.prepareStatement(insert)) {
            stmt.setLong(1, user.getId());
            stmt.setString(2, user.getName());
            stmt.setString(3, user.getLang());
            stmt.setString(4, stemLocation(user.getLocation()));

            if (user.getUtcOffset() == -1) {
                stmt.setNull(5, Types.INTEGER);
            } else {
                stmt.setInt(5, user.getUtcOffset());
            }

            stmt.setString(6, user.getTimeZone());

            stmt.executeUpdate();
        } catch (SQLException e) {
            /*
             * Having a constraint error is likely to indicate
             * that the user already exists in the DB,
             * and we're violating the constraint on the PK ID.
             */
            if (e.getErrorCode() == SQLiteErrorCode.SQLITE_CONSTRAINT.code) {
                logger.debug("User {} - {} already exists in DB.", user.getId(), user.getName());
            } else {
                logger.error("Error while inserting user {} {}", user.getId(), user.getName(), e);
                throw e;
            }
        }
    }

    /**
     * Insert a Tweet in the DB, after trying to localizing it in our geography.
     * Store the User who tweeted too.
     *
     * @param tweet the Twitter status containing the Tweet and the User's detail.
     */
    public void insertTweet(Status tweet) {
        this.lock.lock();

        try {
            try {
                this.insertUser(tweet.getUser());
            } catch (SQLException e) {
                logger.warn("Skipping tweet {} because of error while inserting user {}.",
                        tweet.getId(), tweet.getUser().getId()
                );
            }

            /**
             * The status could contain various geolocation information.
             * We honour at first the GPS coordinates.
             * Then the Place details.
             * In case of place we are given a bounding box of points and
             * we'll use the centroid of the constructed polygon while
             * assigning a country to the Tweet.
             *
             * We won't store in the DB Tweets that do not ship any location info.
             */
            GeoLocation geoLocation = tweet.getGeoLocation();
            if (geoLocation == null && tweet.getPlace() != null) {
                final int rows = tweet.getPlace().getBoundingBoxCoordinates().length;
                final int columns = tweet.getPlace().getBoundingBoxCoordinates()[rows - 1].length;

                final GeoLocation first = tweet.getPlace().getBoundingBoxCoordinates()[0][0];
                final GeoLocation last = tweet.getPlace().getBoundingBoxCoordinates()[rows - 1][columns - 1];

                geoLocation = Geography.midPoint(first, last);
            } else if (geoLocation == null) {
                return;
            }

            final String country = this.geography.query(
                    new Coordinate(geoLocation.getLongitude(), geoLocation.getLatitude())
            );
            assert country != null;

            if (country.equals(Geography.UNKNOWN_COUNTRY)) {
                logger.warn("Got a tweet whose country is {}: {} - ({}, {})",
                        Geography.UNKNOWN_COUNTRY,
                        tweet.getId(),
                        geoLocation.getLatitude(), geoLocation.getLongitude());
            }

            logger.debug("Tweet {}: {}", tweet.getId(), country);

            /**
             * If the Tweet already exists in the DB, we have nothing to do here.
             * Please note that, as a performance optimization,
             * we could skip this step and catch an eventual SQL exception regarding the Tweet's PK.
             */
            /* Disabled due to performance optimization, we check the error code below.
            String select = String.format("SELECT %s FROM %s WHERE %s = ?;",
                    ID, TABLE_TWEET, ID);
            try (PreparedStatement stmt = this.c.prepareStatement(select)) {
                stmt.setLong(1, tweet.getId());

                if (stmt.executeQuery().next()) {
                    logger.debug("Tweet {} already exists in DB.", tweet.getId());
                    return;
                }
            } catch (SQLException e) {
                logger.error("Error while selecting tweet {}", tweet.getId());
                logger.debug(e);

                return;
            }
            */

            /* Better use PreparedStatement,
             * avoid some Russians hackers to
             * SQL inject us! :)
             */
            final String insert = String.format(
                    "INSERT INTO %s (%s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, ?);",
                    TABLE_TWEET,
                    ID, LAT, LON, COUNTRY, USER_ID);

            try (PreparedStatement stmt = this.c.prepareStatement(insert)) {
                stmt.setLong(1, tweet.getId());
                stmt.setDouble(2, geoLocation.getLatitude());
                stmt.setDouble(3, geoLocation.getLongitude());
                stmt.setString(4, country);
                stmt.setLong(5, tweet.getUser().getId());

                stmt.executeUpdate();
            } catch (SQLException e) {
            /*
             * Having a constraint error is likely to indicate
             * that the Tweet already exists in the DB,
             * and we're violating the constraint on the PK ID.
             */
                if (e.getErrorCode() == SQLiteErrorCode.SQLITE_CONSTRAINT.code) {
                    logger.debug("Tweet {} already exists in DB.", tweet.getId());
                } else {
                    logger.error("Error while inserting tweet {}", tweet.getId(), e);
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Close the Storage.
     *
     * @throws SQLException on close error.
     */
    public void close() throws SQLException {
        try {
            this.lock.lock();
            this.c.close();
        } finally {
            this.lock.unlock();
        }
    }
}
