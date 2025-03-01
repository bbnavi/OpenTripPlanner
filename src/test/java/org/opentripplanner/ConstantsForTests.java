package org.opentripplanner;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;

import org.opentripplanner.model.calendar.CalendarServiceData;
import org.opentripplanner.graph_builder.module.DirectTransferGenerator;
import org.opentripplanner.graph_builder.module.StreetLinkerModule;
import org.opentripplanner.graph_builder.module.osm.DefaultWayPropertySetSource;
import org.opentripplanner.graph_builder.module.osm.OpenStreetMapModule;
import org.opentripplanner.gtfs.GtfsContext;
import org.opentripplanner.gtfs.GtfsLibrary;
import org.opentripplanner.openstreetmap.impl.AnyFileBasedOpenStreetMapProviderImpl;
import org.opentripplanner.routing.edgetype.factory.PatternHopFactory;
import org.opentripplanner.routing.edgetype.factory.TransferGraphLinker;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.DefaultStreetVertexIndexFactory;

import static org.opentripplanner.calendar.impl.CalendarServiceDataFactoryImpl.createCalendarServiceData;

public class ConstantsForTests {

    public static final String CALTRAIN_GTFS = "src/test/resources/caltrain_gtfs.zip";

    public static final String PORTLAND_GTFS = "src/test/resources/google_transit.zip";

    public static final String KCM_GTFS = "src/test/resources/kcm_gtfs.zip";
    
    public static final String FAKE_GTFS = "src/test/resources/testagency.zip";

    public static final String FARE_COMPONENT_GTFS = "src/test/resources/farecomponent_gtfs.zip";

    public static final String OSLO_MINIMAL_GTFS = "src/test/resources/oslo/oslo_gtfs_minimal.zip";

    public static final String OSLO_MINIMAL_OSM = "src/test/resources/oslo/oslo_osm_minimal.pbf";

    public static final String HSL_MINIMAL_GTFS = "src/test/resources/hsl/hsl_gtfs_minimal.zip";

    public static final String HERRENBERG_OSM = "src/test/resources/herrenberg/herrenberg.osm.pbf";
    // contains Hindenburgstr. being blocked due to roadworks and manually patched maxspeed of 5 km/h in the Alzentag neighbourhood
    public static final String HERRENBERG_HINDENBURG_UNDER_CONSTRUCTION_OSM = "src/test/resources/herrenberg/herrenberg-hindenburg-under-construction.osm.pbf";
    public static final String HERRENBERG_AND_AROUND_OSM = "src/test/resources/herrenberg/herrenberg-and-around.osm.pbf";
    public static final String HERRENBERG_ONLY_BRONNTOR_BUS_STOP = "src/test/resources/herrenberg/only-bronntor-bus-stop.gtfs.zip" ;
    public static final String HERRENBERG_S1_TRAIN_ONLY = "src/test/resources/herrenberg/s1-only.gtfs.zip" ;

    public static final String VERMONT_GTFS = "/vermont/ruralcommunity-flex-vt-us.zip";

    public static final String VERMONT_OSM = "/vermont/vermont-rct.osm.pbf";

    public static final String DEUFRINGEN_OSM = "src/test/resources/deufringen/deufringen.osm.pbf" ;
    public static final String DEUFRINGEN_GTFS = "src/test/resources/deufringen/bus-764-only.gtfs.zip" ;

    public static final String BOEBLINGEN_OSM = "src/test/resources/boeblingen/boeblingen.osm.pbf" ;
    public static final String BOEBLINGEN_GTFS = "src/test/resources/boeblingen/bus-751-only.gtfs.zip" ;

    public static final String VAIHINGEN_NORD_OSM = "src/test/resources/stuttgart/vaihingen-nord.osm.pbf" ;

    private static ConstantsForTests instance = null;

    private Graph portlandGraph = null;

    private GtfsContext portlandContext = null;

    private Graph vermontGraph = null;

    private ConstantsForTests() {

    }

    public static ConstantsForTests getInstance() {
        if (instance == null) {
            instance = new ConstantsForTests();
        }
        return instance;
    }

    public GtfsContext getPortlandContext() {
        if (portlandGraph == null) {
            setupPortland();
        }
        return portlandContext;
    }

    public Graph getPortlandGraph() {
        if (portlandGraph == null) {
            setupPortland();
        }
        return portlandGraph;
    }

    private void setupPortland() {
        try {
            portlandContext = GtfsLibrary.readGtfs(new File(ConstantsForTests.PORTLAND_GTFS));
            portlandGraph = new Graph();
            PatternHopFactory factory = new PatternHopFactory(portlandContext);
            factory.run(portlandGraph);
            TransferGraphLinker linker = new TransferGraphLinker(portlandGraph);
            linker.run();
            // TODO: eliminate GTFSContext
            // this is now making a duplicate calendarservicedata but it's oh so practical
            portlandGraph.putService(
                    CalendarServiceData.class,
                    createCalendarServiceData(portlandContext.getOtpTransitService())
            );
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        StreetLinkerModule ttsnm = new StreetLinkerModule();
        ttsnm.buildGraph(portlandGraph, new HashMap<Class<?>, Object>());
    }

    public Graph getVermontGraph() {
        if (vermontGraph == null) {
            vermontGraph = getGraph(VERMONT_OSM, VERMONT_GTFS);
            vermontGraph.setUseFlexService(true);
        }
        return vermontGraph;
    }

    private Graph getGraph(String osmFile, String gtfsFile) {
        try {
            Graph g = new Graph();
            OpenStreetMapModule loader = new OpenStreetMapModule();
            loader.setDefaultWayPropertySetSource(new DefaultWayPropertySetSource());
            AnyFileBasedOpenStreetMapProviderImpl provider = new AnyFileBasedOpenStreetMapProviderImpl();

            File file = new File(
                    URLDecoder.decode(this.getClass().getResource(osmFile).getFile(),
                            "UTF-8"));

            provider.setPath(file);
            loader.setProvider(provider);

            loader.buildGraph(g, new HashMap<>());

            GtfsContext ctx = GtfsLibrary.readGtfs(new File(
                    URLDecoder.decode(this.getClass().getResource(gtfsFile).getFile(),
                            "UTF-8")));
            PatternHopFactory factory = new PatternHopFactory(ctx);
            factory.run(g);

            CalendarServiceData csd =  createCalendarServiceData(ctx.getOtpTransitService());
            g.putService(CalendarServiceData.class, csd);
            g.updateTransitFeedValidity(csd);
            g.hasTransit = true;

            new DirectTransferGenerator(2000).buildGraph(g, new HashMap<>());

            new StreetLinkerModule().buildGraph(g, new HashMap<>());

            g.index(new DefaultStreetVertexIndexFactory());

            return g;
        } catch(Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static Graph buildGraph(String path) {
        GtfsContext context;
        try {
            context = GtfsLibrary.readGtfs(new File(path));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        Graph graph = new Graph();
        PatternHopFactory factory = new PatternHopFactory(context);
        factory.run(graph);
        graph.putService(
                CalendarServiceData.class,
                createCalendarServiceData(context.getOtpTransitService())
        );
        return graph;
    }

}
