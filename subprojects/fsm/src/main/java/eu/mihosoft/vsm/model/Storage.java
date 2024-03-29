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

import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Concurrent data storage based on a concurrent hashmap. It sends events whenever stored elements are added, removed
 * or updated. Changes within stored elements are not reported.
 *
 * TODO potentially allow automatic registering of vmf models (report property changes etc.)
 */
public final class Storage {

    /**
     * The storage.
     */
    private final ConcurrentMap<String, Object> map = new ConcurrentHashMap<>();

    /**
     * Lock used to synchronize access to the storage.
     */
    private final ReentrantLock writeMapLock = new ReentrantLock();

    /**
     * state machine listeners.
     */
    private final List<FSMExecutor> listeners = new ArrayList<>();

    private Storage() {
        //
    }

    /**
     * Creates a new storage instance.
     * @return a new storage instance
     */
    public static Storage newInstance() {
        return new Storage();
    }

    /**
     * Adds a data updated listener to this storage.
     * @param executor the executor to add
     * @return a subscription to cancel update notification for the specified listener
     */
    public Subscription addListener(FSMExecutor executor) {
        writeMapLock.lock();

        try {
            this.listeners.add(executor);
        } finally {
            writeMapLock.unlock();
        }

        return () -> {
            writeMapLock.lock();
            try {
                this.listeners.remove(executor);
            } finally {
                writeMapLock.unlock();
            }
        };
    }

    /**
     * Stores the specified object.
     * @param key key used for mapping the specified value
     * @param value value to store
     */
    public void storeValue(String key, Object value) {
        writeMapLock.lock();
        try {
            map.put(key, value);
            fireDataChangedEvent(key,value);
        } finally {
            writeMapLock.unlock();
        }
    }

    /**
     * Updates the value specified by its key. The update is performed atomically.
     * @param key key of the value to update
     * @param func function that atomically performs the update
     */
    public void updateValue(String key, BiFunction<String, Object, Object> func) {

        writeMapLock.lock();
        try {
            var res = map.compute(key, func);
            fireDataChangedEvent(key,res);
        } finally {
            writeMapLock.unlock();
        }
    }

    /**
     * Atomically performs a computation with the key and the value as its inputs. The function should not modify
     * the map or its elements.
     * @param key key of the value to update
     * @param func function that atomically performs the computation
     */
    public <T> T compute(String key, BiFunction<String, Object, T> func) {
        writeMapLock.lock();
        try {
            var res = func.apply(key, map.get(key));
            return res;
        } finally {
            writeMapLock.unlock();
        }
    }

    /**
     * Returns a value specified by its key.
     * @param key the key of the value to return
     * @param <T> type of the value
     * @return optional value (empty, if the value does not exist)
     */
    public <T> Optional<T> getValue(String key) {
        writeMapLock.lock();
        try {
            Optional<T> result = Optional.ofNullable((T) map.get(key));
            return result;
        } finally {
            writeMapLock.unlock();
        }
    }

    /**
     * Removes a value specified by its key.
     * @param key the key of the value to return
     * @param <T> type of the value
     * @return optional value (empty, if the value to be removed does not exist)
     */
    public <T> Optional<T> removeValue(String key) {
        Optional<T> result = Optional.ofNullable((T) map.remove(key));

        return result;
    }

    /**
     * Indicates whether this storage contains the value specified by its key.
     * @param key key of the value to check
     * @return {@code true} if the value exists in this storage; {@code false} otherwise
     */
    public boolean hasValue(String key) {
        return map.containsKey(key);
    }

    /**
     * Fires data-changed events to all registered listeners.
     * @param key the key of the value that has changed
     * @param value the value that has changed
     */
    private void fireDataChangedEvent(String key, Object value) {
        writeMapLock.lock();
        try {
            for(FSMExecutor listener : listeners) {
                listener.trigger(Event.newBuilder().
                        withName(Events.DATA_UPDATED.name()).
                        withArgs(key)//,value) // TODO value is dangerous if not immutable
                        .build());
            }
        } finally {
            writeMapLock.unlock();
        }
    }

    /**
     * Events triggered by this storage.
     */
    public enum Events {
        /**
         * Indicates data update (value added or removed).
         */
        DATA_UPDATED("fsm:storage:data-updated");

        private final String name;

        private Events(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

