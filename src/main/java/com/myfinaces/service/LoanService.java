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
