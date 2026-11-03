package eu.wohlben.qits.http;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * The one retry policy every service-to-service call on the platform shares: patience for an edge
 * switchover, and a clear failure for anything longer.
 *
 * <p><b>Why.</b> Services call each other through the public edge. The edge updates start-first,
 * but the routing mesh can drop a connection when the old task stops, and a not-yet-ready edge
 * answers {@code 503} with {@code Retry-After: 1}. A caller that gives up on the first such answer
 * turns a two-second rollover into a failed request; a caller that waits forever turns an outage
 * into a hang. This waits {@value #BUDGET_SECONDS} seconds at most.
 *
 * <p><b>What is retried.</b>
 *
 * <ul>
 *   <li>A failure <b>before the request was sent</b> — connection refused, a connect timeout, a
 *       TLS handshake failure — for every method: nothing reached the peer, so a second attempt
 *       cannot be a second effect.
 *   <li>A connection <b>reset before a response</b> (a reset, a broken pipe, an EOF, "header parser
 *       received no bytes") for {@code GET}, {@code HEAD}, {@code PUT} and {@code DELETE} only: the
 *       request may have reached the peer, which only an idempotent method survives.
 *   <li>A {@code 502}, {@code 503} or {@code 504} <b>that carries {@code Retry-After}</b>, for the
 *       same four methods. The header is what tells a switchover from a real failure: the edge
 *       sends it while it is not ready, and a {@code 503} without it is an application's answer to
 *       be read, not waited out. {@code POST} and {@code PATCH} are never retried once a response
 *       arrived.
 * </ul>
 *
 * <p><b>What is not.</b> An unresolvable host (a DNS failure is a wrong name or a missing service,
 * not a switchover, even though {@code java.net.http} reports it as a {@link ConnectException}); a
 * request timeout (the request may be half-processed); any other status; and any request whose body
 * cannot be sent twice, or that opens an event stream — SSE reconnection belongs to its own code.
 *
 * <p><b>Nothing to the process's own host.</b> A call to a loopback target — {@code localhost} (or
 * a {@code *.localhost} name), {@code 127.0.0.0/8}, {@code ::1} — never crosses the edge: the
 * platform's rule is that a service reaches itself at {@code http://localhost:8080}, so there is no
 * switchover to ride out, and a refusal there is a process that is not listening. Such a call gets
 * one attempt. The check reads the URI's host literally and never resolves a name. {@link
 * #withLoopbackRetries()} opts back in, for a test whose stand-in for the edge is a loopback server.
 *
 * <p><b>How long.</b> The first pause is {@value #FIRST_BACKOFF_MILLIS} ms and each one doubles.
 * A {@code Retry-After} in seconds raises a pause to at least that long; one that points past the
 * remaining budget ends the retries at once, because waiting less than the server asked is pointless
 * and waiting longer breaks the promise. A pause is cut to whatever budget remains, and no attempt
 * starts after the budget ({@value #BUDGET_SECONDS} s from the first attempt) is spent — the last
 * error is raised, or the last response returned, unchanged. {@value #BUDGET_PROPERTY} overrides the
 * budget ({@code 0} turns retrying off).
 *
 * <p><b>What it says.</b> Nothing for a call that succeeded first time. A call that needed a second
 * attempt logs ONE warning when it is over — succeeded or given up — naming the method, the host,
 * the number of attempts, the time spent and the outcome. Single attempts are DEBUG.
 */
public final class RetryPolicy {

  static final long FIRST_BACKOFF_MILLIS = 250;
  static final long BUDGET_SECONDS = 30;

  /** The budget, as a Duration ({@code PT10S}) or a number with a unit ({@code 10s}, {@code 500ms}). */
  public static final String BUDGET_PROPERTY = "qits.http.retry.budget";

  static final String RETRY_AFTER = "Retry-After";

  private static final Logger LOG = Logger.getLogger(RetryPolicy.class);
  private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "PUT", "DELETE");
  private static final Set<Integer> SWITCHOVER_STATUSES = Set.of(502, 503, 504);
  private static final int MAX_CAUSE_DEPTH = 16;
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
  private static final Pattern SHORT_DURATION =
      Pattern.compile("(\\d+)\\s*(ms|s|m)?", Pattern.CASE_INSENSITIVE);

  /** The clock and the pauses, so a test runs the 30-second budget in no time. */
  public interface Timing {

    long nanoTime();

    void sleep(Duration pause) throws InterruptedException;

    /** The asynchronous pause: completes once {@code pause} has passed. */
    CompletableFuture<Void> delay(Duration pause);

    Timing SYSTEM =
        new Timing() {
          @Override
          public long nanoTime() {
            return System.nanoTime();
          }

          @Override
          public void sleep(Duration pause) throws InterruptedException {
            TimeUnit.NANOSECONDS.sleep(pause.toNanos());
          }

          @Override
          public CompletableFuture<Void> delay(Duration pause) {
            return CompletableFuture.runAsync(
                () -> {},
                CompletableFuture.delayedExecutor(pause.toNanos(), TimeUnit.NANOSECONDS));
          }
        };
  }

  /** One exchange; {@code attempt} counts from 1. */
  @FunctionalInterface
  public interface Attempt<R, X extends Exception> {
    R send(int attempt) throws X, InterruptedException;
  }

  /** What the policy needs to know about a response, and how to let go of one it retries past. */
  public interface Responses<R> {
    int status(R response);

    Optional<String> header(R response, String name);

    /** Releases a response that is being retried past: its body is never read. */
    void discard(R response);
  }

  /** Where a transport failure happened, which is what decides whether it may be retried. */
  enum Failure {
    /** Nothing reached the peer: refused, connect timeout, TLS handshake. Every method. */
    BEFORE_SEND,
    /** The connection died with no response: reset, broken pipe, EOF. Idempotent methods. */
    BEFORE_RESPONSE,
    /** Not a switchover. Never retried. */
    OTHER
  }

  private final Duration firstBackoff;
  private final Duration budget;
  private final Timing timing;
  private final Consumer<String> warnings;
  private final boolean loopbackRetries;

  private RetryPolicy(
      Duration firstBackoff,
      Duration budget,
      Timing timing,
      Consumer<String> warnings,
      boolean loopbackRetries) {
    this.loopbackRetries = loopbackRetries;
    this.firstBackoff = firstBackoff;
    this.budget = budget;
    this.timing = timing;
    this.warnings = warnings;
  }

  /** 250 ms doubling, 30 s budget, real time, warnings to this class's logger. */
  public static RetryPolicy standard() {
    return new RetryPolicy(
        Duration.ofMillis(FIRST_BACKOFF_MILLIS),
        Duration.ofSeconds(BUDGET_SECONDS),
        Timing.SYSTEM,
        LOG::warn,
        false);
  }

  /** One attempt and no retry: the raw client's behaviour. */
  public static RetryPolicy none() {
    return standard().withBudget(Duration.ZERO);
  }

  /**
   * {@link #standard()} with the budget {@value #BUDGET_PROPERTY} sets, when it sets one. Outside
   * an application with an MP-config implementation, the standard policy.
   */
  public static RetryPolicy fromConfig() {
    Optional<String> configured;
    try {
      configured = ConfigProvider.getConfig().getOptionalValue(BUDGET_PROPERTY, String.class);
    } catch (RuntimeException | LinkageError noConfig) {
      configured = Optional.empty();
    }
    return configured
        .map(String::trim)
        .filter(v -> !v.isEmpty())
        .map(v -> standard().withBudget(parseBudget(v)))
        .orElseGet(RetryPolicy::standard);
  }

  public RetryPolicy withBudget(Duration budget) {
    if (budget.isNegative()) {
      throw new IllegalArgumentException("a retry budget cannot be negative: " + budget);
    }
    return new RetryPolicy(firstBackoff, budget, timing, warnings, loopbackRetries);
  }

  public RetryPolicy withTiming(Timing timing) {
    return new RetryPolicy(firstBackoff, budget, timing, warnings, loopbackRetries);
  }

  /** Where the one warning per retried call goes; the logger unless a test listens instead. */
  RetryPolicy withWarnings(Consumer<String> warnings) {
    return new RetryPolicy(firstBackoff, budget, timing, warnings, loopbackRetries);
  }

  /**
   * The same policy, retrying loopback targets too. For a test that stands a local server in for
   * the edge; a service has no use for it, since its loopback calls never cross one.
   */
  public RetryPolicy withLoopbackRetries() {
    return new RetryPolicy(firstBackoff, budget, timing, warnings, true);
  }

  /**
   * Whether a call to {@code uri} may be retried at all: a positive budget, and a target that is
   * not this process's own host unless {@link #withLoopbackRetries()} said otherwise.
   */
  public boolean retries(URI uri) {
    return !budget.isZero() && (loopbackRetries || !loopback(uri));
  }

  /**
   * {@code localhost}, a {@code *.localhost} name, {@code 127.0.0.0/8} or {@code ::1}, read off the
   * URI as written. No name is resolved: a DNS lookup to decide whether to retry would be a network
   * call of its own.
   */
  static boolean loopback(URI uri) {
    if (uri == null || uri.getHost() == null) {
      return false;
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.equals("localhost") || host.endsWith(".localhost")) {
      return true;
    }
    if (IPV4.matcher(host).matches()) {
      return host.startsWith("127.");
    }
    if (host.indexOf(':') >= 0) {
      try {
        // A literal: getByName parses it and does no lookup.
        return java.net.InetAddress.getByName(host).isLoopbackAddress();
      } catch (java.net.UnknownHostException | RuntimeException notALiteral) {
        return false;
      }
    }
    return false;
  }

  public Duration budget() {
    return budget;
  }

  static Duration parseBudget(String value) {
    if (value.regionMatches(true, 0, "P", 0, 1)) {
      return Duration.parse(value);
    }
    Matcher m = SHORT_DURATION.matcher(value);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          BUDGET_PROPERTY + " must be a Duration (PT10S) or a number with ms, s or m: " + value);
    }
    long amount = Long.parseLong(m.group(1));
    String unit = m.group(2) == null ? "s" : m.group(2).toLowerCase(Locale.ROOT);
    return switch (unit) {
      case "ms" -> Duration.ofMillis(amount);
      case "m" -> Duration.ofMinutes(amount);
      default -> Duration.ofSeconds(amount);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Classification

  /** {@code GET}, {@code HEAD}, {@code PUT}, {@code DELETE}: the methods a resend cannot double. */
  public static boolean idempotent(String method) {
    return method != null && IDEMPOTENT.contains(method.toUpperCase(Locale.ROOT));
  }

  /** A 502, 503 or 504 that says when to come back: the edge's switchover answer. */
  public static boolean switchoverStatus(int status, boolean hasRetryAfter) {
    return hasRetryAfter && SWITCHOVER_STATUSES.contains(status);
  }

  /**
   * A {@code Retry-After} as seconds; empty for an HTTP-date or anything unreadable, which still
   * counts as present and is waited out with the ordinary backoff.
   */
  static OptionalLong retryAfterSeconds(String value) {
    if (value == null) {
      return OptionalLong.empty();
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(trimmed));
    } catch (NumberFormatException tooLarge) {
      return OptionalLong.of(Long.MAX_VALUE);
    }
  }

  /** Where in the exchange {@code thrown} happened, read off its whole cause chain. */
  static Failure classify(Throwable thrown) {
    // Disqualifiers first: java.net.http reports an unresolvable host as a ConnectException with
    // an UnresolvedAddressException inside, and a request timeout is no evidence of anything.
    for (Throwable t : chain(thrown)) {
      if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
        return Failure.OTHER;
      }
      if (t instanceof HttpTimeoutException && !(t instanceof HttpConnectTimeoutException)) {
        return Failure.OTHER;
      }
    }
    for (Throwable t : chain(thrown)) {
      if (t instanceof ConnectException
          || t instanceof HttpConnectTimeoutException
          || t instanceof SSLHandshakeException) {
        return Failure.BEFORE_SEND;
      }
    }
    for (Throwable t : chain(thrown)) {
      if (t instanceof SocketException || t instanceof EOFException || closedConnection(t)) {
        return Failure.BEFORE_RESPONSE;
      }
    }
    return Failure.OTHER;
  }

  /**
   * The spellings of "the peer went away" that are not a {@link SocketException}: java.net.http's
   * plain IOExceptions, and Vert.x' {@code HttpClosedException} (the REST client's transport).
   */
  private static boolean closedConnection(Throwable t) {
    if ("io.vertx.core.http.HttpClosedException".equals(t.getClass().getName())) {
      return true;
    }
    if (!(t instanceof IOException) || t.getMessage() == null) {
      return false;
    }
    String message = t.getMessage().toLowerCase(Locale.ROOT);
    return message.contains("header parser received no bytes")
        || message.contains("connection reset")
        || message.contains("connection closed")
        || message.contains("connection was closed");
  }

  private static Iterable<Throwable> chain(Throwable thrown) {
    java.util.List<Throwable> causes = new java.util.ArrayList<>();
    Throwable t = thrown;
    for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
      causes.add(t);
      if (t.getCause() == t) {
        break;
      }
      t = t.getCause();
    }
    return causes;
  }

  // ---------------------------------------------------------------------------------------------
  // The loop

  /**
   * Runs {@code attempt} until it answers with something not worth retrying or the budget is
   * spent, and returns the last response or raises the last failure, unchanged.
   *
   * @param replayable whether the request can be sent again at all — a body that cannot be
   *     re-read, or an event stream, makes the first attempt the only one
   */
  public <R, X extends Exception> R execute(
      String method,
      URI uri,
      boolean replayable,
      Attempt<R, ? extends X> attempt,
      Responses<? super R> responses)
      throws X, InterruptedException {
    return run(
        method, uri, replayable && retries(uri), attempt, responses, null, timing.nanoTime());
  }

  /**
   * The same loop for a caller that already holds the first response — a REST-client response
   * filter, which sees the exchange only after it happened. {@code next} is asked for attempt 2
   * onwards. The first attempt's start is unknown, so the budget counts from now, which overstates
   * it by one round trip at most.
   */
  public <R, X extends Exception> R resume(
      String method,
      URI uri,
      R first,
      Attempt<R, ? extends X> next,
      Responses<? super R> responses)
      throws X, InterruptedException {
    return run(method, uri, retries(uri), next, responses, first, timing.nanoTime());
  }

  private <R, X extends Exception> R run(
      String method,
      URI uri,
      boolean replayable,
      Attempt<R, ? extends X> attempt,
      Responses<? super R> responses,
      R first,
      long started)
      throws X, InterruptedException {
    int n = 1;
    R response = first;
    Throwable failure = null;
    if (first == null) {
      try {
        response = attempt.send(n);
      } catch (InterruptedException interrupted) {
        throw interrupted;
      } catch (Exception e) {
        failure = e;
      }
    }
    while (true) {
      Duration pause = replayable ? pause(method, response, failure, responses, n, started) : null;
      if (pause == null) {
        report(method, uri, n, started, response, failure, responses);
        if (failure != null) {
          throw sneaky(failure);
        }
        return response;
      }
      LOG.debugf(
          "%s %s: attempt %d answered %s, retrying in %d ms",
          method, host(uri), n, outcome(response, failure, responses), pause.toMillis());
      if (response != null) {
        discardQuietly(responses, response);
      }
      try {
        timing.sleep(pause);
      } catch (InterruptedException interrupted) {
        report(method, uri, n, started, null, interrupted, responses);
        throw interrupted;
      }
      n++;
      response = null;
      failure = null;
      try {
        response = attempt.send(n);
      } catch (InterruptedException interrupted) {
        report(method, uri, n, started, null, interrupted, responses);
        throw interrupted;
      } catch (Exception e) {
        failure = e;
      }
    }
  }

  /**
   * The asynchronous loop: {@code attempt} is asked for a fresh exchange each time, and the
   * returned future completes with the last response or the last failure. Cancelling it cancels
   * the exchange in flight and stops the retries.
   */
  public <R> CompletableFuture<R> executeAsync(
      String method,
      URI uri,
      boolean replayable,
      IntFunction<CompletableFuture<R>> attempt,
      Responses<? super R> responses) {
    CompletableFuture<R> result = new CompletableFuture<>();
    AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
    result.whenComplete(
        (r, t) -> {
          CompletableFuture<?> current = inFlight.get();
          if (result.isCancelled() && current != null) {
            current.cancel(true);
          }
        });
    attemptAsync(
        method,
        uri,
        replayable && retries(uri),
        attempt,
        responses,
        1,
        timing.nanoTime(),
        result,
        inFlight);
    return result;
  }

  private <R> void attemptAsync(
      String method,
      URI uri,
      boolean replayable,
      IntFunction<CompletableFuture<R>> attempt,
      Responses<? super R> responses,
      int n,
      long started,
      CompletableFuture<R> result,
      AtomicReference<CompletableFuture<?>> inFlight) {
    if (result.isDone()) {
      return;
    }
    CompletableFuture<R> exchange;
    try {
      exchange = attempt.apply(n);
    } catch (RuntimeException | Error e) {
      exchange = CompletableFuture.failedFuture(e);
    }
    inFlight.set(exchange);
    exchange.whenComplete(
        (response, thrown) -> {
          Throwable failure = unwrap(thrown);
          if (result.isDone()) {
            if (response != null) {
              discardQuietly(responses, response);
            }
            return;
          }
          Duration pause =
              replayable ? pause(method, response, failure, responses, n, started) : null;
          if (pause == null) {
            report(method, uri, n, started, response, failure, responses);
            if (failure != null) {
              result.completeExceptionally(failure);
            } else {
              result.complete(response);
            }
            return;
          }
          LOG.debugf(
              "%s %s: attempt %d answered %s, retrying in %d ms",
              method, host(uri), n, outcome(response, failure, responses), pause.toMillis());
          if (response != null) {
            discardQuietly(responses, response);
          }
          CompletableFuture<Void> wait = timing.delay(pause);
          inFlight.set(wait);
          wait.whenComplete(
              (ignored, waitFailure) -> {
                if (waitFailure != null) {
                  report(method, uri, n, started, null, failure, responses);
                  result.completeExceptionally(failure != null ? failure : waitFailure);
                } else {
                  attemptAsync(
                      method, uri, replayable, attempt, responses, n + 1, started, result,
                      inFlight);
                }
              });
        });
  }

  /**
   * The pause before attempt {@code n + 1}, or {@code null} when attempt {@code n}'s outcome is
   * final. Exactly one of {@code response} and {@code failure} is set.
   */
  private <R> Duration pause(
      String method,
      R response,
      Throwable failure,
      Responses<? super R> responses,
      int n,
      long started) {
    if (!worthRetrying(method, response, failure, responses)) {
      return null;
    }
    OptionalLong retryAfter =
        failure != null
            ? OptionalLong.empty()
            : retryAfterSeconds(responses.header(response, RETRY_AFTER).orElse(null));
    long remaining = budget.toNanos() - (timing.nanoTime() - started);
    if (remaining <= 0) {
      return null;
    }
    long pause = backoffNanos(n);
    if (retryAfter.isPresent()) {
      long asked = retryAfter.getAsLong() >= Long.MAX_VALUE / 1_000_000_000L
          ? Long.MAX_VALUE
          : TimeUnit.SECONDS.toNanos(retryAfter.getAsLong());
      if (asked > remaining) {
        return null;
      }
      pause = Math.max(pause, asked);
    }
    return Duration.ofNanos(Math.min(pause, remaining));
  }

  /** 250 ms, 500 ms, 1 s, … — doubling from the first pause, never overflowing. */
  private long backoffNanos(int n) {
    int shift = Math.min(n - 1, 30);
    long first = firstBackoff.toNanos();
    return first > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : first << shift;
  }

  private <R> void report(
      String method,
      URI uri,
      int attempts,
      long started,
      R response,
      Throwable failure,
      Responses<? super R> responses) {
    if (attempts <= 1) {
      return;
    }
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(timing.nanoTime() - started);
    // "gave up": still a switchover answer when the budget ran out. "got through": the peer
    // answered with something else. "failed": a failure this policy does not wait out.
    String verdict;
    if (failure instanceof InterruptedException) {
      verdict = "was interrupted";
    } else if (worthRetrying(method, response, failure, responses)) {
      verdict = "gave up";
    } else {
      verdict = failure == null ? "got through" : "failed";
    }
    warnings.accept(
        String.format(
            "%s %s %s after %d attempts in %d ms (retry budget %d ms): %s",
            method,
            host(uri),
            verdict,
            attempts,
            elapsedMillis,
            budget.toMillis(),
            outcome(response, failure, responses)));
  }

  /** Whether attempt's outcome is one this policy waits out — budget aside. */
  private static <R> boolean worthRetrying(
      String method, R response, Throwable failure, Responses<? super R> responses) {
    if (failure != null) {
      Failure where = classify(failure);
      return where == Failure.BEFORE_SEND
          || (where == Failure.BEFORE_RESPONSE && idempotent(method));
    }
    return response != null
        && idempotent(method)
        && switchoverStatus(
            responses.status(response), responses.header(response, RETRY_AFTER).isPresent());
  }

  private static <R> String outcome(R response, Throwable failure, Responses<? super R> responses) {
    if (failure != null) {
      return failure.getClass().getName()
          + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
    return response == null ? "nothing" : "HTTP " + responses.status(response);
  }

  static String host(URI uri) {
    if (uri == null || uri.getHost() == null) {
      return String.valueOf(uri);
    }
    return uri.getPort() >= 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
  }

  private static <R> void discardQuietly(Responses<? super R> responses, R response) {
    try {
      responses.discard(response);
    } catch (RuntimeException ignored) {
      // A body we are walking away from; nothing to report.
    }
  }

  private static Throwable unwrap(Throwable thrown) {
    Throwable t = thrown;
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  /** Rethrows the attempt's own exception, checked or not, without wrapping it. */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException sneaky(Throwable thrown) throws E {
    throw (E) thrown;
  }
}
