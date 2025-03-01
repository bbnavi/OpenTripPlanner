package org.opentripplanner.graph_builder.module.osm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.openstreetmap.model.OSMNode;
import org.opentripplanner.visibility.VLPoint;
import org.opentripplanner.visibility.VLPolygon;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

public class Ring {

    public static class RingConstructionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public List<OSMNode> nodes;

    public VLPolygon geometry;

    public List<Ring> holes = new ArrayList<Ring>();

    // equivalent to the ring representation, but used for JTS operations
    private Polygon jtsPolygon;

    /**
     * <p>
     * Why is there a boolean parameter called javaSucks? Because otherwise the two constructors
     * have the same erasure, meaning that even though Java has enough information at compile-time
     * to figure out which constructor I am talking about, it intentionally throws this away in the
     * interest of having worse run-time performance. Thanks, Java!
     * </p>
     * <p>
     * Oh, and most people would solve this problem by making a static factory method but that won't
     * work because then all of this class's outer classes would have to be static.
     * </p>
     *
     * @param osmNodes
     * @param javaSucks
     */
    public Ring(List<OSMNode> osmNodes, boolean javaSucks) {
        ArrayList<VLPoint> vertices = new ArrayList<VLPoint>();
        nodes = osmNodes;
        for (OSMNode node : osmNodes) {
            VLPoint point = new VLPoint(node.lon, node.lat);
            vertices.add(point);
        }
        geometry = new VLPolygon(vertices);
    }

    public Ring(List<Long> osmNodes, Map<Long, OSMNode> _nodes) {
        ArrayList<VLPoint> vertices = new ArrayList<VLPoint>();
        nodes = new ArrayList<OSMNode>(osmNodes.size());
        for (long nodeId : osmNodes) {
            OSMNode node = _nodes.get(nodeId);
            if (nodes.contains(node)) {
                // hopefully, this only happens in order to
                // close polygons
                continue;
            }
            VLPoint point = new VLPoint(node.lon, node.lat);
            nodes.add(node);
            vertices.add(point);
        }
        geometry = new VLPolygon(vertices);
    }

    public Polygon toJtsPolygon() {
        if (jtsPolygon != null) {
            return jtsPolygon;
        }
        GeometryFactory factory = GeometryUtils.getGeometryFactory();

        LinearRing shell;
        try {
            shell = factory.createLinearRing(toCoordinates(geometry));
        } catch (IllegalArgumentException e) {
            throw new RingConstructionException();
        }

        // we need to merge connected holes here, because JTS does not believe in
        // holes that touch at multiple points (and, weirdly, does not have a method
        // to detect this other than this crazy DE-9IM stuff

        List<Polygon> polygonHoles = new ArrayList<Polygon>();
        for (Ring ring : holes) {
            LinearRing linearRing = factory.createLinearRing(toCoordinates(ring.geometry));
            Polygon polygon = factory.createPolygon(linearRing, new LinearRing[0]);
            for (Iterator<Polygon> it = polygonHoles.iterator(); it.hasNext();) {
                Polygon otherHole = it.next();
                if (otherHole.relate(polygon, "F***1****")) {
                    polygon = (Polygon) polygon.union(otherHole);
                    it.remove();
                }
            }
            polygonHoles.add(polygon);
        }

        ArrayList<LinearRing> lrholelist = new ArrayList<LinearRing>(polygonHoles.size());

        for (Polygon hole : polygonHoles) {
            Geometry boundary = hole.getBoundary();
            if (boundary instanceof LinearRing) {
                lrholelist.add((LinearRing) boundary);
            } else {
                // this is a case of a hole inside a hole. OSM technically
                // allows this, but it would be a giant hassle to get right. So:
                LineString line = hole.getExteriorRing();
                LinearRing ring = factory.createLinearRing(line.getCoordinates());
                lrholelist.add(ring);
            }
        }
        LinearRing[] lrholes = lrholelist.toArray(new LinearRing[lrholelist.size()]);
        jtsPolygon = factory.createPolygon(shell, lrholes);
        return jtsPolygon;
    }

    private Coordinate[] toCoordinates(VLPolygon geometry) {
        Coordinate[] coords = new Coordinate[geometry.n() + 1];
        int i = 0;
        for (VLPoint point : geometry.vertices) {
            coords[i++] = new Coordinate(point.x, point.y);
        }
        VLPoint first = geometry.vertices.get(0);
        coords[i++] = new Coordinate(first.x, first.y);
        return coords;
    }
}
