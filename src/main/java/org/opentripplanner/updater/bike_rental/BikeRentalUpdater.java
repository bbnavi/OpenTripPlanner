package org.opentripplanner.updater.bike_rental;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentripplanner.graph_builder.linking.SimpleStreetSplitter;
import org.opentripplanner.routing.bike_rental.BikeRentalStation;
import org.opentripplanner.routing.bike_rental.BikeRentalStationService;
import org.opentripplanner.routing.bike_rental.BikeRentalStationService.RentalType;
import org.opentripplanner.routing.edgetype.RentABikeOffEdge;
import org.opentripplanner.routing.edgetype.RentABikeOnEdge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.opentripplanner.updater.GraphUpdaterManager;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.opentripplanner.updater.JsonConfigurable;
import org.opentripplanner.updater.PollingGraphUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic bike-rental station updater which updates the Graph with bike rental stations from one BikeRentalDataSource.
 */
public class BikeRentalUpdater extends PollingGraphUpdater {

    private static final Logger LOG = LoggerFactory.getLogger(BikeRentalUpdater.class);

    private GraphUpdaterManager updaterManager;

    private static final String DEFAULT_NETWORK_LIST = "default";

    private Map<BikeRentalStation, BikeRentalStationVertex> verticesByStation = new HashMap<BikeRentalStation, BikeRentalStationVertex>();

    private BikeRentalDataSource source;

    private Graph graph;

    private SimpleStreetSplitter linker;

    private BikeRentalStationService service;

    private String network = "default";
    private RentalType rentalType;

    @Override
    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
        this.updaterManager = updaterManager;
    }

    @Override
    protected void configurePolling (Graph graph, JsonNode config) throws Exception {

        // Set data source type from config JSON
        String sourceType = config.path("sourceType").asText();
        String apiKey = config.path("apiKey").asText();
        // Each updater can be assigned a unique network ID in the configuration to prevent returning bikes at
        // stations for another network. TODO shouldn't we give each updater a unique network ID by default?
        String networkName = config.path("network").asText(DEFAULT_NETWORK_LIST);
        BikeRentalDataSource source = null;
        if (sourceType != null) {
            if (sourceType.equals("jcdecaux")) {
                source = new JCDecauxBikeRentalDataSource();
            } else if (sourceType.equals("b-cycle")) {
                source = new BCycleBikeRentalDataSource(apiKey, networkName);
            } else if (sourceType.equals("bixi")) {
                source = new BixiBikeRentalDataSource();
            } else if (sourceType.equals("keolis-rennes")) {
                source = new KeolisRennesBikeRentalDataSource();
            } else if (sourceType.equals("ov-fiets")) {
                source = new OVFietsKMLDataSource();
            } else if (sourceType.equals("city-bikes")) {
                source = new CityBikesBikeRentalDataSource();
            } else if (sourceType.equals("vcub")) {
                source = new VCubDataSource();
            } else if (sourceType.equals("citi-bike-nyc")) {
                source = new CitiBikeNycBikeRentalDataSource(networkName);
            } else if (sourceType.equals("next-bike")) {
                source = new NextBikeRentalDataSource(networkName);
            } else if (sourceType.equals("kml")) {
                source = new GenericKmlBikeRentalDataSource();
            } else if (sourceType.equals("sf-bay-area")) {
                source = new SanFranciscoBayAreaBikeRentalDataSource(networkName);
            } else if (sourceType.equals("share-bike")) {
                source = new ShareBikeRentalDataSource();
            } else if (sourceType.equals("uip-bike")) {
                source = new UIPBikeRentalDataSource(apiKey);
            } else if (sourceType.equals("gbfs")) {
                source = new GbfsBikeRentalDataSource(networkName);
            } else if (sourceType.equals("smoove")) {
                source = new SmooveBikeRentalDataSource(networkName);
            } else if (sourceType.equals("bicimad")) {
                source = new BicimadBikeRentalDataSource();
            } else if (sourceType.equals("samocat")) {
                source = new SamocatScooterRentalDataSource(networkName);
            } else if (sourceType.equals("sharingos")) {
                source = new SharingOSBikeRentalDataSource(networkName);
            } else if (sourceType.equals("vilkku")) {
                source = new VilkkuBikeRentalDataSource(networkName);
            } else if (sourceType.equals("kaakau")) {
                source = new KaakauBikeRentalDataSource(networkName);
            }
        }

        if (source == null) {
            throw new IllegalArgumentException("Unknown bike rental source type: " + sourceType);
        } else if (source instanceof JsonConfigurable) {
            ((JsonConfigurable) source).configure(graph, config);
        }

        // Configure updater
        LOG.info("Setting up bike rental updater.");
        this.graph = graph;
        this.source = source;
        this.network = networkName;
        this.rentalType = parseRentalType(config.path("rentalType").asText(), networkName);


        if (pollingPeriodSeconds <= 0) {
            LOG.info("Creating bike-rental updater running once only (non-polling): {}", source);
        } else {
            LOG.info("Creating bike-rental updater running every {} seconds: {}", pollingPeriodSeconds, source);
        }

    }

    @Override
    public void setup(Graph setupGraph) throws Exception {
        this.graph = setupGraph;
        while (graph.getVertices() == null) {
            LOG.warn("Graph has no vertices. Sleeping 5 sec");
            Thread.sleep(5000);
        }
        // Creation of network linker library will not modify the graph
        linker = new SimpleStreetSplitter(graph);
        // Adding a bike rental station service needs a graph writer runnable
        service = graph.getService(BikeRentalStationService.class, true);
        service.setNetworkType(this.network, this.rentalType);
    }

    public static RentalType parseRentalType(String input, String network) {
        var parseResult = RentalType.fromString(input);
        if(parseResult.isEmpty()) {
            LOG.warn("Could not parse rentalType value '{}' of bike sharing with id '{}'. Defaulting to {}.",
                    input, network, RentalType.STATION_BASED);
        }
        return parseResult.orElse(RentalType.STATION_BASED);
    }

    @Override
    protected void runPolling() throws Exception {
        LOG.debug("Updating bike rental stations from " + source);
        if (!source.update()) {
            LOG.debug("No updates");
            return;
        }
        List<BikeRentalStation> stations = source.getStations();

        // Create graph writer runnable to apply these stations to the graph
        BikeRentalGraphWriterRunnable graphWriterRunnable = new BikeRentalGraphWriterRunnable(stations);
        updaterManager.execute(graphWriterRunnable);
    }

    @Override
    public void teardown() {
    }

    @Override
    public String toString() {
        return "BikeRentalUpdater{" +
                "network='" + network + '\'' +
                ", rentalType=" + rentalType +
                ", source=" + source +
                '}';
    }

    private class BikeRentalGraphWriterRunnable implements GraphWriterRunnable {

        private List<BikeRentalStation> stations;

        public BikeRentalGraphWriterRunnable(List<BikeRentalStation> stations) {
            this.stations = stations;
        }

        @Override
        public void run(Graph graph) {
            // Apply stations to graph
            Set<BikeRentalStation> stationSet = new HashSet<>();
            Set<String> defaultNetworks = new HashSet<>(Arrays.asList(network));
            /* add any new stations and update bike counts for existing stations */
            for (BikeRentalStation station : stations) {
                if (station.networks == null) {
                    /* API did not provide a network list, use default */
                    station.networks = defaultNetworks;
                }
                service.addBikeRentalStation(station);
                stationSet.add(station);
                BikeRentalStationVertex vertex = verticesByStation.get(station);
                if (vertex == null) {
                    vertex = new BikeRentalStationVertex(graph, station);
                    if (!linker.link(vertex)) {
                        // the toString includes the text "Bike rental station"
                        LOG.warn("{} not near any streets; it will not be usable.", station);
                    }
                    verticesByStation.put(station, vertex);
                    new RentABikeOnEdge(vertex, vertex, station.networks);
                    if (station.allowDropoff)
                        new RentABikeOffEdge(vertex, vertex, station.networks);
                } else {
                    vertex.setStation(station);
                }
            }
            /* remove existing stations that were not present in the update */
            List<BikeRentalStation> toRemove = new ArrayList<BikeRentalStation>();
            for (Entry<BikeRentalStation, BikeRentalStationVertex> entry : verticesByStation.entrySet()) {
                BikeRentalStation station = entry.getKey();
                if (stationSet.contains(station))
                    continue;
                BikeRentalStationVertex vertex = entry.getValue();
                if (graph.containsVertex(vertex)) {
                    graph.removeVertexAndEdges(vertex);
                }
                toRemove.add(station);
                service.removeBikeRentalStation(station);
                // TODO: need to unsplit any streets that were split
            }
            for (BikeRentalStation station : toRemove) {
                // post-iteration removal to avoid concurrent modification
                verticesByStation.remove(station);
            }
        }
    }
}
