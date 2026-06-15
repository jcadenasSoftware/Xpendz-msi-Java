package com.myfinaces.ui;

import com.myfinaces.db.AccountRepository;
import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryHeatmapView {

    private SummaryHeatmapView() {
    }

    public static VBox create() {
        VBox root = new VBox(16);
        root.setMinWidth(0);
        return root;
    }

    public static void setContent(
        VBox root,
        String userUid,
        TransactionRepository txRepo,
        AccountRepository accountRepo,
        CategoryRepository categoryRepo,
        ObjectProperty<SummaryViewSwitcher.ViewMode> viewModeProp,
        SummaryInsightDrawer insightDrawer
    ) {
        int year = LocalDate.now().getYear();
        String kind = "EXPENSE";
        AccountRepository.Account account = null;
        String currencyCode = "COP";

        Node heatmap = build(userUid, txRepo, year, kind, account, currencyCode, (sel) -> {
            if (sel == null || insightDrawer == null) {
                return;
            }

            List<String> ids = null;
            try {
                ids = new java.util.ArrayList<>();
                if (sel.categoryId() != null && !sel.categoryId().isBlank()) {
                    ids.add(sel.categoryId());
                    for (CategoryRepository.Category c : categoryRepo.listChildren(userUid, sel.categoryId())) {
                        if (c != null && c.id() != null) {
                            ids.add(c.id());
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            java.util.Locale esCo = java.util.Locale.forLanguageTag("es-CO");
            String monthLabel = java.time.Month.of(sel.month()).getDisplayName(java.time.format.TextStyle.FULL, esCo);
            String monthCap = monthLabel == null || monthLabel.isBlank() ? "" : (monthLabel.substring(0, 1).toUpperCase(esCo) + monthLabel.substring(1));
            String subtitle = monthCap + " " + year + " · " + ("INCOME".equalsIgnoreCase(kind) ? "Ingresos" : "Gastos");
            long totalCents = sel.cents();
            long avgCents = 0L;
            int count = 0;
            for (int mm = 1; mm <= 12; mm++) {
                if (sel.monthsCents()[mm] != 0) {
                    avgCents += sel.monthsCents()[mm];
                    count++;
                }
            }
            avgCents = count == 0 ? 0L : (avgCents / count);

            insightDrawer.show(new SummaryInsightDrawer.Context(
                userUid,
                sel.categoryName(),
                subtitle,
                year,
                kind,
                currencyCode,
                null,
                sel.categoryId(),
                ids,
                sel.month(),
                sel.monthsCents(),
                totalCents,
                avgCents
            ));
        });

        root.getChildren().setAll(heatmap);
        VBox.setVgrow(heatmap, Priority.ALWAYS);
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        Integer year,
        String kind,
        AccountRepository.Account account,
        String currencyCode
    ) {
        return build(userUid, txRepo, year, kind, account, currencyCode, null);
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        Integer year,
        String kind,
        AccountRepository.Account account,
        String currencyCode,
        java.util.function.Consumer<FinancialHeatmapGrid.Selection> onSelect
    ) {
        int y = year == null ? LocalDate.now().getYear() : year;
        String k = kind == null ? "EXPENSE" : kind;
        String accountId = account == null ? null : account.id();
        String cc = currencyCode == null || currencyCode.isBlank() ? "COP" : currencyCode;

        Node grid = FinancialHeatmapGrid.build(userUid, txRepo, y, k, cc, accountId, onSelect);

        VBox root = new VBox(0, grid);
        root.getStyleClass().add("summary-heatmap-view");
        root.setAlignment(Pos.TOP_LEFT);
        root.setPadding(new Insets(0));
        root.setMinWidth(0);
        return root;
    }
}
