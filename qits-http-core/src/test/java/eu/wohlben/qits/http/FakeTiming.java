package eu.wohlben.qits.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Virtual time: a pause is recorded and moves the clock, and takes no time at all. Attempts take
 * none either, so the pauses alone account for the budget and the arithmetic is exact.
 */
final class FakeTiming implements RetryPolicy.Timing {

  private long now = 1_000_000_000L;
  private final List<Duration> pauses = Collections.synchronizedList(new ArrayList<>());
  private Runnable onPause = () -> {};

  @Override
  public synchronized long nanoTime() {
    return now;
  }

  @Override
  public void sleep(Duration pause) {
    advance(pause);
  }

  @Override
  public CompletableFuture<Void> delay(Duration pause) {
    advance(pause);
    return CompletableFuture.completedFuture(null);
  }

  private void advance(Duration pause) {
    synchronized (this) {
      now += pause.toNanos();
    }
    pauses.add(pause);
    onPause.run();
  }

  /** Runs after every pause — to bring a peer back mid-retry, say. */
  FakeTiming onPause(Runnable hook) {
    this.onPause = hook;
    return this;
  }

  List<Duration> pauses() {
    return List.copyOf(pauses);
  }

  Duration total() {
    return pauses().stream().reduce(Duration.ZERO, Duration::plus);
  }
}
