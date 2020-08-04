package eu.mihosoft.vsm.model;

import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Concurrent data storage based on a concurrent hashmap. It sends events whenever stored elements are added or removed.
 * Changes within stored elements are not reported.
 *
 * TODO potentially allow automatic registering of vmf models (report property changes etc.)
 */
public final class Storage {

    private final ConcurrentMap<String, Object> map = new ConcurrentHashMap<>();
    private final ReentrantLock writeMapLock = new ReentrantLock();

    private final List<Executor> listeners = new ArrayList<>();

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
    public Subscription addListener(Executor executor) {
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
     * Returns a value specified by its key.
     * @param key the key of the value to return
     * @param <T> type of the value
     * @return optional value (empty, if the value does not exist)
     */
    public <T> Optional<T> getValue(String key) {
        Optional<T> result = Optional.ofNullable((T) map.get(key));

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
            for(Executor listener : listeners) {
                listener.trigger(Events.DATA_UPDATED.name(), key, value);
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
        DATA_UPDATED
    }
}

