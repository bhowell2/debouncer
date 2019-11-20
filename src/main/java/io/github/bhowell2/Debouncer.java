
package io.github.bhowell2;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A debouncer that allows for debouncing on the first or last event (after the elapsed
 * interval time) or immediately running the callback and debouncing later events within
 * the interval, and allows for specifying a forced run timeout. The forced run timeout
 * allows for forcing a callback to be run even if the debouncer keeps registering events
 * within the specified debounce interval for the given key; which run the most recently
 * registered callback and then queues up the most recent event to be run.
 *
 * Modeled after https://stackoverflow.com/questions/4742210/implementing-debounce-in-java.
 *
 * @param <K> the key identifying an event to be debounced
 */
public class Debouncer<K> {

	public interface Callback<K> {
		void call(K key);
	}

	private final ScheduledExecutorService scheduler;
	private final ConcurrentHashMap<K, TimerTask> debounceMap;

	/**
	 * Creates a debouncer with the provided pool size. The user very likely wants to
	 * immediately pass of the callback's code to another thread, rather than running
	 * it on the debounce scheduler threads - not doing this could have a detrimental
	 * impact on the debouncer.
	 *
	 * @param poolSize size of the underlying scheduling pool. if this debouncer will be used
	 *                 for many different events (i.e., different keys) then a larger pool should
	 *                 be used, but a single pool size should generally suffice so long as the
	 *                 callback calls the code on another thread.
	 */
	public Debouncer(int poolSize) {
		this.debounceMap = new ConcurrentHashMap<>(poolSize);
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(poolSize);
		executor.setRemoveOnCancelPolicy(true);
		this.scheduler = Executors.unconfigurableScheduledExecutorService(executor);
	}

	/**
	 * Creates a debouncer that will schedule events to debouce at the provided
	 * default interval if no interval is supplied when adding the event to the
	 * debouncer.
	 * @param scheduler runs the events when their debounce interval has expired
	 * @param concurrency the concurrency level for the hashmap. this allows for increasing
	 *                    the throughput of the hashmap when many write-type operations are
	 *                    happening from different threads
	 */
	public Debouncer(ScheduledExecutorService scheduler, int concurrency) {
		this.scheduler = scheduler;
		this.debounceMap = new ConcurrentHashMap<>(concurrency);
	}

	/**
	 * Creates a debounce task for the provided key that will run the last event's
	 * callback within the debounce interval. If a forced-timeout is not set (or 0
	 * or less) and events continue to occur before the debounce interval expires,
	 * it will be reset indefinitely. Use {@link #addRunLast(long, long, TimeUnit, Object, Callback)}
	 * to provide a forced-timeout.
	 *
	 * Note: if a task already exists for this key a different interval will not change 
	 * the underlying task's interval and the interval will remain for that key until 
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param timeUnit the TimeUnit for the interval
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunLast(long interval, TimeUnit timeUnit, K key, Callback<K> callback) {
		addRunLast(interval, -1, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will run the last event's 
	 * callback within the debounce interval. If a forced-timeout is not set (or 0
	 * or less) and events continue to occur before the debounce interval expires,
	 * it will be reset indefinitely.
	 *
	 * Note: if a task already exists for this key a different interval will not change 
	 * the underlying task's interval and the interval will remain for that key until 
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param forcedTimeout if greater than 0 will force the callback to be run after the specified interval 
	 * @param timeUnit the TimeUnit for the interval and forcedTimeout
	 * @param key identifies the given debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunLast(long interval, long forcedTimeout, TimeUnit timeUnit, K key, Callback<K> callback) {
		add(false, true, interval, forcedTimeout, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will run the first event's
	 * callback within the debounce interval. If a forced-timeout is not set and
	 * events continue to occur before the debounce interval expires, it will be
	 * reset indefinitely. Use {@link #addRunFirst(long, long, TimeUnit, Object, Callback)}
	 * to provide a forced-timeout.
	 *
	 * Note: if a task already exists for this key a different interval will not change
	 * the underlying task's interval and the interval will remain for that key until
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param timeUnit the TimeUnit for the interval
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunFirst(long interval, TimeUnit timeUnit, K key, Callback<K> callback) {
		addRunFirst(interval, -1, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will run the first event's
	 * callback within the debounce interval. If a forced-timeout is not set and
	 * events continue to occur before the debounce interval expires, it will be
	 * reset indefinitely.
	 *
	 * Note: if a task already exists for this key a different interval will not change
	 * the underlying task's interval and the interval will remain for that key until
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param forcedTimeout if greater than 0 will force the callback to be run after the specified interval
	 * @param timeUnit the TimeUnit for the interval and forcedTimeout
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunFirst(long interval, long forcedTimeout, TimeUnit timeUnit, K key, Callback<K> callback) {
		add(false, false, interval, forcedTimeout, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will immediately run the
	 * provided callback (on the scheduler thread) and ensure that future callbacks
	 * for the given event key are not run until the debounce interval has expired.
	 * If a forced-timeout is not set and events continue to occur before the debounce
	 * interval expires, it will be reset indefinitely.
	 * Use {@link #addRunImmediately(long, long, TimeUnit, Object, Callback)} to provide
	 * a forced-timeout.
	 *
	 * Note: if a task already exists for this key a different interval will not change
	 * the underlying task's interval and the interval will remain for that key until
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param timeUnit the TimeUnit for the interval
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunImmediately(long interval, TimeUnit timeUnit, K key, Callback<K> callback) {
		addRunImmediately(interval, -1, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will immediately run the
	 * provided callback (on the scheduler thread) and ensure that future callbacks
	 * for the given event key are not run until the debounce interval has expired.
	 * If a forced-timeout is not set and events continue to occur before the debounce
	 * interval expires, it will be reset indefinitely.
	 *
	 * Note: if a task already exists for this key a different interval will not change
	 * the underlying task's interval and the interval will remain for that key until
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param forcedTimeout if greater than 0 will force the callback to be run after the specified interval
	 * @param timeUnit the TimeUnit for the interval and forcedTimeout
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunImmediately(long interval, long forcedTimeout, TimeUnit timeUnit, K key, Callback<K> callback) {
		add(true, false, interval, forcedTimeout, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will immediately run the
	 * provided callback (on the scheduler thread) and run the last callback that
	 * occurs before the debounce interval expires. If no callback occurs before
	 * the debounce interval expires no callback is run since the callback was already
	 * called when the initial event occurred. If a forced-timeout is not set and events
	 * continue to occur before the debounce interval expires, it will be reset indefinitely.
	 * Use {@link #addRunImmediatelyAndRunLast(long, long, TimeUnit, Object, Callback)} to
	 * provide a forced-timeout.
	 *
	 * Note: if a task already exists for this key a different interval will not change
	 * the underlying task's interval and the interval will remain for that key until
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param timeUnit the TimeUnit for the interval
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunImmediatelyAndRunLast(long interval, TimeUnit timeUnit, K key, Callback<K> callback) {
		addRunImmediatelyAndRunLast(interval, -1, timeUnit, key, callback);
	}

	/**
	 * Creates a debounce task for the provided key that will immediately run the
	 * provided callback (on the scheduler thread) and run the last callback that
	 * occurs before the debounce interval expires. If no callback occurs before
	 * the debounce interval expires no callback is run since the callback was already
	 * called when the initial event occurred. If a forced-timeout is not set and events
	 * continue to occur before the debounce interval expires, it will be reset indefinitely.
	 *
	 * Note: if a task already exists for this key a different interval will not change
	 * the underlying task's interval and the interval will remain for that key until
	 * the task expires (as stated above this could occur indefinitely if a forced-timeout
	 * is not set).
	 *
	 * Note: the callback is run on the {@link ScheduledExecutorService} and will block
	 * future debounce tasks from being checked/run if the callback is long-running.
	 * (Ideally the callback will immediately pass on the code to run to another thread.)
	 *
	 * @param interval the interval on which this event should be debounced
	 * @param forcedTimeout if greater than 0 will force the callback to be run after the specified interval
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 */
	public void addRunImmediatelyAndRunLast(long interval, long forcedTimeout, TimeUnit timeUnit, K key, Callback<K> callback) {
		add(true, true, interval, forcedTimeout, timeUnit, key, callback);
	}

	/**
	 * It should first be noted that supplying {@code forcedTimeout > 0} will ensure
	 * that the debounce interval expires at some time in the future, rather than potentially
	 * being extended indefinitely because more events keep occuring before the interval
	 * expires (thus extending the interval). When reading the functionality below have this
	 * in mind - so
	 *
	 * Creates a debounce task for the provided key with the following options:
	 * 1. Run the callback immediately, blocking future events from running until
	 *    the debounce interval expires.
	 *    I.e., {@code runImmediately = true} and {@code runLast = false}
	 *
	 * 2. Run the callback immediately and if any other events occur before the
	 *    debounce interval expires run the last one's callback when the interval
	 *    expires.
	 *    I.e., {@code runImmediately = true} and {@code runLast = true}
	 *
	 * 3. Run the first event's callback when the debounce interval expires.
	 *    I.e., {@code runImmediately = false} and {@code runLast = false}
	 *
	 * 4. Run the last event's callback that occurs before the debounce interval expires.
	 *    I.e., {@code runImmediately = false} and {@code runLast = true}
	 *
	 * @param runImmediately whether or not the run the callback immediately instead of waiting for the debounce interval
	 *                       to expire to run the callback
	 * @param runLast whether or not to run the last callback that occurred before the debounce interval expired
	 * @param interval the interval on which this event should be debounced
	 * @param forcedTimeout if greater than 0 will force the callback to be run after the specified interval
	 * @param timeUnit the TimeUnit for the interval and forcedTimeout
	 * @param key identifies the debounce task
	 * @param callback called when debounce interval elapses
	 * @throws IllegalArgumentException if {@code interval} is less than 0 or greater than {@code forcedTimeout}
	 */
	private void add(boolean runImmediately,
	                 boolean runLast,
	                 long interval,
	                 long forcedTimeout,
	                 TimeUnit timeUnit,
	                 K key,
	                 Callback<K> callback) {
		if (interval < 0 || (forcedTimeout > 0 && forcedTimeout < interval)) {
			throw new IllegalArgumentException("Interval cannot be less than 0 and forcedTimeout cannot be less than interval.");
		}
		/*
		 * The task is created here so that it can be used with putIfAbsent. In the case
		 * of runImmediately = true, the actual task's callback should be empty if no task
		 * currently exists for the given key - this can be overridden in the do-while loop
		 * since the task is not actually scheduled unless there was no task set for the
		 * given key. This allows for both runImmediately = true and runLast = true, which
		 * will run the callback immediately and if an event for the key occurs again within
		 * the debounce period its callback will be set to run on expiry.
		 * */
		TimerTask task = new TimerTask(interval, forcedTimeout, timeUnit, key, callback);
		TimerTask prev;
		if (runLast) {
			do {
				prev = debounceMap.putIfAbsent(key, task);
				if (prev == null) {
					if (runImmediately) {
						/*
						 * Since runImmediately is true and the task was created above, need to
						 * override the callback and set it to nothing and schedule the callback
						 * that should run immediately on the scheduler thread pool like all
						 * other callbacks would run.
						 * */
						task.extend(k -> {});
						scheduler.submit(() -> callback.call(key));
					}
					task.schedule(interval, timeUnit);
				}
			}
			/*
			 * Previous will be null if there was no value and a task will have been
			 * registered. However if previous is not null, attempt to extend it and
			 * override the callback so that the last callback is run rather than the
			 * previous callback.
			 * */
			while (prev != null && !prev.extend(callback));
		} else {
			do {
				prev = debounceMap.putIfAbsent(key, task);
				if (prev == null) {
					if (runImmediately) {
						/*
						 * Since runImmediately is true and the task was created above, need to
						 * override the callback and set it to nothing and schedule the callback
						 * that should run immediately on the scheduler thread pool like all
						 * other callbacks would run.
						 * */
						task.extend(key1 -> {});
						scheduler.submit(() -> callback.call(key));
					}
					task.schedule(interval, timeUnit);
				}
			}
			/*
			 * Previous will be null if there was no value and a task will have been
			 * registered. However if previous is not null, attempt to extend it and
			 * override the callback so that the last callback is run rather than the
			 * previous callback.
			 * */
			while (prev != null && !prev.extend());
		}
	}

	public boolean hasTaskForKey(K key) {
		return this.debounceMap.containsKey(key);
	}

	/**
	 * Returns the current time remaining for the debounce task for the provided key.
	 * If another event occurs before the remaining time expires this COULD change, depending
	 * on whether or not this value is the standard interval or the forced timeout (the lesser
	 * of the two is returned here).
	 *
	 * @param key identifies event
	 * @return 0 (or less) if the task has expired or does not exist. otherwise, it will be the
	 *         interval expiration time or the forced timeout (whichever will occur first).
	 */
	public long getEventsExpirationTimeNanos(K key) {
		TimerTask task = this.debounceMap.get(key);
		if (task != null) {
			return task.getDueTimeNanos();
		}
		return 0;
	}

	/**
	 * Returns how long until the debounce task is forced to expire (if {@code forcedTimeout}
	 * was set to 0 or less, this will be {@link Long#MAX_VALUE}).
	 *
	 * WARNING: do not try to do something like:
	 * {@code debouncer.getRemainingForcedExpirationTimeNanos() - System.currentTimeMillis()}
	 * as this does not make sense, because {@link System#nanoTime()} does not return anything
	 * related to {@link System#currentTimeMillis()} (i.e., they are not simply offset by 10^6).
	 *
	 * Comparing this value and some time in the future should be done with {@link System#nanoTime()}.
	 *
	 * @param key identifies event
	 * @return 0 if no event (currently) exists for the given key. {@link Long#MAX_VALUE} if the debounce
	 *         inerval was not set or was set to 0. some other positive interger otherwise
	 */
	public long getEventsForcedExpirationTimeNanos(K key) {
		TimerTask task = this.debounceMap.get(key);
		if (task != null) {
			return task.getForcedExpirationTimeNanos();
		}
		return 0;
	}

	/**
	 * Cancels the debounce task. This does not run the callback and simply removes the
	 * task from the debouncer so other tasks for the key can be submitted. This does not
	 * guarantee that the callback does not run - as it could already be running when this
	 * is called.
	 *
	 * @param key the key to cancel
	 * @return {@code true} if the debounce task existed and had not completed and was therefore cancelled.
	 *         {@code false} otherwise.
	 */
	public boolean cancel(K key) {
		TimerTask task = this.debounceMap.get(key);
		if (task != null) {
			return task.cancelTask(false, false, false);
		}
		return false;
	}

	/**
	 * Cancels the debounce task.
	 *
	 * @param key the key to cancel
	 * @param runCallback whether or not to run the callback
	 * @param runOnSchedulerThread whether or not to run the callback (if {@code runCallback=true} on the
	 *                             scheduler thread rather than blocking the calling thread to run the callback)
	 * @param interruptIfRunning whether or not to interrupt the callback if it is already running (generally it
	 *                           does not make much sense to run the callback as well, but you could still set
	 *                           {@code runCallback=true}).
	 * @return {@code true} if the debounce task existed and had not completed and was therefore cancelled.
	 *         {@code false} otherwise (did not exist, or already ran).
	 */
	public boolean cancel(K key, boolean runCallback, boolean runOnSchedulerThread, boolean interruptIfRunning) {
		/*
		* Do not want to remove the task here. Use task.cancel, to make sure that it synchronzied with run already.
		* */
		TimerTask task = this.debounceMap.get(key);
		if (task != null) {
			return task.cancelTask(runCallback, runOnSchedulerThread, interruptIfRunning);
		}
		return false;
	}


	/**
	 * See {@link ExecutorService#shutdown()}.
	 */
	public void shutdown() {
		scheduler.shutdown();
	}

	/**
	 * See {@link ExecutorService#shutdownNow()}.
	 * @return list of queued runnables (i.e., {@link TimerTask}s).
	 */
	public List<Runnable> shutdownNow() {
		return scheduler.shutdownNow();
	}

	/**
	 * Calls shutdown and then waits for the executor service to complete.
	 * See {@link ExecutorService#shutdown()} and
	 * {@link ExecutorService#awaitTermination(long, TimeUnit)}.
	 *
	 * Note: this will block until the scheduler is closed or until the
	 * timeout elapses.
	 *
	 * @param timeout how long to wait
	 * @param timeUnit the time unit for timeout
	 * @return true of the scheduler was shutdown before the timeout elapsed, false otherwise
	 * @throws Exception if interrupted while waiting
	 */
	public boolean shutdownAndAwaitTermination(long timeout, TimeUnit timeUnit) throws Exception {
		this.shutdown();
		return scheduler.awaitTermination(timeout, timeUnit);
	}

	// The task that wakes up when the wait time elapses
	private class TimerTask implements Runnable {

		private ScheduledFuture<?> scheduledFuture;
		private final K key;
		private Callback<K> callback;
		private final TimeUnit timeUnit;
		private final long interval, forcedExpirationTimeNanos;
		private long dueTimeNanos;

		TimerTask(long interval, long forcedTimeout, TimeUnit timeUnit, K key, Callback<K> callback) {
			this.interval = interval;
			// force due time never changes
			this.forcedExpirationTimeNanos = forcedTimeout > 0
				? System.nanoTime() + timeUnit.toNanos(forcedTimeout)
				// never force timeout
				: Long.MAX_VALUE;
			this.timeUnit = timeUnit;
			this.key = key;
			this.callback = callback;
			extend();
		}

		private boolean hasEnded() {
			return this.dueTimeNanos < 0;
		}

		private void setHasEnded() {
			this.dueTimeNanos = -1;
		}

		long getForcedExpirationTimeNanos() {
			return this.forcedExpirationTimeNanos;
		}

		/**
		 * Returns whatever the due-time is at the time of this call. Does not synchronize.
		 * If another debounce event occurs for the key the due-time could be extended
		 * (will not be extended if forced expiration is true).
		 * @return the current time this task will expire if it
		 */
		long getDueTimeNanos() {
			return Math.min(dueTimeNanos, forcedExpirationTimeNanos);
		}

		/**
		 * Resets the dueTime of the task, without resetting the callback.
		 * I.e., does not run the latest callback, but runs whatever
		 * callback was last registered.
		 * <p>
		 * If this is always called for a given key, then the first event's
		 * callback will always be run when the debounce period has expired.
		 *
		 * @return true if the task's debounce time has been extended, false otherwise (means task has been completed and
		 * removed from the map and a new task should be added for the event).
		 */
		synchronized boolean extend() {
			return extend(null);
		}

		/**
		 * Resets the due time of the task and resets the callback. I.e.,
		 * calls the most recent callback rather than the first callback
		 * within the debounce period.
		 * <p>
		 * If this is always called for a given key, then the latest event's
		 * callback will always be run when the debounce interval expires.
		 *
		 * @param callback the callback to call when the debounce period has ended (null will not change callback)
		 * @return true if the task's debounce time has been extended, false otherwise (means task has been completed and
		 * removed from the map and a new task should be added for the event).
		 */
		synchronized boolean extend(Callback<K> callback) {
			if (hasEnded()) {
				return false;
			}
			dueTimeNanos = System.nanoTime() + timeUnit.toNanos(this.interval);
			if (callback != null) {
				this.callback = callback;
			}
			return true;
		}

		/**
		 * If the task has not ended (i.e., been run) then it will be cancelled.
		 * @param runCallback whether or not to run the callback
		 * @param runOnSchedulerThread if {@code runCallback=true} then run the callback on the scheduler it would usually
		 *                             be run on rather than the current thread.
		 * @param interruptIfRunning whether or not to
		 * @return {@code true} if it the task had not already ended and was cancelled,
		 *         {@code false} otherwise
		 */
		synchronized boolean cancelTask(boolean runCallback, boolean runOnSchedulerThread, boolean interruptIfRunning) {
			/*
			* Need to check whether or not the task has ended, because it is possible
			* that this method call (is synchronized) is waiting on run (or vice versa).
			* */
			if (this.hasEnded()) {
				return false;
			}
			this.setHasEnded();
			this.scheduledFuture.cancel(interruptIfRunning);
			if (runCallback) {
				try {
					if (runOnSchedulerThread) {
						scheduler.submit(() -> callback.call(key));
					} else {
						callback.call(key);
					}
				} finally {
					debounceMap.remove(key);
				}
			} else {
				debounceMap.remove(key);
			}
			return true;
		}

		synchronized void schedule(long delay, TimeUnit timeUnit) {
			this.scheduledFuture = scheduler.schedule(this, delay, timeUnit);
		}

		public synchronized void run() {
			/*
			 * Need to check whether or not the task has ended, because it is possible
			 * that this method call (is synchronized) is waiting on cancelTask (or vice versa).
			 * */
			if (this.hasEnded()) {
				return;
			}
			long now = System.nanoTime();
			// need to convert
			long forceTimeRemainingNanos = forcedExpirationTimeNanos - now;
			long remainingNanos = dueTimeNanos - now;
			if (forceTimeRemainingNanos <= 0 || remainingNanos <= 0) {
				this.setHasEnded();
				try {
					callback.call(key);
				} finally {
					debounceMap.remove(key);
				}
			} else {
				// task should not yet expire. reschedule it for the soonest due-time
				this.schedule(Math.min(forceTimeRemainingNanos, remainingNanos), TimeUnit.NANOSECONDS);
			}
		}

	}

}
