package pl.allegro.tech.hermes.tracker.mongo.consumers;

import com.codahale.metrics.MetricRegistry;
import com.github.fakemongo.Fongo;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import pl.allegro.tech.hermes.api.SentMessageTrace;
import pl.allegro.tech.hermes.api.SentMessageTraceStatus;
import pl.allegro.tech.hermes.tracker.consumers.AbstractLogRepositoryTest;
import pl.allegro.tech.hermes.tracker.consumers.LogRepository;
import pl.allegro.tech.hermes.tracker.mongo.LogSchemaAware;
import pl.allegro.tech.hermes.metrics.PathsCompiler;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.awaitility.Duration.ONE_SECOND;
import static org.assertj.core.api.Assertions.assertThat;
import static pl.allegro.tech.hermes.api.SentMessageTrace.Builder.sentMessageTrace;

public class MongoLogRepositoryTest extends AbstractLogRepositoryTest implements LogSchemaAware {

    private final DB database = new Fongo("trace").getDB("test");

    @Override
    protected LogRepository createLogRepository() {
        return new MongoLogRepository(database, 1000, 100, "cluster", "host", new MetricRegistry(), new PathsCompiler("localhost"));
    }

    @Override
    protected void awaitUntilMessageIsPersisted(String topic, String subscription, String messageId, SentMessageTraceStatus status, String... extraRequestHeadersKeywords) {
        await().atMost(ONE_SECOND).until(() -> {
            List<SentMessageTrace> messages = getLastUndeliveredMessages(topic, subscription, status);
            assertThat(messages).hasSize(1).extracting(MESSAGE_ID).containsExactly(messageId);
            assertThat(messages.get(0).getExtraRequestHeaders()).contains(Arrays.asList(extraRequestHeadersKeywords));
        });
    }

    @Override
    protected void awaitUntilBatchMessageIsPersisted(String topic, String subscription, String messageId, String batchId, SentMessageTraceStatus status, String... extraRequestHeadersKeywords) throws Exception {
        await().atMost(ONE_SECOND).until(() -> {
            List<SentMessageTrace> messages = getLastUndeliveredMessages(topic, subscription, status);
            assertThat(messages).hasSize(1).extracting(MESSAGE_ID).containsExactly(messageId);
            assertThat(messages).hasSize(1).extracting(BATCH_ID).containsExactly(batchId);
            assertThat(messages.get(0).getExtraRequestHeaders()).contains(Arrays.asList(extraRequestHeadersKeywords));
        });
    }

    private List<SentMessageTrace> getLastUndeliveredMessages(String topicName, String subscriptionName, SentMessageTraceStatus status) {
        try (
                DBCursor cursor = database.getCollection(COLLECTION_SENT_NAME)
                        .find(new BasicDBObject(TOPIC_NAME, topicName)
                                .append(LogSchemaAware.SUBSCRIPTION, subscriptionName).append(STATUS, status.toString()))
                        .sort(new BasicDBObject(TIMESTAMP, -1)).limit(1)
        ) {
            return StreamSupport.stream(cursor.spliterator(), false)
                    .map(this::convert)
                    .collect(Collectors.toList());
        }
    }

    private SentMessageTrace convert(DBObject rawObject) {
        BasicDBObject object = (BasicDBObject) rawObject;
        return sentMessageTrace(
                        object.getString(MESSAGE_ID),
                        object.getString(BATCH_ID),
                        SentMessageTraceStatus.valueOf(object.getString(STATUS))
                )
                .withTimestamp(object.getLong(TIMESTAMP))
                .withSubscription(object.getString(LogSchemaAware.SUBSCRIPTION))
                .withTopicName(object.getString(TOPIC_NAME))
                .withReason(object.getString(REASON))
                .withPartition(object.getInt(PARTITION, -1))
                .withOffset(object.getLong(OFFSET, -1))
                .withCluster(object.getString(CLUSTER, ""))
                .withExtraRequestHeaders(object.getString(EXTRA_REQUEST_HEADERS))
                .build();
    }
}
