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
import java.util.List;

import static org.axonframework.serializer.MessageSerializer.serializeMetaData;
import static org.axonframework.serializer.MessageSerializer.serializePayload;
import static org.axonframework.upcasting.UpcastUtils.upcastAndDeserialize;

/**
 * Implementation of the StorageStrategy that stores each event as a separate document. This makes it easier to query
 * the event store for specific events, but does not allow for atomic storage of a single commit.
 * <p/>
 * The structure is as follows:
 * <ul>
 * <li>aggregateIdentifier => [aggregateIdentifier]</li>
 * <li>sequenceNumber => [sequenceNumber of first event]</li>
 * <li>timestamp => [timestamp of first event]</li>
 * <li>type => [aggregate type]</li>
 * <li>serializedPayload => [payload of the event]</li>
 * <li>payloadType => [type of the payload]</li>
 * <li>payloadRevision => [revision of the payload]</li>
 * <li>serializedMetaData => [meta data of the event]</li>
 * <li>eventIdentifier => [identifier of the event]</li>
 * </ul>
 * <p/>
 * <em>Note: the SerializedType of Message Meta Data is not stored. Upon retrieval, it is set to the default value
 * (name = "org.axonframework.messaging.metadata.MetaData", revision = null). See {@link org.axonframework.serializer.SerializedMetaData#isSerializedMetaData(org.axonframework.serializer.SerializedObject)}</em>
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class DocumentPerEventStorageStrategy implements StorageStrategy {

    private static final int ORDER_ASC = 1;
    private static final int ORDER_DESC = -1;

    @Override
    public DBObject[] createDocuments(Serializer eventSerializer, List<DomainEventMessage<?>> messages) {
        DBObject[] dbObjects = new DBObject[messages.size()];
        for (int i = 0, messagesSize = messages.size(); i < messagesSize; i++) {
            DomainEventMessage message = messages.get(i);
            dbObjects[i] = new EventEntry(message, eventSerializer).asDBObject();
        }
        return dbObjects;
    }

    @Override
    public DBCursor findEvents(DBCollection collection, String aggregateIdentifier,
                               long firstSequenceNumber) {
        return collection.find(EventEntry.forAggregate(aggregateIdentifier, firstSequenceNumber))
                         .sort(new BasicDBObject(EventEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_ASC));
    }

    @Override
    public List<DomainEventMessage> extractEventMessages(DBObject entry, Serializer serializer,
                                                         UpcasterChain upcasterChain, boolean skipUnknownTypes) {
        return new EventEntry(entry).getDomainEvents(serializer, upcasterChain, skipUnknownTypes);
    }

    @Override
    public void ensureIndexes(DBCollection eventsCollection, DBCollection snapshotsCollection) {
        // eventsCollection.ensureIndex(new BasicDBObject(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
        eventsCollection.createIndex(new BasicDBObject(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
                                             .append(EventEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                     "uniqueAggregateIndex",
                                     true);

        // eventsCollection.ensureIndex(new BasicDBObject(EventEntry.TIME_STAMP_PROPERTY, 1)
        eventsCollection.createIndex(new BasicDBObject(EventEntry.TIME_STAMP_PROPERTY, 1)
                                             .append(EventEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                     "orderedEventStreamIndex",
                                     false);
        // snapshotsCollection.ensureIndex(new BasicDBObject(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
        snapshotsCollection.createIndex(new BasicDBObject(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, 1)
                                                .append(EventEntry.SEQUENCE_NUMBER_PROPERTY, 1),
                                        "uniqueAggregateIndex",
                                        true);
    }

    @Override
    public DBCursor findEvents(DBCollection collection, MongoCriteria criteria) {
        DBObject filter = criteria == null ? null : criteria.asMongoObject();
        DBObject sort = BasicDBObjectBuilder.start()
                                            .add(EventEntry.TIME_STAMP_PROPERTY, ORDER_ASC)
                                            .add(EventEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_ASC)
                                            .get();
        return collection.find(filter).sort(sort);
    }

    @Override
    public DBCursor findLastSnapshot(DBCollection collection, String aggregateIdentifier) {
        DBObject mongoEntry = BasicDBObjectBuilder
                .start()
                .add(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier)
                .get();
        return collection.find(mongoEntry)
                         .sort(new BasicDBObject(EventEntry.SEQUENCE_NUMBER_PROPERTY, ORDER_DESC))
                         .limit(1);
    }

    /**
     * Data needed by different types of event logs.
     *
     * @author Allard Buijze
     * @author Jettro Coenradie
     * @since 2.0 (in incubator since 0.7)
     */
    private static final class EventEntry implements SerializedDomainEventData {

        /**
         * Property name in mongo for the Aggregate Identifier.
         */
        private static final String AGGREGATE_IDENTIFIER_PROPERTY = "aggregateIdentifier";

        /**
         * Property name in mongo for the Sequence Number.
         */
        private static final String SEQUENCE_NUMBER_PROPERTY = "sequenceNumber";

        /**
         * Property name in mongo for the Time Stamp.
         */
        private static final String TIME_STAMP_PROPERTY = "timeStamp";

        private static final String SERIALIZED_PAYLOAD_PROPERTY = "serializedPayload";
        private static final String PAYLOAD_TYPE_PROPERTY = "payloadType";
        private static final String PAYLOAD_REVISION_PROPERTY = "payloadRevision";
        private static final String META_DATA_PROPERTY = "serializedMetaData";
        private static final String EVENT_IDENTIFIER_PROPERTY = "eventIdentifier";
        /**
         * Charset used for the serialization is usually UTF-8, which is presented by this constant.
         */
        private final String aggregateIdentifier;
        private final long sequenceNumber;
        private final long timeStamp;
        private final Object serializedPayload;
        private final String payloadType;
        private final String payloadRevision;
        private final Object serializedMetaData;
        private final String eventIdentifier;

        /**
         * Constructor used to create a new event entry to store in Mongo.
         *
         * @param event      The actual DomainEvent to store
         * @param serializer Serializer to use for the event to store
         */
        private EventEntry(DomainEventMessage event, Serializer serializer) {
            this.aggregateIdentifier = event.getAggregateIdentifier();
            this.sequenceNumber = event.getSequenceNumber();
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
            this.timeStamp = event.getTimestamp().toEpochMilli();
        }

        /**
         * Creates a new EventEntry based onm data provided by Mongo.
         *
         * @param dbObject Mongo object that contains data to represent an EventEntry
         */
        private EventEntry(DBObject dbObject) {
            this.aggregateIdentifier = (String) dbObject.get(AGGREGATE_IDENTIFIER_PROPERTY);
            this.sequenceNumber = ((Number) dbObject.get(SEQUENCE_NUMBER_PROPERTY)).longValue();
            this.serializedPayload = dbObject.get(SERIALIZED_PAYLOAD_PROPERTY);
            this.timeStamp = (long) dbObject.get(TIME_STAMP_PROPERTY);
            this.payloadType = (String) dbObject.get(PAYLOAD_TYPE_PROPERTY);
            this.payloadRevision = (String) dbObject.get(PAYLOAD_REVISION_PROPERTY);
            this.serializedMetaData = dbObject.get(META_DATA_PROPERTY);
            this.eventIdentifier = (String) dbObject.get(EVENT_IDENTIFIER_PROPERTY);
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
                                       .add(EventEntry.AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier)
                                       .add(EventEntry.SEQUENCE_NUMBER_PROPERTY, new BasicDBObject("$gte",
                                                                                                   firstSequenceNumber))
                                       .get();
        }

        /**
         * Returns the actual DomainEvent from the EventEntry using the provided Serializer.
         *
         * @param eventSerializer  Serializer used to de-serialize the stored DomainEvent
         * @param upcasterChain    Set of upcasters to use when an event needs upcasting before
         *                         de-serialization
         * @param skipUnknownTypes whether to skip unknown event types
         * @return The actual DomainEventMessage instances stored in this entry
         */
        @SuppressWarnings("unchecked")
        public List<DomainEventMessage> getDomainEvents(Serializer eventSerializer,
                                                        UpcasterChain upcasterChain, boolean skipUnknownTypes) {
            return upcastAndDeserialize(this, eventSerializer, upcasterChain, skipUnknownTypes);
        }

        private Class<?> getRepresentationType() {
            Class<?> representationType = String.class;
            if (serializedPayload instanceof DBObject) {
                representationType = DBObject.class;
            }
            return representationType;
        }

        @Override
        public String getEventIdentifier() {
            return eventIdentifier;
        }

        @Override
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }

        /**
         * getter for the sequence number of the event.
         *
         * @return long representing the sequence number of the event
         */
        @Override
        public long getSequenceNumber() {
            return sequenceNumber;
        }

        @Override
        public Instant getTimestamp() {
            return Instant.ofEpochMilli(timeStamp);
        }

        @SuppressWarnings("unchecked")
        @Override
        public SerializedObject getMetaData() {
            return new SerializedMetaData(serializedMetaData, getRepresentationType());
        }

        @SuppressWarnings("unchecked")
        @Override
        public SerializedObject getPayload() {
            return new SimpleSerializedObject(serializedPayload, getRepresentationType(), payloadType, payloadRevision);
        }

        /**
         * Returns the current EventEntry as a mongo DBObject.
         *
         * @return DBObject representing the EventEntry
         */
        public DBObject asDBObject() {
            return BasicDBObjectBuilder.start()
                                       .add(AGGREGATE_IDENTIFIER_PROPERTY, aggregateIdentifier)
                                       .add(SEQUENCE_NUMBER_PROPERTY, sequenceNumber)
                                       .add(SERIALIZED_PAYLOAD_PROPERTY, serializedPayload)
                                       .add(TIME_STAMP_PROPERTY, timeStamp)
                                       .add(PAYLOAD_TYPE_PROPERTY, payloadType)
                                       .add(PAYLOAD_REVISION_PROPERTY, payloadRevision)
                                       .add(META_DATA_PROPERTY, serializedMetaData)
                                       .add(EVENT_IDENTIFIER_PROPERTY, eventIdentifier)
                                       .get();
        }
    }
}
