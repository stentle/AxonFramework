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

import com.mongodb.*;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventstore.mongo.criteria.MongoCriteria;
import org.axonframework.serializer.*;
import org.axonframework.upcasting.UpcasterChain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.axonframework.serializer.MessageSerializer.serializeMetaData;
import static org.axonframework.serializer.MessageSerializer.serializePayload;
import static org.axonframework.upcasting.UpcastUtils.upcastAndDeserialize;

/**
 * Implementation of the StorageStrategy that stores each commit as a single document. The document contains an
 * array
 * containing each separate event.
 * <p/>
 * The structure is as follows:
 * <ul>
 * <li>aggregateIdentifier => [aggregateIdentifier]</li>
 * <li>sequenceNumber => [sequenceNumber of first event]</li>
 * <li>firstSequenceNumber => [sequenceNumber of first event]</li>
 * <li>lastSequenceNumber => [sequenceNumber of last event]</li>
 * <li>timestamp => [timestamp of first event]</li>
 * <li>firstTimeStamp => [timestamp of first event]</li>
 * <li>lastTimeStamp => [timestamp of last event]</li>
 * <li>type => [aggregate type]</li>
 * <li>events => array of:
 * <ul>
 * <li>serializedPayload => [payload of the event]</li>
 * <li>payloadType => [type of the payload]</li>
 * <li>payloadRevision => [revision of the payload]</li>
 * <li>serializedMetaData => [meta data of the event]</li>
 * <li>eventIdentifier => [identifier of the event]</li>
 * <li>sequenceNumber => [sequence number of the event]</li>
 * <li>timestamp => [timestamp of the event]</li>
 * </ul>
 * </li>
 * </ul>
 * <p/>
 * <em>Note: the SerializedType of Message Meta Data is not stored. Upon retrieval, it is set to the default value
 * (name = "org.axonframework.messaging.metadata.MetaData", revision = null). See {@link org.axonframework.serializer.SerializedMetaData#isSerializedMetaData(org.axonframework.serializer.SerializedObject)}</em>
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class DocumentPerCommitStorageStrategy implements StorageStrategy {

    private static final int ORDER_ASC = 1;
    private static final int ORDER_DESC = -1;

    @Override
    public DBObject[] createDocuments(Serializer eventSerializer, List<DomainEventMessage<?>> messages) {
        return new DBObject[]{new CommitEntry(eventSerializer, messages).asDBObject()};
    }

    @Override
    public DBCursor findEvents(DBCollection collection, String aggregateIdentifier,
                               long firstSequenceNumber) {
        return collection.find(CommitEntry.forAggregate(aggregateIdentifier, firstSequenceNumber))
                         .sort(new BasicDBObject(CommitEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_ASC));
    }

    @Override
    public DBCursor findEvents(DBCollection collection, MongoCriteria criteria) {
        DBObject filter = criteria == null ? null : criteria.asMongoObject();
        DBObject sort = BasicDBObjectBuilder.start()
                                            .add(CommitEntry.TIME_STAMP_PROPERTY, ORDER_ASC)
                                            .add(CommitEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_ASC)
                                            .get();
        return collection.find(filter).sort(sort);
    }

    @Override
    public List<DomainEventMessage> extractEventMessages(DBObject entry, Serializer serializer,
                                                         UpcasterChain upcasterChain, boolean skipUnknownTypes) {
        return new CommitEntry(entry).getDomainEvents(serializer, upcasterChain, skipUnknownTypes);
    }

    @Override
    public void ensureIndexes(DBCollection eventsCollection, DBCollection snapshotsCollection) {
        // eventsCollection.ensureIndex(new BasicDBObject(CommitEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
        eventsCollection.createIndex(new BasicDBObject(CommitEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
                                             .append(CommitEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                     "uniqueAggregateIndex",
                                     true);

        // eventsCollection.ensureIndex(new BasicDBObject(CommitEntry.TIME_STAMP_PROPERTY, 1)
        eventsCollection.createIndex(new BasicDBObject(CommitEntry.TIME_STAMP_PROPERTY, 1)
                                             .append(CommitEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                     "orderedEventStreamIndex",
                                     false);
        // snapshotsCollection.ensureIndex(new BasicDBObject(CommitEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
        snapshotsCollection.createIndex(new BasicDBObject(CommitEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
                                                .append(CommitEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                        "uniqueAggregateIndex",
                                        true);
    }

    @Override
    public DBCursor findLastSnapshot(DBCollection collection, String aggregateIdentifier) {
        DBObject mongoEntry = BasicDBObjectBuilder
                .start()
                .add(CommitEntry.AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier)
                .get();
        return collection.find(mongoEntry)
                         .sort(new BasicDBObject(CommitEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_DESC))
                         .limit(1);
    }

    /**
     * Data needed by different types of event logs.
     *
     * @author Allard Buijze
     * @author Jettro Coenradie
     * @since 2.0 (in incubator since 0.7)
     */
    private static final class CommitEntry {

        private static final String AGGREGATE_IDENTIFIER_PROPERTY = "aggregateIdentifier";
        private static final String SEQUENCE_NUMBER_PROPERTY = "sequenceNumber";
        private static final String TIME_STAMP_PROPERTY = "timestamp";
        private static final String FIRST_TIME_STAMP_PROPERTY = "firstTimeStamp";
        private static final String LAST_TIME_STAMP_PROPERTY = "lastTimeStamp";
        private static final String FIRST_SEQUENCE_NUMBER_PROPERTY = "firstSequenceNumber";
        private static final String LAST_SEQUENCE_NUMBER_PROPERTY = "lastSequenceNumber";
        private static final String EVENTS_PROPERTY = "events";

        /**
         * Charset used for the serialization is usually UTF-8, which is presented by this constant.
         */
        private final String aggregateIdentifier;
        private final long firstSequenceNumber;
        private final long lastSequenceNumber;
        private final long firstTimestamp;
        private final long lastTimestamp;
        private final EventEntry[] eventEntries;

        /**
         * Constructor used to create a new event entry to store in Mongo.
         *
         * @param eventSerializer Serializer to use for the event to store
         * @param events          The events contained in this commit
         */
        private CommitEntry(Serializer eventSerializer, List<DomainEventMessage<?>> events) {
            this.aggregateIdentifier = events.get(0).getAggregateIdentifier();
            this.firstSequenceNumber = events.get(0).getSequenceNumber();
            this.firstTimestamp = events.get(0).getTimestamp().toEpochMilli();
            final DomainEventMessage lastEvent = events.get(events.size() - 1);
            this.lastTimestamp = lastEvent.getTimestamp().toEpochMilli();
            this.lastSequenceNumber = lastEvent.getSequenceNumber();
            eventEntries = new EventEntry[events.size()];
            for (int i = 0, eventsLength = events.size(); i < eventsLength; i++) {
                DomainEventMessage event = events.get(i);
                eventEntries[i] = new EventEntry(eventSerializer, event);
            }
        }

        /**
         * Creates a new CommitEntry based onm data provided by Mongo.
         *
         * @param dbObject Mongo object that contains data to represent an CommitEntry
         */
        @SuppressWarnings("unchecked")
        private CommitEntry(DBObject dbObject) {
            this.aggregateIdentifier = (String) dbObject.get(AGGREGATE_IDENTIFIER_PROPERTY);
            this.firstSequenceNumber = ((Number) dbObject.get(FIRST_SEQUENCE_NUMBER_PROPERTY)).longValue();
            this.lastSequenceNumber = ((Number) dbObject.get(LAST_SEQUENCE_NUMBER_PROPERTY)).longValue();
            this.firstTimestamp = (long) dbObject.get(FIRST_TIME_STAMP_PROPERTY);
            this.lastTimestamp = (long) dbObject.get(LAST_TIME_STAMP_PROPERTY);
            List<DBObject> entries = (List<DBObject>) dbObject.get(EVENTS_PROPERTY);
            eventEntries = new EventEntry[entries.size()];
            for (int i = 0, entriesSize = entries.size(); i < entriesSize; i++) {
                eventEntries[i] = new EventEntry(entries.get(i));
            }
        }

        /**
         * Returns the mongo DBObject used to query mongo for events for specified aggregate identifier.
         *
         * @param aggregateIdentifier Identifier of the aggregate to obtain the mongo DBObject for
         * @param firstSequenceNumber number representing the first event to obtain
         * @return Created DBObject based on the provided parameters to be used for a query
         */
        public static DBObject forAggregate(String aggregateIdentifier, long firstSequenceNumber) {
            return BasicDBObjectBuilder.start()
                                       .add(CommitEntry.AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier)
                                       .add(CommitEntry.SEQUENCE_NUMBER_PROPERTY, new BasicDBObject("$gte",
                                                                                                    firstSequenceNumber))
                                       .get();
        }

        /**
         * Returns the actual DomainEvent from the CommitEntry using the provided Serializer.
         *
         * @param eventSerializer Serializer used to de-serialize the stored DomainEvent
         * @param upcasterChain   Set of upcasters to use when an event needs upcasting before
         *                        de-serialization
         * @return The actual DomainEventMessage instances stored in this entry
         */
        @SuppressWarnings("unchecked")
        public List<DomainEventMessage> getDomainEvents(Serializer eventSerializer,
                                                        UpcasterChain upcasterChain, boolean skipUnknownTypes) {
            List<DomainEventMessage> messages = new ArrayList<>();
            for (final EventEntry eventEntry : eventEntries) {
                messages.addAll(upcastAndDeserialize(new DomainEventData(this, eventEntry),
                                                     eventSerializer, upcasterChain, skipUnknownTypes));
            }
            return messages;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }

        /**
         * Returns the current CommitEntry as a mongo DBObject.
         *
         * @return DBObject representing the CommitEntry
         */
        public DBObject asDBObject() {
            final BasicDBList events = new BasicDBList();
            BasicDBObjectBuilder commitBuilder = BasicDBObjectBuilder.start()
                                                                     .add(AGGREGATE_IDENTIFIER_PROPERTY,
                                                                          aggregateIdentifier)
                                                                     .add(SEQUENCE_NUMBER_PROPERTY, firstSequenceNumber)
                                                                     .add(LAST_SEQUENCE_NUMBER_PROPERTY,
                                                                          lastSequenceNumber)
                                                                     .add(FIRST_SEQUENCE_NUMBER_PROPERTY,
                                                                          firstSequenceNumber)
                                                                     .add(TIME_STAMP_PROPERTY, firstTimestamp)
                                                                     .add(FIRST_TIME_STAMP_PROPERTY, firstTimestamp)
                                                                     .add(LAST_TIME_STAMP_PROPERTY, lastTimestamp)
                                                                     .add(EVENTS_PROPERTY, events);

            for (EventEntry eventEntry : eventEntries) {
                events.add(eventEntry.asDBObject());
            }
            return commitBuilder.get();
        }

        private static class DomainEventData implements SerializedDomainEventData {

            private final CommitEntry commitEntry;
            private final EventEntry eventEntry;

            public DomainEventData(CommitEntry commitEntry, EventEntry eventEntry) {
                this.commitEntry = commitEntry;
                this.eventEntry = eventEntry;
            }

            @Override
            public String getEventIdentifier() {
                return eventEntry.getEventIdentifier();
            }

            @Override
            public String getAggregateIdentifier() {
                return commitEntry.getAggregateIdentifier();
            }

            @Override
            public long getSequenceNumber() {
                return eventEntry.getSequenceNumber();
            }

            @Override
            public Instant getTimestamp() {
                return Instant.ofEpochMilli(eventEntry.getTimestamp());
            }

            @Override
            public SerializedObject getMetaData() {
                return eventEntry.getMetaData();
            }

            @Override
            public SerializedObject getPayload() {
                return eventEntry.getPayload();
            }
        }
    }

    /**
     * Represents an entry for a single event inside a commit
     */
    private static final class EventEntry {

        private static final String SERIALIZED_PAYLOAD_PROPERTY = "serializedPayload";
        private static final String PAYLOAD_TYPE_PROPERTY = "payloadType";
        private static final String PAYLOAD_REVISION_PROPERTY = "payloadRevision";
        private static final String META_DATA_PROPERTY = "serializedMetaData";
        private static final String EVENT_IDENTIFIER_PROPERTY = "eventIdentifier";
        private static final String EVENT_SEQUENCE_NUMBER_PROPERTY = "sequenceNumber";
        private static final String EVENT_TIMESTAMP_PROPERTY = "timestamp";

        private final Object serializedPayload;
        private final String payloadType;
        private final String payloadRevision;
        private final Object serializedMetaData;
        private final String eventIdentifier;
        private final long sequenceNumber;
        private final long timestamp;

        private EventEntry(Serializer serializer, DomainEventMessage event) {
            this.eventIdentifier = event.getIdentifier();
            Class<?> serializationTarget = String.class;
            if (serializer.canSerializeTo(DBObject.class)) {
                serializationTarget = DBObject.class;
            }
            SerializedObject serializedPayloadObject = serializePayload(event, serializer, serializationTarget);
            SerializedObject serializedMetaDataObject = serializeMetaData(event, serializer, serializationTarget);

            this.serializedPayload = serializedPayloadObject.getData();
            this.payloadType = serializedPayloadObject.getType().getName();
            this.payloadRevision = serializedPayloadObject.getType().getRevision();
            this.serializedMetaData = serializedMetaDataObject.getData();
            this.sequenceNumber = event.getSequenceNumber();
            this.timestamp = event.getTimestamp().toEpochMilli();
        }

        private EventEntry(DBObject dbObject) {
            this.serializedPayload = dbObject.get(SERIALIZED_PAYLOAD_PROPERTY);
            this.payloadType = (String) dbObject.get(PAYLOAD_TYPE_PROPERTY);
            this.payloadRevision = (String) dbObject.get(PAYLOAD_REVISION_PROPERTY);
            this.serializedMetaData = dbObject.get(META_DATA_PROPERTY);
            this.eventIdentifier = (String) dbObject.get(EVENT_IDENTIFIER_PROPERTY);
            this.sequenceNumber = (Long) dbObject.get(EVENT_SEQUENCE_NUMBER_PROPERTY);
            this.timestamp = (long) dbObject.get(EVENT_TIMESTAMP_PROPERTY);
        }

        public Class<?> getRepresentationType() {
            Class<?> representationType = String.class;
            if (serializedPayload instanceof DBObject) {
                representationType = DBObject.class;
            }
            return representationType;
        }

        public String getEventIdentifier() {
            return eventIdentifier;
        }

        @SuppressWarnings("unchecked")
        public SerializedObject getMetaData() {
            return new SerializedMetaData(serializedMetaData, getRepresentationType());
        }

        @SuppressWarnings("unchecked")
        public SerializedObject getPayload() {
            return new SimpleSerializedObject(serializedPayload, getRepresentationType(), payloadType, payloadRevision);
        }

        public long getSequenceNumber() {
            return sequenceNumber;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public DBObject asDBObject() {
            final BasicDBObjectBuilder entryBuilder = BasicDBObjectBuilder.start();
            return entryBuilder.add(SERIALIZED_PAYLOAD_PROPERTY, serializedPayload)
                               .add(PAYLOAD_TYPE_PROPERTY, payloadType)
                               .add(PAYLOAD_REVISION_PROPERTY, payloadRevision)
                               .add(EVENT_TIMESTAMP_PROPERTY, timestamp)
                               .add(EVENT_SEQUENCE_NUMBER_PROPERTY, sequenceNumber)
                               .add(META_DATA_PROPERTY, serializedMetaData)
                               .add(EVENT_IDENTIFIER_PROPERTY, eventIdentifier)
                               .get();
        }
    }
}
