package com.github.telegrapher.stocache;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SmallMemoryCacheTest {

    private SmallMemoryCache<Integer, Integer> cache;

    @Before
    public void setup(){
        cache = new SmallMemoryCache<>(10, 10000);
    }

    @Test
    public void test_putOneElementRetrieveOneElement() throws ExecutionException, InterruptedException {

        int testValue = cache.getValue(1, () -> {return 1;} );
        testValue = cache.getValue(1, () -> {return 2;} );
        assertEquals(1, testValue);
    }

    @Test
    public void test_overflowCacheAndRetrieve() throws ExecutionException, InterruptedException {

        /* given */
        int testValue = 0;
        // Load 30 elements in a cache of capacity = 10
        for (int key=0; key<30; key++){
             final int value = key;
             testValue = cache.getValue(key, () -> {return value;} );
        }

        /* when */
        // Since this value hasn't been evicted, the '8' will not be read and the original in cache (25)
        // will be returned
        int nonEvictedValue = cache.getValue(25, () -> {return 8;});
        // Test that capacity is no less than 10 by getting a cached response for '20'
        int lastNonEvictedValue = cache.getValue(20, () -> {return 8;});
        // Test that capacity is no more than 10 by getting an uncached response for '19'
        int firstEvictedValue = cache.getValue(19, () -> {return 8;});
        // Read another value that has been evicted, and reloaded with a different value
        int evictedValue = cache.getValue(6, () -> {return 8;});

        /* then */
        assertEquals(25, nonEvictedValue);
        assertEquals(20, lastNonEvictedValue);
        assertEquals(8, firstEvictedValue);
        assertEquals(8, evictedValue);
    }

    @Test
    public void test_cacheReceivesANull() throws ExecutionException, InterruptedException {
        /* given */ //setup

        /* when */ /* then */
        assertNull( cache.getValue(1, () -> {return null;} ));
    }

    @Test
    public void test_cacheExpiration() throws ExecutionException, InterruptedException {
        /* given */
        // 10 ms expiration.
        cache = new SmallMemoryCache<>(10, 10);

        /* when */
        int testValue = cache.getValue(1, () -> {return 1;} );
        Thread.sleep(15);
        // Read value after 15 ms, load the new one.
        testValue = cache.getValue(1, () -> {return 2;} );

        /* then */
        assertEquals(2, testValue);
    }

    @Test
    public void testCounterWithConcurrency() throws ExecutionException, InterruptedException {

        AtomicInteger errorCollector = new AtomicInteger();

        cache = new SmallMemoryCache<>(1000, 10);

        int numberOfThreads = 1000;
        ExecutorService service = Executors.newFixedThreadPool(100);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            service.execute(() -> {
                for (int key = 0; key < 1500; key++) {
                    final int value = key;
                    try {
                        //int testValue = cache.getValue(key, () -> {Thread.sleep(value);return value;} );
                        int testValue = cache.getValue(key, () -> {
                            return value;
                        });
                    } catch (ExecutionException e) {
                        errorCollector.getAndIncrement();
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        errorCollector.getAndIncrement();
                        e.printStackTrace();
                    }
                }
                latch.countDown();
            });
        }
        latch.await();
        assertEquals(0, errorCollector.intValue());
    }
}
