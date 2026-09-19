package myfinances.application.loan;

import java.util.Objects;
import myfinances.domain.loan.projection.LoanSummaryProjection;

/**
 * Decide qué comandos debe emitir la edición de un préstamo comparando los
 * valores nuevos contra la proyección actual, antes de llamar a
 * LoanApplicationService.
 *
 * Reglas:
 * - principalChanged  -> AdjustPrincipalCommand
 * - metadataChanged   -> UpdateMetadataCommand (solo con los campos que difieren)
 * - sin cambios       -> ningún comando
 *
 * Así un ajuste de solo monto nunca produce un UpdateMetadataCommand que el
 * aggregate rechazaría con NO_EFFECTIVE_CHANGE, y un "Guardar" repetido sobre
 * el mismo estado no vuelve a calcular deltas.
 */
public final class LoanEditPlanner {

    private LoanEditPlanner() {}

    public record Decision(
        boolean principalChanged,
        long deltaCents,
        boolean counterpartyChanged,
        String counterpartyName,
        boolean accountChanged,
        String accountId,
        boolean notesChanged,
        String notes
    ) {
        public boolean metadataChanged() {
            return counterpartyChanged || accountChanged || notesChanged;
        }

        public boolean hasChanges() {
            return principalChanged || metadataChanged();
        }
    }

    public static Decision decide(
        long currentPrincipalCents,
        String currentAccountId,
        String currentNotes,
        String counterpartyName,
        String accountId,
        Long principalCents,
        String notes
    ) {
        boolean principalChanged = principalCents != null && principalCents != currentPrincipalCents;
        String counterpartyValue = normalize(counterpartyName);
        String notesValue = normalize(notes);
        return new Decision(
            principalChanged,
            principalChanged ? principalCents - currentPrincipalCents : 0L,
            false,
            counterpartyValue,
            accountId != null && !Objects.equals(accountId, currentAccountId),
            accountId,
            notesValue != null && !Objects.equals(notesValue, currentNotes),
            notesValue
        );
    }

    public static Decision decide(
        LoanSummaryProjection existing,
        String counterpartyName,
        String accountId,
        Long principalCents,
        String notes
    ) {
        String counterpartyValue = normalize(counterpartyName);
        Decision base = decide(
            existing.principalCents(),
            existing.defaultAccountId(),
            existing.notes(),
            counterpartyName,
            accountId,
            principalCents,
            notes
        );
        return new Decision(
            base.principalChanged(),
            base.deltaCents(),
            counterpartyValue != null && !counterpartyValue.equals(existing.counterparty()),
            counterpartyValue,
            base.accountChanged(),
            base.accountId(),
            base.notesChanged(),
            base.notes()
        );
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
