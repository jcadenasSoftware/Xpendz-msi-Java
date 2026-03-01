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
        String copyrightName = "Ing. Joel Cadenas";
        String website = "https://jcadenas.com";

        Label title = new Label("Mis Finanzas");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Tu control financiero en un solo lugar");
        subtitle.getStyleClass().add("text-secondary");

        ImageView logo = new ImageView();
        try {
            var logoStream = LoginView.class.getResourceAsStream("/images/logo.png");
            if (logoStream != null) {
                logo.setImage(new Image(logoStream));
            }
        } catch (Exception ignored) {
        }
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setFitWidth(84);

        TextField email = new TextField();
        email.setPromptText("correo@dominio.com");
        email.setMaxWidth(280);

        PasswordField password = new PasswordField();
        password.setPromptText("Contraseña");
        password.setMaxWidth(280);

        Button login = new Button("Iniciar sesión");
        login.getStyleClass().add("btn-primary");
        Button register = new Button("Registrarse");
        register.getStyleClass().addAll("btn-secondary", "btn-accent");

        Button google = new Button("Continuar con Google");
        google.getStyleClass().addAll("btn-secondary", "btn-accent");
        boolean googleEnabled = googleOAuthClientId != null && !googleOAuthClientId.isBlank();
        google.setDisable(!googleEnabled);

        setButtonIcon(login, new FontIcon("fas-sign-in-alt"));
        setButtonIcon(register, new FontIcon("fas-user-plus"));
        setButtonIcon(google, new FontIcon("fab-google"));

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setPrefSize(28, 28);
        progress.managedProperty().bind(progress.visibleProperty());

        Label status = new Label();
        status.getStyleClass().add("text-danger");

        Label emailLabel = new Label("Correo");
        Label passwordLabel = new Label("Contraseña");
        emailLabel.getStyleClass().add("form-label");
        passwordLabel.getStyleClass().add("form-label");

        VBox form = new VBox(8, emailLabel, email, passwordLabel, password);
        form.setAlignment(Pos.CENTER);

        HBox buttons = new HBox(10, login, register, progress);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPrefWidth(280);
        buttons.setMaxWidth(280);
        login.setMaxWidth(Double.MAX_VALUE);
        register.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(login, Priority.ALWAYS);
        HBox.setHgrow(register, Priority.ALWAYS);
        google.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(google, new Insets(4, 0, 0, 0));

        Separator leftSep = new Separator();
        Separator rightSep = new Separator();
        leftSep.setMaxWidth(Double.MAX_VALUE);
        rightSep.setMaxWidth(Double.MAX_VALUE);
        Label orLabel = new Label("o");
        orLabel.getStyleClass().add("login-divider-text");
        HBox divider = new HBox(10, leftSep, orLabel, rightSep);
        divider.setAlignment(Pos.CENTER);
        divider.getStyleClass().add("login-divider");

        VBox header = new VBox(8, logo, title, subtitle);
        header.setAlignment(Pos.CENTER);

        VBox formBox = new VBox(12, header, form, buttons, divider, google, status);
        formBox.getStyleClass().addAll("login-card", "card");
        formBox.setPrefWidth(440);
        formBox.setMaxWidth(440);
        formBox.setAlignment(Pos.CENTER);
        formBox.setMinHeight(Region.USE_PREF_SIZE);
        formBox.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane centered = new StackPane(formBox);
        centered.setAlignment(Pos.CENTER);
        centered.setPadding(new Insets(0));

        Label footerLine1 = new Label("© 2025 " + copyrightName);
        footerLine1.getStyleClass().add("login-footer-brand");
        Hyperlink footerLink = new Hyperlink(website);
        footerLink.getStyleClass().add("login-footer-link");
        footerLink.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(website));
            } catch (Exception ignored) {
            }
        });

        VBox footerBox = new VBox(2, footerLine1, footerLink);
        footerBox.setAlignment(Pos.CENTER);
        HBox footer = new HBox(footerBox);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("login-footer");
        footer.setPadding(new Insets(10, 16, 10, 16));
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setMinHeight(Region.USE_PREF_SIZE);
        footer.setMaxHeight(Region.USE_PREF_SIZE);

        BorderPane root = new BorderPane();
        root.setCenter(centered);
        root.setBottom(footer);
        root.getStyleClass().add("app-root");
        BorderPane.setMargin(centered, new Insets(0));
        root.setMinHeight(Region.USE_PREF_SIZE);
        root.setMaxHeight(Region.USE_PREF_SIZE);
        BorderPane.setAlignment(footer, Pos.CENTER);

        playLoginSoundIfPresent();

        Runnable disableInputs = () -> {
            email.setDisable(true);
            password.setDisable(true);
            login.setDisable(true);
            register.setDisable(true);
            progress.setVisible(true);
        };

        Runnable enableInputs = () -> {
            email.setDisable(false);
            password.setDisable(false);
            login.setDisable(false);
            register.setDisable(false);
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

        register.setOnAction(e -> runAuthTask(
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
