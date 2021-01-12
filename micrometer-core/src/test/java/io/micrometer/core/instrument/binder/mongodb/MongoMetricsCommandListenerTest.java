/**
 * Copyright 2019 VMware, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.binder.mongodb;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.ServerAddress;
import com.mongodb.event.ClusterListenerAdapter;
import com.mongodb.event.ClusterOpeningEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MongoMetricsCommandListener}.
 *
 * @author Christophe Bornet
 */
class MongoMetricsCommandListenerTest extends AbstractMongoDbTest {

    private MeterRegistry registry;
    private AtomicReference<String> clusterId;
    private MongoClient mongo;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        clusterId = new AtomicReference<>();
        MongoClientOptions options = MongoClientOptions.builder()
                .addCommandListener(new MongoMetricsCommandListener(registry))
                .addClusterListener(new ClusterListenerAdapter() {
                    @Override
                    public void clusterOpening(ClusterOpeningEvent event) {
                        clusterId.set(event.getClusterId().getValue());
                    }
                }).build();
        mongo = new MongoClient(new ServerAddress(HOST, port), options);
    }

    @Test
    void shouldCreateSuccessCommandMetric() {
        mongo.getDatabase("test")
                .getCollection("testCol")
                .insertOne(new Document("testDoc", new Date()));

        Tags tags = Tags.of(
                "cluster.id", clusterId.get(),
                "server.address", String.format("%s:%s", HOST, port),
                "command", "insert",
                "status", "SUCCESS"
        );
        assertThat(registry.get("mongodb.driver.commands").tags(tags).timer().count()).isEqualTo(1);
    }

    @Test
    void shouldCreateFailedCommandMetric() {
        mongo.getDatabase("test")
                .getCollection("testCol")
                .dropIndex("nonExistentIndex");

        Tags tags = Tags.of(
                "cluster.id", clusterId.get(),
                "server.address", String.format("%s:%s", HOST, port),
                "command", "dropIndexes",
                "status", "FAILED"
        );
        assertThat(registry.get("mongodb.driver.commands").tags(tags).timer().count()).isEqualTo(1);
    }

    @Test
    void shouldSupportConcurrentCommands() throws InterruptedException {
        boolean concurrent = true;

        for (int i = 0; i < 100; i++) {
            Thread insertThread = new Thread(() -> mongo.getDatabase("test")
                    .getCollection("testCol")
                    .insertOne(new Document("testDoc", new Date())));

            Thread updateThread = new Thread(() -> mongo.getDatabase("test")
                    .getCollection("testCol")
                    .updateOne(new Document("nonExistentField", "foobar"),
                            new Document("$set", new Document("nonExistentField", "foobaz"))));

            Thread deleteThread = new Thread(() -> mongo.getDatabase("test")
                    .getCollection("testCol")
                    .deleteOne(new Document("nonExistentField", "foobar")));

            Thread dropIndexesThread = new Thread(() -> mongo.getDatabase("test")
                    .getCollection("testCol")
                    .dropIndex("nonExistentIndex"));

            if (concurrent) {
                insertThread.start();
                updateThread.start();
                deleteThread.start();
                dropIndexesThread.start();

                insertThread.join();
                updateThread.join();
                deleteThread.join();
                dropIndexesThread.join();
            } else {
                insertThread.start();
                insertThread.join();

                updateThread.start();
                updateThread.join();

                deleteThread.start();
                deleteThread.join();

                dropIndexesThread.start();
                dropIndexesThread.join();
            }

            int iterationsCompleted = i + 1;
            long insertCommandCount = getCommandTimerStopCountSafe("insert");
            long updateCommandCount = getCommandTimerStopCountSafe("update");
            long deleteCommandCount = getCommandTimerStopCountSafe("delete");
            long dropIndexesCommandCount = getCommandTimerStopCountSafe("dropIndexes");

            String message = String.format(
                    "iterationsCompleted=%d, insertCommandCount=%d, updateCommandCount=%d, deleteCommandCount=%d, dropIndexesCommandCount=%d",
                    iterationsCompleted, insertCommandCount, updateCommandCount, deleteCommandCount, dropIndexesCommandCount);
            System.out.println(message);

            assertThat(insertCommandCount).isEqualTo(iterationsCompleted);
            assertThat(updateCommandCount).isEqualTo(iterationsCompleted);
            assertThat(deleteCommandCount).isEqualTo(iterationsCompleted);
            assertThat(dropIndexesCommandCount).isEqualTo(iterationsCompleted);
        }
    }

    private long getCommandTimerStopCountSafe(String command) {
        try {
            return registry.get("mongodb.driver.commands").tags(Tags.of("command", command)).timer().count();
        } catch (MeterNotFoundException e) {
            return 0L;
        }
    }

    @AfterEach
    void destroy() {
        if (mongo != null) {
            mongo.close();
        }
    }

}
