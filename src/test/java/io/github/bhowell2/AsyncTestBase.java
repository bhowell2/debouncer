package io.github.bhowell2;

import org.junit.jupiter.api.AfterEach;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provides some quick functionality in the form of wrappers for multi-threaded testing.
 *
 * Extending classes should not use
 *
 * @author Blake Howell
 */
public abstract class AsyncTestBase {

	@FunctionalInterface
	public interface Runner {
		void run() throws Exception;
	}

	public interface LatchRunner {
		void run(CountDownLatch latch) throws Exception;
	}

	// private so cannot be set by user
	private AtomicReference<Exception> asyncFailure = new AtomicReference<>(null);

	private ExecutorService executorService = Executors.newFixedThreadPool(5);

	@AfterEach
	public void afterEach() throws Exception {
		executorService.shutdownNow();
	}

	protected void submitToExecutorService(Runner runner) {
		submitToExecutorService(null, runner);
	}

	/**
	 * Ensures that a test fails when code running on another thread throws an error.
	 * This needs to be run with one of the {@code wrapAsyncTest(...)} methods so that
	 * the the test fails automatically. Otherwise the user needs to handle the failure
	 * cause themselves and make sure the test fails.
	 *
	 * @param latch latch to be counted down on failure
	 * @param runner code to run on the executor service's thread
	 */
	protected void submitToExecutorService(CountDownLatch latch, Runner runner) {
		executorService.submit(() -> {
			try {
				runner.run();
			} catch (Exception e) {
				asyncFailure.set(e);
				if (latch != null) {
					while (latch.getCount() > 0) {
						latch.countDown();
					}
				}
			}
		});
	}

	protected void assertOnAnotherThread(Runner runner) {
		assertOnAnotherThread(null, runner);
	}

	protected void assertOnAnotherThread(CountDownLatch latch, Runner runner) {
		try {
			runner.run();
		} catch (Exception e) {
			asyncFailure.set(e);
			if (latch != null) {
				while (latch.getCount() > 0) {
					latch.countDown();
				}
			}
		}
	}

	protected void failOnAnotherThread(String message) {
		failOnAnotherThread(new Exception(message));
	}

	protected void failOnAnotherThread(Exception e) {
		asyncFailure.set(e);
	}

	/**
	 * Used to wrap tests that make assertions on another thread, but due to sleeping
	 * on the main thread do not actually need to countdown a latch.
	 *
	 * @param runner
	 * @throws Exception
	 */
	protected void wrapAsyncTest(Runner runner) throws Exception {
		wrapAsyncTest(new CountDownLatch(0), latch -> runner.run(), () -> {});
	}

	protected void wrapAsyncTest(LatchRunner beforeLatchAwait, Runner afterLatchAwait) throws Exception {
		wrapAsyncTest(new CountDownLatch(1), beforeLatchAwait, afterLatchAwait);
	}

	protected void wrapAsyncTest(CountDownLatch latch, LatchRunner beforeLatchAwait, Runner afterLatchAwait) throws Exception {
		wrapAsyncTest(5, TimeUnit.SECONDS, latch, beforeLatchAwait, afterLatchAwait);
	}

	/**
	 * Use when running code on another thread that may not
	 * @param latchTimeout
	 * @param timeUnit
	 * @param latch
	 * @param beforeLatchAwait
	 * @param afterLatchAwait
	 * @throws Exception
	 */
	protected void wrapAsyncTest(long latchTimeout,
	                             TimeUnit timeUnit,
	                             CountDownLatch latch,
	                             LatchRunner beforeLatchAwait,
	                             Runner afterLatchAwait) throws Exception {
		beforeLatchAwait.run(latch);
		if (!latch.await(latchTimeout, timeUnit)) {
			fail("Await time expired.");
		}
		// check if there was a failure
		if (asyncFailure.get() != null) {
			throw asyncFailure.get();
		}
		afterLatchAwait.run();
		// check after everything
		if (asyncFailure.get() != null) {
			throw asyncFailure.get();
		}
	}

	/**
	 * Use for flaky tests from asynchronous interleaving that may sometimes fail
	 * because the scheduler is indeterminate.
	 * @param maxRetryCount
	 */
	protected void retryOnFailure(int maxRetryCount, Runner runner) throws Exception {
		AtomicReference<Exception> error = new AtomicReference<>(null);
		AtomicInteger retryCount = new AtomicInteger(maxRetryCount);
		do {
			error.set(null);
			try {
				runner.run();
			} catch (Exception e) {
				error.set(e);
				// sleep for just a bit to attempt to potentially help realign schedulers for async tests
				Thread.sleep(100);
			}
		} while (retryCount.decrementAndGet() > 0 && error.get() != null);
		if (error.get() != null) {
			throw error.get();
		}
	}

}
