![](https://github.com/bhowell2/debouncer/workflows/build/badge.svg) 
[![codecov](https://codecov.io/gh/bhowell2/debouncer/branch/master/graph/badge.svg)](https://codecov.io/gh/bhowell2/debouncer)
![](https://img.shields.io/maven-central/v/io.github.bhowell2/debouncer)

# Debouncer 
A multi-feature debouncer for Java projects, allowing to debounce immediately (i.e., run the event immediately and then 
no future events until the debounce interval expires), on the first event (when the debounce interval expires), the last 
event, or even debounce immediately and on the most recent event. This also provides a forced-timeout feature so that 
an event will not be debounced indefinitely.

## Requirements
Requires Java 1.8+, because of lambda use. There are no external dependencies.

## Install
This can be obtained from the Maven Central repository:

### Maven
```xml
<dependency>
    <groupId>io.github.bhowell2</groupId>
    <artifactId>debouncer</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle
```groovy
dependencies {
    compile "io.github.bhowell2:debouncer:1.1.0"
}
```

## Usage
**It should be noted that if a forced-timeout is not supplied an event could be debounced indefinitely 
(examples below), because every time an event is received, the debounce interval is extended.** All 
callbacks are run on the debouncer's executor service thread, so they should not be long running as that 
would block other callbacks from being run and could even block your calling thread to add another event 
- ideally the user will call another thread to run the callback's actual code.

In most cases the user will always call the same debounce type (e.g. run-first or run-last) in the same place, 
but this is not a strict requirement and the user could call `addRunLast("key", k -> {})` then `addRunFirst(...)` 
which would reset the debounce interval, but still call the run last callback (see below for more information on 
this behavior). 

*For the examples below assume threads sleep for the exact amount of time. This, of course, is never really 
the case and threads will sleep for a minimum of the sleep time, but due to scheduling they will not kick 
back in till some time after the specified sleep time.*

### Tasks
These are created for each `key` supplied to the debouncer. A task is active until the debounce interval or 
the forced-timeout has expired and the task's callback has been run. The next call for the same `key` will 
create a new task. A task's timeout and forced-timeout cannot be changed once it is created and subsequent 
attempts to debounce an event for a given key will extend the debounce interval by the initial timeout amount.
The forced-timeout will never be changed from the event's initial creation. The callback will be changed 
if a subsequent call of type `runLast` is made while a task is active.

**Subsequent calls for the same `key` while a task is still active will ALWAYS extend expiration time.**

### Run Immediately (`addRunImmediately(...)`)
This will immediately run the callback and will not allow any other callbacks for the given event `key`
to run until the debounce interval has expired. 

Calls after `runImmediately` (while task is active):
`runImmediately`    - will not run anything because a task is active
`runFirst`          - will not run anything, because a task is active.
`runLast`           - will run the last `runLast`'s callback when the interval expires

```java
Debouncer debouncer = new Debouncer(1);
debouncer.addRunImmediately(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This prints immediately.");
});
debouncer.addRunImmediately(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This will not print at all if submitted within 10ms.");
});
```

### Run First (`addRunFirst(...)`)
This will run the first callback for the given event `key` when the debounce interval expires.

Calls after `runFirst` (while task is active):
`runImmediately`    - will not run anything because a task is active
`runFirst`          - will not run anything, because a task is active.
`runLast`           - will run the last `runLast`'s callback when the interval expires, discarding the initial `runFirst` callback

```java
Debouncer debouncer = new Debouncer(1);
debouncer.addRunFirst(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This prints when the debounce interval expires. Any other " +
   "events added for the same key will not be run until this expires. So long as " +
   "addRunFirst() or addRunImmediately() is used, this will not be overridden. " +
   "However, this will be overridden if addRunLast() is used before the " +
   "debounce interval expires.");
});
Thread.sleep(5);
/*
This will not extend the debounce interval by 15ms, but only by 10, the original 
interval set above.
*/
debouncer.addRunFirst(15, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This will not print at all if submitted within 10ms.");
});

```

### Run Last (`addRunLast(...)`)
This will run the last callback for the given event `key` when the debounce interval expires.

Calls after `runFirst` (while task is active):
`runImmediately`    - will not run anything because a task is active
`runFirst`          - will not run anything, because a task is active.
`runLast`           - will discard the most recent callback and override with the latest

```java
Debouncer debouncer = new Debouncer(1);
debouncer.addRunLast(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This will only be run if addRunLast() is called again before " +
   "the debounce interval expires.");
});
Thread.sleep(5);
/*
This will not extend the debounce interval by 15ms, but only by 10, the original 
interval set above.
*/
debouncer.addRunLast(15, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This will print in 10ms. Note, it is not after 15ms.");
});
```

### Run Immediately and Run Last (`addRunImmediatelyAndRunLast(...)`)
This is provided out of convenience so the user can always make the same call and does not have to make conditional 
calls. It would be the same as calling `addRunImmediately` and then when another task for the given event arrives 
calling `addRunLast`, but avoids the user having to keep track of whether or not they need to call `addRunImmediately` 
or `addRunLast`. 

```java
Debouncer debouncer = new Debouncer(1);
debouncer.addRunImmediatelyAndRunLast(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This is called immediately. If no other events for the " +
   "key are submitted nothing is run again.");
});
// if another event is submitted, the last callback will be run
debouncer.addRunImmediatelyAndRunLast(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This is not run, because overridden below.");
});
debouncer.addRunImmediatelyAndRunLast(10, TimeUnit.MILLISECONDS, "key", k -> {
  System.out.println("This is run 10ms from now.");
});
```

**Note there is not a `runImmediatelyAndRunFirst`, because `runFirst` never overrides the callback for an active task.**
