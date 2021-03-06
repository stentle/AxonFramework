/*
 * Copyright (c) 2010-2015. Axon Framework
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

package org.axonframework.commandhandling.model;

import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.annotation.CommandHandler;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.annotation.EventHandler;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventsourcing.SimpleDomainEventStream;
import org.axonframework.eventsourcing.annotation.AggregateIdentifier;
import org.axonframework.eventstore.EventStore;
import org.axonframework.messaging.GenericMessage;
import org.axonframework.messaging.metadata.MetaData;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class EventSourcingRepositoryTest {

    private EventStore eventStore;
    private Repository<StubAggregate> repository;
    private EventBus eventBus;

    @Before
    public void setUp() throws Exception {
        eventStore = Mockito.mock(EventStore.class);
        eventBus = Mockito.mock(EventBus.class);
        Mockito.when(eventStore.readEvents(Matchers.anyString()))
                .thenAnswer(invocationOnMock ->
                                    new SimpleDomainEventStream(new GenericDomainEventMessage<Object>(invocationOnMock.getArgumentAt(0, String.class), 1, "Test1"),
                                                                new GenericDomainEventMessage<Object>(invocationOnMock.getArgumentAt(0, String.class), 2, "Test2"),
                                                                new GenericDomainEventMessage<Object>(invocationOnMock.getArgumentAt(0, String.class), 3, "Test3")));
        repository = new EventSourcingRepository<>(StubAggregate.class, eventStore, eventBus);
        DefaultUnitOfWork.startAndGet(new GenericCommandMessage<Object>("Stub"));
    }

    @After
    public void tearDown() throws Exception {
        while (CurrentUnitOfWork.isStarted()) {
            CurrentUnitOfWork.get().rollback();
        }
    }

    @Test
    public void testLoadAggregate() throws Exception {
        Aggregate<StubAggregate> actual = repository.load("test");

        assertEquals(3L, (long) actual.version());
    }

    @Test
    public void testLoadAggregateAndApplyEvent() throws Exception {
        Aggregate<StubAggregate> actual = repository.load("test");

        assertEquals(3L, (long) actual.version());
        verify(eventBus, never()).publish(Matchers.<EventMessage<?>[]>anyVararg());
        actual.execute(StubAggregate::changeState);
        assertEquals(6L, (long) actual.version());
        List<DomainEventMessage<String>> domainEventMessages = actual.invoke(StubAggregate::getMessages);
        assertEquals(6, domainEventMessages.size());
        DomainEventMessage<String> message = domainEventMessages.get(5);
        assertEquals("Got messages: 5", message.getPayload());

        for (int i = 0; i < 3; i++) {
            verify(eventStore, never()).appendEvents(domainEventMessages.get(i));
            verify(eventBus, never()).publish(domainEventMessages.get(i));
        }
        for (int i = 3; i < 6; i++) {
            verify(eventStore).appendEvents(domainEventMessages.get(i));
            verify(eventBus).publish(domainEventMessages.get(i));
        }
    }

    @Test
    public void testCreateNewAggregate() throws Exception {
        Aggregate<StubAggregate> instance = repository.newInstance(() -> new StubAggregate("id", "Hello"));
        List<DomainEventMessage<String>> messages = instance.invoke(StubAggregate::getMessages);
        assertEquals(2, messages.size());
        assertEquals("Hello", messages.get(0).getPayload());
        assertEquals("Got messages: 1", messages.get(1).getPayload());
        verify(eventStore).appendEvents(messages.get(0));
        verify(eventBus).publish(messages.get(0));
        verify(eventStore).appendEvents(messages.get(1));
        verify(eventBus).publish(messages.get(1));
    }

    @AggregateRoot
    public static class StubAggregate {

        @AggregateIdentifier
        private String id;

        private final List<DomainEventMessage<String>> messages = new ArrayList<>();

        public StubAggregate() {
        }

        public StubAggregate(String id, String message) {
            this.id = id;
            AggregateLifecycle.doApply(message)
                    .andThenApply(() -> "Got messages: " + messages.size());
        }

        @CommandHandler
        public void handle(String command) {
            AggregateLifecycle.doApply(command);
        }

        public void changeState() {
            AggregateLifecycle.doApply("Test more");
        }

        @EventHandler
        public void handle(String value, DomainEventMessage<String> message) {
            this.id = message.getAggregateIdentifier();
            if (value.startsWith("Test")) {
                AggregateLifecycle.doApply("Last one")
                        .andThenApply(() -> new GenericMessage<>("Got messages: " + messages.size(),
                                                                 MetaData.with("key", "value")));
            }
            this.messages.add(message);
        }

        public List<DomainEventMessage<String>> getMessages() {
            return messages;
        }
    }
}
