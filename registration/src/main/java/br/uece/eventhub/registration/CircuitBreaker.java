package br.uece.eventhub.registration;

import java.util.function.Supplier;

/**
 * Circuit Breaker simples (CLOSED -> OPEN -> HALF_OPEN).
 * Abre apos N falhas consecutivas; enquanto aberto falha rapido, sem chamar o servico remoto.
 */
final class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    static final class OpenException extends RuntimeException {
        OpenException(String name) { super("circuito " + name + " aberto"); }
    }

    private final String name;
    private final int failureThreshold;
    private final long openMillis;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0;

    CircuitBreaker(String name, int failureThreshold, long openMillis) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    <T> T call(Supplier<T> remote) {
        beforeCall();
        try {
            T result = remote.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure();
            throw e;
        }
    }

    private synchronized void beforeCall() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt < openMillis) throw new OpenException(name);
            transition(State.HALF_OPEN);
        }
    }

    private synchronized void onSuccess() {
        consecutiveFailures = 0;
        if (state != State.CLOSED) transition(State.CLOSED);
    }

    private synchronized void onFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            openedAt = System.currentTimeMillis();
            if (state != State.OPEN) transition(State.OPEN);
        }
    }

    private void transition(State next) {
        Log.info("[circuit-breaker] " + name + ": " + state + " -> " + next);
        state = next;
    }

    synchronized State state() {
        return state;
    }
}
