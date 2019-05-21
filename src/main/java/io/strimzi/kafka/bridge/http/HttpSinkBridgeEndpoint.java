/*
 * Copyright 2018 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.kafka.bridge.http;

import io.strimzi.kafka.bridge.Endpoint;
import io.strimzi.kafka.bridge.SinkBridgeEndpoint;
import io.strimzi.kafka.bridge.SinkTopicSubscription;
import io.strimzi.kafka.bridge.converter.MessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpJsonMessageConverter;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HttpSinkBridgeEndpoint<V, K> extends SinkBridgeEndpoint<V, K> {

    private RoutingContext routingContext;

    //unique id assigned to every consumer during its creation.
    private String consumerInstanceId;

    private String consumerBaseUri;

    private MessageConverter messageConverter;

    private HttpBridgeContext httpBridgeContext;

    HttpSinkBridgeEndpoint(Vertx vertx, HttpBridgeConfig httpBridgeConfigProperties, HttpBridgeContext context) {
        super(vertx, httpBridgeConfigProperties);
        this.httpBridgeContext = context;
    }

    @Override
    public void open() {

    }

    @Override
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity"})
    public void handle(Endpoint<?> endpoint) {

        routingContext = (RoutingContext) endpoint.get();
        JsonObject bodyAsJson = null;
        // TODO: it seems that getBodyAsJson raises an exception when the body is empty and not null
        try {
            bodyAsJson = routingContext.getBodyAsJson();
        } catch (Exception ex) {

        }

        messageConverter = new HttpJsonMessageConverter();

        switch (this.httpBridgeContext.getOpenApiOperation()) {

            case SUBSCRIBE:
                doSubscribe(bodyAsJson);
                break;

            case ASSIGN:
                doAssign(bodyAsJson);
                break;

            case POLL:
                doPoll();
                break;

            case DELETE_CONSUMER:
                doDeleteConsumer();
                break;

            case COMMIT:
                doCommit(bodyAsJson);
                break;

            case SEEK:
                doSeek(bodyAsJson);
                break;

            case SEEK_TO_BEGINNING:
            case SEEK_TO_END:
                doSeekTo(bodyAsJson, this.httpBridgeContext.getOpenApiOperation());
                break;
        }

    }

    private void doSeek(JsonObject bodyAsJson) {
        JsonArray seekOffsetsList = bodyAsJson.getJsonArray("offsets");

        List<Future> seekHandlers = new ArrayList<>(seekOffsetsList.size());
        for (int i = 0; i < seekOffsetsList.size(); i++) {
            TopicPartition topicPartition = new TopicPartition(seekOffsetsList.getJsonObject(i));
            long offset = seekOffsetsList.getJsonObject(i).getLong("offset");
            Future<Void> fut = Future.future();
            seekHandlers.add(fut);
            this.seek(topicPartition, offset, fut.completer());
        }

        CompositeFuture.join(seekHandlers).setHandler(done -> {
            if (done.succeeded()) {
                sendSeekResponse(routingContext.response(), ErrorCodeEnum.NO_CONTENT);
            } else {
                sendSeekResponse(routingContext.response(), ErrorCodeEnum.INTERNAL_SERVER_ERROR);
            }
        });
    }

    private void doSeekTo(JsonObject bodyAsJson, HttpOpenApiOperations seekToType) {
        JsonArray seekPartitionsList = bodyAsJson.getJsonArray("partitions");

        Set<TopicPartition> set = seekPartitionsList.stream()
                .map(JsonObject.class::cast)
                .map(json -> new TopicPartition(json.getString("topic"), json.getInteger("partition")))
                .collect(Collectors.toSet());

        Handler<AsyncResult<Void>> seekHandler = done -> {
            if (done.succeeded()) {
                sendSeekResponse(routingContext.response(), ErrorCodeEnum.NO_CONTENT);
            } else {
                sendSeekResponse(routingContext.response(), ErrorCodeEnum.INTERNAL_SERVER_ERROR);
            }
        };

        if (seekToType == HttpOpenApiOperations.SEEK_TO_BEGINNING) {
            this.seekToBeginning(set, seekHandler);
        } else {
            this.seekToEnd(set, seekHandler);
        }
    }

    private void doCommit(JsonObject bodyAsJson) {
        Map<TopicPartition, OffsetAndMetadata> offsetData = new HashMap<>();
        JsonObject jsonOffsetData = bodyAsJson;
        JsonArray offsetsList = jsonOffsetData.getJsonArray("offsets");

        for (int i = 0; i < offsetsList.size(); i++) {
            TopicPartition topicPartition = new TopicPartition(offsetsList.getJsonObject(i));
            OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(offsetsList.getJsonObject(i));
            offsetData.put(topicPartition, offsetAndMetadata);
        }

        this.commit(offsetData, status -> {
            sendConsumerCommitOffsetResponse(routingContext.response(), status.succeeded());
        });
    }

    private void doDeleteConsumer() {
        this.close();
        this.handleClose();
        log.info("Deleted consumer {} from group {}", routingContext.pathParam("name"), routingContext.pathParam("groupid"));
        sendConsumerDeletionResponse(routingContext.response());
    }

    private void doPoll() {
        if (routingContext.request().getParam("timeout") != null) {
            this.pollTimeOut = Long.parseLong(routingContext.request().getParam("timeout"));
        }

        if (routingContext.request().getParam("max_bytes") != null) {
            this.maxBytes = Long.parseLong(routingContext.request().getParam("max_bytes"));
        }

        this.consume(records -> {
            if (records.succeeded()) {
                Buffer buffer = (Buffer) messageConverter.toMessages(records.result());
                if (buffer.getBytes().length > this.maxBytes) {
                    sendConsumerRecordsFailedResponse(routingContext.response(), ErrorCodeEnum.UNPROCESSABLE_ENTITY.getValue(), "Response is too large");
                } else {
                    sendConsumerRecordsResponse(routingContext.response(), buffer);
                }

            } else {
                sendConsumerRecordsFailedResponse(routingContext.response(), ErrorCodeEnum.INTERNAL_SERVER_ERROR.getValue(), "Internal server error");
            }
        });
    }

    private void doAssign(JsonObject bodyAsJson) {
        JsonArray partitionsList = bodyAsJson.getJsonArray("partitions");
        this.topicSubscriptions.addAll(
                partitionsList.stream()
                        .map(JsonObject.class::cast)
                        .map(json -> new SinkTopicSubscription(json.getString("topic"), json.getInteger("partition"), json.getLong("offset")))
                        .collect(Collectors.toList())
        );

        this.setAssignHandler(assignResult -> {
            if (assignResult.succeeded()) {
                sendConsumerSubscriptionResponse(routingContext.response(), ErrorCodeEnum.NO_CONTENT);
            }
        });

        this.assign(false);
    }

    private void doSubscribe(JsonObject bodyAsJson) {
        // cannot specify both topics list and topic pattern
        if (bodyAsJson.containsKey("topics") && bodyAsJson.containsKey("topic_pattern")) {
            sendConsumerSubscriptionResponse(routingContext.response(), ErrorCodeEnum.CONFLICT);
            return;
        }

        // one of topics list or topic pattern has to be specified
        if (!bodyAsJson.containsKey("topics") && !bodyAsJson.containsKey("topic_pattern")) {
            sendConsumerSubscriptionResponse(routingContext.response(), ErrorCodeEnum.BAD_REQUEST);
            return;
        }

        this.setSubscribeHandler(subscribeResult -> {
            if (subscribeResult.succeeded()) {
                sendConsumerSubscriptionResponse(routingContext.response(), ErrorCodeEnum.NO_CONTENT);
            }
        });

        if (bodyAsJson.containsKey("topics")) {
            JsonArray topicsList = bodyAsJson.getJsonArray("topics");
            this.topicSubscriptions.addAll(
                topicsList.stream()
                        .map(String.class::cast)
                        .map(topic -> new SinkTopicSubscription(topic))
                        .collect(Collectors.toList())
            );
            this.subscribe(false);
        } else if (bodyAsJson.containsKey("topic_pattern")) {
            Pattern pattern = Pattern.compile(bodyAsJson.getString("topic_pattern"));
            this.subscribe(pattern, false);
        }
    }

    /**
     * Add a configuration parameter with key and value to the provided Properties bag
     *
     * @param key key of the configuration parameter
     * @param value value of the configuration parameter
     * @param props Properties bag where to put the configuration parameter
     */
    private void addConfigParameter(String key, String value, Properties props) {
        if (value != null) {
            props.put(key, value);
        }
    }

    @Override
    public void handle(Endpoint<?> endpoint, Handler<?> handler) {
        routingContext = (RoutingContext) endpoint.get();
        JsonObject bodyAsJson = routingContext.getBodyAsJson();

        switch (this.httpBridgeContext.getOpenApiOperation()) {

            case CREATE_CONSUMER:

                // get the consumer group-id
                groupId = routingContext.pathParam("groupid");

                JsonObject json = bodyAsJson;
                // if no name, a random one is assigned
                consumerInstanceId = json.getString("name",
                        "kafka-bridge-consumer-" + UUID.randomUUID().toString());

                if (this.httpBridgeContext.getHttpSinkEndpoints().containsKey(consumerInstanceId)) {
                    routingContext.response().setStatusMessage("Consumer instance with the specified name already exists.")
                            .setStatusCode(ErrorCodeEnum.CONSUMER_ALREADY_EXISTS.getValue())
                            .end();
                    return;
                }

                // construct base URI for consumer
                String requestUri = routingContext.request().absoluteURI();
                if (!routingContext.request().path().endsWith("/")) {
                    requestUri += "/";
                }
                consumerBaseUri = requestUri + "instances/" + consumerInstanceId;

                // get supported consumer configuration parameters
                Properties config = new Properties();
                addConfigParameter(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        json.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, null), config);
                addConfigParameter(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        json.getString(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, null), config);
                addConfigParameter(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,
                        json.getString(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, null), config);
                addConfigParameter(ConsumerConfig.CLIENT_ID_CONFIG, consumerInstanceId, config);

                // create the consumer
                this.initConsumer(false, config);

                ((Handler<String>) handler).handle(consumerInstanceId);

                log.info("Created consumer {} in group {}", consumerInstanceId, groupId);
                // send consumer instance id(name) and base URI as response
                sendConsumerCreationResponse(routingContext.response(), consumerInstanceId, consumerBaseUri);
                break;
        }
    }

    private void sendConsumerCreationResponse(HttpServerResponse response, String instanceId, String uri) {
        JsonObject jsonResponse = new JsonObject();
        jsonResponse.put("instance_id", instanceId);
        jsonResponse.put("base_uri", uri);

        response.putHeader("Content-length", String.valueOf(jsonResponse.toBuffer().length()))
                .write(jsonResponse.toBuffer())
                .end();
    }

    private void sendConsumerSubscriptionResponse(HttpServerResponse response, ErrorCodeEnum errorCodeEnum) {
        response.setStatusCode(errorCodeEnum.getValue())
                .end();
    }

    private void sendConsumerRecordsResponse(HttpServerResponse response, Buffer buffer) {
        response.putHeader("Content-length", String.valueOf(buffer.length()))
                .write(buffer)
                .end();
    }

    private void sendConsumerDeletionResponse(HttpServerResponse response) {
        response.setStatusCode(204)
                .end();
    }

    private void sendConsumerCommitOffsetResponse(HttpServerResponse response, boolean result) {
        if (result) {
            response.setStatusCode(200);
        } else {
            response.setStatusCode(500);
        }
        response.end();
    }

    private void sendConsumerRecordsFailedResponse(HttpServerResponse response, int errCode, String errMsg) {
        response.setStatusCode(errCode);
        response.setStatusMessage(errMsg);
        response.end();
    }

    private void sendSeekResponse(HttpServerResponse response, ErrorCodeEnum errorCodeEnum) {
        response.setStatusCode(errorCodeEnum.getValue())
                .end();
    }
}
