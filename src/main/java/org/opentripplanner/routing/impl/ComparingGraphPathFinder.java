package org.opentripplanner.routing.impl;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.opentripplanner.api.parameter.QualifiedMode;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.standalone.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This variant of the GraphPathFinder "fans out" a routing request into potentially multiple ones and compares the
 * sets of results. It can then discard nonsensical or inferiour results.
 * <p>
 * This is useful for the mode PARK_RIDE because this mode forces you to drive to a P&R station and take public transport
 * even if it would be _a lot_ faster to just drive to the destination all the way.
 */
public class ComparingGraphPathFinder extends GraphPathFinder {

    private static final Logger LOG = LoggerFactory.getLogger(ComparingGraphPathFinder.class);

    public ComparingGraphPathFinder(Router router) {
        super(router);
    }

    @Override
    public List<GraphPath> graphPathFinderEntryPoint(RoutingRequest options) {

        List<GraphPath> results;
        if (options.parkAndRide) {
            LOG.debug("Detected a P&R routing request. Will execute two requests to also get car-only routes.");

            // cloning needs to be happen beforehand to prevent a race condition
            RoutingRequest clonedOptions = options.clone();
            // car-only
            CompletableFuture<List<GraphPath>> carOnlyF = CompletableFuture.supplyAsync(() -> runCarOnlyRequest(clonedOptions));
            // the normal P&R
            CompletableFuture<List<GraphPath>> parkAndRideF = CompletableFuture.supplyAsync(() -> new GraphPathFinder(router).graphPathFinderEntryPoint(options));
            // the CompletableFutures are there to make sure that the computations run in parallel
            List<List<GraphPath>> allResults = Stream.of(parkAndRideF, carOnlyF)
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            List<GraphPath> parkAndRide = allResults.get(0);
            List<GraphPath> carOnly = allResults.get(1);

            results = filterOut(parkAndRide, carOnly);

        } else {
            results = super.graphPathFinderEntryPoint(options);
        }

        return results;
    }


    private List<GraphPath> runCarOnlyRequest(RoutingRequest clonedOptions) {
        clonedOptions.parkAndRide = false;
        clonedOptions.setModes(new TraverseModeSet(TraverseMode.CAR));
        List<GraphPath> results;
        try {
             results = new GraphPathFinder(router).graphPathFinderEntryPoint(clonedOptions);
        } catch(Exception e)  {
            throw e;
        }
        finally {
            clonedOptions.cleanup();
        }

        return results;
    }

    /**
     * We filter out unsuitable P+R routes.
     *
     * Right now "unsuitable" means that driving to the P+R is more than 50% of the distance of
     * driving all the way to the destination.
     */
    private List<GraphPath> filterOut(List<GraphPath> parkAndRide, List<GraphPath> carOnly) {
        if (!carOnly.isEmpty()) {
            double halfDistanceOfCarOnly = carOnly.get(0).streetMeters() / 2;
            List<GraphPath> onlyFastOnes = parkAndRide.stream().filter(graphPath -> graphPath.streetMeters() < halfDistanceOfCarOnly).collect(Collectors.toList());
            if (haveGraphsBeenFilteredOut(parkAndRide, onlyFastOnes)) {
                return Lists.newArrayList(Iterables.concat(onlyFastOnes, carOnly));
            } else return parkAndRide;
        } else return parkAndRide;
    }

    private boolean haveGraphsBeenFilteredOut(List<GraphPath> parkAndRide, List<GraphPath> onlyFastOnes) {
        return onlyFastOnes.size() < parkAndRide.size();
    }
}
