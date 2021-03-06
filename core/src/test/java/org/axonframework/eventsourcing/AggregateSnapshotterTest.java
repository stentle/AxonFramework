/*
 * Copyright (c) 2010-2012. Axon Framework
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

package org.axonframework.eventsourcing;

import org.axonframework.common.DirectExecutor;
import org.axonframework.domain.StubAggregate;
import org.axonframework.eventstore.SnapshotEventStore;
import org.axonframework.messaging.metadata.MetaData;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
public class AggregateSnapshotterTest {

    private AggregateSnapshotter testSubject;
    private AggregateFactory mockAggregateFactory;

    @Before
    @SuppressWarnings({"unchecked"})
    public void setUp() throws Exception {
        SnapshotEventStore mockEventStore = mock(SnapshotEventStore.class);
        mockAggregateFactory = mock(AggregateFactory.class);
        when(mockAggregateFactory.getAggregateType()).thenReturn(Object.class);
        testSubject = new AggregateSnapshotter();
        testSubject.setAggregateFactories(Arrays.<AggregateFactory<?>>asList(mockAggregateFactory));
        testSubject.setEventStore(mockEventStore);
        testSubject.setExecutor(DirectExecutor.INSTANCE);
    }

    @Test
    @SuppressWarnings({"unchecked"})
    public void testCreateSnapshot() {
        String aggregateIdentifier = UUID.randomUUID().toString();
        DomainEventMessage firstEvent = new GenericDomainEventMessage<>(aggregateIdentifier, (long) 0,
                                                                        "Mock contents", MetaData.emptyInstance());
        SimpleDomainEventStream eventStream = new SimpleDomainEventStream(firstEvent);
        Object aggregate = new Object();
        when(mockAggregateFactory.createAggregate(aggregateIdentifier, firstEvent)).thenReturn(aggregate);

        DomainEventMessage snapshot = testSubject.createSnapshot(Object.class,
                                                                 aggregateIdentifier, eventStream);

        verify(mockAggregateFactory).createAggregate(aggregateIdentifier, firstEvent);
        assertSame(aggregate, snapshot.getPayload());
    }

    @Test
    @SuppressWarnings({"unchecked"})
    public void testCreateSnapshot_FirstEventLoadedIsSnapshotEvent() {
        UUID aggregateIdentifier = UUID.randomUUID();
        StubAggregate aggregate = new StubAggregate(aggregateIdentifier);

        DomainEventMessage<StubAggregate> first = new GenericDomainEventMessage<>(
                aggregate.getIdentifier(), 0, aggregate);
        DomainEventMessage second = new GenericDomainEventMessage<>(
                aggregateIdentifier.toString(), 0, "Mock contents", MetaData.emptyInstance());
        SimpleDomainEventStream eventStream = new SimpleDomainEventStream(first, second);

        when(mockAggregateFactory.createAggregate(any(), any(DomainEventMessage.class)))
                .thenAnswer(invocation -> ((DomainEventMessage) invocation.getArguments()[1]).getPayload());

        DomainEventMessage snapshot = testSubject.createSnapshot(Object.class,
                                                                 aggregateIdentifier.toString(), eventStream);
        assertSame("Snapshotter did not recognize the aggregate snapshot", aggregate, snapshot.getPayload());

        verify(mockAggregateFactory).createAggregate(any(), any(DomainEventMessage.class));
    }
}
