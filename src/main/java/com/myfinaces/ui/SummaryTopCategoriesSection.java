package com.myfinaces.ui;

import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryTopCategoriesSection {

    private SummaryTopCategoriesSection() {
    }

    private record TopCat(
        String rootId,
        String name,
        long totalYearCents,
        long currentMonthCents,
        long prevMonthCents
    ) {
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo
    ) {
        int year = LocalDate.now().getYear();
        String kind = "EXPENSE";

        int currentMonth = LocalDate.now().getMonthValue();
        int prevMonth = currentMonth <= 1 ? 0 : currentMonth - 1;

        Map<String, long[]> totalsByRootMonth = new HashMap<>();
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
                totalsByRootMonth.put(r.id(), new long[13]);
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
                long[] arr = totalsByRootMonth.computeIfAbsent(row.rootCategoryId(), __ -> new long[13]);
                arr[m] += v;
                if (row.rootCategoryName() != null && !row.rootCategoryName().isBlank()) {
                    rootNameById.putIfAbsent(row.rootCategoryId(), row.rootCategoryName());
                }
            }
        } catch (Exception ignored) {
        }

        List<TopCat> cats = new ArrayList<>();
        long totalYear = 0L;
        for (Map.Entry<String, long[]> e : totalsByRootMonth.entrySet()) {
            String id = e.getKey();
            long[] arr = e.getValue();
            long sum = 0L;
            for (int m = 1; m <= 12; m++) {
                sum += arr[m];
            }
            totalYear += sum;

            String name = rootNameById.getOrDefault(id, "Desconocido");
            long curr = currentMonth >= 1 && currentMonth <= 12 ? arr[currentMonth] : 0L;
            long prev = prevMonth >= 1 && prevMonth <= 12 ? arr[prevMonth] : 0L;
            cats.add(new TopCat(id, name, sum, curr, prev));
        }

        cats.sort((a, b) -> Long.compare(b.totalYearCents(), a.totalYearCents()));
        if (cats.size() > 5) {
            cats = cats.subList(0, 5);
        }

        Label title = new Label("Top categorías");
        title.getStyleClass().add("account-name");

        String totalLabel = totalYear <= 0L
            ? "Sin datos este año"
            : "Total año: " + DashboardFormatters.formatMoney(totalYear, "COP");
        Label subtitle = new Label(totalLabel);
        subtitle.getStyleClass().add("text-secondary");

        VBox list = new VBox(10);
        list.setMinWidth(0);
        list.getStyleClass().add("summary-topcat-list");

        if (cats.isEmpty()) {
            Label empty = new Label("Sin datos este año");
            empty.getStyleClass().add("text-secondary");
            list.getChildren().add(empty);
        } else {
            long max = cats.stream().mapToLong(TopCat::totalYearCents).max().orElse(0L);
            for (TopCat c : cats) {
                list.getChildren().add(buildRow(c, totalYear, max));
            }
        }

        VBox section = new VBox(12, title, subtitle, list);
        section.setMinWidth(0);
        section.setPadding(new Insets(0));
        return section;
    }

    private static Node buildRow(TopCat c, long totalYear, long max) {
        double share = totalYear <= 0L ? 0.0 : ((double) c.totalYearCents() / (double) totalYear);
        String pct = String.format(Locale.forLanguageTag("es-CO"), "%.0f%%", share * 100.0);

        Label name = new Label(c.name());
        name.getStyleClass().add("summary-topcat-name");
        name.setMinWidth(0);

        Label amount = new Label(DashboardFormatters.formatMoney(c.totalYearCents(), "COP"));
        amount.getStyleClass().add("summary-topcat-amount");

        Label pctLabel = new Label(pct);
        pctLabel.getStyleClass().add("summary-topcat-pct");

        HBox header = new HBox(10, name, pctLabel, amount);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);

        ProgressBar bar = new ProgressBar(max <= 0L ? 0.0 : ((double) c.totalYearCents() / (double) max));
        bar.getStyleClass().add("summary-topcat-bar");
        bar.setMaxWidth(Double.MAX_VALUE);

        Label trend = new Label(formatTrend(c.currentMonthCents(), c.prevMonthCents()));
        trend.getStyleClass().add("summary-topcat-trend");

        VBox row = new VBox(6, header, bar, trend);
        row.setMinWidth(0);
        row.getStyleClass().add("summary-topcat-row");
        row.setPadding(new Insets(12));

        return row;
    }

    private static String formatTrend(long currentCents, long prevCents) {
        if (prevCents <= 0L) {
            if (currentCents <= 0L) {
                return "Sin tendencia";
            }
            return "▲ Subió vs mes anterior";
        }

        double pct = ((double) currentCents / (double) prevCents) - 1.0;
        double v = pct * 100.0;
        if (Math.abs(v) < 0.5) {
            return "● Estable vs mes anterior";
        }

        String formatted = String.format(Locale.ROOT, "%+.0f%%", v);
        if (v > 0) {
            return "▲ " + formatted + " vs mes anterior";
        }
        return "▼ " + formatted + " vs mes anterior";
    }
}
