package io.github.bhowell2;


import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.concurrent.TimeUnit.*;

/**
 * Normally latches would be used often for async tests, however they are not used
 * very often here, because the actual timing is of importance. Thread.sleep() has
 * been heavily abused in these tests.
 *
 * Almost all tests are wrapped with a retry block so that if they fail due to some
 * thread interleaving issues they can be retried.
 */
class DebouncerTest extends AsyncTestBase {

	// debouncer with default 10ms interval
	Debouncer<Object> debouncer = new Debouncer<>(1);

	/*
	 *
	 * THROWS
	 *
	 * */

	@Test
	public void testFailsWithInvalidInterval() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> {
			debouncer.addRunLast(-1, TimeUnit.MILLISECONDS, "whatever", key -> {
			});
		}, "Should not be able to set negative interval.");
		assertThrows(IllegalArgumentException.class, () -> {
			debouncer.addRunLast(10, 5, TimeUnit.MILLISECONDS, "whatever", key -> {
			});
		}, "Should not be able to set forced timeout less than interval.");
	}


	/*
	 *
	 * RUN FIRST
	 *
	 * */

	@Test
	public void testRunFirst() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger countKey1 = new AtomicInteger(0),
				countKey2 = new AtomicInteger(0);
			String key1 = "key1", key2 = "key2";

			/*
			 * Callback for key1 should only be called once, because the
			 * */
			debouncer.addRunFirst(10, TimeUnit.MILLISECONDS, key1, key -> {
				countKey1.incrementAndGet();
			});
			debouncer.addRunFirst(10, TimeUnit.MILLISECONDS, key1, key -> {
				countKey1.incrementAndGet();
			});

			debouncer.addRunFirst(10, TimeUnit.MILLISECONDS, key2, key -> {
				countKey2.incrementAndGet();
			});
			Thread.sleep(12);
			assertEquals(1, countKey1.get());
			assertEquals(1, countKey2.get());
			debouncer.addRunFirst(10, TimeUnit.MILLISECONDS, key2, key -> {
				// submit key 2 again. should increment, because should be run after all
				countKey2.incrementAndGet();
			});
			Thread.sleep(12);
			assertEquals(1, countKey1.get(), "Should not have changed from above.");
			assertEquals(2, countKey2.get());
		});
	}

	@Test
	public void testRunFirstWithForcedTimeout() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>());

			/*
			 * Every time this is called, it will reset the 20ms timeout, but the
			 * forced timeout of 30ms will from the first call should not be reset.
			 * */
			Consumer<Integer> run = i -> {
				debouncer.addRunFirst(30, 50, MILLISECONDS, "akey", key -> {
					counter.incrementAndGet();
					eventsCalled.add(i);
				});
			};

			run.accept(0);
			// give a bit of buffer time then check was not run immediately
			Thread.sleep(2);
			assertEquals(0, counter.get(), "Should not have run immediately.");
			run.accept(1);    // should not run
			Thread.sleep(18); // 20ms in, 30ms till forced expiration
			run.accept(2);    // should not be called
			Thread.sleep(15);  // 35ms in, 15ms till forced expiration
			assertEquals(0, counter.get(),
			             "The initial 30ms interval has expired, but nothing should have been run as the interval " +
				             "should have been reset by each call.");
			run.accept(3);    // should not run as interval has not expired
			Thread.sleep(20);
			assertEquals(1, counter.get(),
			             "The forced interval should have expired by now and the first event submitted should have been run.");
			assertEquals(0, eventsCalled.get(0),
			             "The forced interval should have expired by now and the first event submitted should have been run.");
			/*
			 * Should submit for a new run last event since the forced interval should have expired by now,
			 * even though there should be 10ms left before the regular interval expiry.
			 * */
			run.accept(4);
			Thread.sleep(2);
			run.accept(5);
			Thread.sleep(33);
			assertEquals(2, counter.get(), "Should have run the last submitted task for a new interval.");
			assertEquals(4, eventsCalled.get(1), "Should have been called on last submission");
		});
	}


	/*
	 *
	 * RUN LAST
	 *
	 * */

	@Test
	public void testRunLast() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			AtomicReference<Integer> lastRun = new AtomicReference<>(null);
			int eventCount = 10;
			for (int i = 0; i < eventCount; i++) {
				final int iFinal = i;
				/*
				 * Default timeout is 10ms, sleep for 5ms and resubmit event. This should cause
				 * the event to only be run once when the final event is submitted (because the
				 * debouncer keeps getting reset).
				 * */
				Thread.sleep(5);
				debouncer.addRunLast(10, MILLISECONDS, "whatever", key -> {
					counter.incrementAndGet();
					lastRun.set(iFinal);
				});
			}
			// loop takes 50ms to run. wait for it to finish and then some so that debouncer can complete
			Thread.sleep(75);
			assertEquals(eventCount - 1, lastRun.get(), "Only last callback should have been run.");
			assertEquals(1, counter.get(), "Callback should have only been run once.");
		});
	}

	@Test
	public void testRunLastWithForcedTimeout() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>());

			/*
			 * Every time this is called, it will reset the 20ms timeout, but the
			 * forced timeout of 30ms will from the first call should not be reset.
			 * */
			Consumer<Integer> run = i -> {
				debouncer.addRunLast(30, 50, MILLISECONDS, "akey", key -> {
					counter.incrementAndGet();
					eventsCalled.add(i);
				});
			};

			run.accept(0);
			// give a bit of buffer time then check was not run immediately
			Thread.sleep(2);
			assertEquals(0, counter.get(), "Should not have run immediately.");
			run.accept(1);    // should not run
			Thread.sleep(18); // 20ms in, 30ms till forced expiration
			run.accept(2);    // should not be called
			Thread.sleep(15);  // 35ms in, 15ms till forced expiration
			assertEquals(0, counter.get(),
			             "The initial 30ms interval has expired, but nothing should have been run as the interval " +
				             "should have been reset by each call.");
			run.accept(3);    // should not run as interval has not expired
			Thread.sleep(20);
			assertEquals(1, counter.get(),
			             "The forced interval should have expired by now and the last event submitted should have been run.");
			assertEquals(3, eventsCalled.get(0),
			             "The forced interval should have expired by now and the last event submitted should have been run.");
			/*
			 * Should submit for a new run last event since the forced interval should have expired by now,
			 * even though there should be 10ms left before the regular interval expiry.
			 * */
			run.accept(4);
			Thread.sleep(35);
			assertEquals(2, counter.get(), "Should have run the last submitted task for a new interval.");
			assertEquals(4, eventsCalled.get(1), "Should have been called on last submission");
		});
	}

	/*
	 *
	 * RUN IMMEDIATE
	 *
	 * */

	@Test
	public void testRunImmediately() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			for (int i = 0; i < 15; i++) {
				debouncer.addRunImmediately(10, MILLISECONDS, "test", key -> {
					counter.incrementAndGet();
				});
				// sleep 1ms, debouncer runs for 10ms by default.
				Thread.sleep(1);
				/*
				 * Check that it was run immediately (would be run after 10ms otherwise).
				 * No iteration of the loop should be run again, because they are still all
				 * submitted within the 10ms debounce window, resetting it each time.
				 * */
				assertEquals(1, counter.get());
			}
			// wait 50 for good measure to make sure completed
			Thread.sleep(50);
			assertEquals(1, counter.get());
		});
	}

	@Test
	public void testRunImmediatelyWithForcedTimeout() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>());

			/*
			 * Every time this is called, it will reset the 20ms timeout, but the
			 * forced timeout of 30ms will from the first call should not be reset.
			 * */
			Consumer<Integer> run = i -> {
				debouncer.addRunImmediately(30, 50, MILLISECONDS, "akey", key -> {
					counter.incrementAndGet();
					eventsCalled.add(i);
				});
			};

			run.accept(0);
			// give a bit of buffer time then check was run immediately
			Thread.sleep(2);
			assertEquals(1, counter.get(), "Should have run immediately.");
			assertEquals(0, eventsCalled.get(0));
			run.accept(1);    // should not run
			Thread.sleep(18); // 20ms in, 30ms till forced expiration
			run.accept(2);    // should not be called
			Thread.sleep(15);  // 35ms in, 15ms till forced expiration
			assertEquals(1, counter.get(),
			             "The initial 30ms interval has expired, but nothing should have been run as the interval " +
				             "should have been reset by each call.");
			assertEquals(0, eventsCalled.get(0));
			run.accept(3);    // should not run as interval has not expired
			Thread.sleep(20);
			/*
			 * Should run immediately since the forced interval should have expired by now,
			 * even though there should be 10ms left before the regular interval expiry.
			 * */
			run.accept(4);
			Thread.sleep(2);
			assertEquals(2, counter.get(), "Should have run immediately for the second time.");
			assertEquals(4, eventsCalled.get(1), "Should have run immediately for the second time.");
			run.accept(5);
			/*
			 * 30ms is the normal reset time, so should have expired by now, but nothing
			 * should have run since it was run immediate only
			 * */
			Thread.sleep(35);
			assertEquals(2, counter.get(), "No event should be called again.");
			assertEquals(4, eventsCalled.get(1), "No event should have been called again.");
		});
	}


	@Test
	public void testRunImmediatelyAndRunLast() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			AtomicReference<String> lastRunVal = new AtomicReference<>(null);
			debouncer.addRunImmediatelyAndRunLast(10, MILLISECONDS, "1", key -> {
				counter.incrementAndGet();
			});
			Thread.sleep(1);
			assertEquals(1, counter.get());
			debouncer.addRunImmediatelyAndRunLast(10, MILLISECONDS, "1", key -> {
				counter.incrementAndGet();
				lastRunVal.set("before last");
			});
			// should not be incremented yet, 10ms haven't elapsed
			assertEquals(1, counter.get());
			debouncer.addRunImmediatelyAndRunLast(10, MILLISECONDS, "1", key -> {
				counter.incrementAndGet();
				lastRunVal.set("last");
			});
			// give some extra time to make sure last is run when debounce period expires after 10ms
			Thread.sleep(15);
			assertEquals(2, counter.get());
			assertEquals("last", lastRunVal.get());
		});
	}

	@Test
	public void testRunImmediatelyAndRunLastWithForcedTimeout() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>());

			/*
			 * Every time this is called, it will reset the 30ms timeout, but the
			 * forced timeout of 50ms will from the first call should not be reset.
			 * */
			Consumer<Integer> run = i -> {
				debouncer.addRunImmediatelyAndRunLast(30, 50, MILLISECONDS, "akey", key -> {
					counter.incrementAndGet();
					eventsCalled.add(i);
				});
			};

			run.accept(0);
			// give a bit of buffer time then check was run immediately
			Thread.sleep(2);
			assertEquals(1, counter.get(), "Should have run immediately.");
			assertEquals(0, eventsCalled.get(0));
			run.accept(1);    // should not be run as it will be overridden by later event
			Thread.sleep(18); // 20ms in, 30ms till forced expiration
			run.accept(2);    // should not be called
			Thread.sleep(15);  // 35ms in, 15ms till forced expiration
			assertEquals(1, counter.get(),
			             "The initial 30ms interval has expired, but nothing should have been run as the interval " +
				             "should have been reset by each call");
			run.accept(3);    // last call. should be called below
			Thread.sleep(20); //
			assertEquals(2, counter.get(), "Should have run last by now.");
			assertEquals(3, eventsCalled.get(1));
		});
	}


	/*
	 *
	 * Interleaved debounce types
	 *
	 * */

	@Test
	public void testRunLastAfterRunImmediate() throws Exception {
		// Makes run immediate call and then call run last. Should run both.
		String key = "akey";
		retryOnFailure(10, () -> {
			wrapAsyncTest(new CountDownLatch(3), latch -> {
				List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>(2));
				debouncer.addRunImmediately(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(() -> {
						assertEquals(key, k);
						latch.countDown();
					});
					eventsCalled.add(1);
				});
				Thread.sleep(2);
				assertEquals(1, eventsCalled.size());
				assertEquals(1, eventsCalled.get(0));
				debouncer.addRunLast(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(() -> {
						assertEquals(key, k);
						latch.countDown();
					});
					eventsCalled.add(2);
				});
				Thread.sleep(2);
				assertEquals(1, eventsCalled.size(), "Should not have called run last callback yet.");
				// make sure has expired (should after 8ms from now)
				Thread.sleep(12);
				assertEquals(2, eventsCalled.size());
				assertEquals(2, eventsCalled.get(1));
				latch.countDown();
			}, () -> {
				// dont need to do anything here
			});
		});
	}

	@Test
	public void testRunImmediatelyAfterRunFirst() throws Exception {
		// should not run immediately since a debounce event is already queued for the key
		String key = "akey";
		retryOnFailure(10, () -> {
			wrapAsyncTest(new CountDownLatch(2), latch -> {
				List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>(2));
				debouncer.addRunFirst(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						assertEquals(key, k);
						latch.countDown();
					});
					eventsCalled.add(1);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size());
				debouncer.addRunImmediately(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						fail("Should never be called.");
					});
					eventsCalled.add(2);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size(), "Should not have called run first yet. And run immediate should not have run.");
				// make sure has expired (should after 8ms from now)
				Thread.sleep(12);
				assertEquals(1, eventsCalled.size());
				assertEquals(1, eventsCalled.get(0));
				latch.countDown();
			}, () -> {
				// dont need to do anything here
			});
		});
	}

	@Test
	public void testRunFirstAfterRunLastCall() throws Exception {
		/*
		 * Should not call the run first callback since run last was already queued and
		 * addRunFirst never overrides if a debounce event is already active.
		 * */
		retryOnFailure(10, () -> {
			String key = "akey";
			wrapAsyncTest(new CountDownLatch(2), latch -> {
				List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>(2));
				debouncer.addRunLast(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						assertEquals(key, k);
						latch.countDown();
					});
					eventsCalled.add(1);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size());
				debouncer.addRunFirst(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						fail("Should never be called.");
					});
					eventsCalled.add(2);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size(), "Should not have called run last yet and run first should never run.");
				// reset the debounce interval again with a runFirstCall and make sure it was reset
				debouncer.addRunFirst(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						fail("Should never be called.");
					});
					eventsCalled.add(2);
				});
				Thread.sleep(5);
				assertEquals(0, eventsCalled.size(), "Should not have called run last yet and run first should never run.");
				// make sure has expired (should after 5ms from now)
				Thread.sleep(10);
				assertEquals(1, eventsCalled.size());
				assertEquals(1, eventsCalled.get(0), "Should have called the only run last callback.");
				latch.countDown();
			}, () -> {
				// dont need to do anything here
			});

			/*
			 *
			 * */
			String key2 = "akey2";
			wrapAsyncTest(new CountDownLatch(2), latch -> {
				List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>(2));
				debouncer.addRunLast(10, MILLISECONDS, key2, k -> {
					assertOnAnotherThread(latch, () -> {
						assertEquals(key2, k);
						latch.countDown();
					});
					eventsCalled.add(1);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size());
				debouncer.addRunFirst(10, MILLISECONDS, key2, k -> {
					assertOnAnotherThread(latch, () -> {
						fail("Should never be called.");
					});
					eventsCalled.add(2);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size(), "Should not have called run last yet and run first should never run.");
				debouncer.addRunLast(10, MILLISECONDS, key2, k -> {
					assertOnAnotherThread(latch, () -> {
						assertEquals(key2, k);
						latch.countDown();
					});
					eventsCalled.add(3);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size(), "Nothing should have been run yet.");
				// make sure expired. should be 8s from now
				Thread.sleep(10);
				assertEquals(1, eventsCalled.size());
				assertEquals(3, eventsCalled.get(0));
				latch.countDown();
			}, () -> {
				// dont need to do anything here
			});
		});
	}

	@Test
	public void testAddRunLastAfterRunFirstCall() throws Exception {
		/*
		 * Should not call the run first callback since run last was called after and
		 * therefore should have overridden the callback.
		 * */
		retryOnFailure(10, () -> {
			String key = "akey";
			wrapAsyncTest(new CountDownLatch(2), latch -> {
				List<Integer> eventsCalled = Collections.synchronizedList(new ArrayList<>(2));
				debouncer.addRunLast(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						assertEquals(key, k);
						latch.countDown();
					});
					eventsCalled.add(1);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size());
				debouncer.addRunFirst(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						fail("Should never be called.");
					});
					eventsCalled.add(2);
				});
				Thread.sleep(2);
				assertEquals(0, eventsCalled.size(), "Should not have called run last yet and run first should never run.");
				// reset the debounce interval again with a runFirstCall and make sure it was reset
				debouncer.addRunFirst(10, MILLISECONDS, key, k -> {
					assertOnAnotherThread(latch, () -> {
						fail("Should never be called.");
					});
					eventsCalled.add(2);
				});
				Thread.sleep(5);
				assertEquals(0, eventsCalled.size(), "Should not have called run last yet and run first should never run.");
				// make sure has expired (should after 5ms from now)
				Thread.sleep(8);
				assertEquals(1, eventsCalled.size());
				assertEquals(1, eventsCalled.get(0), "Should have called the only run last callback.");
				latch.countDown();
			}, () -> {
				// dont need to do anything here
			});
		});
	}

	@Test
	public void testHasKeyForTask() throws Exception {
		retryOnFailure(10, () -> {
			debouncer.addRunImmediately(10, MILLISECONDS, "key", key -> {});
			Thread.sleep(1);
			assertTrue(debouncer.hasTaskForKey("key"));
			Thread.sleep(10);
			assertFalse(debouncer.hasTaskForKey("key"));
		});
	}

	/*
	 *
	 * CANCEL TASK
	 *
	 * */

	@Test
	public void testCancelTaskDoNotRunCallback() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			debouncer.addRunFirst(10, MILLISECONDS, "key1", key -> {
				counter.incrementAndGet();
			});
			Thread.sleep(3);
			assertTrue(debouncer.cancel("key1"));
			Thread.sleep(15);
			assertFalse(debouncer.hasTaskForKey("key1"));
			assertEquals(0, counter.get());
			debouncer.addRunFirst(10, MILLISECONDS, "key1", key -> {
				counter.incrementAndGet();
			});
			Thread.sleep(15);
			assertFalse(debouncer.cancel("key1"));
			assertEquals(1, counter.get());
		});
	}

	@Test
	public void testCancelTaskAndCallbackOnCallingThread() throws Exception {
		retryOnFailure(10, () -> {
			wrapAsyncTest(() -> {
				Thread currentThread = Thread.currentThread();
				AtomicInteger counter = new AtomicInteger(0);
				String key = "akey";
				debouncer.addRunFirst(10, MILLISECONDS, key, k -> {
					try {
						Thread.sleep(10);
						counter.incrementAndGet();
						assertOnAnotherThread(() -> {
							assertEquals(currentThread, Thread.currentThread());
						});
					} catch (Exception e) {
						// should not actually be run on another thread, but this is done for good measure
						failOnAnotherThread(e);
					}
				});
				Thread.sleep(1);
				long startTime = System.nanoTime();
				debouncer.cancel(key, true, false, false);
				long stopTime = System.nanoTime();
				assertEquals(1, counter.get(), "Should have run callback and thus incremeted.");
				assertTrue(MILLISECONDS.convert(stopTime - startTime, NANOSECONDS) >= 10,
				           "Should be at least 10ms, because main thread should be blocked from sleep of callback.");
			});
		});
	}

	@Test
	public void testCancelTaskRunCallback() throws Exception {
		/*
		 * Need to check to make sure that the callback is not run twice.
		 * This is done by creating a lot of tasks and immediately canceling
		 * them with runCallback=true. This way, some will run with
		 * */
		retryOnFailure(10, () -> {
			wrapAsyncTest(() -> {
				Thread callingThread = Thread.currentThread();
				Debouncer<Integer> multiPoolDebouncer = new Debouncer<>(10);
				int count = 100000;
				ConcurrentHashMap<Integer, Integer> keyCallbackCounter = new ConcurrentHashMap<>(count, 1, 10);
				for (int i = 0; i < count; i++) {
					final int iFinal = i;
					keyCallbackCounter.put(iFinal, 0);
					multiPoolDebouncer.addRunFirst(1, MILLISECONDS, iFinal, key -> {
						keyCallbackCounter.computeIfPresent(iFinal, (k, v) -> v + 1);
						assertOnAnotherThread(() -> {
							assertNotEquals(callingThread, Thread.currentThread());
						});
					});
					// need a unique key for this, so just offset by count
					multiPoolDebouncer.addRunFirst(1, MILLISECONDS, iFinal + count, key -> {
						multiPoolDebouncer.cancel(iFinal, true, true, false);
					});
				}
				Thread.sleep(10);
				assertEquals(0, multiPoolDebouncer.shutdownNow().size(), "All tasks should have run by now.");
				keyCallbackCounter.forEach((key, i) -> {
					assertEquals(1, i, "Everything should be 1 as either the callback was called by the cancellation or by the " +
						"debounce callback running before it was possible to cancel.");
				});
			});
		});
	}

	/*
	 *
	 * TASK TIME REMAINING
	 *
	 * */

	@Test
	public void testGetEventsExpirationTime() throws Exception {
		retryOnFailure(10, () -> {
			String key = "key";
			long forcedTimeoutMillis = 200;
			long intervalMillis = 10;
			long expectedExpirationTimeNanos = System.nanoTime() + MILLISECONDS.toNanos(intervalMillis);
			debouncer.addRunFirst(intervalMillis, forcedTimeoutMillis, MILLISECONDS, key, k -> {
				// do nothing
			});
			long diffNanos = debouncer.getEventsExpirationTimeNanos(key) - expectedExpirationTimeNanos;
			assertTrue(Math.abs(MILLISECONDS.convert(diffNanos, NANOSECONDS)) < 5,
			           "Difference between the initial call (plus the forced timeout) and the estimated timeout should " +
				           "be minimal.");
			assertEquals(0, debouncer.getEventsExpirationTimeNanos("dne"),
			             "Should return 0, because there has been no event for the given task.");
		});
	}

	@Test
	public void testGetEventsForcedExpirationTime() throws Exception {
		retryOnFailure(10, () -> {
			String key = "key";
			long forcedTimeoutMillis = 200;
			long expectedForcedExpirationTimeNanos = System.nanoTime() + MILLISECONDS.toNanos(forcedTimeoutMillis);
			debouncer.addRunFirst(10, forcedTimeoutMillis, MILLISECONDS, key, k -> {
				// do nothing
			});
			long diffNanos = debouncer.getEventsForcedExpirationTimeNanos(key) - expectedForcedExpirationTimeNanos;
			assertTrue(Math.abs(MILLISECONDS.convert(diffNanos, NANOSECONDS)) < 5,
			           "Difference between the initial call (plus the forced timeout) to the forced expiration time should " +
				           "be minimal.");
			assertEquals(0, debouncer.getEventsForcedExpirationTimeNanos("dne"),
			             "Should return 0, because there has been no event for the given task.");
		});
	}

	/*
	 *
	 * Tests mainly for code completion.
	 *
	 * */

	@Test
	public void testRunImmediatelyAndRunLast_withNoLastEvent() throws Exception {
		retryOnFailure(10, () -> {
			AtomicInteger counter = new AtomicInteger(0);
			debouncer.addRunImmediatelyAndRunLast(5, MILLISECONDS, "akey", key -> {
				counter.incrementAndGet();
			});
			Thread.sleep(10);
			assertEquals(1, counter.get());
		});
	}

	@Test
	public void testShutdown() throws Exception {
		AtomicInteger counter = new AtomicInteger(0);
		debouncer.addRunLast(10, MILLISECONDS, "neverrun!", key -> {
			counter.incrementAndGet();
		});
		Thread.sleep(1);
		debouncer.shutdown();
		assertEquals(0, counter.get());
	}

	@Test
	public void testShutdownAndAwaitTermination() throws Exception {
		AtomicInteger counter = new AtomicInteger(0);
		debouncer.addRunLast(10, MILLISECONDS, "run before exit", key -> {
			counter.incrementAndGet();
		});
		Thread.sleep(1);
		debouncer.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
		assertEquals(1, counter.get());
	}

	@Test
	public void testShutdownNow() {
		AtomicInteger counter = new AtomicInteger(0);
		debouncer.addRunLast(10, MILLISECONDS, "neverrun!", key -> {
			counter.incrementAndGet();
		});
		List<Runnable> leftovers = debouncer.shutdownNow();
		assertEquals(1, leftovers.size());
		assertEquals(0, counter.get());
	}

	@Test
	public void testSynchronizedExtension() throws Exception {
		AtomicInteger counter = new AtomicInteger(0);
		/*
		 * To obtain 100% code coverage, need to do this many times such that the synchronization
		 * blocks of run() and extend() are in contention and the run block removes the event
		 * while the extend block is waiting, causing the extend block to return false (not extend)
		 * and the caller to create a new task for the event.
		 * */
		for (int i = 0; i < 250; i++) {
			Thread.sleep(1);
			debouncer.addRunFirst(1, MILLISECONDS, "akey", key -> {
				counter.incrementAndGet();
			});
		}
		Thread.sleep(15);
		assertTrue(counter.get() > 0);
	}

	@Test
	public void testSynchronizedExtensionWithCallback() throws Exception {
		AtomicInteger counter = new AtomicInteger(0);
		/*
		 * To obtain 100% code coverage, need to do this many times such that the synchronization
		 * blocks of run() and extend() are in contention and the run block removes the event
		 * while the extend block is waiting, causing the extend block to return false (not extend)
		 * and the caller to create a new task for the event.
		 * */
		for (int i = 0; i < 250; i++) {
			Thread.sleep(1);
			debouncer.addRunLast(1, MILLISECONDS, "akey", key -> {
				counter.incrementAndGet();
			});
		}
		Thread.sleep(15);
		assertTrue(counter.get() > 0);
	}

	@Test
	public void testCustomSchedulerDebouncer() throws Exception {
		wrapAsyncTest(latch -> {
			ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
			AtomicReference threadRef = new AtomicReference(null);
			scheduler.submit(() -> {
				threadRef.set(Thread.currentThread());
			});
			Thread.sleep(1);
			Debouncer customDebouncer = new Debouncer<Object>(scheduler, 1);
			customDebouncer.addRunFirst(1, MICROSECONDS, "whatever", key -> {
				assertOnAnotherThread(() -> {
					assertEquals(threadRef.get(), Thread.currentThread());
				});
				latch.countDown();
			});
		}, () -> {

		});
	}

}
