package com.github.streamshub.console.api.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.streamshub.console.api.support.ComparatorBuilder;
import com.github.streamshub.console.api.support.ListRequestContext;

import static java.util.Comparator.comparing;
import static java.util.Comparator.nullsLast;

@Schema(name = "KafkaClusterAttributes")
@JsonFilter("fieldFilter")
public class KafkaCluster {

    public static class Fields {
        public static final String NAME = "name";
        public static final String NAMESPACE = "namespace";
        public static final String CREATION_TIMESTAMP = "creationTimestamp";
        public static final String NODES = "nodes";
        public static final String CONTROLLER = "controller";
        public static final String AUTHORIZED_OPERATIONS = "authorizedOperations";
        public static final String LISTENERS = "listeners";
        public static final String METRICS = "metrics";
        public static final String KAFKA_VERSION = "kafkaVersion";
        public static final String STATUS = "status";
        public static final String CONDITIONS = "conditions";
        public static final String NODE_POOLS = "nodePools";

        static final Comparator<KafkaCluster> ID_COMPARATOR =
                comparing(KafkaCluster::getId, nullsLast(String::compareTo));

        static final Map<String, Map<Boolean, Comparator<KafkaCluster>>> COMPARATORS =
                ComparatorBuilder.bidirectional(
                        Map.of("id", ID_COMPARATOR,
                                NAME, comparing(KafkaCluster::getName),
                                NAMESPACE, comparing(KafkaCluster::getNamespace),
                                CREATION_TIMESTAMP, comparing(KafkaCluster::getCreationTimestamp)));

        public static final ComparatorBuilder<KafkaCluster> COMPARATOR_BUILDER =
                new ComparatorBuilder<>(KafkaCluster.Fields::comparator, KafkaCluster.Fields.defaultComparator());

        public static final String LIST_DEFAULT =
                NAME + ", "
                + NAMESPACE;

        public static final String DESCRIBE_DEFAULT =
                NAME + ", "
                + NAMESPACE + ", "
                + CREATION_TIMESTAMP + ", "
                + NODES + ", "
                + CONTROLLER + ", "
                + AUTHORIZED_OPERATIONS + ", "
                + LISTENERS + ", "
                + KAFKA_VERSION + ", "
                + STATUS + ", "
                + CONDITIONS + ", "
                + NODE_POOLS;

        private Fields() {
            // Prevent instances
        }

        public static Comparator<KafkaCluster> defaultComparator() {
            return ID_COMPARATOR;
        }

        public static Comparator<KafkaCluster> comparator(String fieldName, boolean descending) {
            return COMPARATORS.getOrDefault(fieldName, Collections.emptyMap()).get(descending);
        }
    }

    @Schema(name = "KafkaClusterListResponse")
    public static final class ListResponse extends DataList<KafkaClusterResource> {
        public ListResponse(List<KafkaCluster> data, ListRequestContext<KafkaCluster> listSupport) {
            super(data.stream()
                    .map(entry -> {
                        var rsrc = new KafkaClusterResource(entry);
                        rsrc.addMeta("page", listSupport.buildPageMeta(entry::toCursor));
                        rsrc.addMeta("configured", entry.isConfigured());
                        rsrc.addMeta("managed", entry.isManaged());
                        return rsrc;
                    })
                    .toList());
            addMeta("page", listSupport.buildPageMeta());
            listSupport.buildPageLinks(KafkaCluster::toCursor).forEach(this::addLink);
        }
    }

    @Schema(name = "KafkaClusterResponse")
    public static final class SingleResponse extends DataSingleton<KafkaClusterResource> {
        public SingleResponse(KafkaCluster data) {
            super(new KafkaClusterResource(data));
        }
    }

    @Schema(name = "KafkaCluster")
    public static final class KafkaClusterResource extends Resource<KafkaCluster> {
        public KafkaClusterResource(KafkaCluster data) {
            super(data.id, "kafkas", data);
            addMeta("configured", data.isConfigured());
            addMeta("managed", data.isManaged());
        }
    }

    String name; // Strimzi Kafka CR only
    String namespace; // Strimzi Kafka CR only
    String creationTimestamp; // Strimzi Kafka CR only
    @JsonIgnore
    String id; // non-final, may be overridden by configuration
    final List<Node> nodes;
    final Node controller;
    final List<String> authorizedOperations;
    List<KafkaListener> listeners; // Strimzi Kafka CR only
    @Schema(readOnly = true, description = """
            Contains the set of metrics optionally retrieved only in a describe operation.
            """)
    String kafkaVersion;
    String status;
    List<Condition> conditions;
    @JsonIgnore
    boolean configured;
    List<String> nodePools;
    @JsonIgnore
    boolean managed;

    public KafkaCluster(String id, List<Node> nodes, Node controller, List<String> authorizedOperations) {
        super();
        this.id = id;
        this.nodes = nodes;
        this.controller = controller;
        this.authorizedOperations = authorizedOperations;
    }

    /**
     * Constructs a "cursor" Topic from the encoded string representation of the subset
     * of Topic fields used to compare entities for pagination/sorting.
     */
    public static KafkaCluster fromCursor(JsonObject cursor) {
        if (cursor == null) {
            return null;
        }

        KafkaCluster cluster = new KafkaCluster(cursor.getString("id"), null, null, null);
        JsonObject attr = cursor.getJsonObject("attributes");
        cluster.setName(attr.getString(Fields.NAME, null));
        cluster.setNamespace(attr.getString(Fields.NAMESPACE, null));
        cluster.setCreationTimestamp(attr.getString(Fields.CREATION_TIMESTAMP, null));

        return cluster;
    }

    public String toCursor(List<String> sortFields) {
        JsonObjectBuilder cursor = Json.createObjectBuilder()
                .add("id", id == null ? Json.createValue("") : Json.createValue(id));

        JsonObjectBuilder attrBuilder = Json.createObjectBuilder();
        maybeAddAttribute(attrBuilder, sortFields, Fields.NAME, name);
        maybeAddAttribute(attrBuilder, sortFields, Fields.NAMESPACE, namespace);
        maybeAddAttribute(attrBuilder, sortFields, Fields.CREATION_TIMESTAMP, creationTimestamp);
        cursor.add("attributes", attrBuilder.build());

        return Base64.getUrlEncoder().encodeToString(cursor.build().toString().getBytes(StandardCharsets.UTF_8));
    }

    static void maybeAddAttribute(JsonObjectBuilder attrBuilder, List<String> sortFields, String key, String value) {
        if (sortFields.contains(key)) {
            attrBuilder.add(key, value != null ? Json.createValue(value) : JsonValue.NULL);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(String creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public Node getController() {
        return controller;
    }

    public List<String> getAuthorizedOperations() {
        return authorizedOperations;
    }

    public List<KafkaListener> getListeners() {
        return listeners;
    }

    public void setListeners(List<KafkaListener> listeners) {
        this.listeners = listeners;
    }

    public String getKafkaVersion() {
        return kafkaVersion;
    }

    public void setKafkaVersion(String kafkaVersion) {
        this.kafkaVersion = kafkaVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public List<String> getNodePools() {
        return nodePools;
    }

    public void setNodePools(List<String> nodePools) {
        this.nodePools = nodePools;
    }

    public void setManaged(boolean managed) {
        this.managed = managed;
    }

    public boolean isManaged() {
        return managed;
    }
}
