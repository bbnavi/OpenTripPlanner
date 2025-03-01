package org.opentripplanner.routing.graph;

import ch.qos.logback.classic.sift.SiftingAppender;
import ch.qos.logback.core.FileAppender;
import com.conveyal.object_differ.ObjectDiffer;
import org.geotools.util.WeakValueHashMap;
import org.jets3t.service.io.TempFile;
import org.junit.Test;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.common.LuceneIndex;
import org.opentripplanner.common.geometry.HashGridSpatialIndex;
import org.opentripplanner.routing.impl.DefaultStreetVertexIndexFactory;
import org.opentripplanner.routing.trippattern.Deduplicator;
import org.opentripplanner.routing.vertextype.TransitStation;

import java.io.File;
import java.lang.ref.SoftReference;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.Inflater;

import static org.junit.Assert.assertFalse;


/**
 * Tests that saving a graph and reloading it (round trip through serialization and deserialization) does not corrupt
 * the graph, and yields exactly the same data.
 * <p>
 * We tried several existing libraries to perform the comparison but nothing did exactly what we needed in a way that
 * we could control precisely.
 * <p>
 * Created by abyrd on 2018-10-26
 */
public class GraphSerializationTest {

    /**
     * Tests that saving a Graph to disk and reloading it results in a separate but semantically identical Graph.
     */
    @Test
    public void testRoundTrip() throws Exception {
        Graph originalGraph = ConstantsForTests.getInstance().getVermontGraph();
        // Remove the transit stations, which have no edges and won't survive serialization.
        List<Vertex> transitVertices = originalGraph.getVertices().stream()
                .filter(v -> v instanceof TransitStation).collect(Collectors.toList());
        transitVertices.forEach(originalGraph::remove);
        originalGraph.index(new DefaultStreetVertexIndexFactory());
        // The cached timezone in the graph is transient and lazy-initialized.
        // Previous tests may have caused a timezone to be cached.
        originalGraph.clearTimeZone();
        // Now round-trip the graph through serialization.
        File tempFile = TempFile.createTempFile("graph", "pdx");
        originalGraph.save(tempFile);
        Graph copiedGraph1 = Graph.load(tempFile);
        assertNoDifferences(originalGraph, copiedGraph1);
        Graph copiedGraph2 = Graph.load(tempFile);
        assertNoDifferences(copiedGraph1, copiedGraph2);
    }

    // Ideally we'd also test comparing two separate but identical complex graphs, built separately from the same inputs.
    // A test that builds the same graph twice will currently fail for the following reasons:
    // There is global state in Vertex.index and the feeds IDs that mean if you build the same graph twice the feed IDs
    // and vertex index numbers will all be unique. This is however evidence that the diff tool is detecting changes in
    // the graph contents. On the other hand deserializing the same graph twice or doing a round trip through
    // serialization and deserialization should produce identical graphs.

    /**
     * Test comparison of two references to the same graph. This should obviously yield no differences at all,
     * and allows us to perform a very deep comparison of almost the entire object graph because there are no problems
     * with lists being reordered, transient indexes being rebuilt, etc. The ObjectDiffer supports such comparisons
     * of identical objects with a special switch specifically for testing.
     * <p>
     * This is as much a test of the ObjectDiffer itself as of OpenTripPlanner serialization. It is situated here
     * instead of in the same package as ObjectDiffer so it has access to the OpenTripPlanner classes, which provide a
     * suitably complex tangle of fields and references for exercising all the differ's capabilities.
     */
    @Test
    public void compareGraphToItself() {
        Graph originalGraph = ConstantsForTests.getInstance().getVermontGraph();
        originalGraph.index(new DefaultStreetVertexIndexFactory());
        // We can exclude relatively few classes here, because the object trees are of course perfectly identical.
        // We do skip edge lists - otherwise we trigger a depth-first search of the graph causing a stack overflow.
        // We also skip some deeply buried weak-value hash maps, which refuse to tell you what their keys are.
        ObjectDiffer objectDiffer = new ObjectDiffer();
        objectDiffer.ignoreFields("incoming", "outgoing", "LOG");
        objectDiffer.useEquals(BitSet.class, LineString.class, Polygon.class);
        // ThreadPoolExecutor contains a weak reference to a very deep chain of Finalizer instances.
        // Method instances usually are part of a proxy which are totally un-reflectable in Java 11
        objectDiffer.ignoreClasses(
                WeakValueHashMap.class,
                ThreadPoolExecutor.class,
                Method.class,
                SoftReference.class,
                TreeMap.class,
                LuceneIndex.class,
                JarFile.class,
                Inflater.class,
                ch.qos.logback.classic.Logger.class
        );
        // This setting is critical to perform a deep test of an object against itself.
        objectDiffer.enableComparingIdenticalObjects();
        objectDiffer.compareTwoObjects(originalGraph, originalGraph);
        assertFalse(objectDiffer.hasDifferences());
    }


    /**
     * Compare two separate essentially empty graphs.
     */
    @Test
    public void testEmptyGraphs() {
        Graph graph1 = new Graph();
        Graph graph2 = new Graph();
        assertNoDifferences(graph1, graph2);
    }

    private static void assertNoDifferences(Graph g1, Graph g2) {
        // Make some exclusions because some classes are inherently transient or contain unordered lists we can't yet compare.
        ObjectDiffer objectDiffer = new ObjectDiffer();
        // Skip incoming and outgoing edge lists. These are unordered lists which will not compare properly.
        // The edges themselves will be compared via another field, and the edge lists are reconstructed after deserialization.
        // Some tests re-build the graph which will result in build times different by as little as a few milliseconds.
        // FIXME streetNotesService comparison fails, see also #2992, guess this is caused by NoteMatcher inequality
        // FIXME graphBuilderAnnotations are not serialized any more?
        // FIXME A few hundred vertices are not restored (unsure about the reason, perhaps due to removed edges?)
        // FIXME Maps differ in size: 48148 vs 47923 for ...graph.Graph.vertexById
        // FIXME Maps differ in size: 48148 vs 47923 for ...graph.Graph.vertices
        objectDiffer.ignoreFields("incoming", "outgoing", "buildTime", "streetNotesService", "graphBuilderAnnotations",
                "vertices", "vertexById", "LOG");
        objectDiffer.useEquals(BitSet.class, LineString.class, Polygon.class);
        // HashGridSpatialIndex contains unordered lists in its bins. This is rebuilt after deserialization anyway.
        // The deduplicator in the loaded graph will be empty, because it is transient and only fills up when items
        // are deduplicated.
        objectDiffer.ignoreClasses(HashGridSpatialIndex.class,
                ThreadPoolExecutor.class,
                Deduplicator.class,
                TreeMap.class,
                LuceneIndex.class,
                graphql.schema.GraphQLSchema.class,
                FileAppender.class,
                SiftingAppender.class,
                ch.qos.logback.core.sift.AppenderTracker.class,
                org.slf4j.Logger.class,
                java.lang.invoke.MethodType.class,
                WeakValueHashMap.class,
                Method.class,
                JarFile.class,
                SoftReference.class,
                Class.class
        );
        objectDiffer.compareTwoObjects(g1, g2);
        // Print differences before assertion so we can see what went wrong.
        assertFalse(objectDiffer.hasDifferences());
    }

}
