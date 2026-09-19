package myfinances.application.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import myfinances.application.loan.LoanEditPlanner.Decision;
import myfinances.domain.loan.journal.LoanType;
import myfinances.domain.loan.projection.LoanSummaryProjection;
import myfinances.domain.loan.snapshot.LoanStatus;
import org.junit.jupiter.api.Test;

/**
 * Sprint 7B — decisión explícita de comandos en la edición de préstamos.
 * Verifica que solo se emitan los comandos que representan un cambio efectivo.
 */
class LoanEditPlannerTest {

    private static LoanSummaryProjection summary(long principal, String account,
                                                 String counterparty, String notes) {
        return new LoanSummaryProjection(
            "loan-1", "owner-1", counterparty, LoanType.LENT, "COP",
            account, notes, principal, 0L, principal, 0L, 0, null, 0,
            LoanStatus.OPEN, null, 0L, "fp");
    }

    private static LoanSummaryProjection summary() {
        return summary(100_000L, "acc-1", "Ana", null);
    }

    @Test
    void onlyPrincipalChangedEmitsOnlyAdjustment() {
        Decision d = LoanEditPlanner.decide(summary(), "Ana", "acc-1", 150_000L, null);
        assertTrue(d.principalChanged());
        assertEquals(50_000L, d.deltaCents());
        assertFalse(d.metadataChanged());
        assertTrue(d.hasChanges());
    }

    @Test
    void onlyAccountChangedEmitsOnlyMetadata() {
        Decision d = LoanEditPlanner.decide(summary(), "Ana", "acc-2", 100_000L, null);
        assertFalse(d.principalChanged());
        assertTrue(d.accountChanged());
        assertTrue(d.metadataChanged());
        assertFalse(d.notesChanged());
    }

    @Test
    void onlyNotesChangedEmitsOnlyMetadata() {
        Decision d = LoanEditPlanner.decide(
            summary(100_000L, "acc-1", "Ana", "vieja"), "Ana", "acc-1", 100_000L, "nueva");
        assertFalse(d.principalChanged());
        assertTrue(d.notesChanged());
        assertEquals("nueva", d.notes());
        assertTrue(d.metadataChanged());
    }

    @Test
    void bothChangedEmitsBothCommands() {
        Decision d = LoanEditPlanner.decide(summary(), "Ana", "acc-2", 80_000L, null);
        assertTrue(d.principalChanged());
        assertEquals(-20_000L, d.deltaCents());
        assertTrue(d.metadataChanged());
    }

    @Test
    void noChangesEmitsNothing() {
        Decision d = LoanEditPlanner.decide(
            summary(100_000L, "acc-1", "Ana", "nota"), "Ana", "acc-1", 100_000L, "nota");
        assertFalse(d.hasChanges());
    }

    @Test
    void blankNotesAreNotAChange() {
        Decision d = LoanEditPlanner.decide(
            summary(100_000L, "acc-1", "Ana", "nota"), "Ana", "acc-1", 100_000L, "   ");
        assertFalse(d.notesChanged());
        assertFalse(d.hasChanges());
    }

    /** Doble Guardar: decidir de nuevo contra el estado actualizado no emite nada. */
    @Test
    void secondSaveAgainstUpdatedStateEmitsNothing() {
        Decision first = LoanEditPlanner.decide(summary(), "Ana", "acc-1", 150_000L, null);
        assertTrue(first.principalChanged());

        LoanSummaryProjection updated = summary(150_000L, "acc-1", "Ana", null);
        Decision second = LoanEditPlanner.decide(updated, "Ana", "acc-1", 150_000L, null);
        assertFalse(second.hasChanges());
        assertEquals(0L, second.deltaCents());
    }

    @Test
    void nullPrincipalMeansUnchanged() {
        Decision d = LoanEditPlanner.decide(summary(), "Ana", "acc-1", null, null);
        assertFalse(d.principalChanged());
        assertNull(d.notes());
        assertFalse(d.hasChanges());
    }
}
