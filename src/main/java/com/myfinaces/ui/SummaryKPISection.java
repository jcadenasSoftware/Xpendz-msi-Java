package com.myfinaces.ui;

import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryKPISection {

    private SummaryKPISection() {
    }

    public record Insights(
        long totalAnnualCents,
        long avgMonthlyCents,
        int bestMonth,
        long bestMonthCents,
        String dominantCategoryName,
        long dominantCategoryCents,
        double dominantCategoryShare,
        long currentMonthCents,
        long prevMonthCents
    ) {
    }

    public static Insights computeInsightsForYear(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo,
        int year
    ) {
        return computeInsights(userUid, txRepo, categoryRepo, year);
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo
    ) {
        int year = LocalDate.now().getYear();
        Insights insights = computeInsights(userUid, txRepo, categoryRepo, year);

        HBox grid = new HBox(12);
        grid.setPadding(new Insets(0));
        grid.getStyleClass().add("summary-kpi-grid");

        String currencyCode = "COP";

        Node c1 = SummaryKPICard.build(
            "Total anual de gastos",
            DashboardFormatters.formatMoney(insights.totalAnnualCents(), currencyCode),
            "Todos los gastos del año",
            "fas-dollar-sign",
            "kpi-badge-blue"
        );
        HBox.setHgrow(c1, Priority.ALWAYS);
        grid.getChildren().add(c1);

        Node c2 = SummaryKPICard.build(
            "Promedio mensual",
            DashboardFormatters.formatMoney(insights.avgMonthlyCents(), currencyCode),
            "Promedio de los meses",
            "fas-chart-line",
            "kpi-badge-indigo"
        );
        HBox.setHgrow(c2, Priority.ALWAYS);
        grid.getChildren().add(c2);

        String monthLabel = insights.bestMonth() <= 0 ? "—" : Month.of(insights.bestMonth()).getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-CO"));
        String bestValue = insights.bestMonth() <= 0 ? "—" : capitalize(monthLabel);
        String bestMeta = insights.bestMonth() <= 0 ? "Sin datos" : DashboardFormatters.formatMoney(insights.bestMonthCents(), currencyCode);
        Node c3 = SummaryKPICard.build(
            "Mes con mayor gasto",
            bestValue,
            bestMeta,
            "fas-calendar-check",
            "kpi-badge-green"
        );
        HBox.setHgrow(c3, Priority.ALWAYS);
        grid.getChildren().add(c3);

        String domName = insights.dominantCategoryName() == null || insights.dominantCategoryName().isBlank() ? "—" : insights.dominantCategoryName();
        String domMeta = insights.dominantCategoryCents() <= 0
            ? "Sin datos"
            : String.format(Locale.forLanguageTag("es-CO"), "%.1f%% del total anual", (insights.dominantCategoryShare() * 100.0));
        Node c4 = SummaryKPICard.build(
            "Categoría dominante",
            domName,
            domMeta,
            "fas-tag",
            "kpi-badge-amber"
        );
        HBox.setHgrow(c4, Priority.ALWAYS);
        grid.getChildren().add(c4);

        String variationValue = formatVariation(insights.currentMonthCents(), insights.prevMonthCents());
        String variationMeta = "vs mes anterior";
        Node c5 = SummaryKPICard.build(
            "Variación mensual (prom.)",
            variationValue,
            variationMeta,
            "fas-percentage",
            "kpi-badge-pink"
        );
        HBox.setHgrow(c5, Priority.ALWAYS);
        grid.getChildren().add(c5);

        VBox section = new VBox(10, grid);
        section.setMinWidth(0);
        return section;
    }

    private static Insights computeInsights(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo,
        int year
    ) {
        String kind = "EXPENSE";
        int currentMonth = LocalDate.now().getMonthValue();
        int prevMonth = currentMonth <= 1 ? 0 : currentMonth - 1;

        Map<Integer, Long> totalByMonth = new HashMap<>();
        Map<String, long[]> totalsByRoot = new HashMap<>();
        Map<String, String> rootNameById = new HashMap<>();

        try {
            for (CategoryRepository.Category r : categoryRepo.listRoots(userUid)) {
                if (r == null || r.id() == null) {
                    continue;
                }
                if (r.kind() == null || !r.kind().equalsIgnoreCase(kind)) {
                    continue;
                }
                rootNameById.put(r.id(), r.name());
                totalsByRoot.put(r.id(), new long[13]);
            }
        } catch (Exception ignored) {
        }

        try {
            for (TransactionRepository.MonthlyCategoryTotal row : txRepo.listMonthlyTotalsByRootCategory(userUid, null, year, kind)) {
                if (row == null) {
                    continue;
                }
                int m = row.month();
                if (m < 1 || m > 12) {
                    continue;
                }
                long v = row.totalAmountCents();
                totalByMonth.put(m, totalByMonth.getOrDefault(m, 0L) + v);

                long[] arr = totalsByRoot.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                arr[m] += v;
                if (row.rootCategoryName() != null && !row.rootCategoryName().isBlank()) {
                    rootNameById.putIfAbsent(row.rootCategoryId(), row.rootCategoryName());
                }
            }
        } catch (Exception ignored) {
        }

        long annual = 0L;
        int bestMonth = 0;
        long bestMonthCents = 0L;
        for (int m = 1; m <= 12; m++) {
            long v = totalByMonth.getOrDefault(m, 0L);
            annual += v;
            if (v > bestMonthCents) {
                bestMonthCents = v;
                bestMonth = m;
            }
        }

        int monthsElapsed = LocalDate.now().getYear() == year ? Math.max(1, Math.min(12, currentMonth - 1)) : 12;
        long avg = monthsElapsed <= 0 ? 0L : (annual / monthsElapsed);

        String dominantCategoryName = "";
        long dominantCategoryCents = 0L;
        for (Map.Entry<String, long[]> e : totalsByRoot.entrySet()) {
            String rootId = e.getKey();
            long[] months = e.getValue();
            long sum = 0L;
            for (int m = 1; m <= 12; m++) {
                sum += months[m];
            }
            if (sum > dominantCategoryCents) {
                dominantCategoryCents = sum;
                dominantCategoryName = rootNameById.getOrDefault(rootId, "");
            }
        }

        long cur = totalByMonth.getOrDefault(currentMonth, 0L);
        long prev = prevMonth <= 0 ? 0L : totalByMonth.getOrDefault(prevMonth, 0L);

        double share = annual <= 0L ? 0.0 : ((double) dominantCategoryCents / (double) annual);

        return new Insights(
            annual,
            avg,
            bestMonth,
            bestMonthCents,
            dominantCategoryName,
            dominantCategoryCents,
            share,
            cur,
            prev
        );
    }

    private static String formatVariation(long currentCents, long prevCents) {
        if (prevCents <= 0L) {
            if (currentCents <= 0L) {
                return "—";
            }
            return "+100%";
        }
        double pct = ((double) currentCents / (double) prevCents) - 1.0;
        double value = pct * 100.0;
        return String.format(Locale.ROOT, "%+.1f%%", value);
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.forLanguageTag("es-CO")) + trimmed.substring(1);
    }
}
