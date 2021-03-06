/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.hydra.kafka.consumer;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.addthis.basis.util.Parameter;

import com.addthis.hydra.store.db.DBKey;
import com.addthis.hydra.store.db.PageDB;
import com.addthis.hydra.task.source.SimpleMark;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.addthis.hydra.kafka.consumer.KafkaSource.putWhileRunning;
import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.cluster.Broker;
import kafka.common.ErrorMapping;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.message.MessageAndOffset;

class FetchTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FetchTask.class);

    private static final int fetchSize = Parameter.intValue(FetchTask.class + ".fetchSize", 1048576);
    private static final int timeout = Parameter.intValue(FetchTask.class + ".timeout", 10000);
    private static final int offsetAttempts = Parameter.intValue(FetchTask.class + ".offsetAttempts", 3);

    private KafkaSource kafkaSource;
    private final String topic;
    private final PartitionMetadata partition;
    private final DateTime startTime;
    private final LinkedBlockingQueue<MessageWrapper> messageQueue;

    public FetchTask(KafkaSource kafkaSource, String topic, PartitionMetadata partition, DateTime startTime,
            LinkedBlockingQueue<MessageWrapper> messageQueue) {
        this.kafkaSource = kafkaSource;
        this.topic = topic;
        this.partition = partition;
        this.startTime = startTime;
        this.messageQueue = messageQueue;
    }

    @Override
    public void run() {
        consume(this.kafkaSource.running, this.topic, this.partition, this.kafkaSource.markDb, this.startTime,
                this.messageQueue, this.kafkaSource.sourceOffsets);
    }

    private static void consume(AtomicBoolean running, String topic, PartitionMetadata partition,
            PageDB<SimpleMark> markDb, DateTime startTime, LinkedBlockingQueue<MessageWrapper> messageQueue,
            ConcurrentMap<String, Long> sourceOffsets) {
        SimpleConsumer consumer = null;
        try {
            if (!running.get()) {
                return;
            }
            // initialize consumer and offsets
            int partitionId = partition.partitionId();
            Broker broker = partition.leader();
            consumer = new SimpleConsumer(broker.host(), broker.port(), timeout, fetchSize, "kafka-source-consumer");
            String sourceIdentifier = topic + "-" + partitionId;
            final long endOffset = ConsumerUtils.latestOffsetAvailable(consumer, topic, partitionId, offsetAttempts);
            final SimpleMark previousMark = markDb.get(new DBKey(0, sourceIdentifier));
            long startOffset = -1;
            if (previousMark != null) {
                startOffset = previousMark.getIndex();
            } else if (startTime != null) {
                startOffset = ConsumerUtils.getOffsetBefore(consumer, topic, partitionId, startTime.getMillis(), offsetAttempts);
                log.info("no previous mark for host: {}, partition: {}, starting from offset: {}, closest to: {}",
                         consumer.host(), partitionId, startOffset, startTime);
            }
            if (startOffset == -1) {
                log.info("no previous mark for host: {}:{}, topic: {}, partition: {}, no offsets available for " +
                         "startTime: {}, starting from earliest", consumer.host(), consumer.port(),
                         topic, partitionId, startTime);
                startOffset = ConsumerUtils.earliestOffsetAvailable(consumer, topic, partitionId, offsetAttempts);
            } else if (startOffset > endOffset) {
                log.warn("initial offset for: {}:{}, topic: {}, partition: {} is beyond latest, {} > {}; kafka data " +
                         "was either wiped (resetting offsets) or corrupted - skipping " +
                         "ahead to offset {} to recover consuming from latest", consumer.host(), consumer.port(),
                         topic, partitionId, startOffset, endOffset, endOffset);
                startOffset = endOffset;
                // Offsets are normally updated when the bundles are consumed from source.next() - since we wont
                // be fetching any bundles to be consumed, we need to update offset map (that gets persisted to marks)
                // here. The sourceOffsets map probably *should not* be modified anywhere else outside of next().
                sourceOffsets.put(sourceIdentifier, startOffset);
            }
            log.info("started consuming topic: {}, partition: {}, from broker: {}:{}, at offset: {}, until offset: {}",
                    topic, partitionId, consumer.host(), consumer.port(), startOffset, endOffset);
            // fetch from broker, add to queue (decoder threads will process queue in parallel)
            long offset = startOffset;
            while (running.get() && (offset < endOffset)) {
                FetchRequest request = new FetchRequestBuilder().addFetch(topic, partitionId, offset, fetchSize).build();
                FetchResponse response = consumer.fetch(request);
                short errorCode = response.errorCode(topic, partitionId);
                ByteBufferMessageSet messageSet = null;
                if (errorCode == ErrorMapping.NoError()) {
                    messageSet = response.messageSet(topic, partitionId);
                // clamp out-of-range offsets
                } else if(errorCode == ErrorMapping.OffsetOutOfRangeCode()) {
                    long earliestOffset = ConsumerUtils.earliestOffsetAvailable(consumer, topic, partitionId, offsetAttempts);
                    if (offset < earliestOffset) {
                        log.error("forwarding invalid early offset: {}:{}, topic: {}, partition: {}, " +
                                  "from offset: {}, to: {}", consumer.host(), consumer.port(), topic, partition,
                                  offset, earliestOffset);
                        offset = earliestOffset;
                        // offset exceptions should only be thrown when offset < earliest, so this case shouldnt ever happen
                    } else {
                        long latestOffset = ConsumerUtils.latestOffsetAvailable(consumer, topic, partitionId, offsetAttempts);
                        log.error("rewinding invalid future offset: {}:{}, topic: {}, partition: {}, " +
                                  "from offset: {}, to: {}", consumer.host(), consumer.port(), topic, partition,
                                  offset, latestOffset);
                        offset = latestOffset;
                    }
                // partition was moved/rebalanced in background, so this consumer's host no longer has data
                } else if(errorCode == ErrorMapping.NotLeaderForPartitionCode() || errorCode == ErrorMapping.UnknownTopicOrPartitionCode()) {
                    Broker newLeader = ConsumerUtils.getNewLeader(consumer, topic, partitionId);
                    log.warn("current partition was moved off of current host while consuming, reconnecting to new " +
                             "leader; topic: {}-{}, old: {}:{}, new leader: {}:{}",
                            topic, partitionId, consumer.host(), consumer.port(), newLeader.host(), newLeader.port());
                    consumer.close();
                    consumer = new SimpleConsumer(newLeader.host(), newLeader.port(), timeout, fetchSize,
                                                  "kafka-source-consumer");
                // any other error
                } else if(errorCode != ErrorMapping.NoError()) {
                    log.error("failed to consume from broker: {}:{}, topic: {}, partition: {}, offset: {}",
                              consumer.host(), consumer.port(), topic, partitionId, offset);
                    throw new RuntimeException(ErrorMapping.exceptionFor(errorCode));
                }

                if (messageSet != null) {
                    for (MessageAndOffset messageAndOffset : messageSet) {
                        // Fetch requests sometimes return bundles that come before the requested offset (presumably
                        // due to batching).  Ignore those early bundles until reaching the desired offset.
                        if (messageAndOffset.offset() >= startOffset) {
                            putWhileRunning(messageQueue, new MessageWrapper(messageAndOffset, consumer.host(), topic,
                                    partitionId, sourceIdentifier), running);
                        }
                        offset = messageAndOffset.nextOffset();
                    }
                }
            }
            log.info("finished consuming topic: {}, partition: {}, from broker: {}:{}, at offset: {}",
                    topic, partitionId, consumer.host(), consumer.port(), offset);
        } catch (BenignKafkaException ignored) {
        } catch (Exception e) {
            log.error("kafka consume thread failed: ", e);
        } finally {
            putWhileRunning(messageQueue, MessageWrapper.messageQueueEndMarker, running);
            if(consumer != null) {
                consumer.close();
            }
        }
    }
}
