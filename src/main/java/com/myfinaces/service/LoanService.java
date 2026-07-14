package com.myfinaces.service;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.config.AppConfig;
import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.LoanMovementRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionKind;
import com.myfinaces.db.TransactionRepository;
import com.myfinaces.sync.FirestoreSyncService;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Servicio centralizado para operaciones financieras de préstamos.
 * <p>
 * Modelo acumulativo: UN préstamo activo por persona + dirección financiera.
 * Si la misma persona solicita más dinero, se acumula sobre el préstamo existente.
 * <p>
 * Cada acción se registra como un {@link LoanMovementRepository.LoanMovement}
 * para mantener un historial completo y un timeline financiero.
 * <p>
 * Flujos:
 * <ul>
 *   <li>{@link #createLoan} — PRESTAR o PEDIR PRESTADO (crea o acumula)</li>
 *   <li>{@link #registerPayment} — RECIBIR PAGO o PAGAR DEUDA</li>
 * </ul>
 */
public final class LoanService {

    private static final String SYSTEM_LOAN_CAT_PREFIX = "sys_loan_";
    private static final String SYSTEM_REPAYMENT_CAT_PREFIX = "sys_repayment_";

    private final LoanRepository loanRepo;
    private final LoanPaymentRepository paymentRepo;
    private final LoanMovementRepository movementRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final CategoryRepository categoryRepo;

    public LoanService(
        LoanRepository loanRepo,
        LoanPaymentRepository paymentRepo,
        LoanMovementRepository movementRepo,
        AccountRepository accountRepo,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo
    ) {
        this.loanRepo = Objects.requireNonNull(loanRepo);
        this.paymentRepo = Objects.requireNonNull(paymentRepo);
        this.movementRepo = Objects.requireNonNull(movementRepo);
        this.accountRepo = Objects.requireNonNull(accountRepo);
        this.txRepo = Objects.requireNonNull(txRepo);
        this.categoryRepo = Objects.requireNonNull(categoryRepo);
    }

    // ══════════════════════════════════════════════════════════════════
    //  R E S U L T   R E C O R D S
    // ══════════════════════════════════════════════════════════════════

    public record LoanResult(
        String loanId,
        String transactionId,
        String movementId,
        LoanRepository.Loan loan,
        boolean wasTopup
    ) {}

    public record PaymentResult(
        String paymentId,
        String transactionId,
        String movementId,
        boolean loanClosed
    ) {}

    public record UpdateLoanResult(
        String loanId,
        String transactionId,
        String movementId,
        LoanRepository.Loan loan
    ) {}

    public record MovementMutationResult(
        String loanId,
        boolean loanDeleted
    ) {}

    // ══════════════════════════════════════════════════════════════════
    //  C R E A T E   L O A N  (find-or-accumulate)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Crea o acumula un préstamo con integración financiera completa.
     * <p>
     * REGLA: Un préstamo activo por persona + dirección financiera.
     * Si ya existe un préstamo OPEN para la misma persona y tipo,
     * se aumenta el saldo pendiente (topup) en lugar de crear uno nuevo.
     * <p>
     * PRESTAR (LENT):
     * - Descuenta saldo de cuenta origen
     * - Crea transacción tipo LOAN_LENT_OUT / LOAN_LENT_TOPUP
     * <p>
     * PEDIR PRESTADO (BORROWED):
     * - Ingresa dinero a cuenta destino
     * - Crea transacción tipo LOAN_BORROWED_IN / LOAN_BORROWED_TOPUP
     */
    public LoanResult createLoan(
        String userUid,
        AuthSession session,
        String type,
        String counterpartyName,
        String accountId,
        long principalCents,
        String currency,
        String notes
    ) throws SQLException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(type);
        Objects.requireNonNull(counterpartyName);
        Objects.requireNonNull(accountId);

        if (principalCents <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a 0");
        }

        String loanCategoryId = ensureLoanCategory(userUid, session);
        long now = Instant.now().getEpochSecond();

        // ── Buscar préstamo activo existente para esta persona + dirección ──
        LoanRepository.Loan existing = loanRepo.findActiveByCounterpartyAndType(
            userUid, counterpartyName, type
        );

        if (existing != null) {
            // ── TOPUP: acumular sobre préstamo existente ──────────────
            boolean isLentTopup = LoanRepository.TYPE_LENT.equals(type);
            String kind = isLentTopup
                ? TransactionKind.LOAN_LENT_TOPUP.name()
                : TransactionKind.LOAN_BORROWED_TOPUP.name();

            // 1. Transacción financiera (afecta saldo de cuenta)
            // El SQL en AccountRepository.computeBalanceCents maneja los signos según el tipo
            String txNote = isLentTopup
                ? "Aumento de préstamo otorgado a: " + existing.counterpartyName()
                : "Aumento de deuda con: " + existing.counterpartyName();
            String txId = txRepo.create(
                userUid, accountId, loanCategoryId, kind, principalCents, now,
                txNote
            );

            // 2. Aumentar principal del préstamo
            long newPrincipal = existing.principalCents() + principalCents;
            loanRepo.update(
                userUid, existing.id(), existing.type(), existing.counterpartyName(),
                newPrincipal, existing.currency(),
                LoanRepository.STATUS_OPEN, appendNote(existing.notes(), notes)
            );

            // 3. Registrar movimiento TOPUP
            String movId = movementRepo.create(
                userUid, existing.id(),
                LoanMovementRepository.MOV_TOPUP,
                principalCents, accountId, txId, notes, now
            );

            // 4. Sync
            LoanRepository.Loan updated = loanRepo.getByIdOrNull(userUid, existing.id());
            syncInBackground(session, userUid, txId, existing.id(), null, movId);

            return new LoanResult(existing.id(), txId, movId, updated, true);

        } else {
            // ── CREATION: nuevo préstamo ──────────────────────────────
            boolean isLentCreation = LoanRepository.TYPE_LENT.equals(type);
            String kind = isLentCreation
                ? TransactionKind.LOAN_LENT_OUT.name()
                : TransactionKind.LOAN_BORROWED_IN.name();

            // 1. Transacción financiera
            // El SQL en AccountRepository.computeBalanceCents maneja los signos según el tipo
            String txNote = isLentCreation
                ? "Préstamo otorgado a: " + counterpartyName
                : "Dinero recibido de: " + counterpartyName;
            String txId = txRepo.create(
                userUid, accountId, loanCategoryId, kind, principalCents, now,
                txNote
            );

            // 2. Crear préstamo
            String loanId = loanRepo.create(
                userUid, type, counterpartyName, accountId,
                principalCents, currency, now, notes
            );

            // 3. Registrar movimiento CREATION
            String movId = movementRepo.create(
                userUid, loanId,
                LoanMovementRepository.MOV_CREATION,
                principalCents, accountId, txId, notes, now
            );

            // 4. Sync
            LoanRepository.Loan created = loanRepo.getByIdOrNull(userUid, loanId);
            syncInBackground(session, userUid, txId, loanId, null, movId);

            return new LoanResult(loanId, txId, movId, created, false);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  R E G I S T E R   P A Y M E N T
    // ══════════════════════════════════════════════════════════════════

    /**
     * Registra un pago/abono a un préstamo con integración financiera.
     * <p>
     * Además del pago en loan_payments, registra un movimiento
     * PAYMENT_IN o PAYMENT_OUT en el historial.
     * Si la deuda se liquida, registra un movimiento CLOSE adicional.
     */
    public PaymentResult registerPayment(
        String userUid,
        AuthSession session,
        String loanId,
        String accountId,
        long amountCents,
        long occurredAt,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(loanId);
        Objects.requireNonNull(accountId);

        if (amountCents <= 0) {
            throw new IllegalArgumentException("El monto del pago debe ser mayor a 0");
        }

        // Obtener préstamo
        LoanRepository.Loan loan = loanRepo.getByIdOrNull(userUid, loanId);
        if (loan == null) {
            throw new IllegalStateException("Préstamo no encontrado");
        }
        if (LoanRepository.STATUS_CLOSED.equals(loan.status())) {
            throw new IllegalStateException("El préstamo ya está liquidado");
        }

        // Validar que no pague más de lo pendiente
        long paidSoFar = paymentRepo.sumPrincipalPaidCents(userUid, loanId);
        long pendingCents = Math.max(0L, loan.principalCents() - paidSoFar);
        if (amountCents > pendingCents) {
            throw new IllegalArgumentException("El monto excede la deuda pendiente ($" + (pendingCents / 100) + ")");
        }

        // Determinar kind y movimiento
        boolean isLent = LoanRepository.TYPE_LENT.equals(loan.type());
        String kind = isLent
            ? TransactionKind.LOAN_REPAYMENT_PRINCIPAL_IN.name()
            : TransactionKind.LOAN_REPAYMENT_PRINCIPAL_OUT.name();
        String movType = isLent
            ? LoanMovementRepository.MOV_PAYMENT_IN
            : LoanMovementRepository.MOV_PAYMENT_OUT;

        String repaymentCategoryId = ensureRepaymentCategory(userUid, session);

        long occ = occurredAt > 0 ? occurredAt : Instant.now().getEpochSecond();

        // 1. Crear transacción (afecta saldo)
        String txNote;
        if (note != null && !note.isBlank()) {
            txNote = note;
        } else if (isLent) {
            txNote = "Pago recibido de: " + loan.counterpartyName();
        } else {
            txNote = "Pago realizado a: " + loan.counterpartyName();
        }
        String txId = txRepo.create(
            userUid, accountId, repaymentCategoryId, kind, amountCents, occ, txNote
        );

        // 2. Registrar pago (tabla legacy)
        String paymentId = paymentRepo.create(
            userUid, loanId, accountId, amountCents, occ, txId, note
        );

        // 3. Registrar movimiento de pago
        String movId = movementRepo.create(
            userUid, loanId, movType, amountCents, accountId, txId, note, occ
        );

        // 4. Verificar si el préstamo se cierra
        long newPaid = paymentRepo.sumPrincipalPaidCents(userUid, loanId);
        long newPending = Math.max(0L, loan.principalCents() - newPaid);
        boolean closed = newPending <= 0L;
        if (closed) {
            loanRepo.update(
                userUid, loanId, loan.type(), loan.counterpartyName(),
                loan.principalCents(), loan.currency(),
                LoanRepository.STATUS_CLOSED, loan.notes()
            );
            // Movimiento de cierre
            movementRepo.create(
                userUid, loanId, LoanMovementRepository.MOV_CLOSE,
                0L, accountId, null, "Préstamo liquidado", occ
            );
        }

        // 5. Sync
        syncInBackground(session, userUid, txId, loanId, paymentId, movId);

        return new PaymentResult(paymentId, txId, movId, closed);
    }

    // ══════════════════════════════════════════════════════════════════
    //  U P D A T E   L O A N
    // ══════════════════════════════════════════════════════════════════

    /**
     * Edita un préstamo existente con integración financiera.
     * <p>
     * Permite modificar el monto principal del préstamo. La diferencia
     * se registra como una transacción de corrección para mantener
     * la integridad financiera.
     * <p>
     * PRESTAR (LENT):
     * - Si aumenta el monto: crea transacción LOAN_LENT_CORRECTION (resta saldo)
     * - Si disminuye el monto: crea transacción LOAN_LENT_CORRECTION (suma saldo, valor negativo)
     * <p>
     * PEDIR PRESTADO (BORROWED):
     * - Si aumenta el monto: crea transacción LOAN_BORROWED_CORRECTION (suma saldo)
     * - Si disminuye el monto: crea transacción LOAN_BORROWED_CORRECTION (resta saldo, valor negativo)
     */
    public UpdateLoanResult updateLoan(
        String userUid,
        AuthSession session,
        String loanId,
        String accountId,
        long newPrincipalCents,
        String notes
    ) throws SQLException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(loanId);

        if (newPrincipalCents <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a 0");
        }

        // Obtener préstamo existente
        LoanRepository.Loan loan = loanRepo.getByIdOrNull(userUid, loanId);
        if (loan == null) {
            throw new IllegalStateException("Préstamo no encontrado");
        }

        long oldPrincipal = loan.principalCents();
        long diffCents = newPrincipalCents - oldPrincipal;

        if (diffCents == 0) {
            // Sin cambios, solo actualizar notas si se proporcionaron
            if (notes != null && !notes.isBlank()) {
                loanRepo.update(
                    userUid, loanId, loan.type(), loan.counterpartyName(),
                    loan.principalCents(), loan.currency(),
                    loan.status(), appendNote(loan.notes(), notes)
                );
                LoanRepository.Loan updated = loanRepo.getByIdOrNull(userUid, loanId);
                return new UpdateLoanResult(loanId, null, null, updated);
            }
            return new UpdateLoanResult(loanId, null, null, loan);
        }

        String loanCategoryId = ensureLoanCategory(userUid, session);
        long now = Instant.now().getEpochSecond();

        // Determinar tipo de corrección
        boolean isLent = LoanRepository.TYPE_LENT.equals(loan.type());
        boolean isIncrease = diffCents > 0;
        String kind;
        if (isLent) {
            kind = isIncrease
                ? TransactionKind.LOAN_LENT_CORRECTION_OUT.name()
                : TransactionKind.LOAN_LENT_CORRECTION_IN.name();
        } else {
            kind = isIncrease
                ? TransactionKind.LOAN_BORROWED_CORRECTION_IN.name()
                : TransactionKind.LOAN_BORROWED_CORRECTION_OUT.name();
        }

        // Crear transacción de corrección (amountCents debe ser >= 0)
        long txAmount = Math.abs(diffCents);
        String txNote = isLent
            ? "Corrección de préstamo otorgado a: " + loan.counterpartyName()
            : "Corrección de deuda con: " + loan.counterpartyName();
        String txId = txRepo.create(
            userUid, accountId, loanCategoryId, kind,
            txAmount, now, txNote
        );

        // Actualizar préstamo con nuevo monto + cuenta
        loanRepo.update(
            userUid, loanId, loan.type(), loan.counterpartyName(),
            newPrincipalCents, loan.currency(),
            loan.status(), appendNote(loan.notes(), notes)
        );

        // Registrar movimiento de corrección
        String movId = movementRepo.create(
            userUid, loanId,
            LoanMovementRepository.MOV_TOPUP, // Reutilizamos TOPUP para correcciones
            diffCents, accountId, txId, notes, now
        );

        // Sync
        LoanRepository.Loan updated = loanRepo.getByIdOrNull(userUid, loanId);
        syncInBackground(session, userUid, txId, loanId, null, movId);

        return new UpdateLoanResult(loanId, txId, movId, updated);
    }

    // ══════════════════════════════════════════════════════════════════
    //  C O N S U L T A S
    // ══════════════════════════════════════════════════════════════════

    /** Lista préstamos abiertos otorgados (me deben). */
    public List<LoanRepository.Loan> listLent(String userUid) throws SQLException {
        return loanRepo.listByType(userUid, LoanRepository.TYPE_LENT, null, true);
    }

    /** Lista préstamos abiertos recibidos (yo debo). */
    public List<LoanRepository.Loan> listBorrowed(String userUid) throws SQLException {
        return loanRepo.listByType(userUid, LoanRepository.TYPE_BORROWED, null, true);
    }

    /** Lista todos los préstamos (abiertos + cerrados). */
    public List<LoanRepository.Loan> listAll(String userUid) throws SQLException {
        List<LoanRepository.Loan> lent = loanRepo.listByType(userUid, LoanRepository.TYPE_LENT, null, false);
        List<LoanRepository.Loan> borrowed = loanRepo.listByType(userUid, LoanRepository.TYPE_BORROWED, null, false);
        lent.addAll(borrowed);
        return lent;
    }

    /** Edita un movimiento del historial del préstamo. */
    public MovementMutationResult updateMovement(
        String userUid,
        AuthSession session,
        String movementId,
        String accountId,
        long amountCents,
        long occurredAtEpochSec,
        String note
    ) throws SQLException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(movementId);
        Objects.requireNonNull(accountId);

        if (amountCents <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a 0");
        }

        LoanMovementRepository.LoanMovement movement = movementRepo.getByIdOrNull(userUid, movementId);
        if (movement == null) {
            throw new IllegalStateException("Movimiento no encontrado");
        }

        LoanRepository.Loan loan = loanRepo.getByIdOrNull(userUid, movement.loanId());
        if (loan == null) {
            throw new IllegalStateException("Préstamo no encontrado");
        }

        LoanMovementRepository.LoanMovement closeBefore = findCloseMovement(userUid, loan.id());
        String storedAccountId = movement.accountId() != null ? movement.accountId() : loan.accountId();

        String cleanNote = (note == null || note.isBlank()) ? null : note;
        long occurred = occurredAtEpochSec > 0L ? occurredAtEpochSec : movement.occurredAtEpochSec();

        if (LoanMovementRepository.MOV_CLOSE.equals(movement.movementType())) {
            throw new IllegalStateException("No se puede editar un cierre automático");
        }

        String syncPaymentId = null;
        if (isPaymentMovement(movement.movementType())) {
            LoanPaymentRepository.LoanPayment payment = findPaymentByLinkedTransaction(userUid, movement.loanId(), movement.linkedTransactionId());
            if (payment == null) {
                throw new IllegalStateException("Pago asociado no encontrado");
            }

            String paymentAccountId = payment.accountId() != null ? payment.accountId() : storedAccountId;
            long paidOthers = paymentRepo.sumPrincipalPaidCents(userUid, loan.id()) - payment.principalCents();
            if (amountCents + paidOthers > loan.principalCents()) {
                throw new IllegalArgumentException("El monto excede el saldo pendiente");
            }

            String repaymentCategoryId = ensureRepaymentCategory(userUid, session);
            boolean isLent = LoanRepository.TYPE_LENT.equals(loan.type());
            String kind = isLent
                ? TransactionKind.LOAN_REPAYMENT_PRINCIPAL_IN.name()
                : TransactionKind.LOAN_REPAYMENT_PRINCIPAL_OUT.name();
            String txNote = cleanNote != null
                ? cleanNote
                : (isLent ? "Pago recibido de: " + loan.counterpartyName() : "Pago realizado a: " + loan.counterpartyName());

            txRepo.update(userUid, movement.linkedTransactionId(), paymentAccountId, repaymentCategoryId, kind, amountCents, occurred, txNote);
            paymentRepo.update(userUid, payment.id(), loan.id(), paymentAccountId, amountCents, occurred, cleanNote);
            movementRepo.update(userUid, movement.id(), loan.id(), movement.movementType(), amountCents, paymentAccountId, movement.linkedTransactionId(), cleanNote, occurred);
            syncPaymentId = payment.id();
        } else {
            String loanCategoryId = ensureLoanCategory(userUid, session);
            boolean isLent = LoanRepository.TYPE_LENT.equals(loan.type());
            boolean isCreation = LoanMovementRepository.MOV_CREATION.equals(movement.movementType());
            String kind = isLent
                ? (isCreation ? TransactionKind.LOAN_LENT_OUT.name() : TransactionKind.LOAN_LENT_TOPUP.name())
                : (isCreation ? TransactionKind.LOAN_BORROWED_IN.name() : TransactionKind.LOAN_BORROWED_TOPUP.name());
            String txNote = cleanNote != null
                ? cleanNote
                : (isCreation
                    ? (isLent ? "Préstamo otorgado a: " + loan.counterpartyName() : "Dinero recibido de: " + loan.counterpartyName())
                    : (isLent ? "Aumento de préstamo otorgado a: " + loan.counterpartyName() : "Aumento de deuda con: " + loan.counterpartyName()));

            long paid = paymentRepo.sumPrincipalPaidCents(userUid, loan.id());
            long otherOriginTotal = sumOriginMovementsExcept(userUid, loan.id(), movement.id());
            if (otherOriginTotal + amountCents < paid) {
                throw new IllegalArgumentException("El monto deja el préstamo por debajo de lo ya abonado");
            }

            txRepo.update(userUid, movement.linkedTransactionId(), storedAccountId, loanCategoryId, kind, amountCents, occurred, txNote);
            movementRepo.update(userUid, movement.id(), loan.id(), movement.movementType(), amountCents, storedAccountId, movement.linkedTransactionId(), cleanNote, occurred);
        }

        MovementMutationResult result = rebuildLoanState(userUid, session, loan.id());
        syncInBackground(session, userUid, movement.linkedTransactionId(), result.loanDeleted() ? null : loan.id(), syncPaymentId, movement.id());
        if (!result.loanDeleted()) {
            try {
                LoanMovementRepository.LoanMovement closeAfter = findCloseMovement(userUid, loan.id());
                if (closeBefore != null && closeAfter == null) {
                    syncDeleteInBackground(session, userUid, null, loan.id(), null, closeBefore.id(), false);
                } else if (closeAfter != null) {
                    syncInBackground(session, userUid, null, loan.id(), null, closeAfter.id());
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** Elimina un movimiento del historial del préstamo. */
    public MovementMutationResult deleteMovement(
        String userUid,
        AuthSession session,
        String movementId
    ) throws SQLException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(movementId);

        LoanMovementRepository.LoanMovement movement = movementRepo.getByIdOrNull(userUid, movementId);
        if (movement == null) {
            throw new IllegalStateException("Movimiento no encontrado");
        }

        if (LoanMovementRepository.MOV_CLOSE.equals(movement.movementType())) {
            throw new IllegalStateException("No se puede eliminar un cierre automático");
        }

        String loanId = movement.loanId();
        LoanRepository.Loan loan = loanRepo.getByIdOrNull(userUid, loanId);
        if (loan == null) {
            throw new IllegalStateException("Préstamo no encontrado");
        }

        LoanMovementRepository.LoanMovement closeBefore = findCloseMovement(userUid, loanId);

        boolean paymentMovement = isPaymentMovement(movement.movementType());
        String syncPaymentId = null;
        if (paymentMovement) {
            LoanPaymentRepository.LoanPayment payment = findPaymentByLinkedTransaction(userUid, loanId, movement.linkedTransactionId());
            if (payment == null) {
                throw new IllegalStateException("Pago asociado no encontrado");
            }
            syncPaymentId = payment.id();
            txRepo.delete(userUid, movement.linkedTransactionId());
            paymentRepo.delete(userUid, payment.id());
            movementRepo.delete(userUid, movementId);
        } else {
            long paid = paymentRepo.sumPrincipalPaidCents(userUid, loanId);
            long remainingOrigin = sumOriginMovementsExcept(userUid, loanId, movementId);
            if (remainingOrigin > 0L && remainingOrigin < paid) {
                throw new IllegalArgumentException("La eliminación dejaría el préstamo por debajo de lo ya abonado");
            }
            if (remainingOrigin == 0L && paid > 0L) {
                throw new IllegalArgumentException("No se puede eliminar el movimiento de origen porque ya existen pagos registrados");
            }

            txRepo.delete(userUid, movement.linkedTransactionId());
            movementRepo.delete(userUid, movementId);
        }

        MovementMutationResult result = rebuildLoanState(userUid, session, loanId);
        if (result.loanDeleted() && closeBefore != null) {
            syncDeleteInBackground(session, userUid, null, loanId, null, closeBefore.id(), false);
        }
        syncDeleteInBackground(session, userUid, movement.linkedTransactionId(), loanId, syncPaymentId, movement.id(), result.loanDeleted());
        if (!result.loanDeleted()) {
            syncInBackground(session, userUid, null, loanId, null, null);
            try {
                LoanMovementRepository.LoanMovement closeAfter = findCloseMovement(userUid, loanId);
                if (closeBefore != null && closeAfter == null) {
                    syncDeleteInBackground(session, userUid, null, loanId, null, closeBefore.id(), false);
                } else if (closeAfter != null) {
                    syncInBackground(session, userUid, null, loanId, null, closeAfter.id());
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** Obtiene un préstamo por ID (null si no existe). */
    public LoanRepository.Loan getLoan(String userUid, String loanId) throws SQLException {
        return loanRepo.getByIdOrNull(userUid, loanId);
    }

    /** Total pagado de un préstamo. */
    public long getPaidCents(String userUid, String loanId) throws SQLException {
        return paymentRepo.sumPrincipalPaidCents(userUid, loanId);
    }

    /** Saldo pendiente de un préstamo. */
    public long getPendingCents(String userUid, String loanId) throws SQLException {
        LoanRepository.Loan loan = loanRepo.getByIdOrNull(userUid, loanId);
        if (loan == null) return 0L;
        long paid = paymentRepo.sumPrincipalPaidCents(userUid, loanId);
        return Math.max(0L, loan.principalCents() - paid);
    }

    /** Saldo de cuenta. */
    public long getAccountBalance(String userUid, String accountId) throws SQLException {
        return accountRepo.computeBalanceCents(userUid, accountId);
    }

    /** Lista cuentas del usuario. */
    public List<AccountRepository.Account> listAccounts(String userUid) throws SQLException {
        return accountRepo.list(userUid);
    }

    /** Obtiene una cuenta específica. */
    public AccountRepository.Account getAccount(String userUid, String accountId) throws SQLException {
        return accountRepo.getById(userUid, accountId);
    }

    /** Lista pagos de un préstamo. */
    public List<LoanPaymentRepository.LoanPayment> listPayments(String userUid, String loanId) throws SQLException {
        return paymentRepo.listByLoan(userUid, loanId);
    }

    /** Lista todos los pagos del usuario. */
    public List<LoanPaymentRepository.LoanPayment> listAllPayments(String userUid) throws SQLException {
        return paymentRepo.listAllByUser(userUid);
    }

    /** Lista movimientos de un préstamo (historial completo). */
    public List<LoanMovementRepository.LoanMovement> listMovements(String userUid, String loanId) throws SQLException {
        return movementRepo.listByLoan(userUid, loanId);
    }

    /** Lista todos los movimientos del usuario. */
    public List<LoanMovementRepository.LoanMovement> listAllMovements(String userUid) throws SQLException {
        return movementRepo.listAllByUser(userUid);
    }

    private static boolean isPaymentMovement(String movementType) {
        return LoanMovementRepository.MOV_PAYMENT_IN.equals(movementType)
            || LoanMovementRepository.MOV_PAYMENT_OUT.equals(movementType);
    }

    private LoanPaymentRepository.LoanPayment findPaymentByLinkedTransaction(String userUid, String loanId, String linkedTransactionId) throws SQLException {
        if (linkedTransactionId == null || linkedTransactionId.isBlank()) {
            return null;
        }
        for (LoanPaymentRepository.LoanPayment payment : paymentRepo.listByLoan(userUid, loanId)) {
            if (linkedTransactionId.equals(payment.linkedTransactionId())) {
                return payment;
            }
        }
        return null;
    }

    private long sumOriginMovementsExcept(String userUid, String loanId, String excludedMovementId) throws SQLException {
        long total = 0L;
        for (LoanMovementRepository.LoanMovement movement : movementRepo.listByLoan(userUid, loanId)) {
            if (excludedMovementId != null && excludedMovementId.equals(movement.id())) {
                continue;
            }
            if (LoanMovementRepository.MOV_CREATION.equals(movement.movementType()) || LoanMovementRepository.MOV_TOPUP.equals(movement.movementType())) {
                total += movement.amountCents();
            }
        }
        return total;
    }

    private LoanMovementRepository.LoanMovement findCloseMovement(String userUid, String loanId) throws SQLException {
        for (LoanMovementRepository.LoanMovement movement : movementRepo.listByLoan(userUid, loanId)) {
            if (LoanMovementRepository.MOV_CLOSE.equals(movement.movementType())) {
                return movement;
            }
        }
        return null;
    }

    private MovementMutationResult rebuildLoanState(String userUid, AuthSession session, String loanId) throws SQLException {
        LoanRepository.Loan loan = loanRepo.getByIdOrNull(userUid, loanId);
        if (loan == null) {
            return new MovementMutationResult(loanId, true);
        }

        List<LoanMovementRepository.LoanMovement> movements = movementRepo.listByLoan(userUid, loanId);
        long originTotal = 0L;
        long paidTotal = paymentRepo.sumPrincipalPaidCents(userUid, loanId);
        LoanMovementRepository.LoanMovement firstOrigin = null;
        LoanMovementRepository.LoanMovement lastPayment = null;
        LoanMovementRepository.LoanMovement closeMovement = null;

        for (LoanMovementRepository.LoanMovement movement : movements) {
            if (LoanMovementRepository.MOV_CREATION.equals(movement.movementType()) || LoanMovementRepository.MOV_TOPUP.equals(movement.movementType())) {
                originTotal += movement.amountCents();
                if (firstOrigin == null) {
                    firstOrigin = movement;
                }
            } else if (isPaymentMovement(movement.movementType())) {
                if (lastPayment == null || movement.occurredAtEpochSec() >= lastPayment.occurredAtEpochSec()) {
                    lastPayment = movement;
                }
            } else if (LoanMovementRepository.MOV_CLOSE.equals(movement.movementType())) {
                closeMovement = movement;
            }
        }

        if (originTotal <= 0L) {
            // Compatibilidad con préstamos heredados que todavía no tienen
            // un movimiento CREATION/TOPUP en el historial. En ese caso,
            // el principal persistido en la tabla de préstamos sigue siendo
            // la única referencia útil para reconstruir el estado.
            if (loan.principalCents() > 0L) {
                originTotal = loan.principalCents();
            } else {
                if (paidTotal > 0L) {
                    throw new IllegalStateException("Estado inválido: existen pagos sin movimientos de origen");
                }
                if (closeMovement != null) {
                    movementRepo.delete(userUid, closeMovement.id());
                }
                loanRepo.delete(userUid, loanId);
                return new MovementMutationResult(loanId, true);
            }
        }

        if (paidTotal > originTotal) {
            throw new IllegalStateException("Estado inválido: los pagos superan el principal");
        }

        boolean closed = paidTotal >= originTotal;
        long loanOccurredAt = firstOrigin != null ? firstOrigin.occurredAtEpochSec() : loan.occurredAtEpochSec();
        String loanAccountId = firstOrigin != null ? firstOrigin.accountId() : loan.accountId();
        loanRepo.updateFull(
            userUid,
            loanId,
            loan.type(),
            loan.counterpartyName(),
            loanAccountId,
            originTotal,
            loan.currency(),
            closed ? LoanRepository.STATUS_CLOSED : LoanRepository.STATUS_OPEN,
            loan.notes(),
            loanOccurredAt
        );

        if (closed) {
            String closeAccountId = lastPayment != null && lastPayment.accountId() != null ? lastPayment.accountId() : loanAccountId;
            long closeOccurredAt = lastPayment != null ? lastPayment.occurredAtEpochSec() : loanOccurredAt;
            if (closeMovement == null) {
                movementRepo.create(userUid, loanId, LoanMovementRepository.MOV_CLOSE, 0L, closeAccountId, null, "Préstamo liquidado", closeOccurredAt);
            } else {
                movementRepo.update(userUid, closeMovement.id(), loanId, LoanMovementRepository.MOV_CLOSE, 0L, closeAccountId, null, "Préstamo liquidado", closeOccurredAt);
            }
        } else if (closeMovement != null) {
            movementRepo.delete(userUid, closeMovement.id());
        }

        return new MovementMutationResult(loanId, false);
    }

    /**
     * Archiva un préstamo cambiando su estado a CLOSED.
     * No elimina registros; solo oculta el préstamo de la lista activa.
     * Mantiene historial financiero completo.
     */
    public void archiveLoan(String userUid, AuthSession session, String loanId) throws SQLException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(loanId);

        // Actualizar estado a CLOSED
        loanRepo.archive(userUid, loanId);

        // Sync en background
        syncInBackground(session, userUid, null, loanId, null, null);
    }

    // ══════════════════════════════════════════════════════════════════
    //  S Y N C   (background)
    // ══════════════════════════════════════════════════════════════════

    private String ensureLoanCategory(String userUid, AuthSession session) throws SQLException {
        String id = SYSTEM_LOAN_CAT_PREFIX + userUid;
        try {
            CategoryRepository.Category existing = categoryRepo.getById(userUid, id);
            if (existing != null) return id;
        } catch (Exception ignored) {}
        try {
            categoryRepo.createWithId(userUid, id, "Préstamos", null);
            syncCategoryInBackground(session, userUid, id);
        } catch (Exception ignored) {}
        return id;
    }

    private String ensureRepaymentCategory(String userUid, AuthSession session) throws SQLException {
        String id = SYSTEM_REPAYMENT_CAT_PREFIX + userUid;
        try {
            CategoryRepository.Category existing = categoryRepo.getById(userUid, id);
            if (existing != null) return id;
        } catch (Exception ignored) {}
        try {
            categoryRepo.createWithId(userUid, id, "Devoluciones", null);
            syncCategoryInBackground(session, userUid, id);
        } catch (Exception ignored) {}
        return id;
    }

    /** Concatena notas sin perder las anteriores. */
    private static String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank()) return existing;
        if (existing == null || existing.isBlank()) return addition;
        return existing + "\n---\n" + addition;
    }

    private void syncInBackground(AuthSession session, String userUid,
                                   String txId, String loanId, String paymentId, String movementId) {
        new Thread(() -> {
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());

                // Sync transaction
                if (txId != null) {
                    try {
                        TransactionRepository.TransactionSyncRow t = txRepo.getForSyncByIdOrNull(userUid, txId);
                        if (t != null) sync.syncTransaction(session, t);
                    } catch (Exception ignored) {}
                }

                // Sync loan
                if (loanId != null) {
                    try {
                        LoanRepository.Loan l = loanRepo.getByIdOrNull(userUid, loanId);
                        if (l != null) sync.syncLoan(session, l);
                    } catch (Exception ignored) {}
                }

                // Sync payment
                if (paymentId != null) {
                    try {
                        LoanPaymentRepository.LoanPayment p = paymentRepo.getByIdOrNull(userUid, paymentId);
                        if (p != null) sync.syncLoanPayment(session, p);
                    } catch (Exception ignored) {}
                }

                // Sync movement
                if (movementId != null) {
                    try {
                        LoanMovementRepository.LoanMovement m = movementRepo.getByIdOrNull(userUid, movementId);
                        if (m != null) sync.syncLoanMovement(session, m);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, "loan-sync").start();
    }

    private void syncDeleteInBackground(AuthSession session, String userUid, String txId, String loanId, String paymentId, String movementId, boolean deleteLoan) {
        new Thread(() -> {
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());

                if (txId != null) {
                    try {
                        sync.deleteTransaction(session, txId);
                    } catch (Exception ignored) {}
                }

                if (paymentId != null) {
                    try {
                        sync.deleteLoanPayment(session, paymentId);
                    } catch (Exception ignored) {}
                }

                if (movementId != null && loanId != null) {
                    try {
                        sync.deleteLoanMovement(session, userUid, loanId, movementId);
                    } catch (Exception ignored) {}
                }

                if (deleteLoan && loanId != null) {
                    try {
                        sync.deleteLoan(session, loanId);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, "loan-delete-sync").start();
    }

    private void syncCategoryInBackground(AuthSession session, String userUid, String categoryId) {
        new Thread(() -> {
            try {
                AppConfig cfg = AppConfig.loadDefault();
                FirestoreSyncService sync = new FirestoreSyncService(cfg.firebaseProjectId());
                CategoryRepository.Category cat = categoryRepo.getById(userUid, categoryId);
                if (cat != null) sync.syncCategory(session, cat);
            } catch (Exception ignored) {}
        }, "loan-cat-sync").start();
    }
}
