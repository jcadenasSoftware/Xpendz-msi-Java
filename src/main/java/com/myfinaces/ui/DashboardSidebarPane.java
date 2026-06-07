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

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("text-secondary", "sidebar-section", "sidebar-group-title");
        return label;
    }

    private static VBox sectionCard(String cardStyleClass, Node... nodes) {
        VBox card = new VBox(6);
        card.getChildren().addAll(nodes);
        card.getStyleClass().add("sidebar-section-card");
        if (cardStyleClass != null && !cardStyleClass.isBlank()) {
            card.getStyleClass().add(cardStyleClass);
        }
        card.setFillWidth(true);
        return card;
    }

    private static VBox sectionBlock(String title, String cardStyleClass, Node... nodes) {
        VBox block = new VBox(6, sectionLabel(title), sectionCard(cardStyleClass, nodes));
        block.getStyleClass().add("sidebar-group");
        block.setFillWidth(true);
        return block;
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

        Circle avatarBg = new Circle(16);
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
        HBox userRow = new HBox(8, avatar, userText);
        userRow.setAlignment(Pos.CENTER_LEFT);
        userRow.getStyleClass().add("sidebar-user-row");

        VBox userBox = new VBox(2, userRow, syncStatus);
        userBox.getStyleClass().add("sidebar-user");

        VBox menu = new VBox(8);
        menu.getStyleClass().add("sidebar");
        menu.setPadding(new Insets(10, 8, 10, 8));
        menu.setPrefWidth(300);
        menu.setMinWidth(300);
        menu.setFillWidth(true);

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
        logo.setFitWidth(40);

        StackPane logoBadge = new StackPane(logo);
        logoBadge.getStyleClass().add("sidebar-logo-badge");

        Label appName = new Label("Xpendz");
        appName.getStyleClass().add("sidebar-app-name");

        HBox brandRow = new HBox(8, logoBadge, appName);
        brandRow.setAlignment(Pos.CENTER);
        brandRow.setMaxWidth(Double.MAX_VALUE);
        brandRow.getStyleClass().add("sidebar-brand-row");

        VBox menuTop = new VBox(6, brandRow, userBox);
        menuTop.getStyleClass().add("sidebar-top");

        home.getStyleClass().add("sidebar-primary-button");
        summary.getStyleClass().add("sidebar-primary-button");

        addAccount.getStyleClass().remove("nav-button");
        addAccount.getStyleClass().remove("btn-primary");
        addAccount.getStyleClass().add("btn-secondary");
        addAccount.getStyleClass().add("sidebar-quick-action");
        addAccount.setMinHeight(26);

        syncNow.getStyleClass().add("sidebar-system-button");
        logout.getStyleClass().addAll("sidebar-system-button", "sidebar-system-danger");
        exit.getStyleClass().addAll("sidebar-system-button", "sidebar-system-danger");

        VBox navMain = sectionBlock("PRINCIPAL", "sidebar-primary-card", home, summary);
        VBox navQuick = sectionBlock("ACCIÓN RÁPIDA", "sidebar-quick-card", addAccount);

        Button[] movementButtons = {transactions, transfers};
        VBox navMovements = sectionBlock("MOVIMIENTOS", "sidebar-nav-card", movementButtons);

        VBox navMgmt = sectionBlock("ORGANIZACIÓN", "sidebar-nav-card", categories, budget, loans);

        VBox navSystem = sectionBlock("SISTEMA", "sidebar-system-card", syncNow, logout, exit);

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
            navQuick,
            navMovements,
            navMgmt,
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
