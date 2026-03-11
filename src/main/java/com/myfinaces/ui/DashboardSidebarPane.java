package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.Separator;

public final class DashboardSidebarPane {

    private DashboardSidebarPane() {
    }

    public static ScrollPane build(
        String userEmail,
        Button transactions,
        Button transfers,
        Button summary,
        Button loans,
        Button budget,
        Button charts,
        Button addAccount,
        Button categories,
        Button syncNow,
        Button logout,
        Button exit
    ) {
        Label userCaption = new Label("Usuario");
        userCaption.getStyleClass().addAll("text-secondary", "sidebar-user-label");

        Label userEmailLabel = new Label(userEmail);
        userEmailLabel.getStyleClass().add("sidebar-user-email");
        VBox userBox = new VBox(2, userCaption, userEmailLabel);
        userBox.getStyleClass().add("sidebar-user");

        VBox menu = new VBox(12);
        menu.getStyleClass().add("sidebar");
        menu.setPadding(new Insets(16));
        menu.setPrefWidth(260);
        menu.setMinWidth(260);

        ImageView logo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                logo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setFitWidth(72);

        Label appName = new Label("Mis Finanzas");
        appName.getStyleClass().addAll("sidebar-title", "sidebar-title-gold");

        VBox brand = new VBox(6, logo, appName);
        brand.getStyleClass().add("sidebar-brand");
        brand.setMinHeight(Region.USE_PREF_SIZE);

        VBox menuTop = new VBox(6, brand, userBox);
        VBox menuMainActions = new VBox(10, transactions, transfers, summary, loans, budget, charts);
        menuMainActions.getStyleClass().add("sidebar-actions");
        VBox.setVgrow(menuMainActions, Priority.NEVER);

        Separator actionsSeparator = new Separator();
        actionsSeparator.getStyleClass().add("sidebar-separator");

        VBox menuSecondaryActions = new VBox(10, addAccount, categories, syncNow, logout, exit);
        menuSecondaryActions.getStyleClass().add("sidebar-actions");
        VBox.setVgrow(menuSecondaryActions, Priority.NEVER);

        Label footerCopyright = new Label(" JCadenas Software");
        footerCopyright.getStyleClass().add("sidebar-footer-text");
        Hyperlink footerLink = new Hyperlink("www.jcadenas.com");
        footerLink.getStyleClass().add("sidebar-footer-link");
        footerLink.setUserData("https://jcadenas.com/portfolio.php");
        footerLink.setOnAction(e -> {
            try {
                Object url = footerLink.getUserData();
                if (!(url instanceof String s) || s.isBlank()) {
                    return;
                }
                if (!java.awt.Desktop.isDesktopSupported()) {
                    return;
                }
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(s));
            } catch (Exception ignored) {
            }
        });
        VBox footer = new VBox(4, footerCopyright, footerLink);
        footer.getStyleClass().add("sidebar-footer");
        footer.setAlignment(Pos.CENTER);

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        menu.getChildren().addAll(menuTop, menuMainActions, actionsSeparator, menuSecondaryActions, spacer, footer);

        ScrollPane sidebarScroll = new ScrollPane(menu);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setFitToHeight(true);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.getStyleClass().add("sidebar-scroll");

        menu.minHeightProperty().bind(sidebarScroll.heightProperty());

        return sidebarScroll;
    }
}
