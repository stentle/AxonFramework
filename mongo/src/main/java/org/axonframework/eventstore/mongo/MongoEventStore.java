/*
 * Copyright (c) 2010-2014. Axon Framework
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

package org.axonframework.eventstore.mongo;

import com.mongodb.Bytes;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoException;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.DomainEventStream;
import org.axonframework.eventstore.EventStreamNotFoundException;
import org.axonframework.eventstore.EventVisitor;
import org.axonframework.eventstore.SnapshotEventStore;
import org.axonframework.eventstore.management.Criteria;
import org.axonframework.eventstore.management.EventStoreManagement;
import org.axonframework.eventstore.mongo.criteria.MongoCriteria;
import org.axonframework.eventstore.mongo.criteria.MongoCriteriaBuilder;
import org.axonframework.commandhandling.model.ConcurrencyException;
import org.axonframework.serializer.Serializer;
import org.axonframework.serializer.xml.XStreamSerializer;
import org.axonframework.upcasting.SimpleUpcasterChain;
import org.axonframework.upcasting.UpcasterAware;
import org.axonframework.upcasting.UpcasterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.Closeable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * <p>Implementation of the <code>EventStore</code> based on a MongoDB instance or replica set. Sharding and pairing
 * are not explicitly supported.</p> <p>This event store implementation needs a serializer as well as a {@link
 * MongoTemplate} to interact with the mongo database.</p> <p><strong>Warning:</strong> This implementation is
 * still in progress and may be subject to alterations. The implementation works, but has not been optimized to fully
 * leverage MongoDB's features, yet.</p>
 *
 * @author Jettro Coenradie
 * @since 2.0 (in incubator since 0.7)
 */
public class MongoEventStore implements SnapshotEventStore, EventStoreManagement, UpcasterAware {

    private static final Logger logger = LoggerFactory.getLogger(MongoEventStore.class);

    private final MongoTemplate mongoTemplate;

    private final Serializer eventSerializer;
    private final StorageStrategy storageStrategy;
    private UpcasterChain upcasterChain = SimpleUpcasterChain.EMPTY;

    /**
     * Constructor that accepts a Serializer and the MongoTemplate. A Document-Per-Event storage strategy is used,
     * causing each event to be stored in a separate Mongo Document.
     * <p/>
     * <em>Note: the SerializedType of Message Meta Data is not stored. Upon retrieval, it is set to the default value
     * (name = "org.axonframework.messaging.metadata.MetaData", revision = null). See {@link org.axonframework.serializer.SerializedMetaData#isSerializedMetaData(org.axonframework.serializer.SerializedObject)}</em>
     *
     * @param eventSerializer Your own Serializer
     * @param mongo           Mongo instance to obtain the database and the collections.
     */
    public MongoEventStore(Serializer eventSerializer, MongoTemplate mongo) {
        this(mongo, eventSerializer, new DocumentPerEventStorageStrategy());
    }

    /**
     * Constructor that uses the default Serializer. A Document-Per-Event storage strategy is used, causing each event
     * to be stored in a separate Mongo Document.
     *
     * @param mongo MongoTemplate instance to obtain the database and the collections.
     */
    public MongoEventStore(MongoTemplate mongo) {
        this(new XStreamSerializer(), mongo);
    }

    /**
     * Constructor that accepts a MongoTemplate and a custom StorageStrategy.
     *
     * @param mongoTemplate   The template giving access to the required collections
     * @param storageStrategy The strategy for storing and retrieving events from the collections
     */
    public MongoEventStore(MongoTemplate mongoTemplate, StorageStrategy storageStrategy) {
        this(mongoTemplate, new XStreamSerializer(), storageStrategy);
    }

    /**
     * Initialize the mongo event store with given <code>mongoTemplate</code>, <code>eventSerializer</code> and
     * <code>storageStrategy</code>.
     *
     * @param mongoTemplate   The template giving access to the required collections
     * @param eventSerializer The serializer to serialize events with
     * @param storageStrategy The strategy for storing and retrieving events from the collections
     */
    public MongoEventStore(MongoTemplate mongoTemplate, Serializer eventSerializer, StorageStrategy storageStrategy) {
        this.eventSerializer = eventSerializer;
        this.mongoTemplate = mongoTemplate;
        this.storageStrategy = storageStrategy;
    }

    /**
     * Make sure an index is created on the collection that stores domain events.
     */
    @PostConstruct
    public void ensureIndexes() {
        storageStrategy.ensureIndexes(mongoTemplate.domainEventCollection(), mongoTemplate.snapshotEventCollection());
    }


    @Override
    public void appendEvents(List<DomainEventMessage<?>> events) {
        if (events == null || events.isEmpty()) {
            return;
        }


        try {
            mongoTemplate.domainEventCollection().insert(storageStrategy.createDocuments(eventSerializer, events));
        // } catch (MongoException.DuplicateKey e) {
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyException("Trying to insert an Event for an aggregate with a sequence "
                                                   + "number that is already present in the Event Store", e);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{} events appended", events.size());
        }
    }

    @Override
    public DomainEventStream readEvents(String identifier) {
        long snapshotSequenceNumber = -1;
        List<DomainEventMessage> lastSnapshotCommit = loadLastSnapshotEvent(identifier);
        if (lastSnapshotCommit != null && !lastSnapshotCommit.isEmpty()) {
            snapshotSequenceNumber = lastSnapshotCommit.get(0).getSequenceNumber();
        }
        final DBCursor dbCursor = storageStrategy.findEvents(mongoTemplate.domainEventCollection(),
                                                             identifier,
                                                             snapshotSequenceNumber + 1);

        DomainEventStream stream = new CursorBackedDomainEventStream(dbCursor, lastSnapshotCommit, false);
        if (!stream.hasNext()) {
            throw new EventStreamNotFoundException(identifier);
        }
        return stream;
    }

    @Override
    public DomainEventStream readEvents(String identifier, long firstSequenceNumber) {
        return readEvents(identifier, firstSequenceNumber, Long.MAX_VALUE);
    }

    @Override
    public DomainEventStream readEvents(String identifier, long firstSequenceNumber,
                                        long lastSequenceNumber) {
        final DBCursor dbCursor = storageStrategy.findEvents(mongoTemplate.domainEventCollection(),
                                                             identifier,
                                                             firstSequenceNumber);

        DomainEventStream stream = new CursorBackedDomainEventStream(dbCursor, null, lastSequenceNumber,
                                                                     false);
        if (!stream.hasNext()) {
            throw new EventStreamNotFoundException(identifier);
        }
        return stream;
    }

    @Override
    public void appendSnapshotEvent(DomainEventMessage snapshotEvent) {
        final DBObject dbObject = storageStrategy.createDocuments(eventSerializer,
                                                                  Collections.singletonList(snapshotEvent))[0];
        try {
            mongoTemplate.snapshotEventCollection().insert(dbObject);
        // } catch (MongoException.DuplicateKey e) {
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyException("Trying to insert a SnapshotEvent with aggregate identifier and sequence "
                                                   + "number that is already present in the Event Store", e);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("snapshot event of type {} appended.");
        }
    }

    @Override
    public void visitEvents(EventVisitor visitor) {
        visitEvents(null, visitor);
    }

    @Override
    public void visitEvents(Criteria criteria, EventVisitor visitor) {
        DBCursor cursor = storageStrategy.findEvents(mongoTemplate.domainEventCollection(),
                                                     (MongoCriteria) criteria);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);
        try (CursorBackedDomainEventStream events = new CursorBackedDomainEventStream(cursor, null, true)) {
            while (events.hasNext()) {
                visitor.doWithEvent(events.next());
            }
        }
    }

    @Override
    public MongoCriteriaBuilder newCriteriaBuilder() {
        return new MongoCriteriaBuilder();
    }

    private List<DomainEventMessage> loadLastSnapshotEvent(String identifier) {
        DBCursor dbCursor = storageStrategy.findLastSnapshot(mongoTemplate.snapshotEventCollection(), identifier);
        if (!dbCursor.hasNext()) {
            return null;
        }
        DBObject first = dbCursor.next();

        return storageStrategy.extractEventMessages(first, eventSerializer, upcasterChain, false);
    }

    @Override
    public void setUpcasterChain(UpcasterChain upcasterChain) {
        this.upcasterChain = upcasterChain;
    }

    private class CursorBackedDomainEventStream implements DomainEventStream, Closeable {

        private final DBCursor dbCursor;
        private final long lastSequenceNumber;
        private final boolean skipUnknownTypes;
        private Iterator<DomainEventMessage> messagesToReturn = Collections.<DomainEventMessage>emptyList().iterator();
        private DomainEventMessage next;

        /**
         * Initializes the DomainEventStream, streaming events obtained from the given <code>dbCursor</code> and
         * optionally the given <code>lastSnapshotEvent</code>.
         *
         * @param dbCursor                  The cursor providing access to the query results in the Mongo instance
         * @param lastSnapshotCommit        The last snapshot event read, or <code>null</code> if no snapshot is
         *                                  available
         * @param skipUnknownTypes          Whether or not the stream should ignore events that cannot be deserialized
         */
        public CursorBackedDomainEventStream(DBCursor dbCursor, List<DomainEventMessage> lastSnapshotCommit,
                                             boolean skipUnknownTypes) {
            this(dbCursor, lastSnapshotCommit, Long.MAX_VALUE, skipUnknownTypes);
        }

        /**
         * Initializes the DomainEventStream, streaming events obtained from the given <code>dbCursor</code> and
         * optionally the given <code>lastSnapshotEvent</code>, which stops streaming once an event with a sequence
         * number higher given than <code>lastSequenceNumber</code>.
         *
         * @param dbCursor                  The cursor providing access to the query results in the Mongo instance
         * @param lastSnapshotCommit        The last snapshot event read, or <code>null</code> if no snapshot is
         *                                  available
         * @param lastSequenceNumber        The highest sequence number this stream may return before indicating
         *                                  end-of-stream
         * @param skipUnknownTypes          Whether or not the stream should ignore events that cannot be deserialized
         */
        public CursorBackedDomainEventStream(DBCursor dbCursor, List<DomainEventMessage> lastSnapshotCommit,
                                             long lastSequenceNumber,
                                             boolean skipUnknownTypes) {
            this.dbCursor = dbCursor;
            this.lastSequenceNumber = lastSequenceNumber;
            this.skipUnknownTypes = skipUnknownTypes;
            if (lastSnapshotCommit != null) {
                messagesToReturn = lastSnapshotCommit.iterator();
            }
            initializeNextItem();
        }

        @Override
        public boolean hasNext() {
            return next != null && next.getSequenceNumber() <= lastSequenceNumber;
        }

        @Override
        public DomainEventMessage next() {
            DomainEventMessage itemToReturn = next;
            initializeNextItem();
            return itemToReturn;
        }

        @Override
        public DomainEventMessage peek() {
            return next;
        }

        /**
         * Ensures that the <code>next</code> points to the correct item, possibly reading from the dbCursor.
         */
        private void initializeNextItem() {
            while (!messagesToReturn.hasNext() && dbCursor.hasNext()) {
                messagesToReturn = storageStrategy.extractEventMessages(dbCursor.next(),
                                                                        eventSerializer, upcasterChain,
                                                                        skipUnknownTypes).iterator();
            }
            next = messagesToReturn.hasNext() ? messagesToReturn.next() : null;
        }

        @Override
        public void close() {
            dbCursor.close();
        }
    }
}
