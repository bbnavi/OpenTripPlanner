package org.opentripplanner.routing.edgetype;

import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.BikeParkVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import java.util.Locale;

/**
 * This represents the connection between a street vertex and a bike park vertex.
 * 
 * Bike park-and-ride and "OV-fiets mode" development has been funded by GoAbout
 * (https://goabout.com/).
 * 
 * @author laurent
 * @author GoAbout
 */
public class StreetBikeParkLink extends Edge {

    private static final long serialVersionUID = 1L;

    private BikeParkVertex bikeParkVertex;

    public StreetBikeParkLink(StreetVertex fromv, BikeParkVertex tov) {
        super(fromv, tov);
        bikeParkVertex = tov;
    }

    public StreetBikeParkLink(BikeParkVertex fromv, StreetVertex tov) {
        super(fromv, tov);
        bikeParkVertex = fromv;
    }

    public String getDirection() {
        return null;
    }

    public double getDistance() {
        return 0;
    }

    public LineString getGeometry() {
        // Return straight line beetween the bike park and the street
        Coordinate[] coordinates = new Coordinate[] { getFromVertex().getCoordinate(),
                getToVertex().getCoordinate() };
        return GeometryUtils.getGeometryFactory().createLineString(coordinates);
    }

    public String getName() {
        return bikeParkVertex.getName();
    }

    public String getName(Locale locale) {
        return bikeParkVertex.getName(locale);
    }

    public State traverse(State s0) {
        RoutingRequest options = s0.getOptions();
        // Disallow traversing two StreetBikeParkLinks in a row.
        // Prevents router using bike rental stations as shortcuts to get around
        // turn restrictions.
        if (s0.getBackEdge() instanceof StreetBikeParkLink) {
            return null;
        }
        // Do not even consider bike park vertices unless bike P+R or bike rental is enabled.
        else if (options.bikeParkAndRide || options.allowBikeRental) {
            StateEditor s1 = s0.edit(this);
            // Assume bike park are more-or-less on-street
            s1.incrementTimeInSeconds(1);
            s1.incrementWeight(1);
            // Do not force any mode, will use the latest one (walking bike or bike)
            return s1.makeState();
        }
        else return null;
    }

    @Override
    public double weightLowerBound(RoutingRequest options) {
        return options.modes.contains(TraverseMode.BICYCLE) ? 0 : Double.POSITIVE_INFINITY;
    }

    public Vertex getFromVertex() {
        return fromv;
    }

    public Vertex getToVertex() {
        return tov;
    }

    public String toString() {
        return "StreetBikeParkLink(" + fromv + " -> " + tov + ")";
    }
}
