package com.myfinaces.ui;

import com.myfinaces.db.CategoryRepository;
import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class SummaryInsightsSection {

    private SummaryInsightsSection() {
    }

    public static Node build(
        String userUid,
        TransactionRepository txRepo,
        CategoryRepository categoryRepo
    ) {
        int year = LocalDate.now().getYear();
        SummaryKPISection.Insights insights = SummaryKPISection.computeInsightsForYear(userUid, txRepo, categoryRepo, year);

        Label title = new Label("Insights");
        title.getStyleClass().add("account-name");

        HBox row = new HBox(12);
        row.setMinWidth(0);
        row.getStyleClass().add("summary-insights-row");

        String currencyCode = "COP";

        Node bestMonth = buildBestMonthCard(insights, currencyCode);
        Node dominantCategory = buildDominantCategoryCard(insights);
        Node variation = buildVariationCard(insights);
        Node avgMonthly = buildAvgMonthlyCard(insights, currencyCode);
        Node trend = buildTrendCard(insights, currencyCode);

        HBox.setHgrow(bestMonth, Priority.ALWAYS);
        HBox.setHgrow(dominantCategory, Priority.ALWAYS);
        HBox.setHgrow(variation, Priority.ALWAYS);
        HBox.setHgrow(avgMonthly, Priority.ALWAYS);
        HBox.setHgrow(trend, Priority.ALWAYS);

        row.getChildren().addAll(bestMonth, dominantCategory, variation, avgMonthly, trend);

        VBox section = new VBox(12, title, row);
        section.setMinWidth(0);
        section.setPadding(new Insets(0));
        return section;
    }

    private static Node buildBestMonthCard(SummaryKPISection.Insights insights, String currencyCode) {
        String monthLabel = insights.bestMonth() <= 0
            ? "—"
            : Month.of(insights.bestMonth()).getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-CO"));
        String value = insights.bestMonth() <= 0 ? "—" : capitalize(monthLabel);
        String context = insights.bestMonth() <= 0
            ? "Sin datos"
            : DashboardFormatters.formatMoney(insights.bestMonthCents(), currencyCode) + " en ese mes";

        return SummaryInsightCard.build(
            "Mayor gasto del mes",
            value,
            context,
            "fas-calendar-day",
            "insight-tone-green"
        );
    }

    private static Node buildDominantCategoryCard(SummaryKPISection.Insights insights) {
        String name = insights.dominantCategoryName() == null || insights.dominantCategoryName().isBlank()
            ? "—"
            : insights.dominantCategoryName();

        String context;
        if (insights.dominantCategoryCents() <= 0) {
            context = "Sin datos";
        } else {
            context = String.format(Locale.forLanguageTag("es-CO"), "Representa %.1f%% del gasto anual", insights.dominantCategoryShare() * 100.0);
        }

        return SummaryInsightCard.build(
            "Categoría dominante",
            name,
            context,
            "fas-tag",
            "insight-tone-amber"
        );
    }

    private static Node buildVariationCard(SummaryKPISection.Insights insights) {
        String value = formatVariation(insights.currentMonthCents(), insights.prevMonthCents());

        String context;
        if (insights.prevMonthCents() <= 0) {
            context = "vs mes anterior";
        } else {
            context = "Cambio vs mes anterior";
        }

        return SummaryInsightCard.build(
            "Variación mensual",
            value,
            context,
            "fas-percentage",
            "insight-tone-pink"
        );
    }

    private static Node buildAvgMonthlyCard(SummaryKPISection.Insights insights, String currencyCode) {
        String value = DashboardFormatters.formatMoney(insights.avgMonthlyCents(), currencyCode);
        String context = "Promedio mensual del año";

        return SummaryInsightCard.build(
            "Promedio mensual",
            value,
            context,
            "fas-chart-line",
            "insight-tone-indigo"
        );
    }

    private static Node buildTrendCard(SummaryKPISection.Insights insights, String currencyCode) {
        long delta = insights.currentMonthCents() - insights.prevMonthCents();
        String value = (delta == 0)
            ? "0"
            : DashboardFormatters.formatMoney(delta, currencyCode);

        String context;
        if (insights.prevMonthCents() <= 0 && insights.currentMonthCents() <= 0) {
            context = "Sin datos de tendencia";
        } else if (delta > 0) {
            context = "Aceleración del gasto";
        } else if (delta < 0) {
            context = "Desaceleración del gasto";
        } else {
            context = "Sin cambios relevantes";
        }

        return SummaryInsightCard.build(
            "Tendencia financiera",
            value,
            context,
            "fas-wave-square",
            "insight-tone-blue"
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
