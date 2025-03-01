package org.opentripplanner.routing.graph;

import static java.lang.Math.min;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import com.google.common.collect.ArrayListMultimap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Calendar;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import graphql.ExceptionWhileDataFetching;
import graphql.GraphQLError;
import graphql.schema.GraphQLSchema;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.sentry.Sentry;
import io.sentry.event.Event;
import io.sentry.event.EventBuilder;

import org.apache.lucene.util.PriorityQueue;
import org.joda.time.LocalDate;
import org.opentripplanner.model.Agency;
import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.FeedInfo;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Trip;
import org.opentripplanner.model.calendar.ServiceDate;
import org.opentripplanner.model.CalendarService;
import org.opentripplanner.common.LuceneIndex;
import org.opentripplanner.common.geometry.HashGridSpatialIndex;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.gtfs.GtfsLibrary;
import org.opentripplanner.index.FieldErrorInstrumentation;
import org.opentripplanner.index.IndexGraphQLSchema;
import org.opentripplanner.index.ResourceConstrainedExecutorServiceExecutionStrategy;
import org.opentripplanner.index.model.StopTimesInPattern;
import org.opentripplanner.index.model.TripTimeShort;
import org.opentripplanner.profile.ProfileTransfer;
import org.opentripplanner.profile.StopCluster;
import org.opentripplanner.profile.StopClusterMode;
import org.opentripplanner.profile.StopNameNormalizer;
import org.opentripplanner.profile.StopTreeCache;
import org.opentripplanner.routing.alertpatch.AlertPatch;
import org.opentripplanner.routing.algorithm.AStar;
import org.opentripplanner.routing.algorithm.ExtendedTraverseVisitor;
import org.opentripplanner.routing.algorithm.TraverseVisitor;
import org.opentripplanner.routing.algorithm.strategies.SearchTerminationStrategy;
import org.opentripplanner.routing.bike_park.BikePark;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;
import org.opentripplanner.routing.car_park.CarPark;
import org.opentripplanner.routing.core.Fare.FareType;
import org.opentripplanner.routing.core.FareRuleSet;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.ServiceDay;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TicketType;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.ParkAndRideLinkEdge;
import org.opentripplanner.routing.edgetype.StreetBikeParkLink;
import org.opentripplanner.routing.edgetype.TablePatternEdge;
import org.opentripplanner.routing.edgetype.Timetable;
import org.opentripplanner.routing.edgetype.TimetableSnapshot;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.impl.DefaultFareServiceImpl;
import org.opentripplanner.routing.services.AlertPatchService;
import org.opentripplanner.routing.services.FareService;
import org.opentripplanner.routing.spt.DominanceFunction;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.trippattern.FrequencyEntry;
import org.opentripplanner.routing.trippattern.RealTimeState;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.BikeParkVertex;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.opentripplanner.routing.vertextype.ParkAndRideVertex;
import org.opentripplanner.routing.vertextype.TransitStation;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.standalone.Router;
import org.opentripplanner.updater.alerts.GtfsRealtimeAlertsUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

/**
 * This class contains all the transient indexes of graph elements -- those that are not
 * serialized with the graph. Caching these maps is essentially an optimization, but a big one.
 * The index is bootstrapped from the graph's list of edges.
 */
public class GraphIndex {

    private static final Logger LOG = LoggerFactory.getLogger(GraphIndex.class);
    private static final int CLUSTER_RADIUS = 400; // meters

    /** maximum distance to walk after leaving transit in Analyst */
    public static final int MAX_WALK_METERS = 3500;

    // TODO: consistently key on model object or id string
    public final Map<String, Vertex> vertexForId = Maps.newHashMap();
    public final Map<String, Map<String, Agency>> agenciesForFeedId = Maps.newHashMap();
    public final Map<String, FeedInfo> feedInfoForId = Maps.newHashMap();
    public final Map<FeedScopedId, Stop> stopForId = Maps.newHashMap();
    public final Map<FeedScopedId, Stop> stationForId = Maps.newHashMap();
    public final Map<FeedScopedId, Trip> tripForId = Maps.newHashMap();
    public final Map<FeedScopedId, Route> routeForId = Maps.newHashMap();
    public final Map<FeedScopedId, String> serviceForId = Maps.newHashMap();
    public final Map<String, TripPattern> patternForId = Maps.newHashMap();
    public final Map<Stop, TransitStop> stopVertexForStop = Maps.newHashMap();
    public final Map<Trip, TripPattern> patternForTrip = Maps.newHashMap();
    public final Multimap<String, TripPattern> patternsForFeedId = ArrayListMultimap.create();
    public final Multimap<Route, TripPattern> patternsForRoute = ArrayListMultimap.create();
    public final Multimap<Stop, TripPattern> patternsForStop = ArrayListMultimap.create();
    public final Multimap<FeedScopedId, Stop> stopsForParentStation = ArrayListMultimap.create();
    final HashGridSpatialIndex<TransitStop> stopSpatialIndex = new HashGridSpatialIndex<TransitStop>();
    public final Map<Stop, StopCluster> stopClusterForStop = Maps.newHashMap();
    public final Map<String, StopCluster> stopClusterForId = Maps.newHashMap();
    public final Map<FeedScopedId, TicketType> ticketTypesForId = Maps.newHashMap();
    public final Map<FeedScopedId, Geometry> flexAreasById = Maps.newHashMap();

    /* Should eventually be replaced with new serviceId indexes. */
    private final CalendarService calendarService;
    private final Map<FeedScopedId,Integer> serviceCodes;

    /* Full-text search extensions */
    public transient LuceneIndex luceneIndex;

    /* Separate transfers for profile routing */
    public Multimap<StopCluster, ProfileTransfer> transfersFromStopCluster;
    private HashGridSpatialIndex<StopCluster> stopClusterSpatialIndex = null;

    /* This is a workaround, and should probably eventually be removed. */
    public Graph graph;

    /** Used for finding first/last trip of the day. This is the time at which service ends for the day. */
    public final int overnightBreak = 60 * 60 * 2; // FIXME not being set, this was done in transitIndex

    /** Store distances from each stop to all nearby street intersections. Useful in speeding up analyst requests. */
    private transient StopTreeCache stopTreeCache = null;

    final transient GraphQLSchema indexSchema;

    public final ExecutorService threadPool;

    public GraphIndex (Graph graph) {
        LOG.info("Indexing graph...");

        FareService fareService = graph.getService(FareService.class);
        if(fareService instanceof DefaultFareServiceImpl) {
            LOG.info("Collecting fare information...");
            DefaultFareServiceImpl defaultFareServiceImpl = (DefaultFareServiceImpl) fareService;
            Map<FareType, Collection<FareRuleSet>> data = defaultFareServiceImpl.getFareRulesPerType();
            for(Entry<FareType, Collection<FareRuleSet>> kv:data.entrySet()) {
                if(FareType.regular == kv.getKey()) {
                    for(FareRuleSet rs: kv.getValue()) {
                        ticketTypesForId.put(rs.getFareAttribute().getId(), new TicketType(rs));
                    }
                }
            }
        }

        for (String feedId : graph.getFeedIds()) {
            for (Agency agency : graph.getAgencies(feedId)) {
                Map<String, Agency> agencyForId = agenciesForFeedId.getOrDefault(feedId, new HashMap<>());
                agencyForId.put(agency.getId(), agency);
                this.agenciesForFeedId.put(feedId, agencyForId);
            }
            this.feedInfoForId.put(feedId, graph.getFeedInfo(feedId));
        }

        Collection<Edge> edges = graph.getEdges();
        /* We will keep a separate set of all vertices in case some have the same label.
         * Maybe we should just guarantee unique labels. */
        Set<Vertex> vertices = Sets.newHashSet();
        for (Edge edge : edges) {
            vertices.add(edge.getFromVertex());
            vertices.add(edge.getToVertex());
            if (edge instanceof TablePatternEdge) {
                TablePatternEdge patternEdge = (TablePatternEdge) edge;
                TripPattern pattern = patternEdge.getPattern();
                patternForId.put(pattern.code, pattern);
            }
        }
        for (Vertex vertex : vertices) {
            vertexForId.put(vertex.getLabel(), vertex);
            if (vertex instanceof TransitStop) {
                TransitStop transitStop = (TransitStop) vertex;
                Stop stop = transitStop.getStop();
                stopForId.put(stop.getId(), stop);
                stopVertexForStop.put(stop, transitStop);
                if (stop.getParentStation() != null) {
                    stopsForParentStation.put(
                        new FeedScopedId(stop.getId().getAgencyId(), stop.getParentStation()), stop);
                }
            }
            else if (vertex instanceof TransitStation) {
                TransitStation transitStation = (TransitStation) vertex;
                Stop stop = transitStation.getStop();
                stationForId.put(stop.getId(), stop);
            }
        }
        for (TransitStop stopVertex : stopVertexForStop.values()) {
            Envelope envelope = new Envelope(stopVertex.getCoordinate());
            stopSpatialIndex.insert(envelope, stopVertex);
        }

        for (TripPattern pattern : patternForId.values()) {
            patternsForFeedId.put(pattern.getFeedId(), pattern);
            patternsForRoute.put(pattern.route, pattern);

            for (Trip trip : pattern.getTrips()) {
                patternForTrip.put(trip, pattern);
                tripForId.put(trip.getId(), trip);
            }
            for (Stop stop: pattern.getStops()) {
                if (!patternsForStop.containsEntry(stop, pattern)) {
                    patternsForStop.put(stop, pattern);
                }
            }
        }
        for (Route route : patternsForRoute.asMap().keySet()) {
            routeForId.put(route.getId(), route);
        }

        // Copy these two service indexes from the graph until we have better ones.
        calendarService = graph.getCalendarService();
        serviceCodes = graph.serviceCodes;
        this.graph = graph;
        threadPool = Executors.newCachedThreadPool(
            new ThreadFactoryBuilder().setNameFormat("GraphQLExecutor-" + graph.routerId + "-%d")
                .build()
        );

        indexSchema = new IndexGraphQLSchema(this).indexSchema;
        getLuceneIndex();
        LOG.info("Done indexing graph.");

        LOG.info("Initializing areas....");
        if (graph.flexAreasById != null) {
            for (FeedScopedId id : graph.flexAreasById.keySet()) {
                flexAreasById.put(id, graph.flexAreasById.get(id));
            }
        }
    }

    /**
     * Stop clustering is slow to perform and only used in profile routing for the moment.
     * Therefore it is not done automatically, and any method requiring stop clusters should call this method
     * to ensure that the necessary indexes are lazy-initialized.
     */
    public synchronized void clusterStopsAsNeeded() {
        if (stopClusterSpatialIndex == null) {
            clusterStops();
            LOG.info("Creating a spatial index for stop clusters.");
            stopClusterSpatialIndex = new HashGridSpatialIndex<>();
            for (StopCluster cluster : stopClusterForId.values()) {
                Envelope envelope = new Envelope(new Coordinate(cluster.lon, cluster.lat));
                stopClusterSpatialIndex.insert(envelope, cluster);
            }
        }
    }


    /** Get all trip patterns running through any stop in the given stop cluster. */
    private Set<TripPattern> patternsForStopCluster(StopCluster sc) {
        Set<TripPattern> tripPatterns = Sets.newHashSet();
        for (Stop stop : sc.children) tripPatterns.addAll(patternsForStop.get(stop));
        return tripPatterns;
    }

    /**
     * Initialize transfer data needed for profile routing.
     * Find the best transfers between each pair of patterns that pass near one another.
     */
    public void initializeProfileTransfers() {
        transfersFromStopCluster = HashMultimap.create();
        final double TRANSFER_RADIUS = 500.0; // meters
        Map<P2<TripPattern>, ProfileTransfer.GoodTransferList> transfers = Maps.newHashMap();
        LOG.info("Finding transfers between clusters...");
        for (StopCluster sc0 : stopClusterForId.values()) {
            Set<TripPattern> tripPatterns0 = patternsForStopCluster(sc0);
            // Accounts for area-like (rather than point-like) nature of clusters
            Map<StopCluster, Double> nearbyStopClusters = findNearbyStopClusters(sc0, TRANSFER_RADIUS);
            for (StopCluster sc1 : nearbyStopClusters.keySet()) {
                double distance = nearbyStopClusters.get(sc1);
                Set<TripPattern> tripPatterns1 = patternsForStopCluster(sc1);
                for (TripPattern tp0 : tripPatterns0) {
                    for (TripPattern tp1 : tripPatterns1) {
                        if (tp0 == tp1) continue;
                        P2<TripPattern> pair = new P2<TripPattern>(tp0, tp1);
                        ProfileTransfer.GoodTransferList list = transfers.get(pair);
                        if (list == null) {
                            list = new ProfileTransfer.GoodTransferList();
                            transfers.put(pair, list);
                        }
                        list.add(new ProfileTransfer(tp0, tp1, sc0, sc1, (int)distance));
                    }
                }
            }
        }
        /* Now filter the transfers down to eliminate long series of transfers in shared trunks. */
        LOG.info("Filtering out long series of transfers on trunks shared between patterns.");
        for (P2<TripPattern> pair : transfers.keySet()) {
            ProfileTransfer.GoodTransferList list = transfers.get(pair);
            TripPattern fromPattern = pair.first; // TODO consider using second (think of express-local transfers in NYC)
            Map<StopCluster, ProfileTransfer> transfersByFromCluster = Maps.newHashMap();
            for (ProfileTransfer transfer : list.good) {
                transfersByFromCluster.put(transfer.sc1, transfer);
            }
            List<ProfileTransfer> retainedTransfers = Lists.newArrayList();
            boolean inSeries = false; // true whenever a transfer existed for the last stop in the stop pattern
            for (Stop stop : fromPattern.stopPattern.stops) {
                StopCluster cluster = this.stopClusterForStop.get(stop);
                //LOG.info("stop {} cluster {}", stop, cluster.id);
                ProfileTransfer transfer = transfersByFromCluster.get(cluster);
                if (transfer == null) {
                    inSeries = false;
                    continue;
                }
                if (inSeries) continue;
                // Keep this transfer: it's not preceded by another stop with a transfer in this stop pattern
                retainedTransfers.add(transfer);
                inSeries = true;
            }
            //LOG.info("patterns {}, {} transfers", pair, retainedTransfers.size());
            for (ProfileTransfer tr : retainedTransfers) {
                transfersFromStopCluster.put(tr.sc1, tr);
                //LOG.info("   {}", tr);
            }
        }
        /*
         * for (Stop stop : transfersForStop.keys()) { System.out.println("STOP " + stop); for
         * (Transfer transfer : transfersForStop.get(stop)) { System.out.println("    " +
         * transfer.toString()); } }
         */
        LOG.info("Done finding transfers.");
    }

    /**
     * Find transfer candidates for profile routing.
     * TODO replace with an on-street search using the existing profile router functions.
     */
    public Map<StopCluster, Double> findNearbyStopClusters (StopCluster sc, double radius) {
        Map<StopCluster, Double> ret = Maps.newHashMap();
        Envelope env = new Envelope(new Coordinate(sc.lon, sc.lat));
        env.expandBy(SphericalDistanceLibrary.metersToLonDegrees(radius, sc.lat),
                SphericalDistanceLibrary.metersToDegrees(radius));
        for (StopCluster cluster : stopClusterSpatialIndex.query(env)) {
            // TODO this should account for area-like nature of clusters. Use size of bounding boxes.
            double distance = SphericalDistanceLibrary.distance(sc.lat, sc.lon, cluster.lat, cluster.lon);
            if (distance < radius) ret.put(cluster, distance);
        }
        return ret;
    }

    /* TODO: an almost similar function exists in ProfileRouter, combine these.
    *  Should these live in a separate class? */
    public List<StopAndDistance> findClosestStopsByWalking(double lat, double lon, int radius) {
        // Make a normal OTP routing request so we can traverse edges and use GenericAStar
        // TODO make a function that builds normal routing requests from profile requests
        RoutingRequest rr = new RoutingRequest(TraverseMode.WALK);
        rr.from = new GenericLocation(lat, lon);
        // FIXME requires destination to be set, not necessary for analyst
        rr.to = new GenericLocation(lat, lon);
        rr.batch = true;
        rr.setRoutingContext(graph);
        rr.walkSpeed = 1;
        rr.dominanceFunction = new DominanceFunction.LeastWalk();
        // RR dateTime defaults to currentTime.
        // If elapsed time is not capped, searches are very slow.
        rr.worstTime = (rr.dateTime + radius);
        AStar astar = new AStar();
        rr.setNumItineraries(1);
        StopFinderTraverseVisitor visitor = new StopFinderTraverseVisitor();
        astar.setTraverseVisitor(visitor);
        astar.getShortestPathTree(rr, 1); // timeout in seconds
        // Destroy the routing context, to clean up the temporary edges & vertices
        rr.rctx.destroy();
        return visitor.stopsFound;
    }

    public List<PlaceAndDistance> findClosestPlacesByWalking(double lat, double lon, int maxDistance, int maxResults,
            List<TraverseMode> filterByModes,
            List<PlaceType> filterByPlaceTypes,
            List<FeedScopedId> filterByStops,
            List<FeedScopedId> filterByRoutes,
            List<String> filterByBikeRentalStations,
            List<String> filterByBikeParks,
            List<String> filterByCarParks) {
        RoutingRequest rr = new RoutingRequest(TraverseMode.WALK);
        rr.allowBikeRental = true;
        //rr.bikeParkAndRide = true;
        //rr.parkAndRide = true;
        //rr.modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.BICYCLE, TraverseMode.CAR);
        rr.from = new GenericLocation(lat, lon);
        rr.batch = true;
        rr.setRoutingContext(graph);
        rr.walkSpeed = 1;
        rr.dominanceFunction = new DominanceFunction.LeastWalk();
        // RR dateTime defaults to currentTime.
        // If elapsed time is not capped, searches are very slow.
        rr.worstTime = (rr.dateTime + maxDistance);
        rr.setNumItineraries(1);
        //rr.arriveBy = true;
        PlaceFinderTraverseVisitor visitor = new PlaceFinderTraverseVisitor(filterByModes, filterByPlaceTypes, filterByStops, filterByRoutes, filterByBikeRentalStations, filterByBikeParks, filterByCarParks);
        AStar astar = new AStar();
        astar.setTraverseVisitor(visitor);
        SearchTerminationStrategy strategy = new SearchTerminationStrategy() {
            @Override
            public boolean shouldSearchTerminate(Vertex origin, Vertex target, State current, ShortestPathTree spt,
                    RoutingRequest traverseOptions) {
                // the first n stops the search visit may not be the nearest n
                // but when we have at least n stops found, we can update the
                // max distance to be the furthest of the places so far
                // and let the search terminate at that distance
                // and then return the first n
                if (visitor.placesFound.size() >= maxResults) {
                    int furthestDistance = 0;
                    for (PlaceAndDistance pad : visitor.placesFound) {
                        if (pad.distance > furthestDistance) {
                            furthestDistance = pad.distance;
                        }
                    }
                    rr.worstTime = (rr.dateTime + furthestDistance);
                }
                return false;
            }
        };
        astar.getShortestPathTree(rr, 100, strategy); // timeout in seconds
        // Destroy the routing context, to clean up the temporary edges & vertices
        rr.rctx.destroy();
        List<PlaceAndDistance> results = visitor.placesFound;
        results.sort((pad1, pad2) -> pad1.distance - pad2.distance);
        return results.subList(0, min(results.size(), maxResults));
    }

    public LuceneIndex getLuceneIndex() {
        synchronized (this) {
            if (luceneIndex == null) {
                File directory;
                try {
                    directory = Files.createTempDirectory(graph.routerId + "_lucene",
                        (FileAttribute<?>[]) new FileAttribute[]{}).toFile();
                } catch (IOException e) {
                    return null;
                }
                // Synchronously lazy-initialize the Lucene index
                luceneIndex = new LuceneIndex(this, directory, false);
            }
            return luceneIndex;
        }
    }

    public static class StopAndDistance {
        public Stop stop;
        public int distance;

        public StopAndDistance(Stop stop, int distance){
            this.stop = stop;
            this.distance = distance;
        }
    }

    public static enum PlaceType {
        STOP, DEPARTURE_ROW, BICYCLE_RENT, BIKE_PARK, CAR_PARK;
    }

    public static class PlaceAndDistance {
        public Object place;
        public int distance;

        public PlaceAndDistance(Object place, int distance) {
            this.place = place;
            this.distance = distance;
        }
    }

    static private class StopFinderTraverseVisitor implements TraverseVisitor {
        List<StopAndDistance> stopsFound = new ArrayList<>();
        @Override public void visitEdge(Edge edge, State state) { }
        @Override public void visitEnqueue(State state) { }
        // Accumulate stops into ret as the search runs.
        @Override public void visitVertex(State state) {
            Vertex vertex = state.getVertex();
            if (vertex instanceof TransitStop) {
                stopsFound.add(new StopAndDistance(((TransitStop) vertex).getStop(),
                    (int) state.getElapsedTimeSeconds()));
            }
        }
    }

    public static class DepartureRow {
        public String id;
        public Stop stop;
        public TripPattern pattern;

        public DepartureRow(Stop stop, TripPattern pattern) {
            this.id = toId(stop, pattern);
            this.stop = stop;
            this.pattern = pattern;
        }

        private static String toId(Stop stop, TripPattern pattern) {
            FeedScopedId id =  stop.getId();
            if(id != null) {
                return id.getAgencyId() + ";" + id.getId() + ";" + pattern.code;
            } else {
                LOG.error("Stop {} with name {} does not contain an id.", stop, stop.getName());
                return "unknown";
            }
        }

        public List<TripTimeShort> getStoptimes(GraphIndex index, long startTime, int timeRange, int numberOfDepartures, boolean omitNonPickups, boolean omitCanceled) {
            return index.stopTimesForPattern(stop, pattern, startTime, timeRange, numberOfDepartures, omitNonPickups, omitCanceled);
        }

        public static DepartureRow fromId(GraphIndex index, String id) {
            String[] parts = id.split(";", 3);
            FeedScopedId stopId = new FeedScopedId(parts[0], parts[1]);
            String code = parts[2];
            return new DepartureRow(index.stopForId.get(stopId), index.patternForId.get(code));
        }
    }

    private static <T> Set<T> toSet(List<T> list) {
        if (list == null) return null;
        return new HashSet<T>(list);
    }

    private class PlaceFinderTraverseVisitor implements ExtendedTraverseVisitor {
        public List<PlaceAndDistance> placesFound = new ArrayList<>();
        private Set<TraverseMode> filterByModes;
        private Set<FeedScopedId> filterByStops;
        private Set<FeedScopedId> filterByRoutes;
        private Set<String> filterByBikeRentalStation;
        private Set<String> seenDepartureRows = new HashSet<String>();
        private Set<FeedScopedId> seenStops = new HashSet<FeedScopedId>();
        private Set<String> seenBicycleRentalStations = new HashSet<String>();
        private Set<String> seenBikeParks = new HashSet<String>();
        private Set<String> seenCarParks = new HashSet<String>();
        private Set<String> filterByBikeParks;
        private Set<String> filterByCarParks;
        private boolean includeStops;
        private boolean includeDepartureRows;
        private boolean includeBikeShares;
        private boolean includeBikeParks;
        private boolean includeCarParks;

        public PlaceFinderTraverseVisitor(
                List<TraverseMode> filterByModes,
                List<PlaceType> filterByPlaceTypes,
                List<FeedScopedId> filterByStops,
                List<FeedScopedId> filterByRoutes,
                List<String> filterByBikeRentalStations,
                List<String> filterByBikeParks,
                List<String> filterByCarParks) {
            this.filterByModes = toSet(filterByModes);
            this.filterByStops = toSet(filterByStops);
            this.filterByRoutes = toSet(filterByRoutes);
            this.filterByBikeRentalStation = toSet(filterByBikeRentalStations);
            this.filterByBikeParks = toSet(filterByBikeParks);
            this.filterByCarParks = toSet(filterByCarParks);

            includeStops = filterByPlaceTypes == null || filterByPlaceTypes.contains(PlaceType.STOP);
            includeDepartureRows = filterByPlaceTypes == null || filterByPlaceTypes.contains(PlaceType.DEPARTURE_ROW);
            includeBikeShares = filterByPlaceTypes == null || filterByPlaceTypes.contains(PlaceType.BICYCLE_RENT);
            includeBikeParks = filterByPlaceTypes == null || filterByPlaceTypes.contains(PlaceType.BIKE_PARK);
            includeCarParks = filterByPlaceTypes == null || filterByPlaceTypes.contains(PlaceType.CAR_PARK);
        }

        @Override public void preVisitEdge(Edge edge, State state) {
            if (edge instanceof ParkAndRideLinkEdge) {
                visitVertex(state.edit(edge).makeState());
            } else if (edge instanceof StreetBikeParkLink) {
                visitVertex(state.edit(edge).makeState());
            }
        }
        @Override public void visitEdge(Edge edge, State state) {
        }
        @Override public void visitEnqueue(State state) {
        }
        @Override public void visitVertex(State state) {
            Vertex vertex = state.getVertex();
            int distance = (int)state.getWalkDistance();
            if (vertex instanceof TransitStop) {
                visitStop(((TransitStop)vertex).getStop(), distance);
            } else if (vertex instanceof BikeRentalStationVertex) {
                visitBikeRentalStation(((BikeRentalStationVertex)vertex).getStation(), distance);
            } else if (vertex instanceof BikeParkVertex) {
                visitBikePark(((BikeParkVertex)vertex).getBikePark(), distance);
            } else if (vertex instanceof ParkAndRideVertex) {
                visitCarPark(((ParkAndRideVertex)vertex).getCarPark(), distance);
            }
        }
        private void visitBikeRentalStation(BikeRentalStation station, int distance) {
            handleBikeRentalStation(station, distance);
        }

        private void visitStop(Stop stop, int distance) {
            handleStop(stop, distance);
            handleDepartureRows(stop, distance);
        }

        private void visitBikePark(BikePark bikePark, int distance) {
            handleBikePark(bikePark, distance);
        }

        private void visitCarPark(CarPark carPark, int distance) {
            handleCarPark(carPark, distance);
        }

        private void handleStop(Stop stop, int distance) {
            if (filterByStops != null && !filterByStops.contains(stop.getId())) return;
            if (includeStops && !seenStops.contains(stop.getId()) && (filterByModes == null || stopHasRoutesWithMode(stop, filterByModes))) {
                placesFound.add(new PlaceAndDistance(stop, distance));
                seenStops.add(stop.getId());
            }
        }

        private void handleDepartureRows(Stop stop, int distance) {
            if (includeDepartureRows) {
                List<TripPattern> patterns = patternsForStop.get(stop)
                    .stream()
                    .filter(pattern -> filterByModes == null || filterByModes.contains(pattern.mode))
                    .filter(pattern -> filterByRoutes == null || filterByRoutes.contains(pattern.route.getId()))
                    .filter(pattern -> pattern.canBoard(pattern.getStopIndex(stop)))
                    .collect(toList());

                for (TripPattern pattern : patterns) {
                    String seenKey = GtfsLibrary.convertIdToString(pattern.route.getId()) + ":" + pattern.code;
                    if (!seenDepartureRows.contains(seenKey)) {
                        DepartureRow row = new DepartureRow(stop, pattern);
                        PlaceAndDistance place = new PlaceAndDistance(row, distance);
                        placesFound.add(place);
                        seenDepartureRows.add(seenKey);
                    }
                }
            }
        }

        private void handleBikeRentalStation(BikeRentalStation station, int distance) {
            if (!includeBikeShares) return;
            if (filterByBikeRentalStation != null && !filterByBikeRentalStation.contains(station.id)) return;
            if (seenBicycleRentalStations.contains(station.id)) return;
            seenBicycleRentalStations.add(station.id);
            placesFound.add(new PlaceAndDistance(station, distance));
        }

        private void handleBikePark(BikePark bikePark, int distance) {
            if (!includeBikeParks) return;
            if (filterByBikeParks != null && !filterByBikeParks.contains(bikePark.id)) return;
            if (seenBikeParks.contains(bikePark.id)) return;
            seenBikeParks.add(bikePark.id);
            placesFound.add(new PlaceAndDistance(bikePark, distance));
        }

        private void handleCarPark(CarPark carPark, int distance) {
            if (!includeCarParks) return;
            if (filterByCarParks != null && !filterByCarParks.contains(carPark.id)) return;
            if (seenCarParks.contains(carPark.id)) return;
            seenCarParks.add(carPark.id);
            placesFound.add(new PlaceAndDistance(carPark, distance));
        }
    }

    private Stream<TraverseMode> modesForStop(Stop stop) {
        return routesForStop(stop).stream().map(GtfsLibrary::getTraverseMode);
    }

    private boolean stopHasRoutesWithMode(Stop stop, Set<TraverseMode> modes) {
        return modesForStop(stop).anyMatch(modes::contains);
    }

    /** An OBA Service Date is a local date without timezone, only year month and day. */
    public BitSet servicesRunning (ServiceDate date) {
        BitSet services = new BitSet(calendarService.getServiceIds().size());
        for (FeedScopedId serviceId : calendarService.getServiceIdsOnDate(date)) {
            int n = serviceCodes.get(serviceId);
            if (n < 0) continue;
            services.set(n);
        }
        return services;
    }

    /**
     * Wraps the other servicesRunning whose parameter is an OBA ServiceDate.
     * Joda LocalDate is a similar class.
     */
    public BitSet servicesRunning (LocalDate date) {
        return servicesRunning(new ServiceDate(date.getYear(), date.getMonthOfYear(), date.getDayOfMonth()));
    }

    /** Dynamically generate the set of Routes passing though a Stop on demand. */
    public Set<Route> routesForStop(Stop stop) {
        Set<Route> routes = Sets.newHashSet();
        for (TripPattern p : patternsForStop.get(stop)) {
            routes.add(p.route);
        }
        return routes;
    }

    /**
     * Fetch upcoming vehicle departures from a stop.
     * Fetches two departures for each pattern during the next 24 hours as default
     */
    public Collection<StopTimesInPattern> stopTimesForStop(Stop stop, boolean omitNonPickups, boolean omitCanceled) {
        return stopTimesForStop(stop, System.currentTimeMillis()/1000, 24 * 60 * 60, 2, omitNonPickups, omitCanceled);
    }

    /**
     * Fetch upcoming vehicle departures from a stop.
     * It goes though all patterns passing the stop for the previous, current and next service date.
     * It uses a priority queue to keep track of the next departures. The queue is shared between all dates, as services
     * from the previous service date can visit the stop later than the current service date's services. This happens
     * eg. with sleeper trains.
     *
     * @param stop Stop object to perform the search for
     * @param startTime Start time for the search. Seconds from UNIX epoch
     * @param timeRange Searches forward for timeRange seconds from startTime
     * @param numberOfDepartures Number of departures to fetch per pattern
     * @param omitNonPickups If true, do not include vehicles that will not pick up passengers.
     * @return
     */
    public List<StopTimesInPattern> stopTimesForStop(final Stop stop, final long startTime, final int timeRange, final int numberOfDepartures, boolean omitNonPickups, boolean omitCanceled) {

        final List<StopTimesInPattern> ret = new ArrayList<>();

        for (final TripPattern pattern : patternsForStop.get(stop)) {

            final List<TripTimeShort> stopTimesForStop = stopTimesForPattern(stop, pattern, startTime, timeRange, numberOfDepartures, omitNonPickups, omitCanceled);


            if (stopTimesForStop.size() >0) {
                final StopTimesInPattern stopTimes = new StopTimesInPattern(pattern);
                stopTimes.times.addAll(stopTimesForStop);
                ret.add(stopTimes);
            }
        }
        return ret;
    }

    /**
     * Fetch next n upcoming vehicle departures for a stop of pattern. It goes
     * though the previous, current and next service date. It uses a priority
     * queue to keep track of the next departures. The queue is shared between
     * all dates, as services from the previous service date can visit the stop
     * later than the current service date's services. This happens eg. with
     * sleeper trains.
     *
     * @param stop
     *            Stop object to perform the search for
     * @param pattern
     *            The selected pattern. If null an empty list is returned.
     * @param startTime
     *            Start time for the search. Seconds from UNIX epoch
     * @param timeRange
     *            Searches forward for timeRange seconds from startTime
     * @param numberOfDepartures
     *            Number of departures to fetch per pattern
     * @param omitCanceled
     * @return
     */
    public List<TripTimeShort> stopTimesForPattern(final Stop stop, final TripPattern pattern, long startTime, final int timeRange,
                                                   int numberOfDepartures, boolean omitNonPickups, boolean omitCanceled) {

        if (pattern == null) {
            return Collections.emptyList();
        }
        if (startTime == 0) {
            startTime = System.currentTimeMillis() / 1000;
        }

        final PriorityQueue<TripTimeShort> ret = new PriorityQueue<TripTimeShort>(numberOfDepartures) {

                @Override
            protected boolean lessThan(final TripTimeShort t1, final TripTimeShort t2) {
                return (t1.serviceDay + t1.realtimeDeparture) > (t2.serviceDay
                        + t2.realtimeDeparture);
                }
            };

        final TimetableSnapshot snapshot = (graph.timetableSnapshotSource != null)
            ? graph.timetableSnapshotSource.getTimetableSnapshot() : null;

        Date date = new Date(startTime * 1000);
        final ServiceDate[] serviceDates = {new ServiceDate(date).previous(), new ServiceDate(date), new ServiceDate(date).next()};
            // Loop through all possible days
        for (final ServiceDate serviceDate : serviceDates) {
            final ServiceDay sd = new ServiceDay(graph, serviceDate, calendarService,
                    pattern.route.getAgency().getId());
                Timetable tt;
                if (snapshot != null){
                    tt = snapshot.resolve(pattern, serviceDate);
                } else {
                    tt = pattern.scheduledTimetable;
                }

                if (!tt.temporallyViable(sd, startTime, timeRange, true)) continue;

            final int starttimeSecondsSinceMidnight = sd.secondsSinceMidnight(startTime);
            int stopIndex = 0;

            // loop through all stops of pattern
            for (final Stop currStop : pattern.stopPattern.stops) {
                if (currStop.equals(stop)) {
                    if(omitNonPickups && pattern.stopPattern.pickups[stopIndex] == pattern.stopPattern.PICKDROP_NONE) continue;
                    for (final TripTimes triptimes : tt.tripTimes) {
                        if (!sd.serviceRunning(triptimes.serviceCode))
                            continue;
                        int stopDepartureTime = triptimes.getDepartureTime(stopIndex);
                        if (!(omitCanceled && triptimes.isCanceledDeparture(stopIndex)) && stopDepartureTime >= starttimeSecondsSinceMidnight && stopDepartureTime < starttimeSecondsSinceMidnight + timeRange) {
                            ret.insertWithOverflow(new TripTimeShort(triptimes, stopIndex, currStop, sd));
                            }
                        }

                        // TODO: This needs to be adapted after #1647 is merged
                    for (final FrequencyEntry freq : tt.frequencyEntries) {
                            if (!sd.serviceRunning(freq.tripTimes.serviceCode)) continue;
                        int departureTime = freq.nextDepartureTime(stopIndex, starttimeSecondsSinceMidnight);
                            if (omitCanceled && departureTime == -1) continue;
                        final int lastDeparture = freq.endTime + freq.tripTimes.getArrivalTime(stopIndex)
                                - freq.tripTimes.getDepartureTime(0);
                        while (departureTime <= lastDeparture && ret.size() < numberOfDepartures) {
                            ret.insertWithOverflow(new TripTimeShort(freq.materialize(stopIndex, departureTime, true),
                                    stopIndex, currStop, sd));
                                departureTime += freq.headway;
                            }
                        }
                    }
                stopIndex++;
                }
            }

        final List<TripTimeShort> result = new ArrayList<>();
        while(ret.size()>0) {
            TripTimeShort tripTimeShort = ret.pop();
            if (!result.contains(tripTimeShort)) {
                result.add(0, tripTimeShort);
                }
            }
        return result;
        }

    /**
     * Get a list of all trips that pass through a stop during a single ServiceDate. Useful when creating complete stop
     * timetables for a single day.
     *
     * @param stop Stop object to perform the search for
     * @param serviceDate Return all departures for the specified date
     * @param omitCanceled
     * @return
     */
    public List<StopTimesInPattern> getStopTimesForStop(Stop stop, ServiceDate serviceDate, boolean omitNonPickups, boolean omitCanceled) {
        List<StopTimesInPattern> ret = new ArrayList<>();
        TimetableSnapshot snapshot = null;
        if (graph.timetableSnapshotSource != null) {
            snapshot = graph.timetableSnapshotSource.getTimetableSnapshot();
        }
        Collection<TripPattern> patterns = patternsForStop.get(stop);
        for (TripPattern pattern : patterns) {
            StopTimesInPattern stopTimes = new StopTimesInPattern(pattern);
            Timetable tt;
            if (snapshot != null){
                tt = snapshot.resolve(pattern, serviceDate);
            } else {
                tt = pattern.scheduledTimetable;
            }
            ServiceDay sd = new ServiceDay(graph, serviceDate, calendarService, pattern.route.getAgency().getId());
            int sidx = 0;
            for (Stop currStop : pattern.stopPattern.stops) {
                if (currStop.equals(stop)) {
                    if(omitNonPickups && pattern.stopPattern.pickups[sidx] == pattern.stopPattern.PICKDROP_NONE) continue;
                    for (TripTimes t : tt.tripTimes) {
                        if (!sd.serviceRunning(t.serviceCode)) continue;
                        if (omitCanceled && t.isTimeCanceled(sidx)) continue;
                        stopTimes.times.add(new TripTimeShort(t, sidx, stop, sd));
                    }
                }
                sidx++;
            }
            ret.add(stopTimes);
        }
        return ret;
    }

    /** Fetch a cache of nearby intersection distances for every transit stop in this graph, lazy-building as needed. */
    public StopTreeCache getStopTreeCache() {
        if (stopTreeCache == null) {
            synchronized (this) {
                if (stopTreeCache == null) {
                    stopTreeCache = new StopTreeCache(graph, MAX_WALK_METERS); // TODO make this max-distance variable
                }
            }
        }
        return stopTreeCache;
    }

    /**
     * Get the most up-to-date timetable for the given TripPattern, as of right now.
     * There should probably be a less awkward way to do this that just gets the latest entry from the resolver without
     * making a fake routing request.
     */
    public Timetable currentUpdatedTimetableForTripPattern (TripPattern tripPattern) {
        RoutingRequest req = new RoutingRequest();
        req.setRoutingContext(graph, (Vertex)null, (Vertex)null);
        // The timetableSnapshot will be null if there's no real-time data being applied.
        if (req.rctx.timetableSnapshot == null) return tripPattern.scheduledTimetable;
        // Get the updated times for right now, which is the only reasonable default since no date is supplied.
        Calendar calendar = Calendar.getInstance();
        ServiceDate serviceDate = new ServiceDate(calendar.getTime());
        return req.rctx.timetableSnapshot.resolve(tripPattern, serviceDate);
    }

    /**
     * Stop clusters can be built in one of two ways, either by geographical proximity and name, or
     * according to a parent/child station topology, if it exists.
     */
    private void clusterStops() {
    	if (graph.stopClusterMode == StopClusterMode.parentStation) {
            clusterByParentStation();
        } else {
            clusterByProximityAndName();
        }
    }

    /**
     * Cluster stops by proximity and name.
     * This functionality was developed for the Washington, DC area and probably will not work anywhere else in the
     * world. It depends on the exact way stops are named and the way street intersections are named in that geographic
     * region and in the GTFS data sets which represent it. Based on comments, apparently it might work for TriMet
     * as well.
     *
     * We can't use a name similarity comparison, we need exact matches. This is because many street names differ by
     * only one letter or number, e.g. 34th and 35th or Avenue A and Avenue B. Therefore normalizing the names before
     * the comparison is essential. The agency must provide either parent station information or a well thought out stop
     * naming scheme to cluster stops -- no guessing is reasonable without that information.
     */
    private void clusterByProximityAndName() {
    	int psIdx = 0; // unique index for next parent stop
	    LOG.info("Clustering stops by geographic proximity and name...");
	    // Each stop without a cluster will greedily claim other stops without clusters.
	    for (Stop s0 : stopForId.values()) {
	        if (stopClusterForStop.containsKey(s0)) continue; // skip stops that have already been claimed by a cluster
	        String s0normalizedName = StopNameNormalizer.normalize(s0.getName());
	        StopCluster cluster = new StopCluster(String.format("C%03d", psIdx++), s0normalizedName);
	        // LOG.info("stop {}", s0normalizedName);
	        // No need to explicitly add s0 to the cluster. It will be found in the spatial index query below.
	        Envelope env = new Envelope(new Coordinate(s0.getLon(), s0.getLat()));
	        env.expandBy(SphericalDistanceLibrary.metersToLonDegrees(CLUSTER_RADIUS, s0.getLat()),
	                SphericalDistanceLibrary.metersToDegrees(CLUSTER_RADIUS));
	        for (TransitStop ts1 : stopSpatialIndex.query(env)) {
	            Stop s1 = ts1.getStop();
	            double geoDistance = SphericalDistanceLibrary.fastDistance(
	                    s0.getLat(), s0.getLon(), s1.getLat(), s1.getLon());
	            if (geoDistance < CLUSTER_RADIUS) {
	                String s1normalizedName = StopNameNormalizer.normalize(s1.getName());
	                // LOG.info("   --> {}", s1normalizedName);
	                // LOG.info("       geodist {} stringdist {}", geoDistance, stringDistance);
	                if (s1normalizedName.equals(s0normalizedName)) {
	                    // Create a bidirectional relationship between the stop and its cluster
	                    cluster.children.add(s1);
	                    stopClusterForStop.put(s1, cluster);
	                }
	            }
	        }
	        cluster.computeCenter();
	        stopClusterForId.put(cluster.id, cluster);
	    }
    }

    /**
     * Rather than using the names and geographic locations of stops to cluster them, group them by their declared
     * parent station in the GTFS data. This should be a much more reliable method where these fields have been
     * included in the GTFS data. However:
     *
     * FIXME OBA parentStation field is a string, not an AgencyAndId, so it has no agency/feed scope.
     * That means it would only work reliably if there is only one GTFS feed loaded.
     * The DC regional graph has no parent stations pre-defined, so we use the alternative proximity / name method.
     * Trimet stops have "landmark" or Transit Center parent stations, so we don't use the parent stop field.
     */
    private void clusterByParentStation() {
        LOG.info("Clustering stops by parent station...");
    	for (Stop stop : stopForId.values()) {
    	    String ps = stop.getParentStation();
    	    if (ps == null || ps.isEmpty()) {
    	        continue;
    	    }
    	    StopCluster cluster;
    	    if (stopClusterForId.containsKey(ps)) {
    	        cluster = stopClusterForId.get(ps);
    	    } else {
    	        cluster = new StopCluster(ps, stop.getName());
    	        Stop parent = graph.parentStopById.get(new FeedScopedId(stop.getId().getAgencyId(), ps));
                cluster.setCoordinates(parent.getLat(), parent.getLon());
                stopClusterForId.put(ps, cluster);

	        }
    	    cluster.children.add(stop);
    	    stopClusterForStop.put(stop, cluster);
    	}
    }

    public Response getGraphQLResponse(String query, Router router, Map<String, Object> variables, String operationName, int timeout, long maxResolves, MultivaluedMap<String, String> headers) {
        Response.ResponseBuilder res = Response.status(Response.Status.OK);
        HashMap<String, Object> content = getGraphQLExecutionResult(query, router, variables,
            operationName, timeout, maxResolves, headers);
        if (content.get("errors") != null) {
            // TODO: Put correct error code, eg. 400 for syntax error
            res = Response.status(Response.Status.INTERNAL_SERVER_ERROR);
        }
        return res.entity(content).build();
    }

    public HashMap<String, Object> getGraphQLExecutionResult(String query, Router router,
        Map<String, Object> variables, String operationName, int timeout, long maxResolves, MultivaluedMap<String, String> headers) {

        GraphQL graphQL = GraphQL.newGraphQL(indexSchema).queryExecutionStrategy(
            new ResourceConstrainedExecutorServiceExecutionStrategy(threadPool, timeout, TimeUnit.MILLISECONDS, maxResolves)
        ).instrumentation(FieldErrorInstrumentation.get(query, router, variables, headers)).build();

        if (variables == null) {
            variables = new HashMap<>();
        }

        ExecutionResult executionResult = graphQL.execute(query, operationName, router, variables);
        HashMap<String, Object> content = new HashMap<>();

        if (!executionResult.getErrors().isEmpty()) {
            content.put("errors",
                executionResult
                    .getErrors()
                    .stream()
                    .map(error -> {
                        if (error instanceof ExceptionWhileDataFetching) {
                            StringWriter sw = new StringWriter();
                            PrintWriter pw = new PrintWriter(sw);
                            ((ExceptionWhileDataFetching) error).getException().printStackTrace(pw);
                            HashMap<String, Object> response = new HashMap<String, Object>();
                            response.put("message", error.getMessage());
                            response.put("locations", error.getLocations());
                            response.put("errorType", error.getErrorType());
                            // Convert stack trace to proper format
                            Stream<StackTraceElement> stack = Arrays.stream(((ExceptionWhileDataFetching) error).getException().getStackTrace());
                            response.put("stack", stack.map(StackTraceElement::toString).collect(Collectors.toList()));
                            return response;
                        } else {
                            return error;
                        }
                    })
                    .collect(Collectors.toList()));

            for (GraphQLError error : executionResult.getErrors()) {
                Sentry.getContext().addExtra("message", error.getMessage());
                Sentry.getContext().addExtra("errorType", error.getErrorType());
                if (error instanceof ExceptionWhileDataFetching) {
                    Sentry.capture(((ExceptionWhileDataFetching) error).getException());
                } else {
                    EventBuilder builder = new EventBuilder()
                        .withMessage(error.toString())
                        .withLevel(Event.Level.ERROR)
                        .withLogger(LOG.getName());
                    Sentry.capture(builder);
                }
            }
        }
        if (executionResult.getData() != null) {
            content.put("data", executionResult.getData());
        }
        return content;
    }

    private Stream<AlertPatch> getAlertPatchStream() {
        if (graph.updaterManager == null) {
            return Stream.empty();
        }
        return graph.updaterManager.getUpdaterList().stream()
            .filter(GtfsRealtimeAlertsUpdater.class::isInstance)
            .map(GtfsRealtimeAlertsUpdater.class::cast)
            .map(GtfsRealtimeAlertsUpdater::getAlertPatchService)
            .map(AlertPatchService::getAllAlertPatches)
            .flatMap(Collection::stream);
    }

    public List<AlertPatch> getAlerts() {
        return getAlertPatchStream()
            .collect(Collectors.toList());
    }

    public List<AlertPatch> getAlertsForRoute(Route route) {
        return getAlertPatchStream()
            .filter(alertPatch -> alertPatch.getRoute() != null)
            .filter(alertPatch -> route.getId().equals(alertPatch.getRoute()))
            .collect(Collectors.toList());
    }

    public List<AlertPatch> getAlertsForTrip(Trip trip) {
        return getAlertPatchStream()
            .filter(alertPatch -> alertPatch.getTrip() != null)
            .filter(alertPatch -> trip.getId().equals(alertPatch.getTrip()))
            .collect(Collectors.toList());
    }

    public List<AlertPatch> getAlertsForPattern(TripPattern pattern) {
        return getAlertPatchStream()
            .filter(alertPatch -> alertPatch.getTripPatterns() != null)
            .filter(alertPatch -> alertPatch.getTripPatterns().stream().anyMatch(tripPattern -> pattern.code.equals(tripPattern.code)))
            .collect(Collectors.toList());
    }

    public List<AlertPatch> getAlertsForAgency(Agency agency) {
        return getAlertPatchStream()
            .filter(alertPatch -> alertPatch.getAgency() != null)
            .filter(alertPatch -> agency.getId().equals(alertPatch.getAgency()))
            .collect(Collectors.toList());
    }

    public List<AlertPatch> getAlertsForStop(Stop stop) {
        return getAlertPatchStream()
                .filter(alertPatch -> alertPatch.getStop() != null)
                .filter(alertPatch -> {

                    return stop.getId().equals(alertPatch.getStop());
                })
                .collect(Collectors.toList());
    }

    public AlertPatch getAlertForId(String id) {
        return getAlertPatchStream().filter(alertPatch -> id.equals(alertPatch.getId())).findFirst().get();
    }

    public Agency getAgencyWithFeedScopeId(String agencyFeedScopeIdRaw) {
        FeedScopedId agencyFeedScopeId = FeedScopedId.convertFromString(agencyFeedScopeIdRaw);

        Map<String, Agency> feedAgencies = agenciesForFeedId.get(agencyFeedScopeId.getAgencyId());
        if (feedAgencies == null) return null;

        Agency agency = feedAgencies.get(agencyFeedScopeId.getId());

        return agency;

    }

    /**
     * Construct a set of all Agencies in this graph, spanning across all feed IDs.
     * I am creating this method only to allow merging pull request #2032 which adds GraphQL.
     * This should probably be done some other way, see javadoc on getAgencyWithoutFeedId.
     */
    public Set<Agency> getAllAgencies() {
        Set<Agency> allAgencies = new HashSet<>();
        for (Map<String, Agency> agencyForId : agenciesForFeedId.values()) {
            allAgencies.addAll(agencyForId.values());
        }
        return allAgencies;
    }
    public Collection<TicketType> getAllTicketTypes() {
        return ticketTypesForId.values();
    }

    /**
     * Method for getting TripTimeShort objects.
     *
     * @param patterns TripPattern objects that are filtered to produce TripTimeShort objects.
     * @param tripIds List of trip gtfsIds that are used to filter TripTimeShort objects
     * @param minDate Only TripTimeShort objects scheduled to run on minDate or after are returned.
     * @param maxDate Only TripTimeShort objects scheduled to run on maxDate or before are returned.
     * @param minDepartureTime Only TripTimeShort objects that have first stop departure time at minDepartureTime or
     *                         after are returned.
     * @param maxDepartureTime Only TripTimeShort objects that have first stop departure time at maxDepartureTime or
     *                         before are returned.
     * @param minArrivalTime Only TripTimeShort objects that have last stop arrival time at minArrivalTime or after are
     *                       returned.
     * @param maxArrivalTime Only TripTimeShort objects that have last stop arrival time at maxArrivalTime or before are
     *                       returned.
     * @param state Only TripTimeShort objects with this RealTimeState are returned. Not null. Does not return SCHEDULED
     *              TripTimeShort objects unless there have been trip updates on them.
     * @return List of TripTimeShort objects.
     */
    public List<TripTimeShort> getTripTimes(final Collection<TripPattern> patterns, final List<String> tripIds, final ServiceDate minDate, final ServiceDate maxDate, final Integer minDepartureTime, final Integer maxDepartureTime, final Integer minArrivalTime, final Integer maxArrivalTime, final RealTimeState state) {
        final TimetableSnapshot snapshot = (graph.timetableSnapshotSource != null) ? graph.timetableSnapshotSource.getTimetableSnapshot() : null;
        final ConcurrentHashMap<TripPattern, Collection<Timetable>> timetableForPattern = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ServiceDay> serviceDaysByAgency = new ConcurrentHashMap<>();
        final CalendarService calendarService = graph.getCalendarService();
        return patterns.stream()
                .distinct()
                .filter(Objects::nonNull)
                .flatMap(pattern -> {
                    final Collection<Timetable> timetables = timetableForPattern.computeIfAbsent(pattern, p -> (snapshot != null) ? snapshot.getTimetables(p) : null);
                    return ((timetables != null) ? timetables : Arrays.asList(pattern.scheduledTimetable)).stream();
                })
                .filter(timetable -> {
                    // date filter
                    boolean isValid = timetable.serviceDate != null;
                    if (timetable.serviceDate != null) {
                        if (minDate != null) {
                            isValid &= timetable.serviceDate.compareTo(minDate) >= 0;
                        }
                        if (maxDate != null) {
                            isValid &= timetable.serviceDate.compareTo(maxDate) <= 0;
                        }
                    }
                    return isValid;
                })
                .flatMap(timetable -> timetable.tripTimes.stream()
                        .filter(tripTimes -> {
                            // time and state filter
                            boolean isValid = tripTimes.getRealTimeState() == state;
                            if (minDate != null && timetable.serviceDate.compareTo(minDate) == 0) {
                                if (minDepartureTime != null) {
                                    isValid &= tripTimes.getScheduledArrivalTime(0) >= minDepartureTime;
                                } else if (minArrivalTime != null) {
                                    isValid &= tripTimes.getScheduledArrivalTime(tripTimes.getNumStops() - 1) >= minArrivalTime;
                                }
                            }
                            if (maxDate != null && timetable.serviceDate.compareTo(maxDate) == 0) {
                                if (maxDepartureTime != null) {
                                    isValid &= tripTimes.getScheduledArrivalTime(0) <= maxDepartureTime;
                                } else if (maxArrivalTime != null) {
                                    isValid &= tripTimes.getScheduledArrivalTime(tripTimes.getNumStops() - 1) <= maxArrivalTime;
                                }
                            }
                            if (tripIds != null) {
                                isValid &= tripIds.contains(tripTimes.trip.getId().toString());
                            }
                            return isValid;
                        })
                        .map(tripTimes -> {
                            final int stopIndex = 0;
                            final Stop stop = timetable.pattern.getStop(stopIndex);
                            final String agencyId = tripTimes.trip.getId().getAgencyId();
                            final String agencyIdServiceDate = agencyId + "_" + timetable.serviceDate.getAsString();
                            final ServiceDay serviceDay = serviceDaysByAgency.computeIfAbsent(agencyIdServiceDate, key -> new ServiceDay(graph, timetable.serviceDate, calendarService, agencyId));
                            return new TripTimeShort(tripTimes, stopIndex, stop, serviceDay);
                        })
                )
                .distinct()
                .sorted(Comparator
                        .comparing((TripTimeShort tripTimeShort) -> tripTimeShort.serviceDay)
                        .thenComparing((TripTimeShort tripTimeShort) -> tripTimeShort.scheduledDeparture)
                )
                .collect(Collectors.toList());
    }
}
