package org.opentripplanner.routing.edgetype;

import org.opentripplanner.common.MavenVersion;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.vertextype.ParkAndRideVertex;

import org.locationtech.jts.geom.LineString;
import java.util.Locale;

/**
 * Parking a car at a park-and-ride station.
 * 
 * We only allow going from CAR to WALK mode. Routing the opposite way would need more information
 * (ie knowing where you park your car in the first place), and is probably better handled by a
 * two-step routing (in walk mode from origin to parking, then in car mode from the parking to
 * destination).
 * 
 * @author laurent
 * 
 */
public class ParkAndRideEdge extends Edge {

    private static final long serialVersionUID = MavenVersion.VERSION.getUID();

    public ParkAndRideEdge(ParkAndRideVertex parkAndRide) {
        super(parkAndRide, parkAndRide);
    }

    @Override
    public State traverse(State s0) {
        RoutingRequest request = s0.getOptions();
        if (!request.parkAndRide) {
            return null;
        }
        if(request.useCarParkAvailabilityInformation
                && request.isTripPlannedForNow()
                && ((ParkAndRideVertex) tov).hasFewSpacesAvailable()){
            return null;
        }
        if(!request.wheelchairAccessible && ((ParkAndRideVertex) tov).hasOnlyDisabledSpaces()){
            return null;
        }
        if (request.arriveBy) {
            /*
             * To get back a car, we need to walk and have car mode enabled.
             */
            if (s0.getNonTransitMode() != TraverseMode.WALK) {
                return null;
            }
            if (!s0.isCarParked()) {
                throw new IllegalStateException("Stolen car?");
            }
            StateEditor s1 = s0.edit(this);
            int time = request.carDropoffTime;
            s1.incrementWeight(time);
            s1.incrementTimeInSeconds(time);
            s1.setCarParked(false);
            s1.setBackMode(TraverseMode.LEG_SWITCH);
            return s1.makeState();
        } else {
            /*
             * To park a car, we need to be in one and have allowed walk modes.
             */
            if (s0.isBikeRenting() || s0.getNonTransitMode() != TraverseMode.CAR) {
                return null;
            }
            if (s0.isCarParked()) {
                throw new IllegalStateException("Can't drive 2 cars");
            }
            StateEditor s1 = s0.edit(this);
                 
            int time = request.carDropoffTime;
            s1.incrementWeight(time);
            final double multiplier = (request.carParkCarLegWeight - 1);
            s1.incrementWeight(s0.getWeight() * multiplier);
            s1.incrementTimeInSeconds(time);
            s1.setCarParked(true);
            s1.setBackMode(TraverseMode.LEG_SWITCH);
            return s1.makeState();
        }
    }

    @Override
    public double getDistance() {
        return 0;
    }

    @Override
    public LineString getGeometry() {
        return null;
    }

    @Override
    public String getName() {
        return getToVertex().getName();
    }

    @Override
    public String getName(Locale locale) {
        return getToVertex().getName(locale);
    }

    @Override
    public boolean hasBogusName() {
        return false;
    }
}
