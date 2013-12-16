package com.thinkaurelius.faunus.formats.titan;

import com.thinkaurelius.faunus.FaunusEdge;
import com.thinkaurelius.faunus.FaunusGraph;
import com.thinkaurelius.faunus.FaunusProperty;
import com.thinkaurelius.faunus.FaunusVertex;
import com.thinkaurelius.faunus.Holder;
import com.thinkaurelius.faunus.Tokens;
import com.thinkaurelius.faunus.formats.titan.util.ConfigurationUtil;
import com.thinkaurelius.faunus.mapreduce.util.EmptyConfiguration;
import com.thinkaurelius.titan.core.TitanEdge;
import com.thinkaurelius.titan.core.TitanFactory;
import com.thinkaurelius.titan.core.TitanProperty;
import com.thinkaurelius.titan.core.TitanVertex;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.TransactionalGraph;
import com.tinkerpop.blueprints.Vertex;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;

import static com.tinkerpop.blueprints.Direction.IN;
import static com.tinkerpop.blueprints.Direction.OUT;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TitanGraphOutputMapReduce {

    public enum Counters {
        VERTICES_ADDED,
        VERTICES_REMOVED,
        VERTEX_PROPERTIES_ADDED,
        VERTEX_PROPERTIES_REMOVED,
        EDGES_ADDED,
        EDGES_REMOVED,
        EDGE_PROPERTIES_ADDED,
        EDGE_PROPERTIES_REMOVED,
        NULL_VERTEX_EDGES_IGNORED,
        NULL_VERTICES_IGNORED,
        NULL_EDGES_IGNORED,
        SUCCESSFUL_TRANSACTIONS,
        FAILED_TRANSACTIONS
    }

    public static final Logger LOGGER = Logger.getLogger(TitanGraphOutputMapReduce.class);
    // some random property that will 'never' be used by anyone
    public static final String TITAN_ID = "_bId0192834";
    public static final String ID_MAP_KEY = "_iDMaPKeY";

    /*private static final String GET_OR_CREATE_VERTEX = "getCreateOrDeleteVertex(faunusVertex,graph,mapContext)";
    private static final String FAUNUS_VERTEX = "faunusVertex";
    private static final String GRAPH = "graph";
    private static final String MAP_CONTEXT = "mapContext";

    public static final String FAUNUS_GRAPH_OUTPUT_BLUEPRINTS_SCRIPT_FILE = "faunus.graph.output.blueprints.script-file";*/


    public static Graph generateGraph(final Configuration configuration) {
        final Class<? extends OutputFormat> format = configuration.getClass(FaunusGraph.FAUNUS_GRAPH_OUTPUT_FORMAT, OutputFormat.class, OutputFormat.class);
        if (TitanOutputFormat.class.isAssignableFrom(format)) {
            return TitanFactory.open(ConfigurationUtil.extractConfiguration(configuration, TitanOutputFormat.FAUNUS_GRAPH_OUTPUT_TITAN));
        } else {
            throw new RuntimeException("The provide graph output format is not a supported TitanOutputFormat: " + format.getName());
        }
    }

    public static Configuration createConfiguration() {
        final Configuration configuration = new EmptyConfiguration();
        configuration.setBoolean("mapred.map.tasks.speculative.execution", false);
        configuration.setBoolean("mapred.reduce.tasks.speculative.execution", false);
        return configuration;
    }

    // WRITE ALL THE VERTICES AND THEIR PROPERTIES
    public static class VertexMap extends Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>> {

        Graph graph;
        boolean trackState;

        private final Holder<FaunusVertex> vertexHolder = new Holder<FaunusVertex>();
        private final LongWritable longWritable = new LongWritable();

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.graph = TitanGraphOutputMapReduce.generateGraph(context.getConfiguration());
            this.trackState = context.getConfiguration().getBoolean(Tokens.FAUNUS_PIPELINE_TRACK_STATE, false);
            LOGGER.setLevel(Level.INFO);
        }

        @Override
        public void map(final NullWritable key, final FaunusVertex value, final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws IOException, InterruptedException {
            try {
                final Vertex titanVertex = this.getCreateOrDeleteVertex(value, context);
                if (null != titanVertex) { // the vertex was state != deleted (if it was we know incident edges are deleted too)
                    // Propagate shell vertices with Blueprints ids
                    final FaunusVertex shellVertex = new FaunusVertex(context.getConfiguration(), value.getIdAsLong());
                    shellVertex.setProperty(TITAN_ID, titanVertex.getId());
                    // TODO: Might need to be OUT for the sake of unidirectional edges in Titan
                    for (final Edge faunusEdge : value.getEdges(IN)) {
                        this.longWritable.set((Long) faunusEdge.getVertex(OUT).getId());
                        context.write(this.longWritable, this.vertexHolder.set('s', shellVertex));
                    }

                    this.longWritable.set(value.getIdAsLong());
                    value.getPropertiesWithState().clear();  // no longer needed in reduce phase
                    value.setProperty(TITAN_ID, titanVertex.getId()); // need this for id resolution in edge-map phase
                    value.removeEdges(Tokens.Action.DROP, IN); // no longer needed in reduce phase
                    context.write(this.longWritable, this.vertexHolder.set('v', value));
                }
            } catch (final Exception e) {
                if (this.graph instanceof TransactionalGraph) {
                    ((TransactionalGraph) this.graph).rollback();
                    context.getCounter(Counters.FAILED_TRANSACTIONS).increment(1l);
                }
                throw new IOException(e.getMessage(), e);
            }

        }

        @Override
        public void cleanup(final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws IOException, InterruptedException {
            if (this.graph instanceof TransactionalGraph) {
                try {
                    ((TransactionalGraph) this.graph).commit();
                    context.getCounter(Counters.SUCCESSFUL_TRANSACTIONS).increment(1l);
                } catch (Exception e) {
                    LOGGER.error("Could not commit transaction during Map.cleanup(): ", e);
                    ((TransactionalGraph) this.graph).rollback();
                    context.getCounter(Counters.FAILED_TRANSACTIONS).increment(1l);
                    throw new IOException(e.getMessage(), e);
                }
            }
            this.graph.shutdown();
        }

        public Vertex getCreateOrDeleteVertex(final FaunusVertex faunusVertex, final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws InterruptedException {
            if (this.trackState && faunusVertex.isDeleted()) {
                final Vertex titanVertex = this.graph.getVertex(faunusVertex.getId());
                // TODO: this is expensive just for reporting purposes
                context.getCounter(Counters.EDGES_REMOVED).increment(faunusVertex.query().direction(OUT).count());
                titanVertex.remove();
                context.getCounter(Counters.VERTICES_REMOVED).increment(1l);
                return null;
            } else if (this.trackState && faunusVertex.isLoaded()) {
                final TitanVertex titanVertex = (TitanVertex) this.graph.getVertex(faunusVertex.getId());
                for (final FaunusProperty faunusProperty : faunusVertex.getPropertiesWithState()) {
                    if (faunusProperty.isNew()) {
                        titanVertex.addProperty(faunusProperty.getName(), faunusProperty.getValue());
                        context.getCounter(Counters.VERTEX_PROPERTIES_ADDED).increment(1l);
                    } else if (faunusProperty.isDeleted()) {
                        for (final TitanProperty titanProperty : titanVertex.getProperties(faunusProperty.getName())) {
                            if (titanProperty.getID() == faunusProperty.getIdAsLong()) {
                                titanProperty.remove();
                                context.getCounter(Counters.VERTEX_PROPERTIES_REMOVED).increment(1l);
                            }
                        }
                    }
                }
                return titanVertex;
            } else {   // state == new || !trackState
                final TitanVertex titanVertex = (TitanVertex) this.graph.addVertex(faunusVertex.getId());
                context.getCounter(Counters.VERTICES_ADDED).increment(1l);
                for (final FaunusProperty property : faunusVertex.getProperties()) {
                    titanVertex.addProperty(property.getName(), property.getValue());
                    context.getCounter(Counters.VERTEX_PROPERTIES_ADDED).increment(1l);
                }
                return titanVertex;
            }
        }
    }

    public static class Reduce extends Reducer<LongWritable, Holder<FaunusVertex>, NullWritable, FaunusVertex> {

        @Override
        public void reduce(final LongWritable key, final Iterable<Holder<FaunusVertex>> values, final Reducer<LongWritable, Holder<FaunusVertex>, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            FaunusVertex faunusVertex = null;
            // generate a map of the faunus id with the blueprints id for all shell vertices (vertices incoming adjacent)
            final java.util.Map<Long, Object> idMap = new HashMap<Long, Object>();
            for (final Holder<FaunusVertex> holder : values) {
                if (holder.getTag() == 's') {
                    idMap.put(holder.get().getIdAsLong(), holder.get().getProperty(TITAN_ID));
                } else {
                    faunusVertex = holder.get();
                }
            }
            if (null != faunusVertex) {
                faunusVertex.setProperty(ID_MAP_KEY, idMap);
                context.write(NullWritable.get(), faunusVertex);
            } else {
                LOGGER.warn("No source vertex: faunusVertex[" + key.get() + "]");
                context.getCounter(Counters.NULL_VERTICES_IGNORED).increment(1l);
            }
        }
    }

    // WRITE ALL THE EDGES CONNECTING THE VERTICES
    public static class EdgeMap extends Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex> {

        Graph graph;
        boolean trackState;

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.graph = TitanGraphOutputMapReduce.generateGraph(context.getConfiguration());
            this.trackState = context.getConfiguration().getBoolean(Tokens.FAUNUS_PIPELINE_TRACK_STATE, false);
            LOGGER.setLevel(Level.INFO);
        }

        @Override
        public void map(final NullWritable key, final FaunusVertex value, final Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            try {
                for (Edge edge : value.getEdgesWithState(OUT)) {
                    this.getCreateOrDeleteEdge(value, (FaunusEdge) edge, context);
                }
            } catch (final Exception e) {
                if (this.graph instanceof TransactionalGraph) {
                    ((TransactionalGraph) this.graph).rollback();
                    context.getCounter(Counters.FAILED_TRANSACTIONS).increment(1l);
                }
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void cleanup(final Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            if (this.graph instanceof TransactionalGraph) {
                try {
                    ((TransactionalGraph) this.graph).commit();
                    context.getCounter(Counters.SUCCESSFUL_TRANSACTIONS).increment(1l);
                } catch (Exception e) {
                    LOGGER.error("Could not commit transaction during Reduce.cleanup(): ", e);
                    ((TransactionalGraph) this.graph).rollback();
                    context.getCounter(Counters.FAILED_TRANSACTIONS).increment(1l);
                    throw new IOException(e.getMessage(), e);
                }
            }
            this.graph.shutdown();
        }

        public Edge getCreateOrDeleteEdge(final FaunusVertex faunusVertex, final FaunusEdge faunusEdge, final Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex>.Context context) throws InterruptedException {
            final TitanVertex titanVertex = (TitanVertex) this.graph.getVertex(faunusVertex.getProperty(TITAN_ID));
            final java.util.Map<Long, Object> idMap = faunusVertex.getProperty(ID_MAP_KEY);

            if (this.trackState && faunusEdge.isDeleted()) {
                final TitanEdge titanEdge = this.getIncident(titanVertex, faunusEdge, idMap.get(faunusEdge.getVertexId(IN)));
                if (null == titanEdge)
                    context.getCounter(Counters.NULL_EDGES_IGNORED).increment(1l);
                else {
                    titanEdge.remove();
                    context.getCounter(Counters.EDGES_REMOVED).increment(1l);
                }
                return null;
            } else if (this.trackState && faunusEdge.isLoaded()) {
                final TitanEdge titanEdge = this.getIncident(titanVertex, faunusEdge, idMap.get(faunusEdge.getVertexId(IN)));
                if (null == titanEdge)
                    context.getCounter(Counters.NULL_EDGES_IGNORED).increment(1l);
                else {
                    // do all property deletions, then do all property additions (ensures proper order of operations)
                    for (final FaunusProperty faunusProperty : faunusEdge.getPropertiesWithState()) {
                        if (faunusProperty.isDeleted()) {
                            titanEdge.removeProperty(faunusProperty.getName());
                            context.getCounter(Counters.EDGE_PROPERTIES_REMOVED).increment(1l);
                        }
                    }
                    for (final FaunusProperty faunusProperty : faunusEdge.getPropertiesWithState()) {
                        if (faunusProperty.isNew()) {
                            titanEdge.setProperty(faunusProperty.getName(), faunusProperty.getValue());
                            context.getCounter(Counters.EDGE_PROPERTIES_ADDED).increment(1l);
                        }
                    }
                }
                return titanEdge;
            } else {   // state == new || !trackStates
                final TitanEdge titanEdge = (TitanEdge) titanVertex.addEdge(faunusEdge.getLabel(), this.graph.getVertex(idMap.get(faunusEdge.getVertexId(IN))));
                context.getCounter(Counters.EDGES_ADDED).increment(1l);
                for (final FaunusProperty faunusProperty : faunusEdge.getProperties()) {
                    titanEdge.setProperty(faunusProperty.getName(), faunusProperty.getValue());
                    context.getCounter(Counters.EDGE_PROPERTIES_ADDED).increment(1l);
                }
                return titanEdge;
            }
        }

        private TitanEdge getIncident(final TitanVertex titanVertex, FaunusEdge faunusEdge, final Object otherVertexId) {
            final Iterable<Edge> edges = (null == otherVertexId) ?   // the shell wasn't propagated because the vertex was deleted -- should we propagate shell?
                    titanVertex.query().direction(OUT).labels(faunusEdge.getLabel()).edges() :
                    titanVertex.query().direction(OUT).labels(faunusEdge.getLabel()).adjacentVertex((TitanVertex) this.graph.getVertex(otherVertexId)).edges();

            for (final Edge edge : edges) {
                if (((TitanEdge) edge).getID() == faunusEdge.getIdAsLong()) {
                    return (TitanEdge) edge;
                }
            }
            return null;
        }
    }
}