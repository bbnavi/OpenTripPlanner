package org.opentripplanner.api.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.opentripplanner.api.model.alertpatch.LocalizedAlert;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.profile.BikeRentalStationInfo;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.graph.Edge;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.Lists;

/**
 * Represents one instruction in walking directions. Three examples from New York City:
 * <p>
 * Turn onto Broadway from W 57th St (coming from 7th Ave):
 * <pre>
 *  distance = 100 (say) 
 *  walkDirection = RIGHT 
 *  streetName = Broadway 
 *  everything else null/false 
 * </pre>
 * </p>
 * <p>
 * Now, turn from Broadway onto Central Park S via Columbus Circle
 * <pre>
 *  distance = 200 (say) 
 *  walkDirection = CIRCLE_COUNTERCLOCKWISE 
 *  streetName = Central Park S 
 *  exit = 1 (first exit) 
 *  immediately everything else false 
 * </pre>
 * </p>
 * <p>
 * Instead, go through the circle to continue on Broadway
 * <pre>
 *  distance = 100 (say)
 *  walkDirection = CIRCLE_COUNTERCLOCKWISE
 *  streetName = Broadway
 *  exit = 3
 *  stayOn = true
 *  everything else false
 * </pre>
 * </p>
 * */
public class WalkStep {

    /**
     * The distance in meters that this step takes.
     */
    public double distance = 0;

    /**
     * The relative direction of this step.
     */
    public RelativeDirection relativeDirection;

    /**
     * The name of the street.
     */
    public String streetName;

    /**
     * The absolute direction of this step.
     */
    public AbsoluteDirection absoluteDirection;

    /**
     * When exiting a highway or traffic circle, the exit name/number.
     */

    public String exit;

    /**
     * Indicates whether or not a street changes direction at an intersection.
     */
    public Boolean stayOn = false;

    /**
     * This step is on an open area, such as a plaza or train platform, and thus the directions should say something like "cross"
     */
    public Boolean area = false;

    /**
     * The name of this street was generated by the system, so we should only display it once, and generally just display right/left directions
     */
    public Boolean bogusName = false;

    /**
     * The longitude of start of the step
     */
    public double lon;

    /**
     * The latitude of start of the step
     */
    public double lat;

    /**
     * The elevation profile as a comma-separated list of x,y values. x is the distance from the start of the step, y is the elevation at this
     * distance.
     */
    public List<P2<Double>> elevation;

    @JsonSerialize
    public List<LocalizedAlert> alerts;

    public transient double angle;

    /**
     * The walkStep's mode; only populated if this is the first step of that mode in the leg.
     * Used only in generating the streetEdges array in StreetSegment; not serialized. 
     */
    public transient String newMode;

    /**
     * The street edges that make up this walkStep.
     * Used only in generating the streetEdges array in StreetSegment; not serialized. 
     */
    public transient List<Edge> edges = Lists.newArrayList();

    /**
     * The bike rental on/off station info.
     * Used only in generating the streetEdges array in StreetSegment; not serialized. 
     */
    public transient BikeRentalStationInfo bikeRentalOnStation, bikeRentalOffStation;

    public void setDirections(double lastAngle, double thisAngle, boolean roundabout) {
        relativeDirection = getRelativeDirection(lastAngle, thisAngle, roundabout);
        setAbsoluteDirection(thisAngle);
    }

    public String toString() {
        String direction = absoluteDirection.toString();
        if (relativeDirection != null) {
            direction = relativeDirection.toString();
        }
        return "WalkStep(" + direction + " on " + streetName + " for " + distance + ")";
    }

    public static RelativeDirection getRelativeDirection(double lastAngle, double thisAngle,
            boolean roundabout) {

        double angleDiff = thisAngle - lastAngle;
        if (angleDiff < 0) {
            angleDiff += Math.PI * 2;
        }
        double ccwAngleDiff = Math.PI * 2 - angleDiff;

        if (roundabout) {
            // roundabout: the direction we turn onto it implies the circling direction
            if (angleDiff > ccwAngleDiff) {
                return RelativeDirection.CIRCLE_CLOCKWISE;
            } else {
                return RelativeDirection.CIRCLE_COUNTERCLOCKWISE;
            }
        }

        // less than 0.3 rad counts as straight, to simplify walking instructions
        if (angleDiff < 0.3 || ccwAngleDiff < 0.3) {
            return RelativeDirection.CONTINUE;
        } else if (angleDiff < 0.7) {
            return RelativeDirection.SLIGHTLY_RIGHT;
        } else if (ccwAngleDiff < 0.7) {
            return RelativeDirection.SLIGHTLY_LEFT;
        } else if (angleDiff < 2) {
            return RelativeDirection.RIGHT;
        } else if (ccwAngleDiff < 2) {
            return RelativeDirection.LEFT;
        } else if (angleDiff < Math.PI) {
            return RelativeDirection.HARD_RIGHT;
        } else {
            return RelativeDirection.HARD_LEFT;
        }
    }

    public void setAbsoluteDirection(double thisAngle) {
        int octant = (int) (8 + Math.round(thisAngle * 8 / (Math.PI * 2))) % 8;
        absoluteDirection = AbsoluteDirection.values()[octant];
    }

    public void addAlerts(Collection<Alert> newAlerts, Locale locale) {
        if (newAlerts == null) {
            return;
        }
        if (alerts == null) {
            alerts = new ArrayList<>();
        }
        ALERT: for (Alert newAlert : newAlerts) {
            for (LocalizedAlert alert : alerts) {
                if (alert.alert.equals(newAlert)) {
                    break ALERT;
                }
            }
            alerts.add(new LocalizedAlert(newAlert, locale));
        }
    }

    public String streetNameNoParens() {
        int idx = streetName.indexOf('(');
        if (idx <= 0) {
            return streetName;
        }
        return streetName.substring(0, idx - 1);
    }

    @JsonSerialize
    public List<P2<Double>> getElevation() {
        return elevation;
    }

}
