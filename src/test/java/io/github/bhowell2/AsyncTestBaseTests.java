package io.github.bhowell2;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to ensure async test base works as expected..
 * @author Blake Howell
 */
public class AsyncTestBaseTests extends AsyncTestBase {

	@Test
	public void testSubmitToExecutorServiceFailsTestImmediately() throws Exception {
		/*
		 * Using IllegalAccessException to ensure that
		 * */
		assertThrows(IllegalAccessException.class, () -> {
			CountDownLatch moreCountdowns = new CountDownLatch(5);
			wrapAsyncTest(moreCountdowns, latch -> {
				submitToExecutorService(moreCountdowns, () -> {
					throw new IllegalAccessException("Latch should fail.");
				});
			}, () -> {
				// this should not even be run
				assertEquals(0, moreCountdowns.getCount());
			});
		});
	}

	@Test
	public void testAfterLatchFailure() throws Exception {
		// Counter makes sure that the two wrap tests are not interleaved.
		AtomicInteger counter = new AtomicInteger(0);
		assertThrows(IllegalAccessException.class, () -> {
			wrapAsyncTest(latch -> {
				counter.incrementAndGet();
				latch.countDown();
			}, () -> {
				assertEquals(1, counter.getAndIncrement());
				throw new IllegalAccessException("Should fail here.");
			});
		});
		assertEquals(2, counter.getAndIncrement());
		assertThrows(AssertionFailedError.class, () -> {
			wrapAsyncTest(latch -> {
				assertEquals(3, counter.getAndIncrement());
				latch.countDown();
			}, () -> {
				assertEquals(4, counter.getAndIncrement());
				fail("fail");
			});
		});
		assertEquals(5, counter.getAndIncrement());
	}

	@Test
	public void testAwaitTimeExpired() throws Exception {
		assertThrows(AssertionFailedError.class, () -> {
			wrapAsyncTest(10,
			              TimeUnit.MILLISECONDS,
			              new CountDownLatch(1),
			              // do not countdown the latch, should cause failure after 10ms
			         latch -> {},
			              () -> {});
		});
		assertDoesNotThrow(() -> {
			wrapAsyncTest(10,
			              TimeUnit.MILLISECONDS,
			              new CountDownLatch(1),
			              // countdown immediately
			              CountDownLatch::countDown,
			              () -> {});
		});
	}

	@Test
	public void testRetryOnFailure() throws Exception {
		AtomicInteger fullFailureCounter = new AtomicInteger(0);
		assertThrows(IllegalAccessException.class, () -> {
			retryOnFailure(10, () -> {
				fullFailureCounter.getAndIncrement();
				throw new IllegalAccessException("every time!");
			});
		});
		assertEquals(10, fullFailureCounter.get());
		AtomicInteger failHalfCounter = new AtomicInteger(0);
		retryOnFailure(10, () -> {
			if (failHalfCounter.incrementAndGet() < 5) {
				throw new IllegalAccessException("every time!");
			}
		});
		assertEquals(5, failHalfCounter.get());
	}

}
