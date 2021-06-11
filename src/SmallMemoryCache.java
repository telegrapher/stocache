package com.github.telegrapher.stocache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Simple memory cache that has the following behaviour:
 * - Uses generics to be reusable without modifications.
 * - Uses Futures to avoid locking repeated attempts of calculation due to cache-misses (thundering herd problem).
 * - Cache hits are efficiently returned, there is little locking (ReentrantReadWriteLock) on the way.
 * - Nulls are considered valid error results, but error results aren't cached.
 * - It doesn't implement any advanced heuristic, it is designed to cache a few tokens in memory.
 * @param <K> Cache Key
 * @param <V> Cache Value
 */
public class SmallMemoryCache<K, V> {

    private final long cacheTTL;
    private final LinkedHashMap<K, CacheElement<V>> cache;
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public SmallMemoryCache(final int maxSize, final long cacheTTL) {
        this.cacheTTL = cacheTTL;

        // Default constructor uses insertion-ordered
        this.cache = new LinkedHashMap<K, CacheElement<V>>(){
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, CacheElement<V>> eldest) {
                return size() > maxSize;
            }
        };
    }

    /**
     * The only public method, it provides the requested value, handling the calculation of the value in a cache miss
     * scenario transparently.
     *
     * It is the only point in the cache where the future is realized, just to be returned.
     *
     * @param key             Key identifying the requested value.
     * @param computeCallable Method used to calculate the value in case it is not stored in the cache already
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws RuntimeException
     */
    public V getValue(final K key, final Callable<V> computeCallable) throws ExecutionException, InterruptedException, RuntimeException {
        try {
            final CacheElement<V> ce = computeIfAbsent(key, computeCallable);
            return ce.futureValue.get();
        } catch (ExecutionException | InterruptedException | RuntimeException e) {
            log.debug("getValue: Error retrieving value from cache.");
            // Remove the key-value pair if we get an exception resolving the future.
            safeRemove(key);
            throw e;
        }
    }


    /**
     * Core method of the cache. It implements a behaviour similar to Map.computeIfAbsent():
     * - If element is in cache and is still valid (not expired), it is returned right way.
     * - If the specified key is not already associated with a value, attempts to compute its value using the given
     *      mapping function and enters it into the cache unless null.
     *
     * @param key
     * @param computeCallable
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private CacheElement<V> computeIfAbsent(final K key, final Callable<V> computeCallable)
            throws ExecutionException, InterruptedException {

        CacheElement<V> ce;
        lock.readLock().lock();
        try {
            ce = cache.get(key);
        } finally {
            lock.readLock().unlock();
        }
        if (ce != null && ce.isValid()){
            return ce;
        }

        lock.writeLock().lock();
        try {
            ce = cache.get(key);
            if (ce != null && ce.isValid()) {
                return ce;
            }
            FutureTask<V> futureTask = new FutureTask<>(computeCallable);
            ce = new CacheElement<>(futureTask, Instant.now().plus(cacheTTL, ChronoUnit.MILLIS));
            futureTask.run();
            cache.put(key, ce);
            return ce;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void safeRemove(final K key){
        lock.writeLock().lock();
        try {
            cache.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Cache element to be stored as a value associated to the key.
     *
     * It stores the desired value as a Future to simplify handling and minimize locking time.
     *
     * @param <V>
     */
    private static class CacheElement<V> {
        final Future<V> futureValue;
        final Instant expiration;

        public boolean isValid() {
            return Instant.now().compareTo(expiration) < 0;
        }

        public CacheElement (Future<V> futureValue, Instant expiration){
            this.futureValue = futureValue;
            this.expiration = expiration ;
        }
    }
}
