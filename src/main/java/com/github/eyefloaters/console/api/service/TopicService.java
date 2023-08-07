package com.github.eyefloaters.console.api.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigResource;

import com.github.eyefloaters.console.api.model.ConfigEntry;
import com.github.eyefloaters.console.api.model.Either;
import com.github.eyefloaters.console.api.model.Error;
import com.github.eyefloaters.console.api.model.NewPartitions;
import com.github.eyefloaters.console.api.model.NewTopic;
import com.github.eyefloaters.console.api.model.OffsetInfo;
import com.github.eyefloaters.console.api.model.Topic;

import static org.apache.kafka.clients.admin.NewPartitions.increaseTo;

@ApplicationScoped
public class TopicService {

    @Inject
    Supplier<Admin> clientSupplier;

    @Inject
    ConfigService configService;

    public CompletionStage<NewTopic> createTopic(NewTopic topic) {
        Admin adminClient = clientSupplier.get();
        String topicName = topic.getName();
        org.apache.kafka.clients.admin.NewTopic newTopic;

        if (topic.getReplicasAssignments() != null) {
            newTopic = new org.apache.kafka.clients.admin.NewTopic(
                    topicName,
                    topic.getReplicasAssignments());
        } else {
            newTopic = new org.apache.kafka.clients.admin.NewTopic(
                    topicName,
                    Optional.ofNullable(topic.getNumPartitions()),
                    Optional.ofNullable(topic.getReplicationFactor()));
        }

        newTopic.configs(topic.getConfigs());

        CreateTopicsResult result = adminClient.createTopics(List.of(newTopic));

        return result.all()
                .thenApply(nothing -> NewTopic.fromKafkaModel(topicName, result))
                .toCompletionStage();
    }

    public CompletionStage<List<Topic>> listTopics(boolean listInternal, List<String> includes, String offsetSpec) {
        Admin adminClient = clientSupplier.get();

        return adminClient.listTopics(new ListTopicsOptions().listInternal(listInternal))
                .listings()
                .thenApply(list -> list.stream().map(Topic::fromTopicListing).toList())
                .toCompletionStage()
                .thenCompose(list -> augmentList(adminClient, list, includes, offsetSpec))
                .thenApply(list -> list.stream().sorted(Comparator.comparing(Topic::getName)).toList());
    }

    public CompletionStage<Topic> describeTopic(String topicName, List<String> includes, String offsetSpec) {
        Admin adminClient = clientSupplier.get();

        return describeTopics(adminClient, List.of(topicName), offsetSpec)
            .thenApply(result -> result.get(topicName))
            .thenApply(result -> {
                if (result.isPrimaryPresent()) {
                    return result.getPrimary();
                }
                throw new CompletionException(result.getAlternate());
            })
            .thenCompose(topic -> {
                CompletableFuture<Topic> promise = new CompletableFuture<>();

                if (includes.contains("configs")) {
                    List<ConfigResource> keys = List.of(new ConfigResource(ConfigResource.Type.TOPIC, topicName));
                    configService.describeConfigs(adminClient, keys)
                        .thenApply(configs -> {
                            configs.forEach((name, either) -> topic.addConfigs(either));
                            promise.complete(topic);
                            return true; // Match `completeExceptionally`
                        })
                        .exceptionally(promise::completeExceptionally);
                } else {
                    promise.complete(topic);
                }

                return promise;
            });
    }

    public CompletionStage<Map<String, ConfigEntry>> describeConfigs(String topicName) {
        return configService.describeConfigs(ConfigResource.Type.TOPIC, topicName);
    }

    public CompletionStage<Map<String, ConfigEntry>> alterConfigs(String topicName, Map<String, ConfigEntry> configs) {
        return configService.alterConfigs(ConfigResource.Type.TOPIC, topicName, configs);
    }

    public CompletionStage<Void> createPartitions(String topicName, NewPartitions partitions) {
        Admin adminClient = clientSupplier.get();

        int totalCount = partitions.getTotalCount();
        var newAssignments = partitions.getNewAssignments();

        org.apache.kafka.clients.admin.NewPartitions newPartitions;

        if (newAssignments != null) {
            newPartitions = increaseTo(totalCount, newAssignments);
        } else {
            newPartitions = increaseTo(totalCount);
        }

        return adminClient.createPartitions(Map.of(topicName, newPartitions))
                .all()
                .toCompletionStage();
    }

    public CompletionStage<Map<String, Error>> deleteTopics(String... topicNames) {
        Admin adminClient = clientSupplier.get();
        Map<String, Error> errors = new HashMap<>();

        var pendingDeletes = adminClient.deleteTopics(Arrays.asList(topicNames))
                .topicNameValues()
                .entrySet()
                .stream()
                .map(entry -> entry.getValue().whenComplete((nothing, thrown) -> {
                    if (thrown != null) {
                        errors.put(entry.getKey(), new Error("Unable to delete topic", thrown.getMessage(), thrown));
                    }
                }))
                .map(KafkaFuture::toCompletionStage)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(pendingDeletes).thenApply(nothing -> errors);
    }

    CompletionStage<List<Topic>> augmentList(Admin adminClient, List<Topic> list, List<String> includes, String offsetSpec) {
        Map<String, Topic> topics = list.stream().collect(Collectors.toMap(Topic::getName, Function.identity()));
        CompletableFuture<Void> configPromise = maybeDescribeConfigs(adminClient, topics, includes);
        CompletableFuture<Void> describePromise = maybeDescribeTopics(adminClient, topics, includes, offsetSpec);

        return CompletableFuture.allOf(configPromise, describePromise).thenApply(nothing -> list);
    }

    CompletableFuture<Void> maybeDescribeConfigs(Admin adminClient, Map<String, Topic> topics, List<String> includes) {
        CompletableFuture<Void> promise = new CompletableFuture<>();

        if (includes.contains("configs")) {
            List<ConfigResource> keys = topics.keySet().stream().map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name)).toList();

            configService.describeConfigs(adminClient, keys)
                .thenApply(configs -> {
                    configs.forEach((name, either) -> topics.get(name).addConfigs(either));
                    promise.complete(null);
                    return true; // Match `completeExceptionally`
                })
                .exceptionally(promise::completeExceptionally);
        } else {
            promise.complete(null);
        }

        return promise;
    }

    CompletableFuture<Void> maybeDescribeTopics(Admin adminClient, Map<String, Topic> topics, List<String> includes, String offsetSpec) {
        CompletableFuture<Void> promise = new CompletableFuture<>();

        if (includes.contains("partitions") || includes.contains("authorizedOperations")) {
            describeTopics(adminClient, topics.keySet(), offsetSpec)
                .thenApply(descriptions -> {
                    descriptions.forEach((name, either) -> {
                        if (includes.contains("partitions")) {
                            topics.get(name).addPartitions(either);
                        }
                        if (includes.contains("authorizedOperations")) {
                            topics.get(name).addAuthorizedOperations(either);
                        }
                    });

                    return promise.complete(null);
                })
                .exceptionally(promise::completeExceptionally);
        } else {
            promise.complete(null);
        }

        return promise;
    }

    CompletionStage<Map<String, Either<Topic, Throwable>>> describeTopics(Admin adminClient, Collection<String> topicNames, String offsetSpec) {
        Map<String, Either<Topic, Throwable>> result = new LinkedHashMap<>(topicNames.size());

        var pendingDescribes = adminClient.describeTopics(topicNames)
                .topicNameValues()
                .entrySet()
                .stream()
                .map(entry ->
                    entry.getValue().whenComplete((description, error) ->
                        result.put(entry.getKey(), Either.of(description, error, Topic::fromTopicDescription))))
                .map(KafkaFuture::toCompletionStage)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);

        var promise = new CompletableFuture<Map<String, Either<Topic, Throwable>>>();

        CompletableFuture.allOf(pendingDescribes)
                .thenCompose(nothing -> listOffsets(adminClient, result, offsetSpec))
                .thenApply(nothing -> promise.complete(result))
                .exceptionally(promise::completeExceptionally);

        return promise;
    }

    CompletionStage<Void> listOffsets(Admin adminClient, Map<String, Either<Topic, Throwable>> topics, String offsetSpec) {
        OffsetSpec reqOffsetSpec = switch (offsetSpec) {
            case "earliest"     -> OffsetSpec.earliest();
            case "latest"       -> OffsetSpec.latest();
            case "maxTimestamp" -> OffsetSpec.maxTimestamp();
            default             -> OffsetSpec.forTimestamp(Instant.parse(offsetSpec).toEpochMilli());
        };

        Map<org.apache.kafka.common.TopicPartition, OffsetSpec> request = topics.entrySet().stream()
                .filter(entry -> entry.getValue().isPrimaryPresent())
                .map(entry -> entry.getValue().getPrimary())
                .filter(topic -> topic.getPartitions().isPrimaryPresent())
                .flatMap(topic -> topic.getPartitions().getPrimary()
                        .stream()
                        .map(partition -> new org.apache.kafka.common.TopicPartition(topic.getName(), partition.getPartition())))
                .collect(Collectors.toMap(Function.identity(), ignored -> reqOffsetSpec));

        var result = adminClient.listOffsets(request);
        var pendingOffsets = request.keySet().stream()
                .map(topicPartition -> result.partitionResult(topicPartition)
                        .whenComplete((offsetResult, error) ->
                        addOffset(topics.get(topicPartition.topic()).getPrimary(),
                                    topicPartition.partition(),
                                    offsetResult,
                                    error)))
                .map(KafkaFuture::toCompletionStage)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);

        var promise = new CompletableFuture<Void>();

        CompletableFuture.allOf(pendingOffsets)
                .thenApply(nothing -> promise.complete(null))
                .exceptionally(promise::completeExceptionally);

        return promise;
    }

    void addOffset(Topic topic, int partitionNo, ListOffsetsResultInfo result, Throwable error) {
        topic.getPartitions()
            .getPrimary()
            .stream()
            .filter(partition -> partition.getPartition() == partitionNo)
            .findFirst()
            .ifPresent(partition -> partition.addOffset(either(result, error)));
    }

    Either<OffsetInfo, Throwable> either(ListOffsetsResultInfo result, Throwable error) {
        Function<ListOffsetsResultInfo, OffsetInfo> transformer = offsetInfo -> {
            Instant timestamp = offsetInfo.timestamp() != -1 ? Instant.ofEpochMilli(offsetInfo.timestamp()) : null;
            return new OffsetInfo(offsetInfo.offset(), timestamp, offsetInfo.leaderEpoch().orElse(null));
        };

        return Either.of(result, error, transformer);
    }
}
