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

package org.axonframework.eventhandling;

import org.axonframework.common.Registration;
import org.axonframework.messaging.MessageDispatchInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * Specification of the mechanism on which the Event Listeners can subscribe for events and event publishers can
 * publish
 * their events. The event bus dispatches events to all subscribed listeners.
 * <p/>
 * Implementations may or may not dispatch the events to event listeners in the dispatching thread.
 *
 * @author Allard Buijze
 * @see EventListener
 * @see SimpleEventBus
 * @since 0.1
 */
public interface EventBus {

    /**
     * The default key used to map an event bus as a resource.
     */
    String KEY = EventBus.class.getName();

    /**
     * Publish a collection of events on this bus (one, or multiple). The events will be dispatched to all subscribed
     * listeners.
     * <p/>
     * Implementations may treat the given <code>events</code> as a single batch and distribute the events as such to
     * all subscribed EventListeners.
     *
     * @param events The collection of events to publish
     */
    default void publish(EventMessage<?>... events) {
        publish(Arrays.asList(events));
    }

    /**
     * Publish a collection of events on this bus (one, or multiple). The events will be dispatched to all subscribed
     * listeners.
     * <p/>
     * Implementations may treat the given <code>events</code> as a single batch and distribute the events as such to
     * all subscribed EventListeners.
     *
     * @param events The collection of events to publish
     */
    void publish(List<EventMessage<?>> events);

    /**
     * Subscribe the given <code>eventProcessor</code> to this bus. When subscribed, it will receive all events
     * published to this bus.
     * <p/>
     * If the given <code>eventProcessor</code> is already subscribed, nothing happens.
     *
     * @param eventProcessor The event processor to subscribe
     * @return a handle to unsubscribe the <code>eventProcessor</code>. When unsubscribed it will no longer receive events.
     * @throws EventListenerSubscriptionFailedException if the listener could not be subscribed
     */
    Registration subscribe(EventProcessor eventProcessor);

    /**
     * Register the given <code>interceptor</code> with this bus. When subscribed it will intercept any event messages
     * published on this bus.
     * <p/>
     * If the given <code>interceptor</code> is already registered, nothing happens.
     *
     * @param dispatchInterceptor The event message dispatch interceptor to register
     * @return a handle to unregister the <code>dispatchInterceptor</code>. When unregistered it will no longer be
     * given event messages published on this bus.
     */
    Registration registerDispatchInterceptor(MessageDispatchInterceptor<EventMessage<?>> dispatchInterceptor);

}
