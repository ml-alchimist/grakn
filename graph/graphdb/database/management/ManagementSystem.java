/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graph.graphdb.database.management;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import grakn.core.graph.core.Cardinality;
import grakn.core.graph.core.Connection;
import grakn.core.graph.core.EdgeLabel;
import grakn.core.graph.core.JanusGraph;
import grakn.core.graph.core.JanusGraphEdge;
import grakn.core.graph.core.JanusGraphException;
import grakn.core.graph.core.JanusGraphVertex;
import grakn.core.graph.core.JanusGraphVertexProperty;
import grakn.core.graph.core.Multiplicity;
import grakn.core.graph.core.PropertyKey;
import grakn.core.graph.core.RelationType;
import grakn.core.graph.core.VertexLabel;
import grakn.core.graph.core.schema.ConsistencyModifier;
import grakn.core.graph.core.schema.EdgeLabelMaker;
import grakn.core.graph.core.schema.Index;
import grakn.core.graph.core.schema.JanusGraphConfiguration;
import grakn.core.graph.core.schema.JanusGraphIndex;
import grakn.core.graph.core.schema.JanusGraphManagement;
import grakn.core.graph.core.schema.JanusGraphSchemaElement;
import grakn.core.graph.core.schema.JanusGraphSchemaType;
import grakn.core.graph.core.schema.Parameter;
import grakn.core.graph.core.schema.PropertyKeyMaker;
import grakn.core.graph.core.schema.RelationTypeIndex;
import grakn.core.graph.core.schema.SchemaAction;
import grakn.core.graph.core.schema.SchemaStatus;
import grakn.core.graph.core.schema.VertexLabelMaker;
import grakn.core.graph.diskstorage.BackendException;
import grakn.core.graph.diskstorage.configuration.BasicConfiguration;
import grakn.core.graph.diskstorage.configuration.ConfigOption;
import grakn.core.graph.diskstorage.configuration.ModifiableConfiguration;
import grakn.core.graph.diskstorage.configuration.TransactionalConfiguration;
import grakn.core.graph.diskstorage.configuration.UserModifiableConfiguration;
import grakn.core.graph.diskstorage.configuration.backend.KCVSConfiguration;
import grakn.core.graph.diskstorage.keycolumnvalue.scan.ScanMetrics;
import grakn.core.graph.diskstorage.keycolumnvalue.scan.StandardScanner;
import grakn.core.graph.diskstorage.log.Log;
import grakn.core.graph.graphdb.database.IndexSerializer;
import grakn.core.graph.graphdb.database.StandardJanusGraph;
import grakn.core.graph.graphdb.database.cache.SchemaCache;
import grakn.core.graph.graphdb.database.serialize.DataOutput;
import grakn.core.graph.graphdb.internal.ElementCategory;
import grakn.core.graph.graphdb.internal.InternalRelationType;
import grakn.core.graph.graphdb.internal.JanusGraphSchemaCategory;
import grakn.core.graph.graphdb.internal.Order;
import grakn.core.graph.graphdb.internal.Token;
import grakn.core.graph.graphdb.olap.VertexJobConverter;
import grakn.core.graph.graphdb.olap.job.IndexRemoveJob;
import grakn.core.graph.graphdb.olap.job.IndexRepairJob;
import grakn.core.graph.graphdb.query.QueryUtil;
import grakn.core.graph.graphdb.transaction.StandardJanusGraphTx;
import grakn.core.graph.graphdb.types.CompositeIndexType;
import grakn.core.graph.graphdb.types.IndexField;
import grakn.core.graph.graphdb.types.IndexType;
import grakn.core.graph.graphdb.types.MixedIndexType;
import grakn.core.graph.graphdb.types.ParameterIndexField;
import grakn.core.graph.graphdb.types.ParameterType;
import grakn.core.graph.graphdb.types.SchemaSource;
import grakn.core.graph.graphdb.types.StandardEdgeLabelMaker;
import grakn.core.graph.graphdb.types.StandardPropertyKeyMaker;
import grakn.core.graph.graphdb.types.StandardRelationTypeMaker;
import grakn.core.graph.graphdb.types.TypeDefinitionCategory;
import grakn.core.graph.graphdb.types.TypeDefinitionDescription;
import grakn.core.graph.graphdb.types.TypeDefinitionMap;
import grakn.core.graph.graphdb.types.VertexLabelVertex;
import grakn.core.graph.graphdb.types.indextype.IndexTypeWrapper;
import grakn.core.graph.graphdb.types.system.BaseKey;
import grakn.core.graph.graphdb.types.system.SystemTypeManager;
import grakn.core.graph.graphdb.types.vertices.EdgeLabelVertex;
import grakn.core.graph.graphdb.types.vertices.JanusGraphSchemaVertex;
import grakn.core.graph.graphdb.types.vertices.PropertyKeyVertex;
import grakn.core.graph.graphdb.types.vertices.RelationTypeVertex;
import org.apache.commons.lang.StringUtils;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static grakn.core.graph.graphdb.configuration.GraphDatabaseConfiguration.REGISTRATION_NS;
import static grakn.core.graph.graphdb.configuration.GraphDatabaseConfiguration.REGISTRATION_TIME;
import static grakn.core.graph.graphdb.configuration.GraphDatabaseConfiguration.ROOT_NS;
import static grakn.core.graph.graphdb.database.management.RelationTypeIndexWrapper.RELATION_INDEX_SEPARATOR;

public class ManagementSystem implements JanusGraphManagement {

    private static final Logger LOG = LoggerFactory.getLogger(ManagementSystem.class);
    private static final String CURRENT_INSTANCE_SUFFIX = "(current)";

    private final StandardJanusGraph graph;
    private final Log sysLog;
    private final ManagementLogger managementLogger;

    private final TransactionalConfiguration transactionalConfig;
    private final ModifiableConfiguration modifyConfig;
    private final UserModifiableConfiguration userConfig;
    private final SchemaCache schemaCache;

    private final StandardJanusGraphTx transaction;

    private final Set<JanusGraphSchemaVertex> updatedTypes;
    private boolean evictGraphFromCache;
    private final List<Callable<Boolean>> updatedTypeTriggers;

    private final Instant txStartTime;
    private boolean graphShutdownRequired;
    private boolean isOpen;

    private final static String FIRSTDASH = "------------------------------------------------------------------------------------------------\n";
    private final static String DASHBREAK = "---------------------------------------------------------------------------------------------------\n";

    public ManagementSystem(StandardJanusGraph graph, KCVSConfiguration config, Log sysLog, ManagementLogger managementLogger, SchemaCache schemaCache) {
        Preconditions.checkArgument(config != null && graph != null && sysLog != null && managementLogger != null);
        this.graph = graph;
        this.sysLog = sysLog;
        this.managementLogger = managementLogger;
        this.schemaCache = schemaCache;
        this.transactionalConfig = new TransactionalConfiguration(config);
        this.modifyConfig = new ModifiableConfiguration(ROOT_NS, transactionalConfig, BasicConfiguration.Restriction.GLOBAL);
        this.userConfig = new UserModifiableConfiguration(modifyConfig, configVerifier);

        this.updatedTypes = new HashSet<>();
        this.evictGraphFromCache = false;
        this.updatedTypeTriggers = new ArrayList<>();
        this.graphShutdownRequired = false;

        this.transaction = graph.buildTransaction().disableBatchLoading().start();
        this.txStartTime = graph.getConfiguration().getTimestampProvider().getTime();
        this.isOpen = true;
    }

    private final UserModifiableConfiguration.ConfigVerifier configVerifier = new UserModifiableConfiguration.ConfigVerifier() {
        @Override
        public void verifyModification(ConfigOption option) {
            Preconditions.checkArgument(graph.getConfiguration().isUpgradeAllowed(option.getName()) ||
                    option.getType() != ConfigOption.Type.FIXED, "Cannot change the fixed configuration option: %s", option);
            Preconditions.checkArgument(option.getType() != ConfigOption.Type.LOCAL, "Cannot change the local configuration option: %s", option);
            if (option.getType() == ConfigOption.Type.GLOBAL_OFFLINE) {
                //Verify that there no other open JanusGraph graph instance and no open transactions
                Set<String> openInstances = getOpenInstancesInternal();
                Preconditions.checkArgument(openInstances.size() < 2, "Cannot change offline config option [%s] since multiple instances are currently open: %s", option, openInstances);
                Preconditions.checkArgument(openInstances.contains(graph.getConfiguration().getUniqueGraphId()),
                        "Only one open instance (" + openInstances.iterator().next() + "), but it's not the current one (" + graph.getConfiguration().getUniqueGraphId() + ")");
                //Indicate that this graph must be closed
                graphShutdownRequired = true;
            }
        }
    };

    public Set<String> getOpenInstancesInternal() {
        Set<String> openInstances = Sets.newHashSet(modifyConfig.getContainedNamespaces(REGISTRATION_NS));
        LOG.debug("Open instances: {}", openInstances);
        return openInstances;
    }

    @Override
    public Set<String> getOpenInstances() {
        Set<String> openInstances = getOpenInstancesInternal();
        String uid = graph.getConfiguration().getUniqueGraphId();
        Preconditions.checkArgument(openInstances.contains(uid), "Current instance [%s] not listed as an open instance: %s", uid, openInstances);
        openInstances.remove(uid);
        openInstances.add(uid + CURRENT_INSTANCE_SUFFIX);
        return openInstances;
    }

    @Override
    public void forceCloseInstance(String instanceId) {
        Preconditions.checkArgument(!graph.getConfiguration().getUniqueGraphId().equals(instanceId),
                "Cannot force close this current instance [%s]. Properly shut down the graph instead.", instanceId);
        Preconditions.checkArgument(modifyConfig.has(REGISTRATION_TIME, instanceId), "Instance [%s] is not currently open", instanceId);
        Instant registrationTime = modifyConfig.get(REGISTRATION_TIME, instanceId);
        Preconditions.checkArgument(registrationTime.compareTo(txStartTime) < 0, "The to-be-closed instance [%s] was started after this transaction" +
                "which indicates a successful restart and can hence not be closed: %s vs %s", instanceId, registrationTime, txStartTime);
        modifyConfig.remove(REGISTRATION_TIME, instanceId);
    }

    private void ensureOpen() {
        Preconditions.checkState(isOpen, "This management system instance has been closed");
    }

    @Override
    public synchronized void commit() {
        ensureOpen();
        //Commit config changes
        if (transactionalConfig.hasMutations()) {
            DataOutput out = graph.getDataSerializer().getDataOutput(128);
            out.writeObjectNotNull(MgmtLogType.CONFIG_MUTATION);
            transactionalConfig.logMutations(out);
            sysLog.add(out.getStaticBuffer());
        }
        transactionalConfig.commit();

        //Commit underlying transaction
        transaction.commit();

        //Communicate schema changes
        if (!updatedTypes.isEmpty() || evictGraphFromCache) {
            managementLogger.sendCacheEviction(updatedTypes, evictGraphFromCache, updatedTypeTriggers, getOpenInstancesInternal());
            for (JanusGraphSchemaVertex schemaVertex : updatedTypes) {
                schemaCache.expireSchemaElement(schemaVertex.longId());
            }
        }

        if (graphShutdownRequired) graph.close();
        close();
    }

    @Override
    public synchronized void rollback() {
        ensureOpen();
        transactionalConfig.rollback();
        transaction.rollback();
        close();
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    private void close() {
        isOpen = false;
    }

    private JanusGraphEdge addSchemaEdge(JanusGraphVertex out, JanusGraphVertex in, TypeDefinitionCategory def, Object modifier) {
        return transaction.addSchemaEdge(out, in, def, modifier);
    }

    // ###### INDEXING SYSTEM #####################

    /* --------------
    Type Indexes
     --------------- */

    public JanusGraphSchemaElement getSchemaElement(long id) {
        JanusGraphVertex v = transaction.getVertex(id);
        if (v == null) return null;
        if (v instanceof RelationType) {
            if (((InternalRelationType) v).getBaseType() == null) return (RelationType) v;
            return new RelationTypeIndexWrapper((InternalRelationType) v);
        }
        if (v instanceof JanusGraphSchemaVertex) {
            JanusGraphSchemaVertex sv = (JanusGraphSchemaVertex) v;
            if (sv.getDefinition().containsKey(TypeDefinitionCategory.INTERNAL_INDEX)) {
                return new JanusGraphIndexWrapper(sv.asIndexType());
            }
        }
        throw new IllegalArgumentException("Not a valid schema element vertex: " + id);
    }

    @Override
    public RelationTypeIndex buildEdgeIndex(EdgeLabel label, String name, Direction direction, PropertyKey... sortKeys) {
        return buildRelationTypeIndex(label, name, direction, Order.ASC, sortKeys);
    }

    @Override
    public RelationTypeIndex buildEdgeIndex(EdgeLabel label, String name, Direction direction, org.apache.tinkerpop.gremlin.process.traversal.Order sortOrder, PropertyKey... sortKeys) {
        return buildRelationTypeIndex(label, name, direction, Order.convert(sortOrder), sortKeys);
    }

    @Override
    public RelationTypeIndex buildPropertyIndex(PropertyKey key, String name, PropertyKey... sortKeys) {
        return buildRelationTypeIndex(key, name, Direction.OUT, Order.ASC, sortKeys);
    }

    @Override
    public RelationTypeIndex buildPropertyIndex(PropertyKey key, String name, org.apache.tinkerpop.gremlin.process.traversal.Order sortOrder, PropertyKey... sortKeys) {
        return buildRelationTypeIndex(key, name, Direction.OUT, Order.convert(sortOrder), sortKeys);
    }

    private RelationTypeIndex buildRelationTypeIndex(RelationType type, String name, Direction direction, Order sortOrder, PropertyKey... sortKeys) {
        Preconditions.checkArgument(type != null && direction != null && sortOrder != null && sortKeys != null);
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "Name cannot be blank: %s", name);
        Token.verifyName(name);
        Preconditions.checkArgument(sortKeys.length > 0, "Need to specify sort keys");
        for (RelationType key : sortKeys) Preconditions.checkArgument(key != null, "Keys cannot be null");
        Preconditions.checkArgument(!(type instanceof EdgeLabel) || !((EdgeLabel) type).isUnidirected() || direction == Direction.OUT,
                "Can only index uni-directed labels in the out-direction: %s", type);
        Preconditions.checkArgument(!((InternalRelationType) type).multiplicity().isUnique(direction),
                "The relation type [%s] has a multiplicity or cardinality constraint in direction [%s] and can therefore not be indexed", type, direction);

        String composedName = composeRelationTypeIndexName(type, name);
        StandardRelationTypeMaker maker;
        if (type.isEdgeLabel()) {
            StandardEdgeLabelMaker lm = (StandardEdgeLabelMaker) transaction.makeEdgeLabel(composedName);
            lm.unidirected(direction);
            maker = lm;
        } else {
            StandardPropertyKeyMaker lm = (StandardPropertyKeyMaker) transaction.makePropertyKey(composedName);
            lm.dataType(((PropertyKey) type).dataType());
            maker = lm;
        }
        maker.status(type.isNew() ? SchemaStatus.ENABLED : SchemaStatus.INSTALLED);
        maker.invisible();
        maker.multiplicity(Multiplicity.MULTI);
        maker.sortKey(sortKeys);
        maker.sortOrder(sortOrder);

        //Compose signature
        long[] typeSig = ((InternalRelationType) type).getSignature();
        Set<PropertyKey> signature = Sets.newHashSet();
        for (long typeId : typeSig) signature.add(transaction.getExistingPropertyKey(typeId));
        for (RelationType sortType : sortKeys) signature.remove(sortType);
        if (!signature.isEmpty()) {
            PropertyKey[] sig = signature.toArray(new PropertyKey[0]);
            maker.signature(sig);
        }
        RelationType typeIndex = maker.make();
        addSchemaEdge(type, typeIndex, TypeDefinitionCategory.RELATIONTYPE_INDEX, null);
        RelationTypeIndexWrapper index = new RelationTypeIndexWrapper((InternalRelationType) typeIndex);
        if (!type.isNew()) updateIndex(index, SchemaAction.REGISTER_INDEX);
        return index;
    }

    private static String composeRelationTypeIndexName(RelationType type, String name) {
        return String.valueOf(type.longId()) + RELATION_INDEX_SEPARATOR + name;
    }

    @Override
    public boolean containsRelationIndex(RelationType type, String name) {
        return getRelationIndex(type, name) != null;
    }

    @Override
    public RelationTypeIndex getRelationIndex(RelationType type, String name) {
        Preconditions.checkArgument(type != null);
        Preconditions.checkArgument(StringUtils.isNotBlank(name));
        String composedName = composeRelationTypeIndexName(type, name);

        //Don't use SchemaCache to make code more compact and since we don't need the extra performance here
        JanusGraphVertex v = Iterables.getOnlyElement(QueryUtil.getVertices(transaction, BaseKey.SchemaName, JanusGraphSchemaCategory.getRelationTypeName(composedName)), null);
        if (v == null) return null;
        return new RelationTypeIndexWrapper((InternalRelationType) v);
    }

    @Override
    public Iterable<RelationTypeIndex> getRelationIndexes(RelationType type) {
        Preconditions.checkArgument(type instanceof InternalRelationType, "Invalid relation type provided: %s", type);
        return Iterables.transform(Iterables.filter(((InternalRelationType) type).getRelationIndexes(), internalRelationType -> !type.equals(internalRelationType)), new Function<InternalRelationType, RelationTypeIndex>() {
            @Override
            public RelationTypeIndex apply(@Nullable InternalRelationType internalType) {
                return new RelationTypeIndexWrapper(internalType);
            }
        });
    }

    /* --------------
    Graph Indexes
     --------------- */

    public static IndexType getGraphIndexDirect(String name, StandardJanusGraphTx transaction) {
        JanusGraphSchemaVertex v = transaction.getSchemaVertex(JanusGraphSchemaCategory.GRAPHINDEX.getSchemaName(name));
        if (v == null) return null;
        return v.asIndexType();
    }

    @Override
    public boolean containsGraphIndex(String name) {
        return getGraphIndex(name) != null;
    }

    @Override
    public JanusGraphIndex getGraphIndex(String name) {
        IndexType index = getGraphIndexDirect(name, transaction);
        return index == null ? null : new JanusGraphIndexWrapper(index);
    }

    @Override
    public Iterable<JanusGraphIndex> getGraphIndexes(Class<? extends Element> elementType) {
        return StreamSupport.stream(
                QueryUtil.getVertices(transaction, BaseKey.SchemaCategory, JanusGraphSchemaCategory.GRAPHINDEX).spliterator(), false)
                .map(janusGraphVertex -> ((JanusGraphSchemaVertex) janusGraphVertex).asIndexType())
                .filter(indexType -> indexType.getElement().subsumedBy(elementType))
                .map(JanusGraphIndexWrapper::new)
                .collect(Collectors.toList());
    }

    @Override
    public String printSchema() {
        return printVertexLabels(false) + printEdgeLabels(false) + printPropertyKeys(false) + printIndexes(false);
    }

    @Override
    public String printVertexLabels() {
        return this.printVertexLabels(true);
    }

    private String printVertexLabels(boolean calledDirectly) {
        StringBuilder sb = new StringBuilder();
        String pattern = "%-30s | %-11s | %-50s |%n";
        Iterable<VertexLabel> labels = getVertexLabels();
        boolean hasResults = false;
        sb.append(FIRSTDASH);
        sb.append(String.format(pattern, "Vertex Label Name", "Partitioned", "Static"));
        sb.append(DASHBREAK);
        for (VertexLabel label : labels) {
            hasResults = true;
            sb.append(String.format(pattern, label.name(), label.isPartitioned(), label.isStatic()));
        }
        if (hasResults && calledDirectly) {
            sb.append(DASHBREAK);
        }
        return sb.toString();
    }

    @Override
    public String printEdgeLabels() {
        return this.printEdgeLabels(true);
    }

    private String printEdgeLabels(boolean calledDirectly) {
        StringBuilder sb = new StringBuilder();
        String pattern = "%-30s | %-11s | %-11s | %-36s |%n";
        Iterable<EdgeLabel> labels = getRelationTypes(EdgeLabel.class);
        boolean hasResults = false;
        if (calledDirectly) {
            sb.append(FIRSTDASH);
        } else {
            sb.append(DASHBREAK);
        }
        sb.append(String.format(pattern, "Edge Label Name", "Directed", "Unidirected", "Multiplicity"));
        sb.append(DASHBREAK);
        for (EdgeLabel label : labels) {
            hasResults = true;
            sb.append(String.format(pattern, label.name(), label.isDirected(), label.isUnidirected(), label.multiplicity()));
        }
        if (hasResults && calledDirectly) {
            sb.append(DASHBREAK);
        }
        return sb.toString();
    }

    @Override
    public String printPropertyKeys() {
        return this.printPropertyKeys(true);
    }

    private String printPropertyKeys(boolean calledDirectly) {
        StringBuilder sb = new StringBuilder();
        String pattern = "%-30s | %-11s | %-50s |\n";
        Iterable<PropertyKey> keys = getRelationTypes(PropertyKey.class);
        boolean hasResults = false;
        if (calledDirectly) {
            sb.append(FIRSTDASH);
        } else {
            sb.append(DASHBREAK);
        }
        sb.append(String.format(pattern, "Property Key Name", "Cardinality", "Data Type"));
        sb.append(DASHBREAK);
        for (PropertyKey key : keys) {
            hasResults = true;
            sb.append(String.format(pattern, key.name(), key.cardinality(), key.dataType()));
        }
        if (hasResults && calledDirectly) {
            sb.append(DASHBREAK);
        }
        return sb.toString();
    }

    @Override
    public String printIndexes() {
        return this.printIndexes(true);
    }

    private String printIndexes(boolean calledDirectly) {
        StringBuilder sb = new StringBuilder();
        String pattern = "%-30s | %-11s | %-9s | %-14s | %-10s %10s |%n";
        String relationPattern = "%-30s | %-11s | %-9s | %-14s | %-8s | %10s |%n";
        Iterable<JanusGraphIndex> vertexIndexes = getGraphIndexes(Vertex.class);
        Iterable<JanusGraphIndex> edgeIndexes = getGraphIndexes(Edge.class);
        Iterable<RelationType> relationTypes = getRelationTypes(RelationType.class);
        LinkedList<RelationTypeIndex> relationIndexes = new LinkedList<>();

        for (RelationType rt : relationTypes) {
            Iterable<RelationTypeIndex> rti = getRelationIndexes(rt);
            rti.forEach(relationIndexes::add);
        }

        if (calledDirectly) {
            sb.append(FIRSTDASH);
        } else {
            sb.append(DASHBREAK);
        }
        sb.append(String.format(pattern, "Vertex Index Name", "Type", "Unique", "Backing", "Key:", "Status"));
        sb.append(DASHBREAK);
        sb.append(iterateIndexes(pattern, vertexIndexes));

        sb.append(DASHBREAK);
        sb.append(String.format(pattern, "Edge Index (VCI) Name", "Type", "Unique", "Backing", "Key:", "Status"));
        sb.append(DASHBREAK);
        sb.append(iterateIndexes(pattern, edgeIndexes));

        sb.append(DASHBREAK);
        sb.append(String.format(relationPattern, "Relation Index", "Type", "Direction", "Sort Key", "Order", "Status"));
        sb.append(DASHBREAK);
        for (RelationTypeIndex ri : relationIndexes) {
            sb.append(String.format(relationPattern, ri.name(), ri.getType(), ri.getDirection(), ri.getSortKey()[0], ri.getSortOrder(), ri.getIndexStatus().name()));
        }
        if (!relationIndexes.isEmpty()) {
            sb.append(DASHBREAK);
        }
        return sb.toString();
    }

    private String iterateIndexes(String pattern, Iterable<JanusGraphIndex> indexes) {
        StringBuilder sb = new StringBuilder();
        for (JanusGraphIndex index : indexes) {
            String type = getIndexType(index);
            PropertyKey[] keys = index.getFieldKeys();
            String[][] keyStatus = getKeyStatus(keys, index);
            sb.append(String.format(pattern, index.name(), type, index.isUnique(), index.getBackingIndex(), keyStatus[0][0] + ":", keyStatus[0][1]));
            if (keyStatus.length > 1) {
                for (int i = 1; i < keyStatus.length; i++) {
                    sb.append(String.format(pattern, "", "", "", "", keyStatus[i][0] + ":", keyStatus[i][1]));
                }
            }
        }
        return sb.toString();

    }

    private String[][] getKeyStatus(PropertyKey[] keys, JanusGraphIndex index) {
        String[][] keyStatus = new String[keys.length][2];
        for (int i = 0; i < keys.length; i++) {
            keyStatus[i][0] = keys[i].name();
            keyStatus[i][1] = index.getIndexStatus(keys[i]).name();
        }
        return keyStatus.length > 0 ? keyStatus : new String[][]{{"", ""}};
    }

    private String getIndexType(JanusGraphIndex index) {
        String type;
        if (index.isCompositeIndex()) {
            type = "Composite";
        } else if (index.isMixedIndex()) {
            type = "Mixed";
        } else {
            type = "Unknown";
        }
        return type;
    }

    /**
     * Returns a {@link GraphIndexStatusWatcher} configured to watch
     * {@code graphIndexName} through graph {@code g}.
     * <p>
     * This method just instantiates an object.
     * Invoke {@link GraphIndexStatusWatcher#call()} to wait.
     *
     * @param g              the graph through which to read index information
     * @param graphIndexName the name of a graph index to watch
     */
    public static GraphIndexStatusWatcher awaitGraphIndexStatus(JanusGraph g, String graphIndexName) {
        return new GraphIndexStatusWatcher(g, graphIndexName);
    }


    /**
     * Returns a {@link RelationIndexStatusWatcher} configured to watch the index specified by
     * {@code relationIndexName} and {@code relationIndexType} through graph {@code g}.
     * <p>
     * This method just instantiates an object.
     * Invoke {@link RelationIndexStatusWatcher#call()} to wait.
     *
     * @param g                 the graph through which to read index information
     * @param relationIndexName the name of the relation index to watch
     * @param relationTypeName  the type on the relation index to watch
     */
    public static RelationIndexStatusWatcher awaitRelationIndexStatus(JanusGraph g, String relationIndexName, String relationTypeName) {
        return new RelationIndexStatusWatcher(g, relationIndexName, relationTypeName);
    }

    private void checkIndexName(String indexName) {
        Preconditions.checkArgument(StringUtils.isNotBlank(indexName));
        Preconditions.checkArgument(getGraphIndex(indexName) == null, "An index with name '%s' has already been defined", indexName);
    }

    private JanusGraphIndex createMixedIndex(String indexName, ElementCategory elementCategory,
                                             JanusGraphSchemaType constraint, String backingIndex) {
        Preconditions.checkArgument(graph.getIndexSerializer().containsIndex(backingIndex), "Unknown external index backend: %s", backingIndex);
        checkIndexName(indexName);

        TypeDefinitionMap def = new TypeDefinitionMap();
        def.setValue(TypeDefinitionCategory.INTERNAL_INDEX, false);
        def.setValue(TypeDefinitionCategory.ELEMENT_CATEGORY, elementCategory);
        def.setValue(TypeDefinitionCategory.BACKING_INDEX, backingIndex);
        def.setValue(TypeDefinitionCategory.INDEXSTORE_NAME, indexName);
        def.setValue(TypeDefinitionCategory.INDEX_CARDINALITY, Cardinality.LIST);
        def.setValue(TypeDefinitionCategory.STATUS, SchemaStatus.ENABLED);
        JanusGraphSchemaVertex indexVertex = transaction.makeSchemaVertex(JanusGraphSchemaCategory.GRAPHINDEX, indexName, def);

        Preconditions.checkArgument(constraint == null || (elementCategory.isValidConstraint(constraint) && constraint instanceof JanusGraphSchemaVertex));
        if (constraint != null) {
            addSchemaEdge(indexVertex, (JanusGraphSchemaVertex) constraint, TypeDefinitionCategory.INDEX_SCHEMA_CONSTRAINT, null);
        }
        updateSchemaVertex(indexVertex);
        return new JanusGraphIndexWrapper(indexVertex.asIndexType());
    }

    @Override
    public void addIndexKey(JanusGraphIndex index, PropertyKey key, Parameter... parameters) {
        Preconditions.checkArgument(key != null && index instanceof JanusGraphIndexWrapper
                && !(key instanceof BaseKey), "Need to provide valid index and key");
        if (parameters == null) parameters = new Parameter[0];
        IndexType indexType = ((JanusGraphIndexWrapper) index).getBaseIndex();
        Preconditions.checkArgument(indexType instanceof MixedIndexType, "Can only add keys to an external index, not %s", index.name());
        Preconditions.checkArgument(indexType instanceof IndexTypeWrapper && key instanceof JanusGraphSchemaVertex
                && ((IndexTypeWrapper) indexType).getSchemaBase() instanceof JanusGraphSchemaVertex);

        JanusGraphSchemaVertex indexVertex = (JanusGraphSchemaVertex) ((IndexTypeWrapper) indexType).getSchemaBase();

        for (IndexField field : indexType.getFieldKeys()) {
            Preconditions.checkArgument(!field.getFieldKey().equals(key), "Key [%s] has already been added to index %s", key.name(), index.name());
        }
        //Assemble parameters
        boolean addMappingParameter = !ParameterType.MAPPED_NAME.hasParameter(parameters);
        Parameter[] extendedParas = new Parameter[parameters.length + 1 + (addMappingParameter ? 1 : 0)];
        System.arraycopy(parameters, 0, extendedParas, 0, parameters.length);
        int arrPosition = parameters.length;
        if (addMappingParameter) {
            extendedParas[arrPosition++] = ParameterType.MAPPED_NAME.getParameter(graph.getIndexSerializer().getDefaultFieldName(key, parameters, indexType.getBackingIndexName()));
        }
        extendedParas[arrPosition] = ParameterType.STATUS.getParameter(key.isNew() ? SchemaStatus.ENABLED : SchemaStatus.INSTALLED);

        addSchemaEdge(indexVertex, key, TypeDefinitionCategory.INDEX_FIELD, extendedParas);
        updateSchemaVertex(indexVertex);
        indexType.resetCache();
        //Check to see if the index supports this
        if (!graph.getIndexSerializer().supports((MixedIndexType) indexType, ParameterIndexField.of(key, parameters))) {
            throw new JanusGraphException("Could not register new index field '" + key.name() + "' with index backend as the data type, cardinality or parameter combination is not supported.");
        }

        try {
            IndexSerializer.register((MixedIndexType) indexType, key, transaction.getBackendTransaction());
        } catch (BackendException e) {
            throw new JanusGraphException("Could not register new index field with index backend", e);
        }
        if (!indexVertex.isNew()) updatedTypes.add(indexVertex);
        if (!key.isNew()) updateIndex(index, SchemaAction.REGISTER_INDEX);
    }

    private JanusGraphIndex createCompositeIndex(String indexName, ElementCategory elementCategory, boolean unique, JanusGraphSchemaType constraint, PropertyKey... keys) {
        checkIndexName(indexName);
        Preconditions.checkArgument(keys != null && keys.length > 0, "Need to provide keys to index [%s]", indexName);
        Preconditions.checkArgument(!unique || elementCategory == ElementCategory.VERTEX, "Unique indexes can only be created on vertices [%s]", indexName);
        boolean allSingleKeys = true;
        boolean oneNewKey = false;
        for (PropertyKey key : keys) {
            Preconditions.checkArgument(key instanceof PropertyKeyVertex, "Need to provide valid keys: %s", key);
            if (key.cardinality() != Cardinality.SINGLE) allSingleKeys = false;
            if (key.isNew()) oneNewKey = true;
            else updatedTypes.add((PropertyKeyVertex) key);
        }

        Cardinality indexCardinality;
        if (unique) indexCardinality = Cardinality.SINGLE;
        else indexCardinality = (allSingleKeys ? Cardinality.SET : Cardinality.LIST);

        boolean canIndexBeEnabled = oneNewKey || (constraint != null && constraint.isNew());

        TypeDefinitionMap def = new TypeDefinitionMap();
        def.setValue(TypeDefinitionCategory.INTERNAL_INDEX, true);
        def.setValue(TypeDefinitionCategory.ELEMENT_CATEGORY, elementCategory);
        def.setValue(TypeDefinitionCategory.BACKING_INDEX, Token.INTERNAL_INDEX_NAME);
        def.setValue(TypeDefinitionCategory.INDEXSTORE_NAME, indexName);
        def.setValue(TypeDefinitionCategory.INDEX_CARDINALITY, indexCardinality);
        def.setValue(TypeDefinitionCategory.STATUS, canIndexBeEnabled ? SchemaStatus.ENABLED : SchemaStatus.INSTALLED);
        JanusGraphSchemaVertex indexVertex = transaction.makeSchemaVertex(JanusGraphSchemaCategory.GRAPHINDEX, indexName, def);
        for (int i = 0; i < keys.length; i++) {
            Parameter[] paras = {ParameterType.INDEX_POSITION.getParameter(i)};
            addSchemaEdge(indexVertex, keys[i], TypeDefinitionCategory.INDEX_FIELD, paras);
        }

        Preconditions.checkArgument(constraint == null || (elementCategory.isValidConstraint(constraint) && constraint instanceof JanusGraphSchemaVertex));
        if (constraint != null) {
            addSchemaEdge(indexVertex, (JanusGraphSchemaVertex) constraint, TypeDefinitionCategory.INDEX_SCHEMA_CONSTRAINT, null);
        }
        updateSchemaVertex(indexVertex);
        JanusGraphIndexWrapper index = new JanusGraphIndexWrapper(indexVertex.asIndexType());
        if (!oneNewKey) updateIndex(index, SchemaAction.REGISTER_INDEX);
        return index;
    }

    @Override
    public JanusGraphManagement.IndexBuilder buildIndex(String indexName, Class<? extends Element> elementType) {
        return new IndexBuilder(indexName, ElementCategory.getByClazz(elementType));
    }

    private class IndexBuilder implements JanusGraphManagement.IndexBuilder {

        private final String indexName;
        private final ElementCategory elementCategory;
        private boolean unique = false;
        private JanusGraphSchemaType constraint = null;
        private final Map<PropertyKey, Parameter[]> keys = new HashMap<>();

        private IndexBuilder(String indexName, ElementCategory elementCategory) {
            this.indexName = indexName;
            this.elementCategory = elementCategory;
        }

        @Override
        public JanusGraphManagement.IndexBuilder addKey(PropertyKey key) {
            Preconditions.checkArgument(key instanceof PropertyKeyVertex, "Key must be a user defined key: %s", key);
            keys.put(key, null);
            return this;
        }

        @Override
        public JanusGraphManagement.IndexBuilder addKey(PropertyKey key, Parameter... parameters) {
            Preconditions.checkArgument(key instanceof PropertyKeyVertex, "Key must be a user defined key: %s", key);
            keys.put(key, parameters);
            return this;
        }

        @Override
        public JanusGraphManagement.IndexBuilder indexOnly(JanusGraphSchemaType schemaType) {
            Preconditions.checkNotNull(schemaType);
            Preconditions.checkArgument(elementCategory.isValidConstraint(schemaType), "Need to specify a valid schema type for this index definition: %s", schemaType);
            constraint = schemaType;
            return this;
        }

        @Override
        public JanusGraphManagement.IndexBuilder unique() {
            unique = true;
            return this;
        }

        @Override
        public JanusGraphIndex buildCompositeIndex() {
            Preconditions.checkArgument(!keys.isEmpty(), "Need to specify at least one key for the composite index");
            PropertyKey[] keyArr = new PropertyKey[keys.size()];
            int pos = 0;
            for (Map.Entry<PropertyKey, Parameter[]> entry : keys.entrySet()) {
                Preconditions.checkArgument(entry.getValue() == null, "Cannot specify parameters for composite index: %s", entry.getKey());
                keyArr[pos++] = entry.getKey();
            }
            return createCompositeIndex(indexName, elementCategory, unique, constraint, keyArr);
        }

        @Override
        public JanusGraphIndex buildMixedIndex(String backingIndex) {
            Preconditions.checkArgument(StringUtils.isNotBlank(backingIndex), "Need to specify backing index name");
            Preconditions.checkArgument(!unique, "An external index cannot be unique");

            JanusGraphIndex index = createMixedIndex(indexName, elementCategory, constraint, backingIndex);
            for (Map.Entry<PropertyKey, Parameter[]> entry : keys.entrySet()) {
                addIndexKey(index, entry.getKey(), entry.getValue());
            }
            return index;
        }
    }

    /* --------------
    Schema Update
     --------------- */

    @Override
    public IndexJobFuture updateIndex(Index index, SchemaAction updateAction) {
        Preconditions.checkArgument(index != null, "Need to provide an index");
        Preconditions.checkArgument(updateAction != null, "Need to provide update action");

        JanusGraphSchemaVertex schemaVertex = getSchemaVertex(index);
        Set<JanusGraphSchemaVertex> dependentTypes;
        Set<PropertyKeyVertex> keySubset = ImmutableSet.of();
        if (index instanceof RelationTypeIndex) {
            dependentTypes = ImmutableSet.of((JanusGraphSchemaVertex) ((InternalRelationType) schemaVertex).getBaseType());
            if (!updateAction.isApplicableStatus(schemaVertex.getStatus())) {
                return null;
            }
        } else if (index instanceof JanusGraphIndex) {
            IndexType indexType = schemaVertex.asIndexType();
            dependentTypes = Sets.newHashSet();
            if (indexType.isCompositeIndex()) {
                if (!updateAction.isApplicableStatus(schemaVertex.getStatus())) {
                    return null;
                }
                for (PropertyKey key : ((JanusGraphIndex) index).getFieldKeys()) {
                    dependentTypes.add((PropertyKeyVertex) key);
                }
            } else {
                keySubset = Sets.newHashSet();
                MixedIndexType mixedIndexType = (MixedIndexType) indexType;
                Set<SchemaStatus> applicableStatus = updateAction.getApplicableStatus();
                for (ParameterIndexField field : mixedIndexType.getFieldKeys()) {
                    if (applicableStatus.contains(field.getStatus())) {
                        keySubset.add((PropertyKeyVertex) field.getFieldKey());
                    }
                }
                if (keySubset.isEmpty()) {
                    return null;
                }

                dependentTypes.addAll(keySubset);
            }
        } else throw new UnsupportedOperationException("Updates not supported for index: " + index);

        IndexIdentifier indexId = new IndexIdentifier(index);
        StandardScanner.Builder builder;
        IndexJobFuture future;
        switch (updateAction) {
            case REGISTER_INDEX:
                setStatus(schemaVertex, SchemaStatus.INSTALLED, keySubset);
                updatedTypes.add(schemaVertex);
                updatedTypes.addAll(dependentTypes);
                setUpdateTrigger(new UpdateStatusTrigger(graph, schemaVertex, SchemaStatus.REGISTERED, keySubset));
                future = new EmptyIndexJobFuture();
                break;
            case REINDEX:
                builder = graph.getBackend().buildEdgeScanJob();
                builder.setFinishJob(indexId.getIndexJobFinisher(graph, SchemaAction.ENABLE_INDEX));
                builder.setJobId(indexId);
                builder.setJob(VertexJobConverter.convert(graph, new IndexRepairJob(indexId.indexName, indexId.relationTypeName)));
                try {
                    future = builder.execute();
                } catch (BackendException e) {
                    throw new JanusGraphException(e);
                }
                break;
            case ENABLE_INDEX:
                setStatus(schemaVertex, SchemaStatus.ENABLED, keySubset);
                updatedTypes.add(schemaVertex);
                if (!keySubset.isEmpty()) {
                    updatedTypes.addAll(dependentTypes);
                }
                future = new EmptyIndexJobFuture();
                break;
            case DISABLE_INDEX:
                setStatus(schemaVertex, SchemaStatus.INSTALLED, keySubset);
                updatedTypes.add(schemaVertex);
                if (!keySubset.isEmpty()) {
                    updatedTypes.addAll(dependentTypes);
                }
                setUpdateTrigger(new UpdateStatusTrigger(graph, schemaVertex, SchemaStatus.DISABLED, keySubset));
                future = new EmptyIndexJobFuture();
                break;
            case REMOVE_INDEX:
                if (index instanceof RelationTypeIndex) {
                    builder = graph.getBackend().buildEdgeScanJob();
                } else {
                    JanusGraphIndex graphIndex = (JanusGraphIndex) index;
                    if (graphIndex.isMixedIndex()) {
                        throw new UnsupportedOperationException("External mixed indexes must be removed in the indexing system directly.");
                    }
                    builder = graph.getBackend().buildGraphIndexScanJob();
                }
                builder.setFinishJob(indexId.getIndexJobFinisher());
                builder.setJobId(indexId);
                builder.setJob(new IndexRemoveJob(graph, indexId.indexName, indexId.relationTypeName));
                try {
                    future = builder.execute();
                } catch (BackendException e) {
                    throw new JanusGraphException(e);
                }
                break;
            default:
                throw new UnsupportedOperationException("Update action not supported: " + updateAction);
        }
        return future;
    }


    private static class GraphCacheEvictionCompleteTrigger implements Callable<Boolean> {
        private static final Logger LOG = LoggerFactory.getLogger(GraphCacheEvictionCompleteTrigger.class);
        private final String graphName;

        private GraphCacheEvictionCompleteTrigger(String graphName) {
            this.graphName = graphName;
        }

        @Override
        public Boolean call() {
            LOG.info("Graph {} has been removed from the graph cache on every JanusGraph node in the cluster.", graphName);
            return true;
        }
    }

    private static class EmptyIndexJobFuture implements IndexJobFuture {

        @Override
        public ScanMetrics getIntermediateResult() {
            return null;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public ScanMetrics get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public ScanMetrics get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }

    private static class UpdateStatusTrigger implements Callable<Boolean> {

        private static final Logger LOG = LoggerFactory.getLogger(UpdateStatusTrigger.class);

        private final StandardJanusGraph graph;
        private final long schemaVertexId;
        private final SchemaStatus newStatus;
        private final Set<Long> propertyKeys;

        private UpdateStatusTrigger(StandardJanusGraph graph, JanusGraphSchemaVertex vertex, SchemaStatus newStatus, Iterable<PropertyKeyVertex> keys) {
            this.graph = graph;
            this.schemaVertexId = vertex.longId();
            this.newStatus = newStatus;
            this.propertyKeys = Sets.newHashSet(Iterables.transform(keys, (Function<PropertyKey, Long>) propertyKey -> propertyKey.longId()));
        }

        @Override
        public Boolean call() throws Exception {
            ManagementSystem management = (ManagementSystem) graph.openManagement();
            try {
                JanusGraphVertex vertex = management.transaction.getVertex(schemaVertexId);
                Preconditions.checkArgument(vertex instanceof JanusGraphSchemaVertex);
                JanusGraphSchemaVertex schemaVertex = (JanusGraphSchemaVertex) vertex;

                Set<PropertyKeyVertex> keys = Sets.newHashSet();
                for (Long keyId : propertyKeys) keys.add((PropertyKeyVertex) management.transaction.getVertex(keyId));
                management.setStatus(schemaVertex, newStatus, keys);
                management.updatedTypes.addAll(keys);
                management.updatedTypes.add(schemaVertex);
                if (LOG.isInfoEnabled()) {
                    Set<String> propNames = Sets.newHashSet();
                    for (PropertyKeyVertex v : keys) {
                        try {
                            propNames.add(v.name());
                        } catch (Throwable t) {
                            LOG.warn("Failed to get name for property key with id {}", v.longId(), t);
                            propNames.add("(ID#" + v.longId() + ")");
                        }
                    }
                    String schemaName = "(ID#" + schemaVertexId + ")";
                    try {
                        schemaName = schemaVertex.name();
                    } catch (Throwable t) {
                        LOG.warn("Failed to get name for schema vertex with id {}", schemaVertexId, t);
                    }
                    LOG.info("Set status {} on schema element {} with property keys {}", newStatus, schemaName, propNames);
                }
                management.commit();
                return true;
            } catch (RuntimeException e) {
                management.rollback();
                throw e;
            }
        }

        @Override
        public int hashCode() {
            return Long.hashCode(schemaVertexId);
        }

        @Override
        public boolean equals(Object oth) {
            if (this == oth) {
                return true;
            } else if (!getClass().isInstance(oth)) {
                return false;
            }
            return schemaVertexId == ((UpdateStatusTrigger) oth).schemaVertexId;
        }

    }

    private void setUpdateTrigger(Callable<Boolean> trigger) {
        updatedTypeTriggers.add(trigger);
    }

    private void setStatus(JanusGraphSchemaVertex vertex, SchemaStatus status, Set<PropertyKeyVertex> keys) {
        if (keys.isEmpty()) setStatusVertex(vertex, status);
        else setStatusEdges(vertex, status, keys);
        vertex.resetCache();
        updateSchemaVertex(vertex);
    }

    private void setStatusVertex(JanusGraphSchemaVertex vertex, SchemaStatus status) {
        Preconditions.checkArgument(vertex instanceof RelationTypeVertex || vertex.asIndexType().isCompositeIndex());

        //Delete current status
        for (JanusGraphVertexProperty p : vertex.query().types(BaseKey.SchemaDefinitionProperty).properties()) {
            if (p.<TypeDefinitionDescription>valueOrNull(BaseKey.SchemaDefinitionDesc).getCategory() == TypeDefinitionCategory.STATUS) {
                if (p.value().equals(status)) return;
                else p.remove();
            }
        }
        //Add new status
        JanusGraphVertexProperty p = transaction.addProperty(vertex, BaseKey.SchemaDefinitionProperty, status);
        p.property(BaseKey.SchemaDefinitionDesc.name(), TypeDefinitionDescription.of(TypeDefinitionCategory.STATUS));
    }

    private void setStatusEdges(JanusGraphSchemaVertex vertex, SchemaStatus status, Set<PropertyKeyVertex> keys) {
        Preconditions.checkArgument(vertex.asIndexType().isMixedIndex());

        for (JanusGraphEdge edge : vertex.getEdges(TypeDefinitionCategory.INDEX_FIELD, Direction.OUT)) {
            if (!keys.contains(edge.vertex(Direction.IN))) continue; //Only address edges with matching keys
            TypeDefinitionDescription desc = edge.valueOrNull(BaseKey.SchemaDefinitionDesc);
            Parameter[] parameters = (Parameter[]) desc.getModifier();
            if (parameters[parameters.length - 1].value().equals(status)) continue;

            Parameter[] paraCopy = Arrays.copyOf(parameters, parameters.length);
            paraCopy[parameters.length - 1] = ParameterType.STATUS.getParameter(status);
            edge.remove();
            addSchemaEdge(vertex, edge.vertex(Direction.IN), TypeDefinitionCategory.INDEX_FIELD, paraCopy);
        }

        for (PropertyKeyVertex prop : keys) prop.resetCache();
    }

    @Override
    public IndexJobFuture getIndexJobStatus(Index index) {
        IndexIdentifier indexId = new IndexIdentifier(index);
        return graph.getBackend().getScanJobStatus(indexId);
    }

    private static class IndexIdentifier {

        private final String indexName;
        private final String relationTypeName;
        private final int hashcode;

        private IndexIdentifier(Index index) {
            Preconditions.checkArgument(index != null);
            indexName = index.name();
            if (index instanceof RelationTypeIndex) relationTypeName = ((RelationTypeIndex) index).getType().name();
            else relationTypeName = null;
            Preconditions.checkArgument(StringUtils.isNotBlank(indexName));
            hashcode = Objects.hash(indexName, relationTypeName);
        }

        private Index retrieve(ManagementSystem management) {
            if (relationTypeName == null) return management.getGraphIndex(indexName);
            else return management.getRelationIndex(management.getRelationType(relationTypeName), indexName);
        }

        @Override
        public String toString() {
            String s = indexName;
            if (relationTypeName != null) s += "[" + relationTypeName + "]";
            return s;
        }

        @Override
        public int hashCode() {
            return hashcode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            } else if (!getClass().isInstance(other)) {
                return false;
            }
            IndexIdentifier oth = (IndexIdentifier) other;
            return indexName.equals(oth.indexName) && (relationTypeName == oth.relationTypeName || (relationTypeName != null && relationTypeName.equals(oth.relationTypeName)));
        }

        public Consumer<ScanMetrics> getIndexJobFinisher() {
            return getIndexJobFinisher(null, null);
        }

        public Consumer<ScanMetrics> getIndexJobFinisher(JanusGraph graph, SchemaAction action) {
            Preconditions.checkArgument((graph != null && action != null) || (graph == null && action == null));
            return metrics -> {
                try {
                    if (metrics.get(ScanMetrics.Metric.FAILURE) == 0) {
                        if (action != null) {
                            ManagementSystem management = (ManagementSystem) graph.openManagement();
                            try {
                                Index index = retrieve(management);
                                management.updateIndex(index, action);
                            } finally {
                                management.commit();
                            }
                        }
                        LOG.debug("Index update job successful for [{}]", IndexIdentifier.this.toString());
                    } else {
                        LOG.error("Index update job unsuccessful for [{}]. Check logs", IndexIdentifier.this.toString());
                    }
                } catch (Throwable e) {
                    LOG.error("Error encountered when updating index after job finished [" + IndexIdentifier.this.toString() + "]: ", e);
                }
            };
        }
    }


    @Override
    public void changeName(JanusGraphSchemaElement element, String newName) {
        Preconditions.checkArgument(StringUtils.isNotBlank(newName), "Invalid name: %s", newName);
        JanusGraphSchemaVertex schemaVertex = getSchemaVertex(element);
        String oldName = schemaVertex.name();
        if (oldName.equals(newName)) return;

        JanusGraphSchemaCategory schemaCategory = schemaVertex.valueOrNull(BaseKey.SchemaCategory);
        Preconditions.checkArgument(schemaCategory.hasName(), "Invalid schema element: %s", element);

        if (schemaVertex instanceof RelationType) {
            InternalRelationType relType = (InternalRelationType) schemaVertex;
            if (relType.getBaseType() != null) {
                newName = composeRelationTypeIndexName(relType.getBaseType(), newName);
            }

            JanusGraphSchemaCategory cat = relType.isEdgeLabel() ?
                    JanusGraphSchemaCategory.EDGELABEL : JanusGraphSchemaCategory.PROPERTYKEY;
            SystemTypeManager.throwIfSystemName(cat, newName);
        } else if (element instanceof VertexLabel) {
            SystemTypeManager.throwIfSystemName(JanusGraphSchemaCategory.VERTEXLABEL, newName);
        } else if (element instanceof JanusGraphIndex) {
            checkIndexName(newName);
        }

        transaction.addProperty(schemaVertex, BaseKey.SchemaName, schemaCategory.getSchemaName(newName));

        updateConnectionEdgeConstraints(schemaVertex, oldName, newName);

        updateSchemaVertex(schemaVertex);
        schemaVertex.resetCache();
        updatedTypes.add(schemaVertex);
    }

    private void updateConnectionEdgeConstraints(JanusGraphSchemaVertex edgeLabel, String oldName, String newName) {
        if (!(edgeLabel instanceof EdgeLabel)) return;
        ((EdgeLabel) edgeLabel).mappedConnections().stream()
                .peek(s -> schemaCache.expireSchemaElement(s.getOutgoingVertexLabel().longId()))
                .map(Connection::getConnectionEdge)
                .forEach(edge -> {
                    TypeDefinitionDescription desc = new TypeDefinitionDescription(TypeDefinitionCategory.CONNECTION_EDGE, newName);
                    edge.property(BaseKey.SchemaDefinitionDesc.name(), desc);
                });
    }

    public JanusGraphSchemaVertex getSchemaVertex(JanusGraphSchemaElement element) {
        if (element instanceof RelationType) {
            Preconditions.checkArgument(element instanceof RelationTypeVertex, "Invalid schema element provided: %s", element);
            return (RelationTypeVertex) element;
        } else if (element instanceof RelationTypeIndex) {
            return (RelationTypeVertex) ((RelationTypeIndexWrapper) element).getWrappedType();
        } else if (element instanceof VertexLabel) {
            Preconditions.checkArgument(element instanceof VertexLabelVertex, "Invalid schema element provided: %s", element);
            return (VertexLabelVertex) element;
        } else if (element instanceof JanusGraphIndex) {
            Preconditions.checkArgument(element instanceof JanusGraphIndexWrapper, "Invalid schema element provided: %s", element);
            IndexType index = ((JanusGraphIndexWrapper) element).getBaseIndex();
            SchemaSource base = ((IndexTypeWrapper) index).getSchemaBase();
            return (JanusGraphSchemaVertex) base;
        }
        throw new IllegalArgumentException("Invalid schema element provided: " + element);
    }

    private void updateSchemaVertex(JanusGraphSchemaVertex schemaVertex) {
        transaction.updateSchemaVertex(schemaVertex);
    }

    /* --------------
    Type Modifiers
     --------------- */

    /**
     * Retrieves the consistency level for a schema element (types and internal indexes)
     */
    @Override
    public ConsistencyModifier getConsistency(JanusGraphSchemaElement element) {
        Preconditions.checkArgument(element != null);
        if (element instanceof RelationType) return ((InternalRelationType) element).getConsistencyModifier();
        else if (element instanceof JanusGraphIndex) {
            IndexType index = ((JanusGraphIndexWrapper) element).getBaseIndex();
            if (index.isMixedIndex()) return ConsistencyModifier.DEFAULT;
            return ((CompositeIndexType) index).getConsistencyModifier();
        } else return ConsistencyModifier.DEFAULT;
    }

    /**
     * Sets the consistency level for those schema elements that support it (types and internal indexes)
     * <p>
     * Note, that it is possible to have a race condition here if two threads simultaneously try to change the
     * consistency level. However, this is resolved when the consistency level is being read by taking the
     * first one and deleting all existing attached consistency levels upon modification.
     */
    @Override
    public void setConsistency(JanusGraphSchemaElement element, ConsistencyModifier consistency) {
        if (element instanceof RelationType) {
            RelationTypeVertex rv = (RelationTypeVertex) element;
            Preconditions.checkArgument(consistency != ConsistencyModifier.FORK || !rv.multiplicity().isConstrained(),
                    "Cannot apply FORK consistency mode to constraint relation type: %s", rv.name());
        } else if (element instanceof JanusGraphIndex) {
            IndexType index = ((JanusGraphIndexWrapper) element).getBaseIndex();
            if (index.isMixedIndex()) {
                throw new IllegalArgumentException("Cannot change consistency on mixed index: " + element);
            }
        } else {
            throw new IllegalArgumentException("Cannot change consistency of schema element: " + element);
        }
        setTypeModifier(element, ModifierType.CONSISTENCY, consistency);
    }

    @Override
    public Duration getTTL(JanusGraphSchemaType type) {
        Preconditions.checkArgument(type != null);
        int ttl;
        if (type instanceof VertexLabelVertex) {
            ttl = ((VertexLabelVertex) type).getTTL();
        } else if (type instanceof RelationTypeVertex) {
            ttl = ((RelationTypeVertex) type).getTTL();
        } else {
            throw new IllegalArgumentException("given type does not support TTL: " + type.getClass());
        }
        return Duration.ofSeconds(ttl);
    }

    /**
     * Sets time-to-live for those schema types that support it
     *
     * @param duration Note that only 'seconds' granularity is supported
     */
    @Override
    public void setTTL(JanusGraphSchemaType type, Duration duration) {
        if (!graph.getBackend().getStoreFeatures().hasCellTTL()) {
            throw new UnsupportedOperationException("The storage engine does not support TTL");
        }
        if (type instanceof VertexLabelVertex) {
            Preconditions.checkArgument(((VertexLabelVertex) type).isStatic(), "must define vertex label as static to allow setting TTL");
        } else {
            Preconditions.checkArgument(type instanceof EdgeLabelVertex || type instanceof PropertyKeyVertex, "TTL is not supported for type " + type.getClass().getSimpleName());
        }

        Integer ttlSeconds = (duration.isZero()) ? null :
                (int) duration.getSeconds();

        setTypeModifier(type, ModifierType.TTL, ttlSeconds);
    }

    private void setTypeModifier(JanusGraphSchemaElement element, ModifierType modifierType, Object value) {
        Preconditions.checkArgument(element != null, "null schema element");

        TypeDefinitionCategory cat = modifierType.getCategory();

        if (cat.hasDataType() && null != value) {
            Preconditions.checkArgument(cat.getDataType().equals(value.getClass()), "modifier value is not of expected type " + cat.getDataType());
        }

        JanusGraphSchemaVertex typeVertex;

        if (element instanceof JanusGraphSchemaVertex) {
            typeVertex = (JanusGraphSchemaVertex) element;
        } else if (element instanceof JanusGraphIndex) {
            IndexType index = ((JanusGraphIndexWrapper) element).getBaseIndex();
            SchemaSource base = ((IndexTypeWrapper) index).getSchemaBase();
            typeVertex = (JanusGraphSchemaVertex) base;
        } else throw new IllegalArgumentException("Invalid schema element: " + element);

        // remove any pre-existing value for the modifier, or return if an identical value has already been set
        for (JanusGraphEdge e : typeVertex.getEdges(TypeDefinitionCategory.TYPE_MODIFIER, Direction.OUT)) {
            JanusGraphSchemaVertex v = (JanusGraphSchemaVertex) e.vertex(Direction.IN);

            TypeDefinitionMap def = v.getDefinition();
            Object existingValue = def.getValue(modifierType.getCategory());
            if (null != existingValue) {
                if (existingValue.equals(value)) {
                    return; //Already has the right value, don't need to do anything
                } else {
                    e.remove();
                    v.remove();
                }
            }
        }

        if (null != value) {
            TypeDefinitionMap def = new TypeDefinitionMap();
            def.setValue(cat, value);
            JanusGraphSchemaVertex cVertex = transaction.makeSchemaVertex(JanusGraphSchemaCategory.TYPE_MODIFIER, null, def);
            addSchemaEdge(typeVertex, cVertex, TypeDefinitionCategory.TYPE_MODIFIER, null);
        }

        updateSchemaVertex(typeVertex);
        updatedTypes.add(typeVertex);
    }

    // ###### TRANSACTION PROXY #########

    @Override
    public boolean containsRelationType(String name) {
        return transaction.containsRelationType(name);
    }

    @Override
    public RelationType getRelationType(String name) {
        return transaction.getRelationType(name);
    }

    @Override
    public boolean containsPropertyKey(String name) {
        return transaction.containsPropertyKey(name);
    }

    @Override
    public PropertyKey getPropertyKey(String name) {
        return transaction.getPropertyKey(name);
    }

    @Override
    public boolean containsEdgeLabel(String name) {
        return transaction.containsEdgeLabel(name);
    }

    @Override
    public EdgeLabel getOrCreateEdgeLabel(String name) {
        return transaction.getOrCreateEdgeLabel(name);
    }

    @Override
    public PropertyKey getOrCreatePropertyKey(String name) {
        return transaction.getOrCreatePropertyKey(name);
    }

    @Override
    public EdgeLabel getEdgeLabel(String name) {
        return transaction.getEdgeLabel(name);
    }

    @Override
    public PropertyKeyMaker makePropertyKey(String name) {
        return transaction.makePropertyKey(name);
    }

    @Override
    public EdgeLabelMaker makeEdgeLabel(String name) {
        return transaction.makeEdgeLabel(name);
    }

    @Override
    public <T extends RelationType> Iterable<T> getRelationTypes(Class<T> clazz) {
        Preconditions.checkNotNull(clazz);
        Iterable<? extends JanusGraphVertex> types;
        if (PropertyKey.class.equals(clazz)) {
            types = QueryUtil.getVertices(transaction, BaseKey.SchemaCategory, JanusGraphSchemaCategory.PROPERTYKEY);
        } else if (EdgeLabel.class.equals(clazz)) {
            types = QueryUtil.getVertices(transaction, BaseKey.SchemaCategory, JanusGraphSchemaCategory.EDGELABEL);
        } else if (RelationType.class.equals(clazz)) {
            types = Iterables.concat(getRelationTypes(EdgeLabel.class), getRelationTypes(PropertyKey.class));
        } else {
            throw new IllegalArgumentException("Unknown type class: " + clazz);
        }
        return Iterables.filter(Iterables.filter(types, clazz), t -> {
            //Filter out all relation type indexes
            return ((InternalRelationType) t).getBaseType() == null;
        });
    }

    @Override
    public boolean containsVertexLabel(String name) {
        return transaction.containsVertexLabel(name);
    }

    @Override
    public VertexLabel getVertexLabel(String name) {
        return transaction.getVertexLabel(name);
    }

    @Override
    public VertexLabel getOrCreateVertexLabel(String name) {
        return transaction.getOrCreateVertexLabel(name);
    }

    @Override
    public VertexLabelMaker makeVertexLabel(String name) {
        return transaction.makeVertexLabel(name);
    }

    @Override
    public VertexLabel addProperties(VertexLabel vertexLabel, PropertyKey... keys) {
        return transaction.addProperties(vertexLabel, keys);
    }

    @Override
    public EdgeLabel addProperties(EdgeLabel edgeLabel, PropertyKey... keys) {
        return transaction.addProperties(edgeLabel, keys);
    }

    @Override
    public EdgeLabel addConnection(EdgeLabel edgeLabel, VertexLabel outVLabel, VertexLabel inVLabel) {
        return transaction.addConnection(edgeLabel, outVLabel, inVLabel);
    }

    @Override
    public Iterable<VertexLabel> getVertexLabels() {
        return Iterables.filter(QueryUtil.getVertices(transaction, BaseKey.SchemaCategory,
                JanusGraphSchemaCategory.VERTEXLABEL), VertexLabel.class);
    }

    // ###### USERMODIFIABLECONFIGURATION PROXY #########

    @Override
    public synchronized String get(String path) {
        ensureOpen();
        return userConfig.get(path);
    }

    @Override
    public synchronized JanusGraphConfiguration set(String path, Object value) {
        ensureOpen();
        return userConfig.set(path, value);
    }
}