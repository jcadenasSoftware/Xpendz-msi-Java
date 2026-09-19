package com.myfinaces.ui;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Guardia de ejecución única para reversión de pagos.
 *
 * Un pago solo puede tener una reversión en vuelo: tryAcquire devuelve false
 * si el mismo paymentEventId ya está siendo revertido, por lo que doble clic
 * o handlers duplicados nunca producen un segundo comando.
 */
public final class PaymentReversalGuard {

    private final Set<String> inFlight = new LinkedHashSet<>();

    public synchronized boolean tryAcquire(String paymentEventId) {
        return inFlight.add(paymentEventId);
    }

    public synchronized void release(String paymentEventId) {
        inFlight.remove(paymentEventId);
    }

    public synchronized boolean isInFlight(String paymentEventId) {
        return inFlight.contains(paymentEventId);
    }
}
