package org.opentripplanner.routing.algorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.common.TurnRestriction;
import org.opentripplanner.common.TurnRestrictionType;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.model.Entrance;
import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.TripPattern;
import org.opentripplanner.model.WgsCoordinate;
import org.opentripplanner.model.WheelChairBoarding;
import org.opentripplanner.routing.algorithm.astar.AStar;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.TimeRestriction;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.ElevatorAlightEdge;
import org.opentripplanner.routing.edgetype.ElevatorBoardEdge;
import org.opentripplanner.routing.edgetype.ElevatorEdge;
import org.opentripplanner.routing.edgetype.ElevatorHopEdge;
import org.opentripplanner.routing.edgetype.FreeEdge;
import org.opentripplanner.routing.edgetype.PathwayEdge;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetTransitEntranceLink;
import org.opentripplanner.routing.edgetype.StreetTransitStopLink;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.edgetype.StreetVehicleParkingLink;
import org.opentripplanner.routing.edgetype.StreetVehicleRentalLink;
import org.opentripplanner.routing.edgetype.TemporaryFreeEdge;
import org.opentripplanner.routing.edgetype.VehicleRentalEdge;
import org.opentripplanner.routing.vehicle_rental.VehicleRentalStation;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.GraphIndex;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.location.TemporaryStreetLocation;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.vehicle_parking.VehicleParking;
import org.opentripplanner.routing.vehicle_parking.VehicleParking.VehicleParkingEntranceCreator;
import org.opentripplanner.routing.vehicle_parking.VehicleParkingHelper;
import org.opentripplanner.routing.vertextype.VehicleRentalStationVertex;
import org.opentripplanner.routing.vertextype.ElevatorOffboardVertex;
import org.opentripplanner.routing.vertextype.ElevatorOnboardVertex;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.routing.vertextype.TemporaryVertex;
import org.opentripplanner.routing.vertextype.TransitEntranceVertex;
import org.opentripplanner.routing.vertextype.TransitStopVertex;
import org.opentripplanner.routing.vertextype.VehicleParkingEntranceVertex;
import org.opentripplanner.util.NonLocalizedString;

public abstract class GraphRoutingTest {

    public static final String TEST_FEED_ID = "testFeed";
    public static final String TEST_VEHICLE_RENTAL_NETWORK = "test network";

    protected Graph graphOf(Builder builder) {
        return builder.graph();
    }

    public abstract static class Builder {

        private final Graph graph = new Graph();

        public abstract void build();

        public Graph graph() {
            build();
            graph.index = new GraphIndex(graph);
            return graph;
        }

        public <T> T v(String label) {
            return vertex(label);
        }

        public <T> T vertex(String label) {
            return (T) graph.getVertex(label);
        }

        // -- Street network
        public IntersectionVertex intersection(String label, double latitude, double longitude) {
            return new IntersectionVertex(graph, label, longitude, latitude);
        }

        public StreetEdge street(
                StreetVertex from,
                StreetVertex to,
                int length,
                StreetTraversalPermission permissions
        ) {
            return new StreetEdge(from, to,
                    GeometryUtils.makeLineString(
                            from.getLat(), from.getLon(), to.getLat(), to.getLon()),
                    String.format("%s%s street", from.getName(), to.getName()),
                    length,
                    permissions,
                    false
            );
        }

        public List<StreetEdge> street(
                StreetVertex from,
                StreetVertex to,
                int length,
                StreetTraversalPermission forwardPermissions,
                StreetTraversalPermission reversePermissions
        ) {
            return List.of(
                    new StreetEdge(from, to,
                            GeometryUtils.makeLineString(
                                    from.getLat(), from.getLon(), to.getLat(), to.getLon()),
                            String.format("%s%s street", from.getName(), to.getName()),
                            length,
                            forwardPermissions,
                            false
                    ),
                    new StreetEdge(to, from,
                            GeometryUtils.makeLineString(
                                    to.getLat(), to.getLon(), from.getLat(), from.getLon()),
                            String.format("%s%s street", from.getName(), to.getName()),
                            length,
                            reversePermissions,
                            true
                    )
            );
        }

        public TurnRestriction turnRestriction(
                Edge from,
                Edge to,
                TurnRestrictionType type,
                TraverseModeSet modes
        ) {
            var turnRestriction = new TurnRestriction(from, to, type, modes);
            graph.addTurnRestriction(from, turnRestriction);
            return turnRestriction;
        }

        public TurnRestriction turnRestriction(Edge from, Edge to, TurnRestrictionType type) {
            return turnRestriction(
                    from, to, type, new TraverseModeSet(TraverseMode.BICYCLE, TraverseMode.CAR));
        }

        public TurnRestriction bicycleTurnRestriction(
                Edge from,
                Edge to,
                TurnRestrictionType type
        ) {
            return turnRestriction(from, to, type, new TraverseModeSet(TraverseMode.BICYCLE));
        }

        public TurnRestriction carTurnRestriction(Edge from, Edge to, TurnRestrictionType type) {
            return turnRestriction(from, to, type, new TraverseModeSet(TraverseMode.CAR));
        }

        public List<ElevatorEdge> elevator(StreetTraversalPermission permission, Vertex ... vertices) {
            List<ElevatorEdge> edges = new ArrayList<>();
            List<ElevatorOnboardVertex> onboardVertices = new ArrayList<>();

            for (Vertex v : vertices) {
                var level = String.format("L-%s", v.getName());
                var boardLabel = String.format("%s-onboard", level);
                var alightLabel = String.format("%s-offboard", level);

                var onboard = new ElevatorOnboardVertex(
                        graph, boardLabel, v.getX(), v.getY(), boardLabel
                );
                var offboard = new ElevatorOffboardVertex(
                        graph, alightLabel, v.getX(), v.getY(), alightLabel
                );

                new FreeEdge(v, offboard);
                new FreeEdge(offboard, v);

                edges.add(new ElevatorBoardEdge(offboard, onboard));
                edges.add(new ElevatorAlightEdge(onboard, offboard, level));

                onboardVertices.add(onboard);
            }

            for (int i = 1; i < onboardVertices.size(); i++) {
                var from = onboardVertices.get(i - 1);
                var to = onboardVertices.get(i);

                edges.add(new ElevatorHopEdge(from, to, permission));
                edges.add(new ElevatorHopEdge(to, from, permission));
            }

            return edges;
        };

        // -- Transit network (pathways, linking)
        public Entrance entranceEntity(String id, double latitude, double longitude) {
            return new Entrance(
                    new FeedScopedId(TEST_FEED_ID, id),
                    id,
                    id,
                    null,
                    WgsCoordinate.creatOptionalCoordinate(latitude, longitude),
                    WheelChairBoarding.NO_INFORMATION,
                    null
            );
        }

        public Stop stopEntity(String id, double latitude, double longitude) {
            return new Stop(
                    new FeedScopedId(TEST_FEED_ID, id),
                    id,
                    id,
                    null,
                    WgsCoordinate.creatOptionalCoordinate(latitude, longitude),
                    WheelChairBoarding.NO_INFORMATION,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        public TransitStopVertex stop(String id, double latitude, double longitude) {
            return new TransitStopVertex(
                    graph, stopEntity(id, latitude, longitude), null);
        }

        public TransitEntranceVertex entrance(String id, double latitude, double longitude) {
            return new TransitEntranceVertex(
                    graph, entranceEntity(id, latitude, longitude));
        }

        public StreetTransitEntranceLink link(StreetVertex from, TransitEntranceVertex to) {
            return new StreetTransitEntranceLink(from, to);
        }

        public StreetTransitEntranceLink link(TransitEntranceVertex from, StreetVertex to) {
            return new StreetTransitEntranceLink(from, to);
        }

        public List<StreetTransitEntranceLink> biLink(
                StreetVertex from,
                TransitEntranceVertex to
        ) {
            return List.of(link(from, to), link(to, from));
        }

        public StreetTransitStopLink link(StreetVertex from, TransitStopVertex to) {
            return new StreetTransitStopLink(from, to);
        }

        public StreetTransitStopLink link(TransitStopVertex from, StreetVertex to) {
            return new StreetTransitStopLink(from, to);
        }

        public List<StreetTransitStopLink> biLink(StreetVertex from, TransitStopVertex to) {
            return List.of(link(from, to), link(to, from));
        }

        public PathwayEdge pathway(Vertex from, Vertex to, int time, int length) {
            return new PathwayEdge(
                    from, to, null,
                    String.format("%s%s pathway", from.getName(), to.getName()),
                    time, length, 0, 0, false
            );
        }

        // -- Street linking
        public TemporaryStreetLocation streetLocation(
                String name,
                double latitude,
                double longitude,
                boolean endVertex
        ) {
            return new TemporaryStreetLocation(
                    name, new Coordinate(longitude, latitude), new NonLocalizedString(name),
                    endVertex
            );
        }

        public TemporaryFreeEdge link(TemporaryVertex from, StreetVertex to) {
            return new TemporaryFreeEdge(from, to);
        }

        public TemporaryFreeEdge link(StreetVertex from, TemporaryVertex to) {
            return new TemporaryFreeEdge(from, to);
        }

        public List<TemporaryFreeEdge> biLink(StreetVertex from, TemporaryVertex to) {
            return List.of(link(from, to), link(to, from));
        }

        // -- Vehicle rental
        public VehicleRentalStation vehicleRentalStationEntity(
                String id,
                double latitude,
                double longitude,
                String ... networks
        ) {
            var bikeRentalStation = new VehicleRentalStation();
            bikeRentalStation.id = id;
            bikeRentalStation.longitude = longitude;
            bikeRentalStation.latitude = latitude;
            bikeRentalStation.networks = Set.of(networks);
            bikeRentalStation.isKeepingVehicleRentalAtDestinationAllowed = false;
            bikeRentalStation.name = new NonLocalizedString(id);
            return bikeRentalStation;
        }

        public VehicleRentalStationVertex vehicleRentalStation(
                String id,
                double latitude,
                double longitude,
                String ... networks
        ) {
            var vertex = new VehicleRentalStationVertex(
                    graph,
                    vehicleRentalStationEntity(id, latitude, longitude, networks)
            );
            new VehicleRentalEdge(vertex);
            return vertex;
        }

        public VehicleRentalStationVertex vehicleRentalStation(
                String id,
                double latitude,
                double longitude
        ) {
            return vehicleRentalStation(id, latitude, longitude, TEST_VEHICLE_RENTAL_NETWORK);
        }

        public StreetVehicleRentalLink link(StreetVertex from, VehicleRentalStationVertex to) {
            return new StreetVehicleRentalLink(from, to);
        }

        public StreetVehicleRentalLink link(VehicleRentalStationVertex from, StreetVertex to) {
            return new StreetVehicleRentalLink(from, to);
        }

        public List<StreetVehicleRentalLink> biLink(StreetVertex from, VehicleRentalStationVertex to) {
            return List.of(link(from, to), link(to, from));
        }

        public void vehicleParking(String id, double x, double y, boolean bicyclePlaces, boolean carPlaces, List<VehicleParking.VehicleParkingEntranceCreator> entrances, String ... tags) {
            vehicleParking(id, x, y, bicyclePlaces, carPlaces, false, null, entrances, tags);
        }

        public void vehicleParking(String id, double x, double y, boolean bicyclePlaces, boolean carPlaces, boolean wheelchairAccessiblePlaces, List<VehicleParking.VehicleParkingEntranceCreator> entrances, String ... tags) {
            vehicleParking(id, x, y, bicyclePlaces, carPlaces, wheelchairAccessiblePlaces, null, entrances, tags);
        }

        public VehicleParking vehicleParking(String id, double x, double y, boolean bicyclePlaces, boolean carPlaces, boolean wheelchairAccessiblePlaces, TimeRestriction openingHours, List<VehicleParkingEntranceCreator> entrances, String ... tags) {
            var vehicleParking = VehicleParking.builder()
                .id(new FeedScopedId(TEST_FEED_ID, id))
                .x(x)
                .y(y)
                .bicyclePlaces(bicyclePlaces)
                .carPlaces(carPlaces)
                .entrances(entrances)
                .openingHours(openingHours)
                .wheelchairAccessibleCarPlaces(wheelchairAccessiblePlaces)
                .tags(List.of(tags))
                .build();

            var vertices = VehicleParkingHelper.createVehicleParkingVertices(graph, vehicleParking);
            VehicleParkingHelper.linkVehicleParkingEntrances(vertices);
            vertices.forEach(v -> biLink(v.getParkingEntrance().getVertex(), v));
            return vehicleParking;
        }

        public VehicleParking.VehicleParkingEntranceCreator vehicleParkingEntrance(StreetVertex streetVertex, String id, boolean carAccessible, boolean walkAccessible) {
            return builder -> builder
                .entranceId(new FeedScopedId(TEST_FEED_ID, id))
                .name(new NonLocalizedString(id))
                .x(streetVertex.getX())
                .y(streetVertex.getY())
                .vertex(streetVertex)
                .carAccessible(carAccessible)
                .walkAccessible(walkAccessible);
        }

        public StreetVehicleParkingLink link(StreetVertex from, VehicleParkingEntranceVertex to) {
            return new StreetVehicleParkingLink(from, to);
        }

        public StreetVehicleParkingLink link(VehicleParkingEntranceVertex from, StreetVertex to) {
            return new StreetVehicleParkingLink(from, to);
        }

        public List<StreetVehicleParkingLink> biLink(StreetVertex from, VehicleParkingEntranceVertex to) {
            return List.of(link(from, to), link(to, from));
        }

        // Transit
        public void tripPattern(TripPattern tripPattern) {
            graph.tripPatternForId.put(tripPattern.getId(), tripPattern);
        }
    }

    public static String graphPathToString(GraphPath graphPath) {
        return graphPath.states.stream()
            .flatMap(s -> Stream.of(
                s.getBackEdge() != null ? s.getBackEdge().getName() : null,
                s.getVertex().getName()
            ))
            .filter(Objects::nonNull)
            .collect(Collectors.joining(" - "));
    }

    protected GraphPath routeParkAndRide(Graph graph, StreetVertex from, StreetVertex to, TraverseModeSet traverseModeSet) {
        RoutingRequest request = new RoutingRequest(traverseModeSet);
        request.setRoutingContext(graph, from, to);
        request.parkAndRide = true;

        AStar aStar = new AStar();
        ShortestPathTree tree = aStar.getShortestPathTree(request);
        return tree.getPath(to, false);
    }
}
