package org.opentripplanner.routing.core;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.api.model.TripPlan;
import org.opentripplanner.api.resource.GraphPathToTripPlanConverter;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.standalone.Router;
import org.opentripplanner.util.TestUtils;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.opentripplanner.routing.core.PolylineAssert.assertThatPolylinesAreEqual;

public class BicycleRoutingTest {

    static Graph graph;

    static GenericLocation nebringen = new GenericLocation(48.56494, 8.85318);
    static GenericLocation herrenbergMarketSquare = new GenericLocation(48.59634, 8.87015);

    static GenericLocation herrenbergErhardtstBismarckstr = new GenericLocation(48.59247, 8.86811);
    static GenericLocation herrenbergMarkusstrMarienstr = new GenericLocation(48.59329, 8.87253);


    static long dateTime = TestUtils.dateInSeconds("Europe/Berlin", 2020, 03, 10, 11, 0, 0);

    @BeforeClass
    public static void setUp() {
        graph = TestGraphBuilder.buildGraph(ConstantsForTests.NEBRINGEN_HERRENBERG_OSM);
    }

    private static String calculatePolyline(Graph graph, GenericLocation from, GenericLocation to) {
        RoutingRequest request = new RoutingRequest();
        request.dateTime = dateTime;
        request.from = from;
        request.to = to;

        request.modes = new TraverseModeSet(TraverseMode.BICYCLE);

        request.setNumItineraries(1);
        request.setRoutingContext(graph);
        request.setOptimize(OptimizeType.GREENWAYS); // this is the default setting in digitransit

        GraphPathFinder gpf = new GraphPathFinder(new Router(graph.routerId, graph));
        List<GraphPath> paths = gpf.graphPathFinderEntryPoint(request);

        TripPlan plan = GraphPathToTripPlanConverter.generatePlan(paths, request);
        return plan.itinerary.get(0).legs.get(0).legGeometry.getPoints();
    }

    @Test
    public void useBikeNetworkRoutesFromNebringenToHerrenberg() {
        var polyline = calculatePolyline(graph, nebringen, herrenbergMarketSquare);
        assertThatPolylinesAreEqual(polyline, "{ilgHgc`u@IGC[y@?@qAZaCAKEMGKSCe@CWEOe@GGgCoNa@mEWqCo@eHI_Ay@wIYeDo@kHe@yEu@gGm@aFWmBkHgEKCeC\\gFn@M_@NoAWAOGSWRi@zAsFYMIOc@aAEIo@yAGIG\\IZ]p@e@v@u@|@m@f@cCn@?pBGt@Kx@[fB]bBYx@c@`Ag@x@g@b@S@YEa@Ge@OeBw@eAa@a@Cc@JiBv@_@P]FS@o@KsAg@qAe@e@Co@HyBh@MFMHKLEPCLi@Po@PqAT_@N_Ad@c@Nk@LYJ]JU@i@?w@Cg@?G@K?G??[COGOkAwBs@cBK]]oAa@mAKu@]{ByAtAULg@Hq@Aq@EsCQa@CC?qB@]BkAJ_C^eCCqBGqAE[[o@GM_DQ_EK{Be@wCm@wDAGqB`AwB`AmB|@GDg@~@e@l@y@t@e@Rk@JsCh@@D?J@LWDGBAHKF?HSHG~@G?MC_@EAx@Gl@YZMb@CH?N?r@DpA");

    }

    @Test
    public void dontUseCycleNetworkInsideHerrenberg() {
        var polyline = calculatePolyline(graph, herrenbergErhardtstBismarckstr, herrenbergMarkusstrMarienstr);
        assertThatPolylinesAreEqual(polyline, "}uqgHs`cu@AK?Cm@iEIo@?eD]UFe@c@iD]kB_@sAACCIRQ");
    }

    @Test
    public void shouldUseDesignatedTrackNearHorberStr() {
        GenericLocation herrenbergPoststr = new GenericLocation(48.59370,8.86446);
        GenericLocation herrenberGueltsteinerStr = new GenericLocation(48.58935, 8.86991);

        var polyline = calculatePolyline(graph, herrenbergPoststr, herrenberGueltsteinerStr);
        assertThatPolylinesAreEqual(polyline, "q}qgHwibu@VQHIBG@BBDJLBMDMBIDIHGFEHCJELEd@MPEZK^K^K^K`@KrD?pA@h@ArBW~A[V@VGxAYi@oFaBwNQ}AZ@");

        GenericLocation herrenbergKalkofenStr = new GenericLocation(48.59483, 8.86202);
        polyline = calculatePolyline(graph, herrenbergKalkofenStr, herrenberGueltsteinerStr);
        assertThatPolylinesAreEqual(polyline, "{drgHszau@?G\\ENDDEl@UHJ?Dl@e@LIXSVS@AJDL@Mg@@EXXBAS}@AE?CEg@Sw@Ok@AMCSWeALIhAu@HIBG@BBDJLBMDMBIDIHGFEHCJELEd@MPEZK^K^K^K`@KrD?pA@h@ArBW~A[V@VGxAYi@oFaBwNQ}AZ@");
    }
}