package com.myfinaces.service.pdf;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.BudgetRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.GoalRepository;
import com.myfinaces.db.LoanPaymentRepository;
import com.myfinaces.db.LoanRepository;
import com.myfinaces.db.TransactionRepository;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class ReportPdfService {

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 24f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2f);
    private static final float BOTTOM_GUARD = 40f;

    private static final Color PRIMARY = hex("#1A56DB");
    private static final Color PRIMARY_DARK = hex("#1E40AF");
    private static final Color INCOME = hex("#16A34A");
    private static final Color EXPENSE = hex("#DC2626");
    private static final Color WARN = hex("#D97706");
    private static final Color TEXT = hex("#0F172A");
    private static final Color MUTED = hex("#64748B");
    private static final Color BORDER = hex("#E2E8F0");
    private static final Color SURFACE = hex("#F8FAFC");
    private static final Color WHITE = Color.WHITE;
    private static final Color CHIPS = hex("#EFF6FF");
    private static final Color CHIPS_BORDER = hex("#BFDBFE");
    private static final Color INCOME_BG = hex("#F0FDF4");
    private static final Color EXPENSE_BG = hex("#FEF2F2");
    private static final Color WARN_BG = hex("#FFFBEB");
    private static final Color TABLE_HEADER = hex("#334155");

    private static final PDFont FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont FONT_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont FONT_ITALIC = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

    private final TransactionRepository txRepo;
    private final AccountRepository accountRepo;
    private final CategoryRepository categoryRepo;
    private final BudgetRepository budgetRepo;
    private final LoanRepository loanRepo;
    private final LoanPaymentRepository loanPaymentRepo;
    private final GoalRepository goalRepo;

    public ReportPdfService(
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        BudgetRepository budgetRepo,
        LoanRepository loanRepo,
        LoanPaymentRepository loanPaymentRepo,
        GoalRepository goalRepo
    ) {
        this.txRepo = Objects.requireNonNull(txRepo, "txRepo");
        this.accountRepo = Objects.requireNonNull(accountRepo, "accountRepo");
        this.categoryRepo = Objects.requireNonNull(categoryRepo, "categoryRepo");
        this.budgetRepo = Objects.requireNonNull(budgetRepo, "budgetRepo");
        this.loanRepo = Objects.requireNonNull(loanRepo, "loanRepo");
        this.loanPaymentRepo = Objects.requireNonNull(loanPaymentRepo, "loanPaymentRepo");
        this.goalRepo = Objects.requireNonNull(goalRepo, "goalRepo");
    }

    public record TransactionsReportRequest(
        String userUid,
        String userName,
        String currencyCode,
        String accountId,
        String kind,
        String rootCategoryId,
        Set<String> selectedSubcategoryIds
    ) {
    }

    public record AccountsReportRequest(
        String userUid,
        String userName
    ) {
    }

    public record MonthlySummaryReportRequest(
        String userUid,
        String userName,
        String currencyCode,
        YearMonth month
    ) {
    }

    public File generateTransactionsReport(TransactionsReportRequest request, File outputFile) throws Exception {
        Objects.requireNonNull(request, "request");

        String userUid = requireNonBlank(request.userUid(), "userUid");
        String userName = safeName(request.userName());
        String currencyCode = safeCurrency(request.currencyCode());
        YearMonth month = YearMonth.now();
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(30);
        long fromEpochSec = fromDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long toEpochSec = toDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1;

        List<String> categoryIds = expandSelectedCategories(userUid, request.rootCategoryId(), request.selectedSubcategoryIds());
        List<TransactionRepository.TransactionRow> transactions = txRepo.listFiltered(
            userUid,
            blankToNull(request.accountId()),
            categoryIds == null || categoryIds.isEmpty() ? null : categoryIds,
            fromEpochSec,
            toEpochSec,
            1000
        );

        String kind = normalizeKind(request.kind());
        if (kind != null) {
            transactions = transactions.stream()
                .filter(row -> kind.equalsIgnoreCase(row.kind()))
                .toList();
        }

        transactions = new ArrayList<>(transactions);
        transactions.sort(Comparator
            .comparingLong(TransactionRepository.TransactionRow::occurredAtEpochSec)
            .reversed()
            .thenComparing(TransactionRepository.TransactionRow::id, Comparator.nullsLast(String::compareTo)));

        File target = ensureOutputFile(outputFile, defaultName("reporte_transacciones_", month + ".pdf"));
        String periodLabel = fromDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("es-CO")))
            + "  –  " + toDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("es-CO")));
        String generatedAt = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-CO")));

        long incomeTotal = transactions.stream()
            .filter(row -> isIncomeKind(row.kind()))
            .mapToLong(row -> Math.abs(row.amountCents()))
            .sum();
        long expenseTotal = transactions.stream()
            .filter(row -> isExpenseKind(row.kind()))
            .mapToLong(row -> Math.abs(row.amountCents()))
            .sum();
        long balanceTotal = incomeTotal - expenseTotal;

        try (PDDocument document = new PDDocument()) {
            PageState page = newPage(document);
            drawBanner(document, page, "Xpendz", "Reporte de Transacciones", periodLabel, userName, generatedAt);
            page.y = 656f;

            List<CardSpec> cards = List.of(
                new CardSpec("Ingresos", money(incomeTotal, currencyCode), INCOME),
                new CardSpec("Gastos", money(expenseTotal, currencyCode), EXPENSE),
                new CardSpec("Balance", money(balanceTotal, currencyCode), balanceTotal >= 0 ? INCOME : EXPENSE)
            );
            drawSummaryCards(page, cards);

            drawChip(page, transactions.size() + " transacciones", PRIMARY, CHIPS, CHIPS_BORDER, CONTENT_WIDTH - 156f, 156f);
            page.y -= 4f;

            drawSectionTitle(page, "Detalle de transacciones", PRIMARY);
            page.y -= 6f;
            drawTransactionsHeader(page);

            if (transactions.isEmpty()) {
                ensureSpace(document, page, 26f, () -> drawCompactHeader(page, "Xpendz · Reporte de Transacciones", null));
                drawCenteredMessage(page, "No hay transacciones en el período seleccionado.");
            } else {
                int indexOffset = 0;
                for (TransactionRepository.TransactionRow row : transactions) {
                    ensureSpace(document, page, 20f, () -> drawCompactHeader(page, "Xpendz · Reporte de Transacciones", "Pág. " + page.pageNumber));
                    drawTransactionRow(page, row, indexOffset++, currencyCode);
                }
            }

            drawFooter(page, "Xpendz · Reporte confidencial");
            page.stream.close();
            document.save(target);
            return target;
        }
    }

    public File generateAccountsReport(AccountsReportRequest request, File outputFile) throws Exception {
        Objects.requireNonNull(request, "request");

        String userUid = requireNonBlank(request.userUid(), "userUid");
        String userName = safeName(request.userName());
        String generatedAt = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-CO")));

        List<AccountRepository.Account> accounts = accountRepo.list(userUid);
        List<AccountBalanceLine> lines = new ArrayList<>();
        for (AccountRepository.Account account : accounts) {
            long balanceCents = accountRepo.computeBalanceCents(userUid, account.id());
            lines.add(new AccountBalanceLine(account, balanceCents, resolveAccountColor(account)));
        }
        lines.sort(Comparator.comparingLong(AccountBalanceLine::balanceCents).reversed().thenComparing(l -> l.account().name(), String.CASE_INSENSITIVE_ORDER));

        long totalBalance = lines.stream().mapToLong(AccountBalanceLine::balanceCents).sum();
        long positiveAccounts = lines.stream().filter(line -> line.balanceCents() >= 0).count();

        File target = ensureOutputFile(outputFile, defaultName("balance_cuentas_", generatedAt.replace('/', '-') + ".pdf"));

        try (PDDocument document = new PDDocument()) {
            PageState page = newPage(document);
            drawBanner(document, page, "Xpendz", "Balance de Cuentas", "Estado actual y distribución de tus cuentas", userName, generatedAt);
            page.y = 656f;

            List<CardSpec> cards = List.of(
                new CardSpec("Balance total", money(totalBalance, "COP"), totalBalance >= 0 ? INCOME : EXPENSE),
                new CardSpec("Cuentas activas", String.valueOf(lines.size()), PRIMARY),
                new CardSpec("Saldo positivo", String.valueOf(positiveAccounts), INCOME)
            );
            drawSummaryCards(page, cards);
            page.y -= 14f;

            drawSectionTitle(page, "Distribución por cuenta", PRIMARY);
            page.y -= 4f;

            if (lines.isEmpty()) {
                drawCenteredMessage(page, "No hay cuentas registradas.");
            } else {
                for (AccountBalanceLine line : lines) {
                    ensureSpace(document, page, 48f, () -> drawCompactHeader(page, "Xpendz · Balance de Cuentas", "Pág. " + page.pageNumber));
                    drawAccountCard(page, line, totalBalance);
                }
            }

            drawFooter(page, "Xpendz · Reporte confidencial");
            page.stream.close();
            document.save(target);
            return target;
        }
    }

    public File generateMonthlySummaryReport(MonthlySummaryReportRequest request, File outputFile) throws Exception {
        Objects.requireNonNull(request, "request");

        String userUid = requireNonBlank(request.userUid(), "userUid");
        String userName = safeName(request.userName());
        String currencyCode = safeCurrency(request.currencyCode());
        YearMonth month = request.month() == null ? YearMonth.now() : request.month();
        String monthKey = month.toString();
        String monthLabel = monthLabel(month);
        String generatedAt = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-CO")));

        long[] range = monthRange(month);
        long fromEpochSec = range[0];
        long toEpochSec = range[1];

        List<TransactionRepository.TransactionRow> rows = txRepo.listFiltered(userUid, null, (List<String>) null, fromEpochSec, toEpochSec, 5000);
        long incomeCents = rows.stream().filter(row -> isIncomeKind(row.kind())).mapToLong(row -> Math.abs(row.amountCents())).sum();
        long expenseCents = rows.stream().filter(row -> isExpenseKind(row.kind())).mapToLong(row -> Math.abs(row.amountCents())).sum();
        long balanceCents = incomeCents - expenseCents;

        List<TransactionRepository.MonthlyCategoryDetailTotal> incomeHierarchy = txRepo.listMonthlyTotalsBySubcategory(userUid, null, month.getYear(), "INCOME");
        List<TransactionRepository.MonthlyCategoryDetailTotal> expenseHierarchy = txRepo.listMonthlyTotalsBySubcategory(userUid, null, month.getYear(), "EXPENSE");
        
        // Filter to current month only
        int currentMonth = month.getMonthValue();
        incomeHierarchy = incomeHierarchy.stream().filter(row -> row.month() == currentMonth).toList();
        expenseHierarchy = expenseHierarchy.stream().filter(row -> row.month() == currentMonth).toList();
        
        List<HierarchyGroup> incomeGroups = toHierarchyGroups(incomeHierarchy);
        List<HierarchyGroup> expenseGroups = toHierarchyGroups(expenseHierarchy);
        List<BudgetRepository.BudgetProgress> budgets = budgetRepo.listProgressByMonthAndCurrency(userUid, monthKey, currencyCode);

        // Get active loans
        List<LoanRepository.Loan> lentLoans = loanRepo.listByType(userUid, LoanRepository.TYPE_LENT, currencyCode, true);
        List<LoanRepository.Loan> borrowedLoans = loanRepo.listByType(userUid, LoanRepository.TYPE_BORROWED, currencyCode, true);

        // Get active goals
        List<GoalRepository.Goal> activeGoals = goalRepo.listByUser(userUid).stream()
            .filter(g -> GoalRepository.STATUS_OPEN.equals(g.status()) && currencyCode.equals(g.currency()))
            .toList();

        long totalBudgetLimit = budgets.stream().mapToLong(b -> b.budget().limitCents()).sum();
        long totalBudgetSpent = budgets.stream().mapToLong(BudgetRepository.BudgetProgress::spentCents).sum();
        int budgetPct = totalBudgetLimit > 0 ? (int) ((totalBudgetSpent * 100) / totalBudgetLimit) : 0;

        File target = ensureOutputFile(outputFile, defaultName("resumen_mensual_", monthKey + ".pdf"));

        try (PDDocument document = new PDDocument()) {
            PageState page = newPage(document);
            drawBanner(document, page, "Xpendz", "Resumen Mensual", monthLabel, userName, generatedAt);
            page.y = 656f;

            List<CardSpec> cards = List.of(
                new CardSpec("Ingresos", money(incomeCents, currencyCode), INCOME),
                new CardSpec("Gastos", money(expenseCents, currencyCode), EXPENSE),
                new CardSpec("Balance", money(balanceCents, currencyCode), balanceCents >= 0 ? INCOME : EXPENSE)
            );
            drawSummaryCards(page, cards);
            page.y -= 6f;

            // Savings rate (same as Android)
            int savingsRate = incomeCents > 0 ? (int) ((balanceCents * 100) / incomeCents) : 0;
            drawSavingsRateChip(page, savingsRate);
            page.y -= 6f;

            // ── 2. CATEGORÍAS DE INGRESOS ─────────────────────────────────
            ensureSpace(document, page, 36f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
            drawSectionTitle(page, "INGRESOS POR CATEGORÍA", INCOME);
            if (incomeGroups.isEmpty()) {
                drawCenteredMessage(page, "Sin movimientos de ingresos en este período.");
            } else {
                drawHierarchySection(document, page, incomeGroups, INCOME, INCOME_BG, currencyCode);
            }
            page.y -= 6f;

            // ── 3. CATEGORÍAS DE GASTOS ─────────────────────────────────--
            ensureSpace(document, page, 36f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
            drawSectionTitle(page, "GASTOS POR CATEGORÍA", EXPENSE);
            if (expenseGroups.isEmpty()) {
                drawCenteredMessage(page, "Sin movimientos de gastos en este período.");
            } else {
                drawHierarchySection(document, page, expenseGroups, EXPENSE, EXPENSE_BG, currencyCode);
            }
            page.y -= 6f;

            // ── 4. PRESUPUESTO DEL MES ─────────────────────────────────---
            ensureSpace(document, page, 60f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
            drawSectionTitle(page, "PRESUPUESTO DEL MES", PRIMARY_DARK);
            if (budgets.isEmpty()) {
                drawCenteredMessage(page, "No hay presupuestos configurados.");
            } else {
                drawBudgetHeader(page);
                
                // Group budgets by root category
                Map<String, List<BudgetRepository.BudgetProgress>> budgetGroups = new java.util.LinkedHashMap<>();
                for (BudgetRepository.BudgetProgress bp : budgets) {
                    String categoryId = bp.budget().categoryId();
                    CategoryRepository.Category cat = categoryRepo.getById(userUid, categoryId);
                    String rootId = (cat != null && cat.parentId() != null) ? cat.parentId() : categoryId;
                    CategoryRepository.Category rootCat = categoryRepo.getById(userUid, rootId);
                    String rootName = (rootCat != null) ? rootCat.name() : "Desconocido";
                    
                    // Store with root info
                    budgetGroups.computeIfAbsent(rootId, k -> new ArrayList<>()).add(bp);
                }
                
                // Sort by total spent descending
                List<Map.Entry<String, List<BudgetRepository.BudgetProgress>>> sortedGroups = budgetGroups.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, List<BudgetRepository.BudgetProgress>> e) -> e.getValue().stream().mapToLong(BudgetRepository.BudgetProgress::spentCents).sum()).reversed())
                    .toList();
                
                int budgetRootIdx = 0;
                for (Map.Entry<String, List<BudgetRepository.BudgetProgress>> entry : sortedGroups) {
                    String rootId = entry.getKey();
                    List<BudgetRepository.BudgetProgress> group = new ArrayList<>(entry.getValue());
                    group.sort(Comparator.comparingLong(BudgetRepository.BudgetProgress::spentCents).reversed());
                    
                    // Calculate root totals
                    long rootLimit = group.stream().mapToLong(b -> b.budget().limitCents()).sum();
                    long rootSpent = group.stream().mapToLong(BudgetRepository.BudgetProgress::spentCents).sum();
                    int rootPct = rootLimit > 0 ? (int) ((rootSpent * 100) / rootLimit) : 0;
                    Color rootColor = rootPct >= 100 ? EXPENSE : (rootPct >= 80 ? WARN : INCOME);
                    
                    // Get root name
                    CategoryRepository.Category rootCat = categoryRepo.getById(userUid, rootId);
                    String rootName = (rootCat != null) ? rootCat.name() : "Desconocido";
                    
                    // Draw root row
                    ensureSpace(document, page, 22f + group.size() * 20f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                    drawBudgetRootRow(page, rootName, rootLimit, rootSpent, rootPct, rootColor, currencyCode);
                    
                    // Draw subcategory rows
                    int budgetSubIdx = 0;
                    for (BudgetRepository.BudgetProgress bp : group) {
                        ensureSpace(document, page, 20f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                        drawBudgetSubRow(page, bp, currencyCode, ((budgetRootIdx + budgetSubIdx) % 2 == 0));
                        budgetSubIdx++;
                    }
                    budgetRootIdx++;
                }
            }

            // ── 5. PRÉSTAMOS ACTIVOS ────────────────────────────────────────
            ensureSpace(document, page, 60f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
            drawSectionTitle(page, "PRÉSTAMOS ACTIVOS", PRIMARY);
            if (lentLoans.isEmpty() && borrowedLoans.isEmpty()) {
                drawCenteredMessage(page, "No hay préstamos activos.");
            } else {
                // Group by type
                if (!lentLoans.isEmpty()) {
                    ensureSpace(document, page, 20f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                    drawText(page.stream, "Préstamos otorgados", MARGIN, page.y + 12f, FONT_BOLD, 8.5f, PRIMARY);
                    page.y -= 16f;
                    for (LoanRepository.Loan loan : lentLoans) {
                        ensureSpace(document, page, 24f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                        long paidCents = loanPaymentRepo.sumPrincipalPaidCents(userUid, loan.id());
                        long remainingCents = loan.principalCents() - paidCents;
                        float progress = loan.principalCents() > 0 ? (float) paidCents / loan.principalCents() : 0f;
                        drawLoanRow(page, loan, paidCents, remainingCents, progress, currencyCode, true);
                    }
                }
                if (!borrowedLoans.isEmpty()) {
                    ensureSpace(document, page, 20f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                    drawText(page.stream, "Préstamos recibidos", MARGIN, page.y + 12f, FONT_BOLD, 8.5f, PRIMARY);
                    page.y -= 16f;
                    for (LoanRepository.Loan loan : borrowedLoans) {
                        ensureSpace(document, page, 24f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                        long paidCents = loanPaymentRepo.sumPrincipalPaidCents(userUid, loan.id());
                        long remainingCents = loan.principalCents() - paidCents;
                        float progress = loan.principalCents() > 0 ? (float) paidCents / loan.principalCents() : 0f;
                        drawLoanRow(page, loan, paidCents, remainingCents, progress, currencyCode, false);
                    }
                }
            }

            // ── 6. METAS ACTIVAS ───────────────────────────────────────────
            ensureSpace(document, page, 60f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
            drawSectionTitle(page, "METAS ACTIVAS", PRIMARY);
            if (activeGoals.isEmpty()) {
                drawCenteredMessage(page, "No hay metas activas.");
            } else {
                for (GoalRepository.Goal goal : activeGoals) {
                    ensureSpace(document, page, 24f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                    long currentCents = accountRepo.computeBalanceCents(userUid, goal.accountId());
                    long remainingCents = goal.targetCents() - currentCents;
                    float progress = goal.targetCents() > 0 ? (float) currentCents / goal.targetCents() : 0f;
                    drawGoalRow(page, goal, currentCents, remainingCents, progress, currencyCode);
                }
            }

            drawFooter(page, "Xpendz · Reporte confidencial");
            page.stream.close();
            document.save(target);
            return target;
        }
    }

    private static String safeName(String value) {
        String fallback = "Usuario";
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String safeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return "COP";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        String k = kind.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "INCOME", "EXPENSE" -> k;
            default -> null;
        };
    }

    private static boolean isIncomeKind(String kind) {
        if (kind == null) {
            return false;
        }
        String k = kind.toUpperCase(Locale.ROOT);
        return "INCOME".equals(k) || "LOAN_BORROWED_IN".equals(k) || "LOAN_REPAYMENT_PRINCIPAL_IN".equals(k);
    }

    private static boolean isExpenseKind(String kind) {
        if (kind == null) {
            return false;
        }
        String k = kind.toUpperCase(Locale.ROOT);
        return "EXPENSE".equals(k) || "LOAN_LENT_OUT".equals(k) || "LOAN_REPAYMENT_PRINCIPAL_OUT".equals(k);
    }

    private List<String> expandSelectedCategories(String userUid, String rootCategoryId, Set<String> selectedSubcategoryIds) throws Exception {
        if ((rootCategoryId == null || rootCategoryId.isBlank()) && (selectedSubcategoryIds == null || selectedSubcategoryIds.isEmpty())) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        if (rootCategoryId != null && !rootCategoryId.isBlank()) {
            ids.add(rootCategoryId.trim());
            collectCategoryTree(userUid, rootCategoryId.trim(), ids);
        }
        if (selectedSubcategoryIds != null) {
            for (String subId : selectedSubcategoryIds) {
                if (subId == null || subId.isBlank()) {
                    continue;
                }
                ids.add(subId.trim());
                collectCategoryTree(userUid, subId.trim(), ids);
            }
        }
        return ids.stream().filter(s -> !s.isBlank()).toList();
    }

    private void collectCategoryTree(String userUid, String parentId, Set<String> ids) throws Exception {
        List<CategoryRepository.Category> children = categoryRepo.listChildren(userUid, parentId);
        for (CategoryRepository.Category child : children) {
            if (child == null || child.id() == null || child.id().isBlank()) {
                continue;
            }
            if (ids.add(child.id())) {
                collectCategoryTree(userUid, child.id(), ids);
            }
        }
    }

    private List<HierarchyGroup> toHierarchyGroups(List<TransactionRepository.MonthlyCategoryDetailTotal> rows) {
        Map<String, String> rootNames = new LinkedHashMap<>();
        Map<String, List<HierarchyItem>> grouped = new LinkedHashMap<>();

        for (TransactionRepository.MonthlyCategoryDetailTotal row : rows) {
            if (row == null || row.rootCategoryId() == null) {
                continue;
            }

            String rootId = row.rootCategoryId();
            String rootName = row.rootCategoryName() == null || row.rootCategoryName().isBlank()
                ? "Desconocido"
                : row.rootCategoryName();
            rootNames.putIfAbsent(rootId, rootName);

            String categoryName;
            if (row.categoryName() == null || row.categoryName().isBlank() || row.categoryId() == null || row.categoryId().endsWith(":NONE")) {
                categoryName = "General";
            } else {
                categoryName = row.categoryName();
            }

            grouped.computeIfAbsent(rootId, k -> new ArrayList<>())
                .add(new HierarchyItem(categoryName, row.totalAmountCents()));
        }

        return grouped.entrySet().stream()
            .map(entry -> {
                List<HierarchyItem> items = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(HierarchyItem::totalCents).reversed())
                    .toList();
                long total = items.stream().mapToLong(HierarchyItem::totalCents).sum();
                return new HierarchyGroup(
                    entry.getKey(),
                    rootNames.getOrDefault(entry.getKey(), "Desconocido"),
                    total,
                    items
                );
            })
            .sorted(Comparator.comparingLong(HierarchyGroup::totalCents).reversed())
            .toList();
    }

    private static String monthLabel(YearMonth month) {
        String monthName = month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, new Locale("es", "CO"));
        monthName = monthName.substring(0, 1).toUpperCase(Locale.ROOT) + monthName.substring(1);
        return monthName + " " + month.getYear();
    }

    private static long[] monthRange(YearMonth month) {
        long from = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1L;
        return new long[] { from, to };
    }

    private static File ensureOutputFile(File outputFile, String defaultName) throws IOException {
        if (outputFile == null) {
            File temp = Files.createTempFile("myfinances_", defaultName).toFile();
            temp.deleteOnExit();
            return temp;
        }
        File parent = outputFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        return outputFile;
    }

    private static String defaultName(String prefix, String suffix) {
        return prefix + System.currentTimeMillis() + "_" + suffix;
    }

    private static Color hex(String value) {
        return Color.decode(value);
    }

    private static String money(long cents, String currencyCode) {
        java.text.NumberFormat fmt = java.text.NumberFormat.getCurrencyInstance(new Locale("es", "CO"));
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(2);
        String raw = fmt.format(cents / 100.0);
        if (currencyCode == null || currencyCode.isBlank() || "COP".equalsIgnoreCase(currencyCode)) {
            return raw;
        }
        return currencyCode.toUpperCase(Locale.ROOT) + " " + raw;
    }

    private static Color resolveAccountColor(AccountRepository.Account account) {
        if (account == null) {
            return PRIMARY;
        }
        if (account.color() != null && !account.color().isBlank()) {
            try {
                return hex(account.color());
            } catch (Exception ignored) {
            }
        }
        return switch (AccountRepository.normalizeType(account.type())) {
            case "CASH" -> hex("#059669");
            case "SAVINGS" -> hex("#D97706");
            case "VIRTUAL_WALLET" -> hex("#0891B2");
            case "DIGITAL_ACCOUNT" -> hex("#E11D48");
            case "CREDIT" -> hex("#7C3AED");
            default -> hex("#2563EB");
        };
    }

    private static String accountTypeLabel(String type) {
        String t = AccountRepository.normalizeType(type);
        return switch (t) {
            case "CASH" -> "Efectivo";
            case "SAVINGS" -> "Ahorro";
            case "VIRTUAL_WALLET" -> "Billetera virtual";
            case "DIGITAL_ACCOUNT" -> "Cuenta digital";
            case "CREDIT" -> "Crédito";
            default -> "Banco";
        };
    }

    private static String accountInitial(AccountRepository.Account account) {
        if (account == null || account.name() == null || account.name().isBlank()) {
            return "?";
        }
        return account.name().trim().substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() <= max) {
            return v;
        }
        return v.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    private static PDFont font(boolean bold, boolean italic) {
        if (bold && italic) {
            return FONT_BOLD;
        }
        if (bold) {
            return FONT_BOLD;
        }
        if (italic) {
            return FONT_ITALIC;
        }
        return FONT;
    }

    private static float textWidth(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text == null ? "" : text) / 1000f * size;
    }

    private static void drawText(PDPageContentStream cs, String text, float x, float y, PDFont font, float size, Color color) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        cs.beginText();
        cs.setNonStrokingColor(color);
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safeText(text));
        cs.endText();
    }

    private static void drawTextAligned(PDPageContentStream cs, String text, float xRight, float y, PDFont font, float size, Color color, boolean center) throws IOException {
        String safe = safeText(text);
        float width = textWidth(font, size, safe);
        float x = center ? xRight - width / 2f : xRight - width;
        drawText(cs, safe, x, y, font, size, color);
    }

    private static void drawWrappedText(PDPageContentStream cs, String text, float x, float yTop, float maxWidth, PDFont font, float size, Color color, float lineGap) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] paragraphs = text.split("\\r?\\n");
        float y = yTop;
        for (String paragraph : paragraphs) {
            List<String> lines = wrap(paragraph, font, size, maxWidth);
            if (lines.isEmpty()) {
                y -= lineGap;
                continue;
            }
            for (String line : lines) {
                drawText(cs, line, x, y, font, size, color);
                y -= lineGap;
            }
        }
    }

    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        String source = text == null ? "" : text.trim();
        if (source.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : source.split("\\s+")) {
            String test = current.isEmpty() ? word : current + " " + word;
            if (textWidth(font, size, test) <= maxWidth) {
                current.setLength(0);
                current.append(test);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (textWidth(font, size, word) <= maxWidth) {
                current.append(word);
            } else {
                lines.addAll(splitLongWord(word, font, size, maxWidth));
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static List<String> splitLongWord(String word, PDFont font, float size, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : word.toCharArray()) {
            String test = current + String.valueOf(ch);
            if (textWidth(font, size, test) <= maxWidth) {
                current.append(ch);
            } else {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                current.append(ch);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static String safeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static void drawRect(PDPageContentStream cs, float x, float y, float w, float h, Color fill) throws IOException {
        cs.setNonStrokingColor(fill);
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    private static void drawStrokeRect(PDPageContentStream cs, float x, float y, float w, float h, Color stroke, float lineWidth) throws IOException {
        cs.setStrokingColor(stroke);
        cs.setLineWidth(lineWidth);
        cs.addRect(x, y, w, h);
        cs.stroke();
    }

    private static void drawLine(PDPageContentStream cs, float x1, float y1, float x2, float y2, Color stroke, float lineWidth) throws IOException {
        cs.setStrokingColor(stroke);
        cs.setLineWidth(lineWidth);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private static void drawRoundedChip(PDPageContentStream cs, float x, float y, float w, float h, Color fill, Color stroke) throws IOException {
        drawRoundedRect(cs, x, y, w, h, 10f, fill);
        drawRoundedRectStroke(cs, x, y, w, h, 10f, stroke, 0.6f);
    }

    private static void drawRoundedRect(PDPageContentStream cs, float x, float y, float w, float h, float radius, Color fill) throws IOException {
        cs.setNonStrokingColor(fill);
        cs.moveTo(x + radius, y);
        cs.lineTo(x + w - radius, y);
        cs.curveTo(x + w, y, x + w, y + radius, x + w, y + radius);
        cs.lineTo(x + w, y + h - radius);
        cs.curveTo(x + w, y + h, x + w - radius, y + h, x + w - radius, y + h);
        cs.lineTo(x + radius, y + h);
        cs.curveTo(x, y + h, x, y + h - radius, x, y + h - radius);
        cs.lineTo(x, y + radius);
        cs.curveTo(x, y, x + radius, y, x + radius, y);
        cs.fill();
    }

    private static void drawRoundedRectStroke(PDPageContentStream cs, float x, float y, float w, float h, float radius, Color stroke, float lineWidth) throws IOException {
        cs.setStrokingColor(stroke);
        cs.setLineWidth(lineWidth);
        cs.moveTo(x + radius, y);
        cs.lineTo(x + w - radius, y);
        cs.curveTo(x + w, y, x + w, y + radius, x + w, y + radius);
        cs.lineTo(x + w, y + h - radius);
        cs.curveTo(x + w, y + h, x + w - radius, y + h, x + w - radius, y + h);
        cs.lineTo(x + radius, y + h);
        cs.curveTo(x, y + h, x, y + h - radius, x, y + h - radius);
        cs.lineTo(x, y + radius);
        cs.curveTo(x, y, x + radius, y, x + radius, y);
        cs.stroke();
    }

    private static void drawBanner(PDDocument document, PageState page, String brand, String title, String subtitle, String userName, String generatedAt) throws IOException {
        PDPageContentStream cs = page.stream;
        drawRect(cs, 0f, PAGE_HEIGHT - 110f, PAGE_WIDTH, 110f, PRIMARY);
        
        // Draw logo
        try {
            PDImageXObject logo = PDImageXObject.createFromFile("src/main/resources/images/xpendz.png", document);
            float logoSize = 28f;
            cs.drawImage(logo, MARGIN, PAGE_HEIGHT - 42f, logoSize, logoSize);
        } catch (Exception e) {
            // Logo not found, continue without it
        }
        
        drawText(cs, brand, MARGIN + 38f, PAGE_HEIGHT - 34f, FONT_BOLD, 18f, WHITE);
        drawText(cs, title, MARGIN + 38f, PAGE_HEIGHT - 56f, FONT, 12f, hex("#BFDBFE"));
        drawText(cs, subtitle, MARGIN + 38f, PAGE_HEIGHT - 74f, FONT, 9.5f, WHITE);
        drawTextAligned(cs, "Usuario: " + userName, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 34f, FONT, 9.5f, WHITE, false);
        drawTextAligned(cs, "Generado: " + generatedAt, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 52f, FONT, 8.5f, hex("#BFDBFE"), false);
        page.y = PAGE_HEIGHT - 156f;
    }

    private static void drawCompactHeader(PageState page, String title, String rightText) throws IOException {
        PDPageContentStream cs = page.stream;
        drawRect(cs, 0f, PAGE_HEIGHT - 40f, PAGE_WIDTH, 40f, PRIMARY);
        drawText(cs, title, MARGIN, PAGE_HEIGHT - 26f, FONT, 10f, WHITE);
        if (rightText != null && !rightText.isBlank()) {
            drawTextAligned(cs, rightText, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 26f, FONT, 9f, hex("#BFDBFE"), false);
        }
        page.y = PAGE_HEIGHT - 70f;
    }

    private static void drawFooter(PageState page, String footerLeft) throws IOException {
        PDPageContentStream cs = page.stream;
        float fy = 20f;
        drawLine(cs, MARGIN, fy + 10f, PAGE_WIDTH - MARGIN, fy + 10f, BORDER, 0.8f);
        drawText(cs, footerLeft, MARGIN, fy, FONT, 8f, MUTED);
        drawTextAligned(cs, "Pág. " + page.pageNumber, PAGE_WIDTH - MARGIN, fy, FONT, 8f, MUTED, false);
    }

    private static void drawCenteredMessage(PageState page, String message) throws IOException {
        PDPageContentStream cs = page.stream;
        drawTextAligned(cs, message, PAGE_WIDTH / 2f, page.y - 6f, FONT, 10f, MUTED, true);
        page.y -= 22f;
    }

    private static void drawSectionTitle(PageState page, String title, Color color) throws IOException {
        float h = 22f;
        drawRect(page.stream, MARGIN, page.y, CONTENT_WIDTH, h, color);
        drawTextAligned(page.stream, title, MARGIN + CONTENT_WIDTH / 2f, page.y + h / 2f + 2f, FONT_BOLD, 9f, WHITE, true);
        page.y -= 24f;
    }

    private static void drawChip(PageState page, String text, Color color, Color bg, Color border, float xOffset, float width) throws IOException {
        float chipY = page.y;
        drawRoundedChip(page.stream, MARGIN + xOffset, chipY, width, 20f, bg, border);
        drawTextAligned(page.stream, text, MARGIN + xOffset + width / 2f, chipY + 6f, FONT_BOLD, 9f, color, true);
        page.y -= 26f;
    }

    private static void drawSavingsRateChip(PageState page, int savingsRate) throws IOException {
        Color rateColor = savingsRate >= 20 ? INCOME : (savingsRate >= 0 ? WARN : EXPENSE);
        Color rateBg = savingsRate >= 20 ? INCOME_BG : (savingsRate >= 0 ? WARN_BG : EXPENSE_BG);
        float width = 190f;
        drawRoundedChip(page.stream, MARGIN, page.y, width, 20f, rateBg, rateColor);
        drawText(page.stream, "Tasa de ahorro: " + savingsRate + "%", MARGIN + 10f, page.y + 6f, FONT_BOLD, 9f, rateColor);
        page.y -= 24f;
    }

    private static void drawSummaryCards(PageState page, List<CardSpec> cards) throws IOException {
        float gap = 8f;
        float cardW = (CONTENT_WIDTH - (gap * 2f)) / 3f;
        float cardH = 58f;
        float y = page.y;
        float radius = 8f;
        for (int i = 0; i < cards.size(); i++) {
            CardSpec card = cards.get(i);
            float x = MARGIN + i * (cardW + gap);
            drawRoundedRect(page.stream, x + 1f, y + 2f, cardW, cardH + 2f, radius, BORDER);
            drawRoundedRect(page.stream, x, y, cardW, cardH, radius, WHITE);
            drawRoundedRectStroke(page.stream, x, y, cardW, cardH, radius, BORDER, 0.6f);
            drawRect(page.stream, x, y, cardW, 4f, card.color());
            drawText(page.stream, card.label(), x + 10f, y + 22f, FONT, 8.5f, MUTED);
            drawText(page.stream, card.value(), x + 10f, y + 43f, FONT_BOLD, 12.5f, card.color());
        }
        page.y -= 36f;
    }

    private void drawTransactionsHeader(PageState page) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, 22f, PRIMARY_DARK);
        float dateW = 62f;
        float typeW = 60f;
        float catW = 112f;
        float accW = 104f;
        float descW = 145f;
        float amtW = CONTENT_WIDTH - dateW - typeW - catW - accW - descW;
        drawTextAligned(cs, "Fecha", MARGIN + dateW / 2f, y + 13.5f, FONT_BOLD, 8f, WHITE, true);
        drawTextAligned(cs, "Tipo", MARGIN + dateW + typeW / 2f, y + 13.5f, FONT_BOLD, 8f, WHITE, true);
        drawTextAligned(cs, "Categoría", MARGIN + dateW + typeW + catW / 2f, y + 13.5f, FONT_BOLD, 8f, WHITE, true);
        drawTextAligned(cs, "Cuenta", MARGIN + dateW + typeW + catW + accW / 2f, y + 13.5f, FONT_BOLD, 8f, WHITE, true);
        drawTextAligned(cs, "Descripción", MARGIN + dateW + typeW + catW + accW + descW / 2f, y + 13.5f, FONT_BOLD, 8f, WHITE, true);
        drawTextAligned(cs, "Monto", MARGIN + CONTENT_WIDTH - 4f, y + 13.5f, FONT_BOLD, 8f, WHITE, false);
        page.y -= 24f;
    }

    private void drawTransactionRow(PageState page, TransactionRepository.TransactionRow row, int index, String currencyCode) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float dateW = 62f;
        float typeW = 60f;
        float catW = 112f;
        float accW = 104f;
        float descW = 145f;

        String descText = row.note() != null && !row.note().isBlank() ? row.note() : "";
        List<String> descLines = wrap(descText, FONT_ITALIC, 7.2f, descW - 8f);
        if (descLines.size() > 2) {
            descLines = descLines.subList(0, 2);
        }
        float rowH = descLines.size() > 1 ? 28f : 20f;

        Color rowBg = index % 2 == 0 ? WHITE : SURFACE;
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, rowH, rowBg);
        Color accent = isIncomeKind(row.kind()) ? INCOME : EXPENSE;
        drawRect(cs, MARGIN, y, 3f, rowH, accent);

        float centerY = y + rowH / 2f;
        
        String date = LocalDate.ofInstant(java.time.Instant.ofEpochSecond(row.occurredAtEpochSec()), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-CO")));
        drawText(cs, date, MARGIN + 6f, centerY + 3f, FONT, 8f, MUTED);
        
        String typeLabel = isIncomeKind(row.kind()) ? "Ingreso" : (isExpenseKind(row.kind()) ? "Gasto" : row.kind());
        Color typeBg = isIncomeKind(row.kind()) ? INCOME_BG : EXPENSE_BG;
        Color typeColor = isIncomeKind(row.kind()) ? INCOME : EXPENSE;
        float typeChipX = MARGIN + dateW + 4f;
        float typeChipW = typeW - 8f;
        float typeChipY = centerY - 7f;
        drawRoundedRect(cs, typeChipX, typeChipY, typeChipW, 14f, 6f, typeBg);
        drawTextAligned(cs, typeLabel, typeChipX + typeChipW / 2f, centerY, FONT_BOLD, 7.2f, typeColor, true);
        
        drawText(cs, truncate(row.categoryName(), 18), MARGIN + dateW + typeW + 4f, centerY + 3f, FONT, 8f, TEXT);
        drawText(cs, truncate(row.accountName(), 16), MARGIN + dateW + typeW + catW + 4f, centerY + 3f, FONT, 8f, MUTED);
        
        float descX = MARGIN + dateW + typeW + catW + accW + 4f;
        float descY = descLines.size() > 1 ? centerY + 4f : centerY + 3f;
        for (String line : descLines) {
            drawText(cs, line, descX, descY, FONT_ITALIC, 7.2f, MUTED);
            descY -= 8f;
        }
        
        String amt = money(Math.abs(row.amountCents()), currencyCode);
        drawTextAligned(cs, amt, PAGE_WIDTH - MARGIN - 4f, centerY + 3f, FONT_BOLD, 8f, accent, false);
        
        drawLine(cs, MARGIN, y, PAGE_WIDTH - MARGIN, y, BORDER, 0.4f);
        page.y -= rowH;
    }

    private void drawAccountCard(PageState page, AccountBalanceLine line, long totalBalance) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float h = 42f;
        float radius = 6f;
        Color accent = line.color();
        
        // Rounded card background with subtle border
        drawRoundedRect(cs, MARGIN, y, CONTENT_WIDTH, h, radius, WHITE);
        drawRoundedRectStroke(cs, MARGIN, y, CONTENT_WIDTH, h, radius, BORDER, 0.4f);
        
        // Left accent bar (rounded on left side)
        drawRoundedRect(cs, MARGIN, y, 4f, h, radius, accent);
        
        // Avatar background
        drawRoundedRect(cs, MARGIN + 16f, y + 10f, 22f, 22f, 5f, tint(accent, 0.18f));
        drawTextAligned(cs, accountInitial(line.account()), MARGIN + 27f, y + 18f, FONT_BOLD, 10f, accent, true);
        
        // Account name and type
        drawText(cs, line.account().name(), MARGIN + 48f, y + 24f, FONT_BOLD, 9.5f, TEXT);
        drawText(cs, accountTypeLabel(line.account().type()), MARGIN + 48f, y + 12f, FONT, 7.5f, MUTED);
        
        // Balance and percentage
        String balanceText = money(line.balanceCents(), "COP");
        Color balanceColor = line.balanceCents() >= 0 ? INCOME : EXPENSE;
        drawTextAligned(cs, balanceText, PAGE_WIDTH - MARGIN - 10f, y + 26f, FONT_BOLD, 9.5f, balanceColor, false);
        
        float pct = totalBalance == 0 ? 0f : Math.abs(line.balanceCents()) / (float) Math.max(1L, Math.abs(totalBalance));
        String pctText = String.format(Locale.forLanguageTag("es-CO"), "%.1f%%", pct * 100);
        drawTextAligned(cs, pctText, PAGE_WIDTH - MARGIN - 10f, y + 12f, FONT, 7.5f, MUTED, false);
        
        // Progress bar
        float barW = 100f;
        drawRoundedRect(cs, PAGE_WIDTH - MARGIN - barW - 10f, y + 6f, barW, 4f, 2f, SURFACE);
        drawRoundedRect(cs, PAGE_WIDTH - MARGIN - barW - 10f, y + 6f, Math.max(4f, barW * Math.min(1f, pct)), 4f, 2f, accent);
        
        page.y -= 48f;
    }

    private void drawBudgetHeader(PageState page) throws IOException {
        PDPageContentStream cs = page.stream;
        drawRect(cs, MARGIN, page.y, CONTENT_WIDTH, 18f, TABLE_HEADER);
        drawText(cs, "Categoría", MARGIN + 6f, page.y + 12f, FONT_BOLD, 7.5f, WHITE);
        drawText(cs, "Límite", MARGIN + 218f, page.y + 12f, FONT_BOLD, 7.5f, WHITE);
        drawText(cs, "Gastado", MARGIN + 300f, page.y + 12f, FONT_BOLD, 7.5f, WHITE);
        drawText(cs, "Uso", MARGIN + 392f, page.y + 12f, FONT_BOLD, 7.5f, WHITE);
        drawTextAligned(cs, "Estado", PAGE_WIDTH - MARGIN - 6f, page.y + 12f, FONT_BOLD, 7.5f, WHITE, false);
        page.y -= 18f;
    }

    private void drawBudgetRow(PageState page, BudgetRepository.BudgetProgress budget) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float h = 24f;
        long limit = budget.budget().limitCents();
        long spent = budget.spentCents();
        int pct = limit > 0 ? (int) ((spent * 100) / limit) : 0;
        Color accent = pct >= 100 ? EXPENSE : (pct >= 80 ? WARN : INCOME);
        Color rowBg = pct >= 100 ? EXPENSE_BG : (pct >= 80 ? WARN_BG : WHITE);
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, h, rowBg);
        drawRect(cs, MARGIN, y, 3f, h, accent);
        drawText(cs, truncate(budget.budget().categoryId(), 22), MARGIN + 6f, y + 8f, FONT, 7.8f, TEXT);
        drawText(cs, money(limit, budget.budget().currency()), MARGIN + 218f, y + 8f, FONT, 7.8f, MUTED);
        drawText(cs, money(spent, budget.budget().currency()), MARGIN + 300f, y + 8f, FONT_BOLD, 7.8f, accent);
        drawText(cs, pct + "%", MARGIN + 392f, y + 8f, FONT_BOLD, 7.8f, accent);
        float barX = MARGIN + 432f;
        float barW = 82f;
        drawRect(cs, barX, y + 9f, barW, 5f, SURFACE);
        drawRect(cs, barX, y + 9f, Math.max(5f, barW * Math.min(1f, pct / 100f)), 5f, accent);
        drawTextAligned(cs, pct >= 100 ? "Excedido" : (pct >= 80 ? "Al límite" : "OK"), PAGE_WIDTH - MARGIN - 6f, y + 8f, FONT_BOLD, 7f, accent, false);
        page.y -= h;
    }

    private void drawBudgetRootRow(PageState page, String rootName, long rootLimit, long rootSpent, int rootPct, Color rootColor, String currencyCode) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float h = 22f;
        Color rootBg = rootColor == INCOME ? INCOME_BG : (rootColor == WARN ? WARN_BG : EXPENSE_BG);
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, h, rootBg);
        drawRect(cs, MARGIN, y, 4f, h, rootColor);
        drawText(cs, truncate(rootName, 30), MARGIN + 10f, y + h / 2f - 1f, FONT_BOLD, 8.5f, TEXT);
        drawText(cs, money(rootLimit, currencyCode), MARGIN + 218f, y + h / 2f - 1f, FONT, 8.5f, MUTED);
        drawText(cs, money(rootSpent, currencyCode), MARGIN + 300f, y + h / 2f - 1f, FONT_BOLD, 8.5f, rootColor);
        drawText(cs, rootPct + "%", MARGIN + 392f, y + h / 2f - 1f, FONT_BOLD, 8.5f, rootColor);
        drawTextAligned(cs, rootPct >= 100 ? "Excedido" : (rootPct >= 80 ? "Al límite" : "OK"), PAGE_WIDTH - MARGIN - 6f, y + h / 2f - 1f, FONT_BOLD, 8f, rootColor, false);
        page.y -= h;
    }

    private void drawBudgetSubRow(PageState page, BudgetRepository.BudgetProgress budget, String currencyCode, boolean zebra) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float h = 28f;
        long limit = budget.budget().limitCents();
        long spent = budget.spentCents();
        int pct = limit > 0 ? (int) ((spent * 100) / limit) : 0;
        Color accent = pct >= 100 ? EXPENSE : (pct >= 80 ? WARN : INCOME);
        
        // Get category name
        String categoryId = budget.budget().categoryId();
        String categoryName = categoryId;
        try {
            CategoryRepository.Category cat = categoryRepo.getById(budget.budget().userUid(), categoryId);
            if (cat != null) {
                categoryName = (cat.parentId() == null || cat.parentId().isBlank()) ? "General" : cat.name();
            }
        } catch (Exception e) {
            // Use categoryId as fallback
        }
        
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, h, zebra ? WHITE : SURFACE);
        drawRect(cs, MARGIN + 4f, y, 2f, h, accent);
        drawText(cs, truncate(categoryName, 28), MARGIN + 14f, y + 11f, FONT, 7.5f, MUTED);
        drawText(cs, money(limit, currencyCode), MARGIN + 218f, y + 11f, FONT, 7.5f, MUTED);
        drawText(cs, money(spent, currencyCode), MARGIN + 300f, y + 11f, FONT_BOLD, 7.5f, accent);
        drawText(cs, pct + "%", MARGIN + 392f, y + 11f, FONT_BOLD, 7.5f, accent);
        drawTextAligned(cs, pct >= 100 ? "Excedido" : (pct >= 80 ? "Al límite" : "OK"), PAGE_WIDTH - MARGIN - 6f, y + 11f, FONT_BOLD, 7f, accent, false);
        float barX = MARGIN + 432f;
        float barW = 82f;
        drawRect(cs, barX, y + 18f, barW, 4f, SURFACE);
        drawRect(cs, barX, y + 18f, Math.max(4f, barW * Math.min(1f, pct / 100f)), 4f, accent);
        page.y -= h;
    }

    private void drawLoanRow(PageState page, LoanRepository.Loan loan, long paidCents, long remainingCents, float progress, String currencyCode, boolean isLent) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float h = 20f;
        Color accent = isLent ? INCOME : EXPENSE;
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, h, WHITE);
        drawRect(cs, MARGIN, y, 3f, h, accent);
        drawText(cs, truncate(loan.counterpartyName(), 25), MARGIN + 6f, y + h / 2f - 1.5f, FONT, 7.5f, TEXT);
        drawText(cs, money(paidCents, currencyCode), MARGIN + 218f, y + h / 2f - 1.5f, FONT_BOLD, 7.5f, accent);
        drawText(cs, money(remainingCents, currencyCode), MARGIN + 300f, y + h / 2f - 1.5f, FONT_BOLD, 7.5f, WARN);
        drawText(cs, (int) (progress * 100) + "%", MARGIN + 392f, y + h / 2f - 1.5f, FONT_BOLD, 7.5f, MUTED);
        float barX = MARGIN + 432f;
        float barW = 82f;
        drawRect(cs, barX, y + h / 2f - 2f, barW, 4f, SURFACE);
        drawRect(cs, barX, y + h / 2f - 2f, Math.max(4f, barW * Math.min(1f, progress)), 4f, accent);
        page.y -= h;
    }

    private void drawGoalRow(PageState page, GoalRepository.Goal goal, long currentCents, long remainingCents, float progress, String currencyCode) throws IOException {
        PDPageContentStream cs = page.stream;
        float y = page.y;
        float h = 20f;
        drawRect(cs, MARGIN, y, CONTENT_WIDTH, h, WHITE);
        drawRect(cs, MARGIN, y, 3f, h, PRIMARY);
        drawText(cs, truncate(goal.name(), 25), MARGIN + 6f, y + h / 2f - 1.5f, FONT, 7.5f, TEXT);
        drawText(cs, money(currentCents, currencyCode), MARGIN + 218f, y + h / 2f - 1.5f, FONT_BOLD, 7.5f, INCOME);
        drawText(cs, money(remainingCents, currencyCode), MARGIN + 300f, y + h / 2f - 1.5f, FONT_BOLD, 7.5f, WARN);
        drawText(cs, (int) (progress * 100) + "%", MARGIN + 392f, y + h / 2f - 1.5f, FONT_BOLD, 7.5f, MUTED);
        float barX = MARGIN + 432f;
        float barW = 82f;
        drawRect(cs, barX, y + h / 2f - 2f, barW, 4f, SURFACE);
        drawRect(cs, barX, y + h / 2f - 2f, Math.max(4f, barW * Math.min(1f, progress)), 4f, PRIMARY);
        page.y -= h;
    }

    private void drawHierarchySection(PDDocument document, PageState page, List<HierarchyGroup> groups, Color accent, Color accentBg, String currencyCode) throws IOException {
        if (groups.isEmpty()) {
            return;
        }
        float rootRowH = 22f;
        float subRowH = 18f;
        int rootIdx = 0;
        for (HierarchyGroup group : groups) {
            ensureSpace(document, page, rootRowH + subRowH, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
            PDPageContentStream cs = page.stream;
            float y = page.y;
            drawRect(cs, MARGIN, y, CONTENT_WIDTH, rootRowH, accentBg);
            drawRect(cs, MARGIN, y, 4f, rootRowH, accent);
            drawText(cs, truncate(group.rootName(), 30), MARGIN + 10f, y + rootRowH / 2f - 1f, FONT_BOLD, 8.5f, TEXT);
            drawTextAligned(cs, money(group.totalCents(), currencyCode), PAGE_WIDTH - MARGIN - 6f, y + rootRowH / 2f - 1f, FONT_BOLD, 8.5f, accent, false);
            drawLine(cs, MARGIN, y + rootRowH, MARGIN + CONTENT_WIDTH, y + rootRowH, BORDER, 0.4f);
            page.y -= rootRowH;

            int subIdx = 0;
            for (HierarchyItem item : group.items()) {
                ensureSpace(document, page, subRowH + 1f, () -> drawCompactHeader(page, "Xpendz · Resumen Mensual", "Pág. " + page.pageNumber));
                cs = page.stream;
                y = page.y;
                Color rowBg = ((rootIdx + subIdx) % 2 == 0) ? WHITE : SURFACE;
                drawRect(cs, MARGIN, y, CONTENT_WIDTH, subRowH, rowBg);
                drawRect(cs, MARGIN + 4f, y, 2f, subRowH, accent);
                drawText(cs, truncate(item.label(), 28), MARGIN + 14f, y + subRowH / 2f - 1.5f, FONT, 7.5f, MUTED);
                drawTextAligned(cs, money(item.totalCents(), currencyCode), PAGE_WIDTH - MARGIN - 6f, y + subRowH / 2f - 1.5f, FONT_BOLD, 7.5f, TEXT, false);
                drawLine(cs, MARGIN + 14f, y + subRowH, MARGIN + CONTENT_WIDTH, y + subRowH, BORDER, 0.3f);
                page.y -= subRowH;
                subIdx++;
            }
            page.y -= 2f;
            rootIdx++;
        }
    }

    private static PageState newPage(PDDocument document) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return new PageState(document.getNumberOfPages(), page, new PDPageContentStream(document, page), PAGE_HEIGHT - MARGIN);
    }

    private static void ensureSpace(PDDocument document, PageState page, float requiredHeight, PageHeaderPainter painter) throws IOException {
        if (page.y - requiredHeight < BOTTOM_GUARD) {
            drawFooter(page, "Xpendz · Reporte confidencial");
            page.stream.close();
            PageState next = newPage(document);
            page.copyFrom(next);
            painter.paint();
        }
    }

    private static Color tint(Color color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }

    private record CardSpec(String label, String value, Color color) {
    }

    private record AccountBalanceLine(AccountRepository.Account account, long balanceCents, Color color) {
    }

    private record HierarchyItem(String label, long totalCents) {
    }

    private record HierarchyGroup(String rootId, String rootName, long totalCents, List<HierarchyItem> items) {
    }

    private static final class PageState {
        private int pageNumber;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        private PageState(int pageNumber, PDPage page, PDPageContentStream stream, float y) {
            this.pageNumber = pageNumber;
            this.page = page;
            this.stream = stream;
            this.y = y;
        }

        private void copyFrom(PageState other) {
            this.pageNumber = other.pageNumber;
            this.page = other.page;
            this.stream = other.stream;
            this.y = other.y;
        }
    }

    @FunctionalInterface
    private interface PageHeaderPainter {
        void paint() throws IOException;
    }
}
