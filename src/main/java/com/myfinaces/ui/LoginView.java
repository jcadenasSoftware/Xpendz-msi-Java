package com.myfinaces.ui;

import com.myfinaces.auth.AuthSession;
import com.myfinaces.auth.FirebaseAuthService;
import com.myfinaces.auth.GooglePkceAuthService;
import com.myfinaces.db.SessionRepository;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.control.Hyperlink;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.kordamp.ikonli.javafx.FontIcon;

public final class LoginView {

    public interface Listener {
        void onLoginSuccess(AuthSession session);
    }

    public static Parent create(
        FirebaseAuthService authService,
        String googleOAuthClientId,
        String googleOAuthClientSecret,
        SessionRepository sessionRepo,
        Listener listener
    ) {
        String copyrightName = "Xpendz";
        String website = "https://jcadenas.com";

        ImageView logo = new ImageView();
        try {
            var logoStream = LoginView.class.getResourceAsStream("/images/xpendz.png");
            if (logoStream == null) {
                logoStream = LoginView.class.getResourceAsStream("/images/logo.png");
            }
            if (logoStream != null) {
                logo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setFitWidth(56);

        TextField email = new TextField();
        email.setPromptText("Correo electrónico");
        email.setMaxWidth(Double.MAX_VALUE);

        PasswordField password = new PasswordField();
        password.setPromptText("Contraseña");
        password.setMaxWidth(Double.MAX_VALUE);

        Button login = new Button("Iniciar sesión →");
        login.getStyleClass().add("btn-primary");

        Button google = new Button("Continuar con Google");
        google.getStyleClass().add("btn-outline");
        boolean googleEnabled = googleOAuthClientId != null && !googleOAuthClientId.isBlank();
        google.setDisable(!googleEnabled);

        setButtonIcon(login, new FontIcon("fas-arrow-right"));
        setButtonIcon(google, new FontIcon("fab-google"));

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setPrefSize(28, 28);
        progress.managedProperty().bind(progress.visibleProperty());

        Label status = new Label();
        status.getStyleClass().add("text-danger");

        Label loginTitle = new Label("Iniciar sesión");
        loginTitle.getStyleClass().add("login-form-title");

        Label emailLabel = new Label("Correo electrónico");
        Label passwordLabel = new Label("Contraseña");
        emailLabel.getStyleClass().add("form-label");
        passwordLabel.getStyleClass().add("form-label");

        FontIcon emailIcon = new FontIcon("fas-envelope");
        emailIcon.getStyleClass().add("input-icon");
        HBox emailRow = new HBox(10, emailIcon, email);
        emailRow.getStyleClass().add("input-row");
        HBox.setHgrow(email, Priority.ALWAYS);

        FontIcon passwordIcon = new FontIcon("fas-lock");
        passwordIcon.getStyleClass().add("input-icon");
        HBox passwordRow = new HBox(10, passwordIcon, password);
        passwordRow.getStyleClass().add("input-row");
        HBox.setHgrow(password, Priority.ALWAYS);

        VBox form = new VBox(8, emailLabel, emailRow, passwordLabel, passwordRow);
        form.setAlignment(Pos.TOP_LEFT);

        HBox buttons = new HBox(10, login, progress);
        buttons.setAlignment(Pos.CENTER_LEFT);
        login.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(login, Priority.ALWAYS);
        google.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(google, new Insets(6, 0, 0, 0));

        Separator leftSep = new Separator();
        Separator rightSep = new Separator();
        leftSep.setMaxWidth(Double.MAX_VALUE);
        rightSep.setMaxWidth(Double.MAX_VALUE);
        Label orLabel = new Label("o continuar con");
        orLabel.getStyleClass().add("login-divider-text");
        HBox divider = new HBox(10, leftSep, orLabel, rightSep);
        divider.setAlignment(Pos.CENTER);
        divider.getStyleClass().add("login-divider");

        Label registerCaption = new Label("¿No tienes cuenta?");
        registerCaption.getStyleClass().add("text-secondary");
        Hyperlink registerLink = new Hyperlink("Regístrate");
        registerLink.getStyleClass().add("login-register-link");
        HBox registerRow = new HBox(6, registerCaption, registerLink);
        registerRow.setAlignment(Pos.CENTER);

        VBox formBox = new VBox(14, loginTitle, form, buttons, divider, google, status, registerRow);
        formBox.getStyleClass().addAll("login-card", "card");
        formBox.setPrefWidth(420);
        formBox.setMaxWidth(420);
        formBox.setAlignment(Pos.CENTER_LEFT);
        formBox.setMinHeight(Region.USE_PREF_SIZE);
        formBox.setMaxHeight(Region.USE_PREF_SIZE);

        Label brandTitle = new Label("Xpendz");
        brandTitle.getStyleClass().add("login-brand-title");

        Label brandLine1 = new Label("Controla tus finanzas");
        Label brandLine2 = new Label("en tiempo real");
        Label brandLine3 = new Label("desde tu celular");
        Label brandLine4 = new Label("y tu computador");
        brandLine1.getStyleClass().add("login-brand-headline");
        brandLine2.getStyleClass().add("login-brand-headline");
        brandLine3.getStyleClass().add("login-brand-headline");
        brandLine4.getStyleClass().add("login-brand-headline");

        int year = java.time.Year.now().getValue();
        Label footerLine1 = new Label("© " + year + " " + copyrightName + " | " + website);
        footerLine1.getStyleClass().add("login-brand-footer");
        footerLine1.setOnMouseClicked(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(website));
            } catch (Exception ignored) {
            }
        });

        StackPane logoBadge = new StackPane(logo);
        logoBadge.getStyleClass().add("login-logo-badge");

        HBox brandRow = new HBox(12, logoBadge, brandTitle);
        brandRow.setAlignment(Pos.CENTER_LEFT);

        VBox brandTop = new VBox(10, brandRow);
        brandTop.setAlignment(Pos.TOP_LEFT);
        VBox brandCopy = new VBox(6, brandLine1, brandLine2, brandLine3, brandLine4);
        brandCopy.setAlignment(Pos.TOP_LEFT);

        Region brandSpacer = new Region();
        VBox.setVgrow(brandSpacer, Priority.ALWAYS);

        VBox brandPane = new VBox(18, brandTop, brandCopy, brandSpacer, footerLine1);
        brandPane.getStyleClass().add("login-brand");
        brandPane.setPadding(new Insets(36, 34, 22, 34));
        brandPane.setMinWidth(420);

        VBox formPane = new VBox(formBox);
        formPane.getStyleClass().add("login-form-pane");
        formPane.setAlignment(Pos.CENTER);
        formPane.setPadding(new Insets(34));
        formPane.setMinWidth(520);

        HBox shell = new HBox(0, brandPane, formPane);
        shell.getStyleClass().add("login-shell");
        shell.setMaxWidth(980);
        shell.setMaxHeight(560);

        StackPane centered = new StackPane(shell);
        centered.setAlignment(Pos.CENTER);
        centered.setPadding(new Insets(22));

        BorderPane root = new BorderPane();
        root.setCenter(centered);
        root.getStyleClass().add("app-root");
        BorderPane.setMargin(centered, new Insets(0));
        root.setMinHeight(Region.USE_PREF_SIZE);
        root.setMaxHeight(Region.USE_PREF_SIZE);

        playLoginSoundIfPresent();

        Runnable disableInputs = () -> {
            email.setDisable(true);
            password.setDisable(true);
            login.setDisable(true);
            registerLink.setDisable(true);
            progress.setVisible(true);
        };

        Runnable enableInputs = () -> {
            email.setDisable(false);
            password.setDisable(false);
            login.setDisable(false);
            registerLink.setDisable(false);
            progress.setVisible(false);
        };

        login.setOnAction(e -> runAuthTask(
            status,
            disableInputs,
            enableInputs,
            new Task<>() {
                @Override
                protected AuthSession call() throws Exception {
                    return authService.signInWithEmailPassword(email.getText().trim(), password.getText());
                }
            },
            sessionRepo,
            listener
        ));

        registerLink.setOnAction(e -> runAuthTask(
            status,
            disableInputs,
            enableInputs,
            new Task<>() {
                @Override
                protected AuthSession call() throws Exception {
                    return authService.signUpWithEmailPassword(email.getText().trim(), password.getText());
                }
            },
            sessionRepo,
            listener
        ));

        google.setOnAction(e -> {
            status.setText("");
            if (!googleEnabled) {
                status.setText("Falta google.oauthClientId en config/app.properties");
                return;
            }

            Platform.runLater(disableInputs);

            Task<AuthSession> task = new Task<>() {
                @Override
                protected AuthSession call() throws Exception {
                    GooglePkceAuthService googleAuth = new GooglePkceAuthService(googleOAuthClientId, googleOAuthClientSecret, 53682);
                    GooglePkceAuthService.GoogleTokens tokens = googleAuth.authenticate();
                    if (tokens.accessToken() != null && !tokens.accessToken().isBlank()) {
                        return authService.signInWithGoogleAccessToken(tokens.accessToken());
                    }
                    return authService.signInWithGoogleIdToken(tokens.idToken());
                }
            };

            task.setOnSucceeded(evt -> {
                try {
                    AuthSession session = task.getValue();
                    sessionRepo.save(session);
                    listener.onLoginSuccess(session);
                } catch (Exception ex) {
                    status.setText(ex.getMessage());
                } finally {
                    enableInputs.run();
                }
            });

            task.setOnFailed(evt -> {
                Throwable ex = task.getException();
                status.setText(ex == null ? "Error desconocido" : ex.getMessage());
                enableInputs.run();
            });

            Thread t = new Thread(task, "google-auth-task");
            t.setDaemon(true);
            t.start();
        });

        return root;
    }

    private static void playLoginSoundIfPresent() {
        try {
            var url = LoginView.class.getResource("/sounds/coins.mp3");
            if (url == null) {
                url = LoginView.class.getResource("/sounds/coins.wav");
            }
            if (url == null) {
                return;
            }

            Media media = new Media(url.toExternalForm());
            MediaPlayer player = new MediaPlayer(media);
            player.setVolume(0.35);
            player.setOnEndOfMedia(player::dispose);
            player.setOnError(player::dispose);
            player.play();
        } catch (Exception ignored) {
        }
    }

    private static void setButtonIcon(Button button, FontIcon icon) {
        icon.getStyleClass().add("icon");
        icon.setIconSize(14);
        button.setGraphic(icon);
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setGraphicTextGap(4);
    }

    private static void runAuthTask(
        Label status,
        Runnable disableInputs,
        Runnable enableInputs,
        Task<AuthSession> task,
        SessionRepository sessionRepo,
        Listener listener
    ) {
        status.setText("");

        Platform.runLater(disableInputs);

        task.setOnSucceeded(evt -> {
            try {
                AuthSession session = task.getValue();
                sessionRepo.save(session);
                listener.onLoginSuccess(session);
            } catch (Exception ex) {
                status.setText(ex.getMessage());
            } finally {
                enableInputs.run();
            }
        });

        task.setOnFailed(evt -> {
            Throwable ex = task.getException();
            status.setText(ex == null ? "Error desconocido" : ex.getMessage());
            enableInputs.run();
        });

        Thread t = new Thread(task, "auth-task");
        t.setDaemon(true);
        t.start();
    }
}
