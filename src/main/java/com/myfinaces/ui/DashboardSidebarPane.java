package com.myfinaces.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.shape.Circle;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Node;

public final class DashboardSidebarPane {

    private DashboardSidebarPane() {
    }

    public static ScrollPane build(
        String userName,
        String userEmail,
        Button home,
        Button transactions,
        Button transfers,
        Button summary,
        Button loans,
        Button budget,
        Button charts,
        Button addAccount,
        Button categories,
        Button syncNow,
        Node syncStatus,
        Button logout,
        Button exit
    ) {
        String email = userEmail == null ? "" : userEmail.trim();
        String nameText = userName == null ? "" : userName.trim();
        if (nameText.isBlank()) {
            nameText = email;
        }

        String initials = "";
        if (!nameText.isBlank()) {
            String[] tokens = nameText.replace("@", " ").replace(".", " ").trim().split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String t : tokens) {
                if (t.isBlank()) {
                    continue;
                }
                sb.append(Character.toUpperCase(t.charAt(0)));
                if (sb.length() >= 2) {
                    break;
                }
            }
            initials = sb.toString();
        }

        Circle avatarBg = new Circle(18);
        avatarBg.getStyleClass().add("sidebar-avatar-bg");
        Label avatarText = new Label(initials);
        avatarText.getStyleClass().add("sidebar-avatar-text");
        StackPane avatar = new StackPane(avatarBg, avatarText);
        avatar.getStyleClass().add("sidebar-avatar");

        Label userNameLabel = new Label(nameText);
        userNameLabel.getStyleClass().add("sidebar-user-name");
        userNameLabel.setMaxWidth(Double.MAX_VALUE);
        userNameLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        Label userEmailLabel = new Label(email);
        userEmailLabel.getStyleClass().addAll("text-secondary", "sidebar-user-email");
        userEmailLabel.setMaxWidth(Double.MAX_VALUE);
        userEmailLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        VBox userText = new VBox(2, userNameLabel, userEmailLabel);
        userText.getStyleClass().add("sidebar-user-text");
        HBox userRow = new HBox(10, avatar, userText);
        userRow.setAlignment(Pos.CENTER_LEFT);
        userRow.getStyleClass().add("sidebar-user-row");

        VBox userBox = new VBox(4, userRow, syncStatus);
        userBox.getStyleClass().add("sidebar-user");

        VBox menu = new VBox(4);
        menu.getStyleClass().add("sidebar");
        menu.setPadding(new Insets(6));
        menu.setPrefWidth(300);
        menu.setMinWidth(300);

        ImageView logo = new ImageView();
        try {
            var logoStream = DashboardView.class.getResourceAsStream("/images/xpendz.png");
            if (logoStream == null) {
                logoStream = DashboardView.class.getResourceAsStream("/images/logo.png");
            }
            if (logoStream != null) {
                logo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setFitWidth(48);

        StackPane logoBadge = new StackPane(logo);
        logoBadge.getStyleClass().add("sidebar-logo-badge");

        Label appName = new Label("Xpendz");
        appName.getStyleClass().add("sidebar-app-name");

        HBox brandRow = new HBox(10, logoBadge, appName);
        brandRow.setAlignment(Pos.CENTER);
        brandRow.setMaxWidth(Double.MAX_VALUE);
        brandRow.getStyleClass().add("sidebar-brand-row");

        VBox menuTop = new VBox(6, brandRow, userBox);

        Label sectionMain = new Label("INICIO");
        sectionMain.getStyleClass().addAll("text-secondary", "sidebar-section");
        VBox navMain = new VBox(6, home, transactions, transfers);
        navMain.getStyleClass().add("sidebar-actions");

        Label sectionMgmt = new Label("GESTIÓN");
        sectionMgmt.getStyleClass().addAll("text-secondary", "sidebar-section");
        VBox navMgmt = new VBox(6, budget, loans, addAccount, categories);
        navMgmt.getStyleClass().add("sidebar-actions");

        Label sectionAnalysis = new Label("ANÁLISIS");
        sectionAnalysis.getStyleClass().addAll("text-secondary", "sidebar-section");
        VBox navAnalysis = new VBox(6, charts, summary);
        navAnalysis.getStyleClass().add("sidebar-actions");

        Label sectionSystem = new Label("SISTEMA");
        sectionSystem.getStyleClass().addAll("text-secondary", "sidebar-section");
        VBox navSystem = new VBox(6, syncNow, logout, exit);
        navSystem.getStyleClass().add("sidebar-actions");

        Label footerCopyright = new Label("© " + java.time.Year.now().getValue() + " Xpendz");
        footerCopyright.getStyleClass().add("sidebar-footer-text");
        Hyperlink footerLink = new Hyperlink("https://jcadenas.com");
        footerLink.getStyleClass().add("sidebar-footer-link");
        footerLink.setUserData("https://jcadenas.com");
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
        VBox footer = new VBox(2, footerCopyright, footerLink);
        footer.getStyleClass().add("sidebar-footer");
        footer.setAlignment(Pos.CENTER);

        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        menu.getChildren().addAll(
            menuTop,
            navMain,
            sectionMgmt,
            navMgmt,
            sectionAnalysis,
            navAnalysis,
            sectionSystem,
            navSystem,
            spacer,
            footer
        );

        ScrollPane sidebarScroll = new ScrollPane(menu);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setFitToHeight(true);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.getStyleClass().add("sidebar-scroll");

        menu.minHeightProperty().bind(sidebarScroll.heightProperty());

        return sidebarScroll;
    }
}
