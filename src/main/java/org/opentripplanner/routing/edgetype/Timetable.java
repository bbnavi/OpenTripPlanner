package org.opentripplanner.routing.edgetype;

import com.beust.jcommander.internal.Lists;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import org.opentripplanner.common.MavenVersion;
import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Trip;
import org.opentripplanner.model.calendar.ServiceDate;
import org.opentripplanner.routing.core.ServiceDay;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StopTransfer;
import org.opentripplanner.routing.core.TransferTable;
import org.opentripplanner.routing.trippattern.FrequencyEntry;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;


/**
 * Timetables provide most of the TripPattern functionality. Each TripPattern may possess more than
 * one Timetable when stop time updates are being applied: one for the scheduled stop times, one for
 * each snapshot of updated stop times, another for a working buffer of updated stop times, etc.
 */
public class Timetable implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(Timetable.class);
    private static final long serialVersionUID = MavenVersion.VERSION.getUID();

    /**
     * A circular reference between TripPatterns and their scheduled (non-updated) timetables.
     */
    public final TripPattern pattern;

    /**
     * Contains one TripTimes object for each scheduled trip (even cancelled ones) and possibly
     * additional TripTimes objects for unscheduled trips. Frequency entries are stored separately.
     */
    public final List<TripTimes> tripTimes = Lists.newArrayList();

    /**
     * Contains one FrequencyEntry object for each block of frequency-based trips.
     */
    public final List<FrequencyEntry> frequencyEntries = Lists.newArrayList();

    /**
     * The ServiceDate for which this (updated) timetable is valid. If null, then it is valid for all dates.
     */
    public final ServiceDate serviceDate;

    /**
     * For each hop, the best running time. This serves to provide lower bounds on traversal time.
     */
    private transient int minRunningTimes[];

    /**
     * For each stop, the best dwell time. This serves to provide lower bounds on traversal time.
     */
    private transient int minDwellTimes[];

    /**
     * Helps determine whether a particular pattern is worth searching for departures at a given time.
     */
    private transient int minTime, maxTime;

    /** Construct an empty Timetable. */
    public Timetable(TripPattern pattern) {
        this.pattern = pattern;
        this.serviceDate = null;
    }

    /** Construct an empty Timetable with a specified serviceDate. */
    public Timetable(TripPattern pattern, ServiceDate serviceDate) {
        this.pattern = pattern;
        this.serviceDate = serviceDate;
    }

    /**
     * Copy constructor: create an un-indexed Timetable with the same TripTimes as the specified timetable.
     */
    Timetable (Timetable tt, ServiceDate serviceDate) {
        tripTimes.addAll(tt.tripTimes);
        this.serviceDate = serviceDate;
        this.pattern = tt.pattern;
    }

    /**
     * Before performing the relatively expensive iteration over all the trips in this pattern, check whether it's even
     * possible to board any of them given the time at which we are searching, and whether it's possible that any of
     * them could improve on the best known time. This is only an optimization, but a significant one. When we search
     * for departures, we look at three separate days: yesterday, today, and tomorrow. Many patterns do not have
     * service at all hours of the day or past midnight. This optimization can cut the search time for each pattern
     * by 66 to 100 percent.
     *
     * @param bestWait -1 means there is not yet any best known time.
     */
    public boolean temporallyViable(ServiceDay sd, long searchTime, int bestWait, boolean boarding) {
        if (this.pattern.services == null)
            return true;
        // Check whether any services are running at all on this pattern.
        if ( ! sd.anyServiceRunning(this.pattern.services)) return false;
        // Make the search time relative to the given service day.
        searchTime = sd.secondsSinceMidnight(searchTime);
        // Check whether any trip can be boarded at all, given the search time
        if (boarding ? (searchTime > this.maxTime) : (searchTime < this.minTime)) return false;
        // Check whether any trip can improve on the best time yet found
        if (bestWait >= 0) {
            long bestTime = boarding ? (searchTime + bestWait) : (searchTime - bestWait);
            if (boarding ? (bestTime < this.minTime) : (bestTime > this.maxTime)) return false;
        }
        return true;
    }

    /**
     * Get the next (previous) trip that departs (arrives) from the specified stop at or after
     * (before) the specified time.
     *
     * For GTFS-Flex service, this method will take into account the fact that the passenger
     * may be boarding or alighting midway between stops (flag stops), and that the vehicle may
     * be deviating off-route to pick up or drop off the passenger (deviated-route service.) In
     * both cases, unscheduled timepoints on the route in between scheduled stops are calculated
     * via linear interpolation.
     *
     * The parameters `flexOffsetScale`, `preBoardDirectTime`, and `postAlightDirectTime` are
     * specific to GTFS-Flex service. If arrivals/departures are being evaluated for default
     * fixed-route service, these parameters will have the value 0 (see
     * {@link #getNextTrip(State, ServiceDay, int, boolean)}).
     *
     * @param s0 State to evaluate the method at; the method uses the state's time.
     * @param serviceDay Only consider trips if their service_id is active on this day
     * @param stopIndex Index of stop in the trip to evaluate departure or arrival at
     * @param boarding If true, find next trip which departs specified stop; if false, find
     *                 previous trip which arrives at specified stop
     * @param flexOffsetScale For GTFS-Flex routing, a control parameter to determine the amount of
     *                        seconds to offset the scheduled timepoint due to a board/alight in
     *                        the middle of a
     *                        {@link org.opentripplanner.routing.edgetype.flex.FlexPatternHop}.
     *                        Use the percentage in [0, 1] ([-1, 0]) from the beginning (end) of
     *                        the hop at which the board (alight) is taking place. Note that the
     *                        value is expected to be negative when evaluating alight points. Use
     *                        0 if this is not a flag stop or deviated-route board (alight) point.
     * @param flexPreBoardDirectTime For GTFS-Flex routing, the amount of time the vehicle travels
     *                               before rejoining the route, or 0 if this is not a deviated-
     *                               route board (alight) point.
     * @param flexPostAlightDirectTime For GTFS-Flex routing, the amount of time the vehicle
     *                                 travels after leaving the route, or 0 if this is not a
     *                                 deviated-route board (alight) point.
     *
     * @return the TripTimes object representing the (possibly updated) best trip, or null if no
     * trip matches both the time and other criteria.
     */
    public TripTimes getNextTrip(State s0, ServiceDay serviceDay, int stopIndex, boolean boarding, double flexOffsetScale,
                                 int flexPreBoardDirectTime, int flexPostAlightDirectTime) {
        /* Search at the state's time, but relative to midnight on the given service day. */
        int time = serviceDay.secondsSinceMidnight(s0.getTimeSeconds());
        // NOTE the time is sometimes negative here. That is fine, we search for the first trip of the day.
        // Adjust for possible boarding time TODO: This should be included in the trip and based on GTFS
        if (boarding) {
            time += s0.getOptions().getBoardTime(this.pattern.mode);
        } else {
            time -= s0.getOptions().getAlightTime(this.pattern.mode);
        }
        TripTimes bestTrip = null;
        Stop currentStop = pattern.getStop(stopIndex);
        // Linear search through the timetable looking for the best departure.
        // We no longer use a binary search on Timetables because:
        // 1. we allow combining trips from different service IDs on the same tripPattern.
        // 2. We mix frequency-based and one-off TripTimes together on tripPatterns.
        // 3. Stoptimes may change with realtime updates, and we cannot count on them being sorted.
        //    The complexity of keeping sorted indexes up to date does not appear to be worth the
        //    apparently minor speed improvement.
        int bestTime = boarding ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        // Hoping JVM JIT will distribute the loop over the if clauses as needed.
        // We could invert this and skip some service days based on schedule overlap as in RRRR.

        boolean omitCanceled =  s0.getOptions().omitCanceled;

        boolean isReverseOptimizing = s0.getOptions().reverseOptimizing;
        int requestStartTime = serviceDay.secondsSinceMidnight(s0.getOptions().getSecondsSinceEpoch());

        for (TripTimes tt : tripTimes) {
            if (tt.isCanceled() && omitCanceled) continue;
            if ((tt.getNumStops() <= stopIndex)) continue;
            if ( ! serviceDay.serviceRunning(tt.serviceCode)) continue; // TODO merge into call on next line
            if ( ! tt.tripAcceptable(s0, stopIndex)) continue;
            int adjustedTime = adjustTimeForTransfer(s0, currentStop, tt.trip, boarding, serviceDay, time);
            if (adjustedTime == -1) continue;
            if (boarding) {
                if (tt.isCanceledDeparture(stopIndex) && omitCanceled) continue; // negative values were previously used for canceled trips/passed stops/skipped stops, but
                // For GTFS-Flex, if this is a flag-stop or deviated-route board/alight, we need to
                // add to the scheduled timepoint the amount of time the vehicle travels along the
                // hop before the board/alight, and subtract the amount of time the vehicle travels
                // off-route before rejoining the route. Both these values are 0 for regular fixed-
                // route board/alights.
                int flexTimeAdjustment = 0;
                if (flexOffsetScale != 0 || flexPreBoardDirectTime != 0) {
                    int timeIntoHop = 0;
                    if (stopIndex + 1 < tt.getNumStops() && flexOffsetScale != 0.0) {
                        timeIntoHop = (int) Math.round(flexOffsetScale * tt.getRunningTime(stopIndex));
                    }
                    int vehicleTime = (flexPreBoardDirectTime == 0) ? 0 : tt.getDemandResponseMaxTime(flexPreBoardDirectTime);
                    flexTimeAdjustment = timeIntoHop - vehicleTime;
                }

                int depTime = tt.getDepartureTime(stopIndex) + flexTimeAdjustment;
                if (depTime < 0) continue; // negative values were previously used for canceled trips/passed stops/skipped stops, but
                                           // now its not sure if this check should be still in place because there is a boolean field
                                           // for canceled trips
                if (depTime >= adjustedTime && depTime < bestTime) {
                    bestTrip = tt;
                    bestTime = depTime;
                }
            } else {
                if (tt.isCanceledArrival(stopIndex) && omitCanceled) continue;
                // For GTFS-Flex, subtract from the scheduled timepoint the amount of time left in
                // the hop after the vehicle drops off the passenger (note flexOffsetScale < 0
                // in this case), and add the amount of time the vehicle travels off-route before
                // the passenger alights.
                int flexTimeAdjustment = 0;
                if (flexOffsetScale != 0 || flexPostAlightDirectTime != 0) {
                    int timeIntoHop = 0;
                    if (stopIndex - 1 >= 0 && flexOffsetScale != 0.0) {
                        timeIntoHop = (int) Math.round(flexOffsetScale * tt.getRunningTime(stopIndex - 1));
                    }
                    int vehicleTime = (flexPostAlightDirectTime == 0) ? 0 : tt.getDemandResponseMaxTime(flexPostAlightDirectTime);
                    flexTimeAdjustment = timeIntoHop + vehicleTime;
                }

                int arvTime = tt.getArrivalTime(stopIndex) + flexTimeAdjustment;
                if (arvTime < 0) continue;
                if (isReverseOptimizing && arvTime < requestStartTime) {
                    continue;
                }
                if (arvTime <= adjustedTime && arvTime > bestTime) {
                    bestTrip = tt;
                    bestTime = arvTime;
                }
            }
        }
        // ACK all logic is identical to above.
        // A sign that FrequencyEntries and TripTimes need a common interface.
        FrequencyEntry bestFreq = null;
        for (FrequencyEntry freq : frequencyEntries) {
            TripTimes tt = freq.tripTimes;
            if (tt.isCanceled()) continue;
            if ( ! serviceDay.serviceRunning(tt.serviceCode)) continue; // TODO merge into call on next line
            if ( ! tt.tripAcceptable(s0, stopIndex)) continue;
            int adjustedTime = adjustTimeForTransfer(s0, currentStop, tt.trip, boarding, serviceDay, time);
            if (adjustedTime == -1) continue;
            LOG.debug("  running freq {}", freq);
            if (boarding) {
                int depTime = freq.nextDepartureTime(stopIndex, adjustedTime); // min transfer time included in search
                if (depTime < 0) continue;
                if (depTime >= adjustedTime && depTime < bestTime) {
                    bestFreq = freq;
                    bestTime = depTime;
                }
            } else {
                int arvTime = freq.prevArrivalTime(stopIndex, adjustedTime); // min transfer time included in search
                if (arvTime < 0) continue;
                if (arvTime <= adjustedTime && arvTime > bestTime) {
                    bestFreq = freq;
                    bestTime = arvTime;
                }
            }
        }
        if (bestFreq != null) {
            // A FrequencyEntry beat all the TripTimes.
            // Materialize that FrequencyEntry entry at the given time.
            bestTrip = bestFreq.tripTimes.timeShift(stopIndex, bestTime, boarding);
        }
        return bestTrip;
    }

    /**
     * Get the next (previous) trip that departs (arrives) from the specified stop at or after
     * (before) the specified time.
     *
     * @param s0 State to evaluate the method at; the method uses the state's time.
     * @param serviceDay Only consider trips if their service_id is active on this day
     * @param stopIndex index of stop in the trip to evaluate departure or arrival at
     * @param boarding if true, find next trip which departs specified stop; if false, find
     *                 previous trip which arrives at specified stop
     *
     * @return the TripTimes object representing the (possibly updated) best trip, or null if no
     * trip matches both the time and other criteria.
     */
    public TripTimes getNextTrip(State s0, ServiceDay serviceDay, int stopIndex, boolean boarding) {
        return getNextTrip(s0, serviceDay, stopIndex, boarding, 0, 0, 0);
    }

    // could integrate with getNextTrip
    public TripTimes getNextCallNRideTrip(State s0, ServiceDay serviceDay, int stopIndex, boolean boarding, int directTime) {
        /* Search at the state's time, but relative to midnight on the given service day. */
        int time = serviceDay.secondsSinceMidnight(s0.getTimeSeconds());
        // NOTE the time is sometimes negative here. That is fine, we search for the first trip of the day.
        // Adjust for possible boarding time TODO: This should be included in the trip and based on GTFS
        if (boarding) {
            time += s0.getOptions().getBoardTime(this.pattern.mode);
        } else {
            time -= s0.getOptions().getAlightTime(this.pattern.mode);
        }
        TripTimes bestTrip = null;
        Stop currentStop = pattern.getStop(stopIndex);
        long bestTime = boarding ? Long.MAX_VALUE : Long.MIN_VALUE;
        boolean useClockTime = !s0.getOptions().flexIgnoreDrtAdvanceBookMin;
        long clockTime = s0.getOptions().clockTimeSec;
        for (TripTimes tt : tripTimes) {
            if (tt.isCanceled()) continue;
            if ( ! serviceDay.serviceRunning(tt.serviceCode)) continue; // TODO merge into call on next line
            if ( ! tt.tripAcceptable(s0, stopIndex)) continue;
            int adjustedTime = adjustTimeForTransfer(s0, currentStop, tt.trip, boarding, serviceDay, time);
            if (adjustedTime == -1) continue;
            if (boarding) {
                long depTime = tt.getCallAndRideBoardTime(stopIndex, adjustedTime, directTime, serviceDay, useClockTime, clockTime);
                if (depTime >= adjustedTime && depTime < bestTime && inBounds(depTime)) {
                    bestTrip = tt;
                    bestTime = depTime;
                }
            } else {
                long arvTime = tt.getCallAndRideAlightTime(stopIndex, adjustedTime, directTime, serviceDay, useClockTime, clockTime);
                if (arvTime < 0) continue;
                if (arvTime <= adjustedTime && arvTime > bestTime && inBounds(arvTime)) {
                    bestTrip = tt;
                    bestTime = arvTime;
                }
            }
        }

        return bestTrip;
    }

    private boolean inBounds(long time) {
        return time >= minTime && time <= maxTime;
    }

    /**
     * Check transfer table rules. Given the last alight time from the State,
     * return the boarding time t0 adjusted for this particular trip's minimum transfer time,
     * or -1 if boarding this trip is not allowed.
     * FIXME adjustedTime can legitimately be -1! But negative times might as well be zero.
     */
    private int adjustTimeForTransfer(State state, Stop currentStop, Trip trip, boolean boarding, ServiceDay serviceDay, int t0) {
        if ( ! state.isEverBoarded()) {
            // This is the first boarding not a transfer.
            return t0;
        }
        TransferTable transferTable = state.getOptions().getRoutingContext().transferTable;
        int transferTime = transferTable.getTransferTime(state.getPreviousStop(), currentStop, state.getPreviousTrip(), trip, boarding);
        // Check whether back edge is TimedTransferEdge
        if (state.getBackEdge() instanceof TimedTransferEdge) {
            // Transfer must be of type TIMED_TRANSFER
            if (transferTime != StopTransfer.TIMED_TRANSFER) {
                return -1;
            }
        }
        if (transferTime == StopTransfer.UNKNOWN_TRANSFER) {
            return t0; // no special rules, just board
        }
        if (transferTime == StopTransfer.FORBIDDEN_TRANSFER) {
            // This transfer is forbidden
            return -1;
        }
        // There is a minimum transfer time to make this transfer. Ensure that it is respected.
        int minTime = serviceDay.secondsSinceMidnight(state.getLastAlightedTimeSeconds());
        if (boarding) {
            minTime += transferTime;
            if (minTime > t0) return minTime;
        } else {
            minTime -= transferTime;
            if (minTime < t0) return minTime;
        }
        return t0;
    }

    /**
     * Finish off a Timetable once all TripTimes have been added to it. This involves caching
     * lower bounds on the running times and dwell times at each stop, and may perform other
     * actions to compact the data structure such as trimming and deduplicating arrays.
     */
    public void finish() {
        int nStops = pattern.stopPattern.size;
        int nHops = nStops - 1;
        /* Find lower bounds on dwell and running times at each stop. */
        minDwellTimes = new int[nHops];
        minRunningTimes = new int[nHops];
        Arrays.fill(minDwellTimes, Integer.MAX_VALUE);
        Arrays.fill(minRunningTimes, Integer.MAX_VALUE);
        // Concatenate raw TripTimes and those referenced from FrequencyEntries
        List<TripTimes> allTripTimes = Lists.newArrayList(tripTimes);
        for (FrequencyEntry freq : frequencyEntries) allTripTimes.add(freq.tripTimes);

        minTime = Integer.MAX_VALUE;
        maxTime = Integer.MIN_VALUE;

        for (TripTimes tt : allTripTimes) {
            if (tt.getNumStops() == nStops) {
                for (int h = 0; h < nHops; ++h) {
                    int dt = tt.getDwellTime(h);
                    if (minDwellTimes[h] > dt) {
                        minDwellTimes[h] = dt;
                    }
                    int rt = tt.getRunningTime(h);
                    if (minRunningTimes[h] > rt) {
                        minRunningTimes[h] = rt;
                    }
                }
                minTime = Math.min(minTime, tt.getDepartureTime(0));
                maxTime = Math.max(maxTime, tt.getArrivalTime(nStops - 1));
            }
        }

        for (TripTimes tt : tripTimes) {
            if (tt.getNumStops() == nStops) {
                minTime = Math.min(minTime, tt.getDepartureTime(0));
                maxTime = Math.max(maxTime, tt.getArrivalTime(nStops - 1));
            }
        }
        // Slightly repetitive code.
        // Again it seems reasonable to have a shared interface between FrequencyEntries and normal TripTimes.
        for (FrequencyEntry freq : frequencyEntries) {
            minTime = Math.min(minTime, freq.getMinDeparture());
            maxTime = Math.max(maxTime, freq.getMaxArrival());
        }
    }

    /** @return the index of TripTimes for this trip ID in this particular Timetable */
    public int getTripIndex(FeedScopedId tripId) {
        int ret = 0;
        for (TripTimes tt : tripTimes) {
            // could replace linear search with indexing in stoptime updater, but not necessary
            // at this point since the updater thread is far from pegged.
            if (tt.trip.getId().equals(tripId)) return ret;
            ret += 1;
        }
        return -1;
    }

    /** @return the matching Trip in this particular Timetable */
    public Trip getTrip(FeedScopedId tripId) {
        for (TripTimes tt : tripTimes) {
            if (tt.trip.getId().equals(tripId)) {
                return tt.trip;
            }
        }
        return null;
    }

    /** @return the index of TripTimes for this trip ID in this particular Timetable, ignoring AgencyIds. */
    public int getTripIndex(String tripId) {
        int ret = 0;
        for (TripTimes tt : tripTimes) {
            if (tt.trip.getId().getId().equals(tripId)) return ret;
            ret += 1;
        }
        return -1;
    }

    public TripTimes getTripTimes(int tripIndex) {
        return tripTimes.get(tripIndex);
    }

    public TripTimes getTripTimes(Trip trip) {
        for (TripTimes tt : tripTimes) {
            if (tt.trip == trip) return tt;
        }
        return null;
    }

    /**
     * Set new trip times for trip given a trip index
     *
     * @param tripIndex trip index of trip
     * @param tt new trip times for trip
     * @return old trip times of trip
     */
    public TripTimes setTripTimes(int tripIndex, TripTimes tt) {
        return tripTimes.set(tripIndex, tt);
    }

    /**
     * Apply the TripUpdate to the appropriate TripTimes from this Timetable. The existing TripTimes
     * must not be modified directly because they may be shared with the underlying
     * scheduledTimetable, or other updated Timetables. The {@link TimetableSnapshot} performs the
     * protective copying of this Timetable. It is not done in this update method to avoid
     * repeatedly cloning the same Timetable when several updates are applied to it at once. We
     * assume here that all trips in a timetable are from the same feed, which should always be the
     * case.
     *
     * @param tripUpdate GTFS-RT trip update
     * @param timeZone time zone of trip update
     * @param updateServiceDate service date of trip update
     * @return new copy of updated TripTimes after TripUpdate has been applied on TripTimes of trip
     *         with the id specified in the trip descriptor of the TripUpdate; null if something
     *         went wrong
     */
    public TripTimes createUpdatedTripTimes(TripUpdate tripUpdate, TimeZone timeZone, ServiceDate updateServiceDate) {
        if (tripUpdate == null) {
            LOG.error("A null TripUpdate pointer was passed to the Timetable class update method.");
            return null;
        }

        // Though all timetables have the same trip ordering, some may have extra trips due to
        // the dynamic addition of unscheduled trips.
        // However, we want to apply trip updates on top of *scheduled* times
        if (!tripUpdate.hasTrip()) {
            LOG.error("TripUpdate object has no TripDescriptor field.");
            return null;
        }

        TripDescriptor tripDescriptor = tripUpdate.getTrip();
        if (!tripDescriptor.hasTripId()) {
            LOG.error("TripDescriptor object has no TripId field");
            return null;
        }
        String tripId = tripDescriptor.getTripId();
        int tripIndex = getTripIndex(tripId);
        if (tripIndex == -1) {
            LOG.info("tripId {} not found in pattern.", tripId);
            return null;
        } else {
            LOG.trace("tripId {} found at index {} in timetable.", tripId, tripIndex);
        }

        TripTimes newTimes = new TripTimes(getTripTimes(tripIndex));

        if (tripDescriptor.hasScheduleRelationship() && tripDescriptor.getScheduleRelationship()
                == TripDescriptor.ScheduleRelationship.CANCELED) {
            newTimes.cancel();
        } else {
            //Whether the trip update has any stop estimates (some trip updates can contain only stop cancellations)
            boolean hasUpdates = tripUpdate.getStopTimeUpdateList().stream()
                                    .anyMatch(stopTimeUpdate -> !stopTimeUpdate.hasScheduleRelationship() || stopTimeUpdate.getScheduleRelationship() == StopTimeUpdate.ScheduleRelationship.SCHEDULED);

            // The GTFS-RT reference specifies that StopTimeUpdates are sorted by stop_sequence.
            Iterator<StopTimeUpdate> updates = tripUpdate.getStopTimeUpdateList().iterator();
            if (!updates.hasNext()) {
                LOG.warn("Won't apply zero-length trip update to trip {}.", tripId);
                return null;
            }
            StopTimeUpdate update = updates.next();

            int numStops = newTimes.getNumStops();
            Integer delay = null;
            Integer firstDelay = null;
            boolean hasMatched = false;
            for (int i = 0; i < numStops; i++) {
                boolean match = false;
                if (update != null) {
                    if (update.hasStopSequence()) {
                        match = update.getStopSequence() == newTimes.getStopSequence(i);
                    } else if (update.hasStopId()) {
                        match = pattern.getStop(i).getId().getId().equals(update.getStopId());
                    }
                }

                if (match) {
                    hasMatched = true;
                    StopTimeUpdate.ScheduleRelationship scheduleRelationship =
                            update.hasScheduleRelationship() ? update.getScheduleRelationship()
                            : StopTimeUpdate.ScheduleRelationship.SCHEDULED;
                    if (scheduleRelationship ==
                            StopTimeUpdate.ScheduleRelationship.NO_DATA) {
                        newTimes.updateArrivalDelay(i, 0);
                        newTimes.updateDepartureDelay(i, 0);
                        if (!hasUpdates) {
                            //If trip update does not contain stop estimates, mark stop in TripTimes having no data
                            newTimes.setStopWithNoData(i);
                        }
                        delay = 0;
                        if (firstDelay == null) firstDelay = delay;
                    } else {
                        long today = updateServiceDate.getAsDate(timeZone).getTime() / 1000;
                        StopTimeEvent arrival = null;
                        StopTimeEvent departure = null;

                        if (update.hasArrival() && (update.getArrival().hasTime() || update.getArrival().hasDelay())) {
                            arrival = update.getArrival();
                        }

                        if (update.hasDeparture() && (update.getDeparture().hasTime() || update.getDeparture().hasDelay())) {
                            departure = update.getDeparture();
                        }

                        if (arrival == null && departure != null) {
                            arrival = departure;
                        }

                        if (departure == null && arrival != null) {
                            departure = arrival;
                        }

                        if (arrival != null) {
                            if (arrival.hasDelay()) {
                                delay = arrival.getDelay();
                                if (firstDelay == null) firstDelay = delay;
                                if (arrival.hasTime()) {
                                    newTimes.updateArrivalTime(i,
                                            (int) (arrival.getTime() - today));
                                } else {
                                    newTimes.updateArrivalDelay(i, delay);
                                }
                            } else if (arrival.hasTime()) {
                                newTimes.updateArrivalTime(i,
                                        (int) (arrival.getTime() - today));
                                delay = newTimes.getArrivalDelay(i);
                                if (firstDelay == null) firstDelay = delay;
                            } else {
                                LOG.error("Arrival time at index {} is erroneous.", i);
                                return null;
                            }
                        } else {
                            if (delay == null) {
                                newTimes.cancelArrivalTime(i);
                            } else {
                                newTimes.updateArrivalDelay(i, delay);
                                if (newTimes.isCanceledArrival(i)) {
                                    newTimes.unCancelArrivalTime(i);
                                }
                            }
                        }

                        if (departure != null) {
                            if (departure.hasDelay()) {
                                delay = departure.getDelay();
                                if (firstDelay == null) firstDelay = delay;
                                if (departure.hasTime()) {
                                    newTimes.updateDepartureTime(i,
                                            (int) (departure.getTime() - today));
                                } else {
                                    newTimes.updateDepartureDelay(i, delay);
                                }
                            } else if (departure.hasTime()) {
                                newTimes.updateDepartureTime(i,
                                        (int) (departure.getTime() - today));
                                delay = newTimes.getDepartureDelay(i);
                                if (firstDelay == null) firstDelay = delay;
                            } else {
                                LOG.error("Departure time at index {} is erroneous.", i);
                                return null;
                            }
                        } else {
                            if (delay == null) {
                                newTimes.cancelDepartureTime(i);
                            } else {
                                newTimes.updateDepartureDelay(i, delay);
                                if (newTimes.isCanceledDeparture(i)) {
                                    newTimes.unCancelDepartureTime(i);
                                }
                            }
                        }
                    }

                    if (scheduleRelationship == StopTimeUpdate.ScheduleRelationship.SKIPPED) {
                        newTimes.cancelArrivalTime(i);
                        newTimes.cancelDepartureTime(i);
                    }

                    if (updates.hasNext()) {
                        update = updates.next();
                    } else {
                        update = null;
                    }
                } else {
                    if (hasMatched) {
                        if (delay == null) {
                            newTimes.cancelArrivalTime(i);
                            newTimes.cancelDepartureTime(i);
                        } else {
                            newTimes.updateArrivalDelay(i, delay);
                            if (newTimes.isCanceledArrival(i)) {
                                newTimes.unCancelArrivalTime(i);
                            }

                            newTimes.updateDepartureDelay(i, delay);
                            if (newTimes.isCanceledDeparture(i)) {
                                newTimes.unCancelDepartureTime(i);
                            }
                        }
                    }
                }
            }
            if (update != null) {
                LOG.error("Part of a TripUpdate object could not be applied successfully to trip {}.", tripId);
                return null;
            }
            if (firstDelay != null) {
                if (newTimes.getArrivalDelay(0) != firstDelay) {
                    LOG.info("Trying to fix TripTimes by propagating delay backwards on trip {}.", tripId);
                    newTimes.propagateDelayBackwards(firstDelay);
                }
            }
        }
        if (!newTimes.timesIncreasing()) {
            LOG.error("TripTimes are non-increasing after applying GTFS-RT delay propagation to trip {}.", tripId);
            return null;
        }

        LOG.debug("A valid TripUpdate object was applied to trip {} using the Timetable class update method.", tripId);
        return newTimes;
    }

    /**
     * Add a trip to this Timetable. The Timetable must be analyzed, compacted, and indexed
     * any time trips are added, but this is not done automatically because it is time consuming
     * and should only be done once after an entire batch of trips are added.
     * Note that the trip is not added to the enclosing pattern here, but in the pattern's wrapper function.
     * Here we don't know if it's a scheduled trip or a realtime-added trip.
     */
    public void addTripTimes(TripTimes tt) {
        tripTimes.add(tt);
    }

    /**
     * Add a frequency entry to this Timetable. See addTripTimes method. Maybe Frequency Entries should
     * just be TripTimes for simplicity.
     */
    public void addFrequencyEntry(FrequencyEntry freq) {
        frequencyEntries.add(freq);
    }

    /**
     * Check that all dwell times at the given stop are zero, which allows removing the dwell edge.
     * TODO we should probably just eliminate dwell-deletion. It won't be important if we get rid of transit edges.
     */
    boolean allDwellsZero(int hopIndex) {
        for (TripTimes tt : tripTimes) {
            if (tt.getDwellTime(hopIndex) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Returns the shortest possible running time for this stop */
    public int getBestRunningTime(int stopIndex) {
        return minRunningTimes[stopIndex];
    }

    /** Returns the shortest possible dwell time at this stop */
    public int getBestDwellTime(int stopIndex) {
        if (minDwellTimes == null) {
            return 0;
        }
        return minDwellTimes[stopIndex];
    }

    public boolean isValidFor(ServiceDate serviceDate) {
        return this.serviceDate == null || this.serviceDate.equals(serviceDate);
    }

    /** Find and cache service codes. Duplicates information in trip.getServiceId for optimization. */
    // TODO maybe put this is a more appropriate place
    public void setServiceCodes (Map<FeedScopedId, Integer> serviceCodes) {
        for (TripTimes tt : this.tripTimes) {
            tt.serviceCode = serviceCodes.get(tt.trip.getServiceId());
        }
        // Repeated code... bad sign...
        for (FrequencyEntry freq : this.frequencyEntries) {
            TripTimes tt = freq.tripTimes;
            tt.serviceCode = serviceCodes.get(tt.trip.getServiceId());
        }
    }

}
