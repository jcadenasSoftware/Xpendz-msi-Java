package myfinances.infrastructure.loan.migration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Identidad compartida para eventos canónicos reconstruidos desde transporte
 * (replay, migración legacy, reconciliación de pagos). El mismo material
 * produce el mismo operationId/eventId en Android y Desktop, de modo que un
 * documento {@code loanPayments/{id}} mantiene identidad estable en todos los
 * dispositivos y puede ser eliminado por reversión desde cualquiera.
 */
public final class CanonicalLoanEventIds {

    private static final String REPLAY_TAG = "historical-loan-replay-v1";

    private CanonicalLoanEventIds() {
    }

    /**
     * operationId para un pago transportado: el docId de {@code loanPayments}
     * ya es el eventId canónico del dispositivo origen, así que se adopta
     * directamente cuando es un UUID v4/v7 válido. Para filas legacy con ids no
     * UUID se cae al id determinístico compartido.
     */
    public static String forTransportPayment(String loanId, String paymentId, long occurredAtEpochSec) {
        if (isShareableUuid(paymentId)) {
            return paymentId;
        }
        return deterministic(loanId, "PAYMENT", "pay:" + paymentId, occurredAtEpochSec);
    }

    /** operationId determinístico para cualquier evento reconstruido (mov:/tx:/synth:/loan:). */
    public static String deterministic(String loanId, String eventType, String sourceKey, long occurredAtEpochSec) {
        String material = loanId + "|" + eventType + "|" + sourceKey + "|" + occurredAtEpochSec + "|" + REPLAY_TAG;
        UUID base = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
        long msb = (base.getMostSignificantBits() & 0xffffffffffff0fffL) | 0x0000000000004000L;
        long lsb = (base.getLeastSignificantBits() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    /**
     * Id determinístico que {@code CanonicalLoanTransactionBackfill} asigna a la
     * transacción materializada de un evento sin {@code transaction_id}.
     */
    public static String deterministicTransactionId(String eventId) {
        return UUID.nameUUIDFromBytes(
            ("canonical-loan-tx:" + eventId).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    /** true si el valor ya es un UUID v4/v7 en formato canónico. */
    public static boolean isShareableUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && (parsed.version() == 4 || parsed.version() == 7);
        } catch (Exception e) {
            return false;
        }
    }
}
