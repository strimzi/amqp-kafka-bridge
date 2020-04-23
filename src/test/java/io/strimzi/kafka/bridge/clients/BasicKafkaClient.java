/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.bridge.clients;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.strimzi.kafka.bridge.utils.KafkaJsonSerializer;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntPredicate;

public class BasicKafkaClient {

    private final String bootstrapServer;

    public BasicKafkaClient(String bootstrapServer) {
        this.bootstrapServer = bootstrapServer;
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendMessagesPlain(long timeoutMs, String topicName, int messageCount, String message, int partition,
                                 boolean withNullKeyRecord) {
        CompletableFuture<Integer> resultPromise = new CompletableFuture<>();
        IntPredicate msgCntPredicate = x -> x == messageCount;

        Properties properties = new Properties();

        properties.setProperty("key.serializer", StringSerializer.class.getName());
        properties.setProperty("value.serializer", StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServer);
        properties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, "producer-sender-plain-");
        properties.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name);

        try (Producer plainProducer = new Producer(properties, resultPromise, msgCntPredicate, topicName, message, partition,
            withNullKeyRecord)) {

            plainProducer.getVertx().deployVerticle(plainProducer);

            return plainProducer.getResultPromise().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendMessagesPlain(String topicName, int messageCount) {
        return sendMessagesPlain(Duration.ofMinutes(2).toMillis(), topicName, messageCount, "\"Hello\" : \"World\"",
            0, false);
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendJsonMessagesPlain(long timeoutMs, String topicName, int messageCount, String message, int partition,
                                     boolean withNullKeyRecord) {
        CompletableFuture<Integer> resultPromise = new CompletableFuture<>();
        IntPredicate msgCntPredicate = x -> x == messageCount;

        Properties properties = new Properties();

        properties.setProperty("key.serializer", KafkaJsonSerializer.class.getName());
        properties.setProperty("value.serializer", KafkaJsonSerializer.class.getName());
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServer);
        properties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, "producer-sender-plain-" + new Random().nextInt(Integer.MAX_VALUE));
        properties.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name);
        try (Producer plainProducer = new Producer(properties, resultPromise, msgCntPredicate, topicName, message,
            partition, withNullKeyRecord)) {

            plainProducer.getVertx().deployVerticle(plainProducer);

            return plainProducer.getResultPromise().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendJsonMessagesPlain(String topicName, int messageCount, String message, int partition, boolean withNullKeyRecord) {
        return sendJsonMessagesPlain(Duration.ofMinutes(2).toMillis(), topicName, messageCount, message, partition,
            withNullKeyRecord);
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendJsonMessagesPlain(String topicName, int messageCount, String message, int partition) {
        return sendJsonMessagesPlain(Duration.ofMinutes(2).toMillis(), topicName, messageCount, message, partition,
            false);
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendJsonMessagesPlain(String topicName, int messageCount, String message) {
        return sendJsonMessagesPlain(Duration.ofMinutes(2).toMillis(), topicName, messageCount, message, 0,
            false);
    }

    /**
     * Send messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return sent message count
     */
    public int sendJsonMessagesPlain(String topicName, int messageCount) {
        return sendJsonMessagesPlain(Duration.ofMinutes(2).toMillis(), topicName, messageCount,
            "{\"Hello\" : \"World\"}", 0, false);
    }

    /**
     * Receive messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return received message count
     */
    @SuppressWarnings("Regexp") // for the `.toLowerCase()` because kafka needs this property as lower-case
    @SuppressFBWarnings("DM_CONVERT_CASE")
    public int receiveMessagesPlain(long timeoutMs, String topicName, int messageCount) {

        CompletableFuture<Integer> resultPromise = new CompletableFuture<>();
        IntPredicate msgCntPredicate = x -> x == messageCount;

        Properties properties = new Properties();

        properties.setProperty("key.serializer", StringDeserializer.class.getName());
        properties.setProperty("value.serializer", StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServer);
        properties.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, "consumer-sender-plain-");
        properties.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.PLAINTEXT.name);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OffsetResetStrategy.EARLIEST.name().toLowerCase());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "consumer-group" + new Random().nextInt(Integer.MAX_VALUE));

        try (Consumer plainConsumer = new Consumer(properties, resultPromise, msgCntPredicate, topicName)) {

            plainConsumer.getVertx().deployVerticle(plainConsumer);

            return plainConsumer.getResultPromise().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Receive messages to external entrypoint of the cluster with PLAINTEXT security protocol setting
     * @return received message count
     */
    public int receiveMessagesPlain(String topicName, int messageCount) {
        return receiveMessagesPlain(Duration.ofMinutes(2).toMillis(), topicName, messageCount);
    }
}