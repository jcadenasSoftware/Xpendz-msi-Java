package com.myfinaces.ui;

import com.myfinaces.db.TransactionRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

public final class SummaryInsightDrawer {

    public record Context(
        String userUid,
        String title,
        String subtitle,
        int year,
        String kind,
        String currencyCode,
        String accountId,
        String categoryId,
        List<String> categoryIds,
        Integer monthFocus,
        long[] monthsCents,
        long totalCents,
        long avgCents
    ) {

        public Context {
            Objects.requireNonNull(userUid, "userUid");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(currencyCode, "currencyCode");
            if (monthsCents == null || monthsCents.length < 13) {
                monthsCents = new long[13];
            }

            if (categoryIds != null && categoryIds.isEmpty()) {
                categoryIds = null;
            }
        }
    }

    private static final String DRAWER_ID = "summary-insight";

    private final SideDrawer drawer;
    private final TransactionRepository txRepo;
    private final BooleanSupplier darkTheme;

    private final Label contextTitle;
    private final Label contextSubtitle;
    private final Label amountLabel;
    private final DrawerTrendIndicator trendIndicator;

    private final Label chipAvg;
    private final Label chipPeak;
    private final Label chipRecent;
    private final Label chipVar;

    private final DrawerMiniChart miniChart;
    private final DrawerTransactionList txList;

    private final Label finalInsight;

    public SummaryInsightDrawer(
        SideDrawer drawer,
        TransactionRepository txRepo,
        BooleanSupplier darkTheme
    ) {
        this.drawer = Objects.requireNonNull(drawer, "drawer");
        this.txRepo = Objects.requireNonNull(txRepo, "txRepo");
        this.darkTheme = darkTheme == null ? () -> false : darkTheme;

        contextTitle = new Label("Detalle");
        contextTitle.getStyleClass().add("sid-title");

        contextSubtitle = new Label("Selecciona un elemento para analizar");
        contextSubtitle.getStyleClass().add("sid-subtitle");
        contextSubtitle.setWrapText(true);

        VBox titleBox = new VBox(2, contextTitle, contextSubtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button close = drawer.buildCloseButton();

        FontIcon tagIcon = new FontIcon("fas-tag");
        tagIcon.setIconSize(14);
        tagIcon.getStyleClass().add("sid-avatar-icon");

        StackPane avatar = new StackPane(tagIcon);
        avatar.getStyleClass().add("sid-avatar");
        avatar.setMinSize(38, 38);
        avatar.setPrefSize(38, 38);

        HBox headerTop = new HBox(12, avatar, titleBox, close);
        headerTop.setAlignment(Pos.CENTER_LEFT);
        headerTop.getStyleClass().add("sid-header-top");

        amountLabel = new Label(" ");
        amountLabel.getStyleClass().add("sid-amount");
        amountLabel.setMinWidth(0);
        amountLabel.setMaxWidth(Double.MAX_VALUE);

        trendIndicator = new DrawerTrendIndicator();
        trendIndicator.setCompact(true);
        trendIndicator.getNode().getStyleClass().add("sid-variation-badge");

        VBox amountBox = new VBox(0, amountLabel);
        amountBox.getStyleClass().add("sid-amount-box");
        amountBox.setMinWidth(0);
        HBox.setHgrow(amountBox, Priority.ALWAYS);

        ((Region) trendIndicator.getNode()).setMinWidth(Region.USE_PREF_SIZE);
        ((Region) trendIndicator.getNode()).setMaxWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(trendIndicator.getNode(), Priority.NEVER);

        HBox headerBottom = new HBox(12, amountBox, trendIndicator.getNode());
        headerBottom.setAlignment(Pos.CENTER_LEFT);
        headerBottom.getStyleClass().add("sid-header-bottom");
        headerBottom.setMinWidth(0);
        headerBottom.setMaxWidth(Double.MAX_VALUE);

        VBox header = new VBox(10, headerTop, headerBottom);
        header.getStyleClass().add("sid-header-premium");
        header.setMinWidth(0);
        header.setMaxWidth(Double.MAX_VALUE);

        chipAvg = new Label("–");
        chipAvg.getStyleClass().add("sid-quick-value");
        chipPeak = new Label("–");
        chipPeak.getStyleClass().add("sid-quick-value");
        chipRecent = new Label("–");
        chipRecent.getStyleClass().add("sid-quick-value");
        chipVar = new Label("–");
        chipVar.getStyleClass().add("sid-quick-value");

        Node q1 = quickChip("Promedio", chipAvg, "far-clock");
        Node q2 = quickChip("Pico", chipPeak, "fas-chart-line");
        Node q3 = quickChip("Reciente", chipRecent, "far-calendar-alt");
        Node q4 = quickChip("Variación", chipVar, "fas-percentage");

        VBox quickCol1 = new VBox(10, q1, q2);
        VBox quickCol2 = new VBox(10, q3, q4);
        quickCol1.setFillWidth(true);
        quickCol1.setMinWidth(0);
        quickCol1.setPrefWidth(0);
        quickCol2.setFillWidth(true);
        quickCol2.setMinWidth(0);
        quickCol2.setPrefWidth(0);
        HBox quickGrid = new HBox(10, quickCol1, quickCol2);
        quickGrid.getStyleClass().add("sid-quick-grid");
        quickGrid.setMinWidth(0);
        quickGrid.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(quickCol1, Priority.ALWAYS);
        HBox.setHgrow(quickCol2, Priority.ALWAYS);

        VBox quickBlock = new VBox(10, sectionTitleLite("Insights rápidos"), quickGrid);
        quickBlock.getStyleClass().add("sid-block");
        quickBlock.setMinWidth(0);
        quickBlock.setMaxWidth(Double.MAX_VALUE);

        miniChart = new DrawerMiniChart();
        VBox chartBlock = new VBox(10, sectionTitleLite("Mini tendencia"), miniChart.getNode());
        chartBlock.getStyleClass().add("sid-block");
        chartBlock.setMinWidth(0);
        chartBlock.setMaxWidth(Double.MAX_VALUE);

        txList = new DrawerTransactionList();
        VBox txBlock = new VBox(10, sectionTitleLite("Movimientos relacionados"), txList.getNode());
        txBlock.getStyleClass().add("sid-block");
        txBlock.setMinWidth(0);
        txBlock.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(txList.getNode(), Priority.ALWAYS);

        finalInsight = new Label(" ");
        finalInsight.getStyleClass().add("sid-final-insight");
        finalInsight.setWrapText(true);
        FontIcon bulb = new FontIcon("far-lightbulb");
        bulb.setIconSize(14);
        bulb.getStyleClass().add("sid-final-icon");
        finalInsight.setGraphic(bulb);
        finalInsight.setGraphicTextGap(10);

        VBox finalBlock = new VBox(finalInsight);
        finalBlock.getStyleClass().addAll("sid-final-block", "sid-block");
        finalBlock.setMinWidth(0);
        finalBlock.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(14, header, quickBlock, chartBlock, txBlock, finalBlock);
        body.getStyleClass().add("sid-root");
        body.setPadding(new Insets(16, 18, 18, 18));
        body.setFillWidth(true);
        body.setMinWidth(0);
        body.setMaxWidth(Double.MAX_VALUE);

        drawer.register(DRAWER_ID, this.darkTheme.getAsBoolean(), body);
    }

    public void show(Context ctx) {
        if (ctx == null) {
            return;
        }

        contextTitle.setText(ctx.title());
        contextSubtitle.setText(ctx.subtitle() == null ? "" : ctx.subtitle());
        amountLabel.setText(DashboardFormatters.formatMoney(ctx.totalCents(), ctx.currencyCode()));

        TrendStats stats = computeTrendStats(ctx.monthsCents(), ctx.monthFocus());
        trendIndicator.setValue(stats.pctChange(), stats.isUp());

        chipAvg.setText(DashboardFormatters.formatMoney(ctx.avgCents(), ctx.currencyCode()));
        chipPeak.setText(stats.peakLabel());
        chipRecent.setText(stats.recentLabel());
        chipVar.setText(String.format(Locale.ROOT, "%+.0f%%", stats.pctChange()));

        finalInsight.setText(buildInsightLine(ctx, stats));

        miniChart.setSeriesLastN(ctx.monthsCents(), ctx.monthFocus(), 6);

        txList.setLoading();
        try {
            List<TransactionRepository.TransactionRow> rows = loadTransactions(ctx);
            txList.setTransactions(rows, ctx.currencyCode());
        } catch (Exception e) {
            txList.setEmpty("Sin movimientos");
        }

        drawer.setDarkTheme(darkTheme.getAsBoolean());
        drawer.show(DRAWER_ID);
    }

    private List<TransactionRepository.TransactionRow> loadTransactions(Context ctx) throws Exception {
        boolean hasSingle = ctx.categoryId() != null && !ctx.categoryId().isBlank();
        boolean hasMany = ctx.categoryIds() != null && !ctx.categoryIds().isEmpty();
        if (!hasSingle && !hasMany) {
            return List.of();
        }

        Long from = null;
        Long to = null;

        if (ctx.monthFocus() != null) {
            int m = Math.max(1, Math.min(12, ctx.monthFocus()));
            LocalDate start = LocalDate.of(ctx.year(), m, 1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            from = start.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            to = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1;
        }

        if (hasMany) {
            List<String> ids = new java.util.ArrayList<>();
            ids.addAll(ctx.categoryIds());
            return txRepo.listFiltered(
                ctx.userUid(),
                ctx.accountId(),
                ids,
                from,
                to,
                28
            );
        }

        return txRepo.listFiltered(ctx.userUid(), ctx.accountId(), ctx.categoryId(), from, to, 28);
    }

    private static Label sectionTitleLite(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sid-section-title-lite");
        return l;
    }

    private record TrendStats(double pctChange, boolean isUp, String peakLabel, String recentLabel, int peakMonth, long peakAbsCents) {
    }

    private static Node quickChip(String label, Label valueLabel, String iconLiteral) {
        Label l = new Label(label);
        l.getStyleClass().add("sid-quick-label");

        VBox txt = new VBox(2, l, valueLabel);
        txt.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(txt, Priority.ALWAYS);

        FontIcon icon = new FontIcon(iconLiteral == null ? "far-dot-circle" : iconLiteral);
        icon.setIconSize(14);
        icon.getStyleClass().add("sid-quick-icon");

        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("sid-quick-badge");
        badge.setMinSize(28, 28);
        badge.setPrefSize(28, 28);

        HBox root = new HBox(10, badge, txt);
        root.setAlignment(Pos.CENTER_LEFT);
        root.getStyleClass().add("sid-quick-chip");
        return root;
    }

    private static String buildInsightLine(Context ctx, TrendStats stats) {
        if (ctx == null || stats == null) {
            return "";
        }

        int focusMonth = ctx.monthFocus() == null ? 0 : Math.max(1, Math.min(12, ctx.monthFocus()));
        long[] months = ctx.monthsCents();

        boolean isPeak = focusMonth > 0 && stats.peakMonth() == focusMonth && stats.peakAbsCents() > 0;
        String peakPhrase = isPeak ? "Pico del año" : null;

        PatternInfo pattern = analyzePattern(months, stats.peakAbsCents(), focusMonth);

        double pct = stats.pctChange();
        String dir = pct >= 0 ? "subió" : "bajó";
        double abs = Math.abs(pct);

        String focus = focusMonth <= 0 ? "" : (" en " + monthLabel(focusMonth));

        String change;
        if (abs >= 35.0) {
            change = "Variación fuerte: " + dir + " " + formatPct(abs) + focus + ".";
        } else if (abs >= 12.0) {
            change = "Cambio relevante: " + dir + " " + formatPct(abs) + focus + ".";
        } else if (abs > 0.0) {
            change = "Estable: variación de " + formatPct(abs) + focus + ".";
        } else {
            change = "Sin variación frente al mes anterior" + focus + ".";
        }

        String patternHint = null;
        if (pattern != null) {
            if (pattern.isRecurringHigh()) {
                patternHint = "Patrón recurrente";
            } else if (pattern.streakLen() >= 2) {
                patternHint = "Racha de " + pattern.streakLen() + " meses";
            }
        }

        String tag = firstNonBlank(peakPhrase, patternHint);
        if (tag != null) {
            return tag + " · " + change + (abs >= 35.0 ? " Revisa movimientos atípicos." : "");
        }
        return change + (abs >= 35.0 ? " Revisa movimientos atípicos." : "");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private record PatternInfo(boolean isRecurringHigh, int streakLen) {
    }

    private static PatternInfo analyzePattern(long[] monthsCents, long peakAbsCents, int focusMonth) {
        if (monthsCents == null || monthsCents.length < 13 || peakAbsCents <= 0) {
            return new PatternInfo(false, 0);
        }

        // High month = at least 65% of annual peak for this category.
        double highThreshold = 0.65 * (double) peakAbsCents;

        int highCount = 0;
        boolean[] high = new boolean[13];
        for (int i = 1; i <= 12; i++) {
            long v = Math.abs(monthsCents[i]);
            boolean isHigh = v >= highThreshold;
            high[i] = isHigh;
            if (isHigh) {
                highCount++;
            }
        }

        boolean recurring = highCount >= 3;

        int streak = 0;
        if (focusMonth >= 1 && focusMonth <= 12 && high[focusMonth]) {
            int left = focusMonth;
            while (left >= 1 && high[left]) {
                left--;
            }
            int right = focusMonth;
            while (right <= 12 && high[right]) {
                right++;
            }
            streak = Math.max(0, (right - 1) - (left + 1) + 1);
        }

        return new PatternInfo(recurring, streak);
    }

    private static String formatPct(double pct) {
        return String.format(Locale.ROOT, "%.0f%%", pct);
    }

    private static String monthLabel(int month) {
        int m = Math.max(1, Math.min(12, month));
        Locale esCo = Locale.forLanguageTag("es-CO");
        String mm = java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, esCo);
        return mm == null ? "" : mm;
    }

    private static TrendStats computeTrendStats(long[] monthsCents, Integer focusMonth) {
        long peak = 0;
        int peakMonth = 1;
        for (int i = 1; i <= 12; i++) {
            long v = Math.abs(monthsCents[i]);
            if (v > peak) {
                peak = v;
                peakMonth = i;
            }
        }

        int lastMonth = focusMonth != null ? focusMonth : 12;
        lastMonth = Math.max(1, Math.min(12, lastMonth));
        long last = monthsCents[lastMonth];
        int prevMonth = Math.max(1, lastMonth - 1);
        long prev = monthsCents[prevMonth];

        double pct;
        if (prev == 0) {
            pct = last == 0 ? 0 : 100.0;
        } else {
            pct = (100.0 * ((double) (last - prev)) / (double) Math.abs(prev));
        }

        Locale esCo = Locale.forLanguageTag("es-CO");
        String peakLabel = java.time.Month.of(peakMonth).getDisplayName(java.time.format.TextStyle.SHORT, esCo);
        String recentLabel = java.time.Month.of(lastMonth).getDisplayName(java.time.format.TextStyle.SHORT, esCo);

        return new TrendStats(pct, pct >= 0, peakLabel, recentLabel, peakMonth, peak);
    }
}
