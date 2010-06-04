/*
 * Copyright (c) 2010. Axon Framework
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

package org.axonframework.monitoring;

/**
 * <p>Classes implementing this interface can be monitored by requesting their statistics object</p>
 *
 * @author Jettro Coenradie
 * @param <T> Statistics object used to obtain monitored statistics from
 * @since 0.6
 */
public interface Monitored<T extends Statistics> {

    /**
     * Returns the statistics object specific for the class implementing this interface
     *
     * @return T specific statistics object
     */
    T getStatistics();
}