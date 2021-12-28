/*
 * Copyright 2019-2021 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vsm.model;

import java.util.concurrent.CompletableFuture;

public interface AsyncFSMExecutor extends FSMExecutor {
    /**
     * Starts the executor and waits until the state machine has stopped.
     */
    void startAndWait();

    /**
     * Starts the state machine and returns the thread object that is performing the
     * execution. This method does return while the state machine is executed
     * @return future that completes if the execution stopped
     */
    CompletableFuture<Void> startAsync();

    /**
     * Stops the execution of the state machine.
     *
     * @return future that completes if the execution stopped
     */
    CompletableFuture<Void> stopAsync();

    /**
     * Execution mode.
     */
    enum ExecutionMode {
        /**
         * Regions aka nested FSMs are processed in the thread of the executed FSM.
         */
        SERIAL_REGIONS,
        /**
         * Regions aka nested FSMs are processed parallel.
         */
        PARALLEL_REGIONS,
    }
}
