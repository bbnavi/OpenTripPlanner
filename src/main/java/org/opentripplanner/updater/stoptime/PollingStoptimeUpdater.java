package org.opentripplanner.updater.stoptime;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentripplanner.updater.*;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.util.SentryUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.TripUpdate;

/**
 * Update OTP stop time tables from some (realtime) source
 *
 * Usage example ('rt' name is an example) in file 'Graph.properties':
 *
 * <pre>
 * rt.type = stop-time-updater
 * rt.frequencySec = 60
 * rt.sourceType = gtfs-http
 * rt.url = http://host.tld/path
 * rt.feedId = TA
 * </pre>
 *
 */
public class PollingStoptimeUpdater extends PollingGraphUpdater {
    private static final Logger LOG = LoggerFactory.getLogger(PollingStoptimeUpdater.class);

    /**
     * Parent update manager. Is used to execute graph writer runnables.
     */
    private GraphUpdaterManager updaterManager;

    /**
     * Update streamer
     */
    private TripUpdateSource updateSource;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Integer logFrequency;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Integer maxSnapshotFrequency;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Boolean purgeExpiredData;

    /**
     * Feed id that is used for the trip ids in the TripUpdates
     */
    private String feedId;

    /**
     * Set only if we should attempt to match the trip_id from other data in TripDescriptor
     */
    private GtfsRealtimeFuzzyTripMatcher fuzzyTripMatcher;

    /**
     * What type should a newly added route have.
     *
     * Possible values are listed at: https://developers.google.com/transit/gtfs/reference/extended-route-types
     *
     * This setting is necessary because as of April 2020 the GTFS-RT spec doesn't allow to specify this.
     */
    private int newRouteType = defaultNewRouteType;

    @Override
    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
        this.updaterManager = updaterManager;
    }

    @Override
    public void configurePolling(Graph graph, JsonNode config) throws Exception {
        // Create update streamer from preferences
        feedId = config.path("feedId").asText("");
        newRouteType = config.path("newRouteType").asInt(defaultNewRouteType);
        String sourceType = config.path("sourceType").asText();
        if (sourceType != null) {
            if (sourceType.equals("gtfs-http")) {
                updateSource = new GtfsRealtimeHttpTripUpdateSource();
            } else if (sourceType.equals("gtfs-file")) {
                updateSource = new GtfsRealtimeFileTripUpdateSource();
            }
        }

        // Configure update source
        if (updateSource == null) {
            throw new IllegalArgumentException(
                    "Unknown update streamer source type: " + sourceType);
        } else if (updateSource instanceof JsonConfigurable) {
            ((JsonConfigurable) updateSource).configure(graph, config);
        }

        // Configure updater FIXME why are the fields objects instead of primitives? this allows null values...
        int logFrequency = config.path("logFrequency").asInt(-1);
        if (logFrequency >= 0) {
            this.logFrequency = logFrequency;
        }
        int maxSnapshotFrequency = config.path("maxSnapshotFrequencyMs").asInt(-1);
        if (maxSnapshotFrequency >= 0) {
            this.maxSnapshotFrequency = maxSnapshotFrequency;
        }
        this.purgeExpiredData = config.path("purgeExpiredData").asBoolean(true);
        if (config.path("fuzzyTripMatching").asBoolean(false)) {
            this.fuzzyTripMatcher = new GtfsRealtimeFuzzyTripMatcher(graph.index);
        }
        LOG.info("Creating stop time updater running every {} seconds : {}", pollingPeriodSeconds, updateSource);
    }

    @Override
    public void setup(Graph graph) throws InterruptedException, ExecutionException {
        // Create a realtime data snapshot source and wait for runnable to be executed
        HashMap<String,Object> sentry = new HashMap<>();
        sentry.put("feedId",feedId);
        sentry.put("updateSource",updateSource.toString());
        sentry.put("frequencySec",pollingPeriodSeconds);

        SentryUtilities.setupSentryFromMap(sentry);
        updaterManager.execute(new GraphWriterRunnable() {
            @Override
            public void run(Graph graph) {
                // Only create a realtime data snapshot source if none exists already
                TimetableSnapshotSource snapshotSource = graph.timetableSnapshotSource;
                if (snapshotSource == null) {
                    snapshotSource = new TimetableSnapshotSource(graph);
                    // Add snapshot source to graph
                    graph.timetableSnapshotSource = (snapshotSource);
                }

                // Set properties of realtime data snapshot source
                if (logFrequency != null) {
                    snapshotSource.statistics.setLogFrequency(logFrequency);
                }
                if (maxSnapshotFrequency != null) {
                    snapshotSource.maxSnapshotFrequency = (maxSnapshotFrequency);
                }
                if (purgeExpiredData != null) {
                    snapshotSource.purgeExpiredData = (purgeExpiredData);
                }
                if (fuzzyTripMatcher != null) {
                    snapshotSource.fuzzyTripMatcher = fuzzyTripMatcher;
                }
            }
        });
    }

    /**
     * Repeatedly makes blocking calls to an UpdateStreamer to retrieve new stop time updates, and
     * applies those updates to the graph.
     */
    @Override
    public void runPolling() {
        // Get update lists from update source
        List<TripUpdate> updates = updateSource.getUpdates();
        boolean fullDataset = updateSource.getFullDatasetValueOfLastUpdates();

        if (updates != null) {
            // Handle trip updates via graph writer runnable
            TripUpdateGraphWriterRunnable runnable =
                    new TripUpdateGraphWriterRunnable(fullDataset, updates, feedId, newRouteType);
            updaterManager.execute(runnable);
        }
    }

    @Override
    public void teardown() {
    }

    public String toString() {
        String s = (updateSource == null) ? "NONE" : updateSource.toString();
        return "Streaming stoptime updater with update source = " + s;
    }
}
