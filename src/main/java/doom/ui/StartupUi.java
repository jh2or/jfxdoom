// Startup form shown before the engine boots when no CLI args
// were supplied. The primary task is WAD / PWAD selection — once
// an IWAD is picked the user can launch immediately and the
// engine boots the same way the original doom.exe did (title
// loop, menus, no level skip). An "Expert mode" toggle reveals
// the rest of the README flags (-warp, -skill, -episode,
// -nomonsters, -respawn, -fast, -deathmatch, -altdeath, -config,
// -record, -playdemo, -timedemo, -maxdemo); when the toggle is
// off, none of those values reach the args array even if the
// user previously typed into them.
package doom.ui;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class StartupUi {

    public enum DisplayMode {
        SCALE_1X(1, false, "Windowed 1× (320×200)"),
        SCALE_2X(2, false, "Windowed 2× (640×400)"),
        SCALE_3X(3, false, "Windowed 3× (960×600)"),
        SCALE_4X(4, false, "Windowed 4× (1280×800)"),
        SCALE_5X(5, false, "Windowed 5× (1600×1000)"),
        FULLSCREEN(0, true, "Fullscreen (letterboxed)");

        public final int scale;
        public final boolean fullscreen;
        private final String label;

        DisplayMode(int scale, boolean fullscreen, String label) {
            this.scale = scale;
            this.fullscreen = fullscreen;
            this.label = label;
        }

        @Override
        public String toString() { return label; }
    }

    @FunctionalInterface
    public interface LaunchCallback {
        void onLaunch(String[] args, DisplayMode display, int renderMul);
    }

    private final TextField iwadField = new TextField();
    private final ListView<String> pwadList =
            new ListView<>(FXCollections.observableArrayList());
    private final ComboBox<DisplayMode> displayBox =
            new ComboBox<>(FXCollections.observableArrayList(DisplayMode.values()));
    // Render-scale entries: label + the RENDER_MUL value passed to
    // Doomdef.setRenderMul. 1x is vanilla 320x200 sample density;
    // 2..4x trade CPU for crisper walls / floors / sprites.
    public static final class RenderScale {
        public final int mul;
        public final String label;
        public RenderScale(int mul, String label) { this.mul = mul; this.label = label; }
        @Override public String toString() { return label; }
    }
    private final ComboBox<RenderScale> renderScaleBox =
            new ComboBox<>(FXCollections.observableArrayList(
                    new RenderScale(1, "1× (vanilla 320×200)"),
                    new RenderScale(2, "2× (640×400)"),
                    new RenderScale(3, "3× (960×600)"),
                    new RenderScale(4, "4× (1280×800)")));
    private final CheckBox expertMode = new CheckBox("Expert mode");

    private final ComboBox<String> skillBox = new ComboBox<>(FXCollections.observableArrayList(
            "1 — I'm too young to die",
            "2 — Hey, not too rough",
            "3 — Hurt me plenty",
            "4 — Ultra-violence",
            "5 — Nightmare!"));
    private final ComboBox<Integer> episodeBox =
            new ComboBox<>(FXCollections.observableArrayList(1, 2, 3, 4));
    private final TextField warpE = new TextField();
    private final TextField warpM = new TextField();
    private final ComboBox<String> netMode = new ComboBox<>(FXCollections.observableArrayList(
            "Single player",
            "Deathmatch",
            "Altdeath"));
    private final CheckBox noMonsters = new CheckBox("No monsters");
    private final CheckBox respawn    = new CheckBox("Respawn monsters");
    private final CheckBox fast       = new CheckBox("Fast monsters");

    private final TextField configField    = new TextField();
    private final TextField recordField    = new TextField();
    private final TextField playdemoField  = new TextField();
    private final TextField timedemoField  = new TextField();
    private final Spinner<Integer> maxdemo = new Spinner<>(128, 32_768, 128, 32);

    // Optional pre-fill (e.g. when returning from a finished
    // game session and we want to remember the last IWAD).
    private String initialIwad;
    private DisplayMode initialDisplay = DisplayMode.SCALE_3X;
    private int initialRenderMul = 2;

    public StartupUi prefill(String iwad, DisplayMode display, int renderMul) {
        this.initialIwad = iwad;
        if (display != null) this.initialDisplay = display;
        if (renderMul >= 1 && renderMul <= 4) this.initialRenderMul = renderMul;
        return this;
    }

    public Scene build(Stage stage, LaunchCallback callback) {
        // Title.
        Label title = new Label("jfxdoom");
        title.setFont(Font.font("System", 20));

        Label subtitle = new Label("Pick a WAD and click Launch — the engine starts the same way doom.exe would.");
        subtitle.setStyle("-fx-text-fill: gray;");

        // Defaults that line up with the README's "common spot".
        File defaultDir = new File("reference-wads");
        if (!defaultDir.isDirectory()) {
            defaultDir = new File(System.getProperty("user.home", "."));
        }
        final File initialDir = defaultDir;

        // Apply prefill values.
        if (initialIwad != null) iwadField.setText(initialIwad);
        displayBox.setValue(initialDisplay);
        for (RenderScale rs : renderScaleBox.getItems()) {
            if (rs.mul == initialRenderMul) { renderScaleBox.setValue(rs); break; }
        }
        if (renderScaleBox.getValue() == null) {
            renderScaleBox.setValue(renderScaleBox.getItems().get(1)); // 2× default
        }

        // ---- IWAD row ----
        Button iwadBrowse = new Button("Browse…");
        iwadBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Doom IWAD");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAD files", "*.wad", "*.WAD"));
            fc.setInitialDirectory(initialDirOf(iwadField.getText(), initialDir));
            File chosen = fc.showOpenDialog(stage);
            if (chosen != null) iwadField.setText(chosen.getAbsolutePath());
        });

        // ---- PWAD list ----
        pwadList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        pwadList.setPrefHeight(80);
        Button pwadAdd = new Button("Add…");
        pwadAdd.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select additional PWADs");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAD files", "*.wad", "*.WAD"));
            fc.setInitialDirectory(initialDirOf(iwadField.getText(), initialDir));
            List<File> chosen = fc.showOpenMultipleDialog(stage);
            if (chosen != null) {
                for (File f : chosen) pwadList.getItems().add(f.getAbsolutePath());
            }
        });
        Button pwadRemove = new Button("Remove");
        pwadRemove.setOnAction(e ->
                pwadList.getItems().removeAll(pwadList.getSelectionModel().getSelectedItems()));
        VBox pwadButtons = new VBox(6, pwadAdd, pwadRemove);

        // ---- primary grid (always visible) ----
        GridPane primary = grid();
        int r = 0;
        primary.add(new Label("IWAD:"),    0, r);
        primary.add(iwadField,             1, r);
        primary.add(iwadBrowse,            2, r++);
        primary.add(new Label("PWADs:"),   0, r);
        primary.add(pwadList,              1, r);
        primary.add(pwadButtons,           2, r++);
        primary.add(new Label("Display:"), 0, r);
        primary.add(displayBox,            1, r++, 2, 1);
        primary.add(new Label("Render:"),  0, r);
        primary.add(renderScaleBox,        1, r++, 2, 1);

        // ---- expert section ----
        skillBox.getSelectionModel().select(2);
        episodeBox.getSelectionModel().select(0);
        warpE.setPromptText("E");
        warpE.setPrefColumnCount(3);
        warpM.setPromptText("M");
        warpM.setPrefColumnCount(3);
        Label warpHelp = new Label("(Doom 1: episode + map; Doom 2: map only — leave E blank)");
        warpHelp.setStyle("-fx-text-fill: gray;");
        HBox warpRow = new HBox(6, warpE, warpM, warpHelp);
        warpRow.setAlignment(Pos.CENTER_LEFT);

        netMode.getSelectionModel().select(0);

        Button configBrowse = new Button("Browse…");
        configBrowse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select config file");
            fc.setInitialDirectory(initialDirOf(configField.getText(), initialDir));
            File chosen = fc.showOpenDialog(stage);
            if (chosen != null) configField.setText(chosen.getAbsolutePath());
        });
        recordField.setPromptText("demo lump name");
        playdemoField.setPromptText("demo lump name");
        timedemoField.setPromptText("demo lump name");
        maxdemo.setEditable(true);
        maxdemo.setPrefWidth(90);

        GridPane expert = grid();
        int er = 0;
        expert.add(new Label("Skill:"),   0, er);
        expert.add(skillBox,              1, er++, 2, 1);
        expert.add(new Label("Episode:"), 0, er);
        expert.add(episodeBox,            1, er++, 2, 1);
        expert.add(new Label("Warp:"),    0, er);
        expert.add(warpRow,               1, er++, 2, 1);
        expert.add(new Separator(),       0, er++, 3, 1);
        expert.add(new Label("Mode:"),    0, er);
        expert.add(netMode,               1, er++, 2, 1);
        HBox flagsRow = new HBox(16, noMonsters, respawn, fast);
        expert.add(flagsRow,              1, er++, 2, 1);
        expert.add(new Separator(),       0, er++, 3, 1);
        expert.add(new Label("Config:"),  0, er);
        expert.add(configField,           1, er);
        expert.add(configBrowse,          2, er++);
        expert.add(new Label("Record:"),    0, er);
        expert.add(recordField,             1, er++, 2, 1);
        expert.add(new Label("Play demo:"), 0, er);
        expert.add(playdemoField,           1, er++, 2, 1);
        expert.add(new Label("Time demo:"), 0, er);
        expert.add(timedemoField,           1, er++, 2, 1);
        expert.add(new Label("Max demo:"),  0, er);
        HBox maxRow = new HBox(6, maxdemo, new Label("KiB"));
        maxRow.setAlignment(Pos.CENTER_LEFT);
        expert.add(maxRow, 1, er++, 2, 1);

        TitledPane expertPane = new TitledPane("Expert options", expert);
        expertPane.setCollapsible(false);
        expertPane.visibleProperty().bind(expertMode.selectedProperty());
        expertPane.managedProperty().bind(expertMode.selectedProperty());
        expert.disableProperty().bind(expertMode.selectedProperty().not());

        // ---- expert toggle row ----
        Label expertHelp = new Label("Reveal skill, warp, deathmatch, demo recording, …");
        expertHelp.setStyle("-fx-text-fill: gray;");
        HBox expertRow = new HBox(10, expertMode, expertHelp);
        expertRow.setAlignment(Pos.CENTER_LEFT);

        // ---- launch button ----
        Button launch = new Button("Launch ▶");
        launch.setDefaultButton(true);
        launch.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> iwadField.getText() == null || iwadField.getText().isBlank(),
                        iwadField.textProperty()));
        launch.setOnAction(e -> {
            RenderScale rs = renderScaleBox.getValue();
            int mul = (rs != null) ? rs.mul : 2;
            callback.onLaunch(assembleArgs(), displayBox.getValue(), mul);
        });
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox launchRow = new HBox(spring, launch);
        launchRow.setPadding(new Insets(8, 0, 0, 0));

        VBox content = new VBox(10,
                title, subtitle, new Separator(),
                primary, new Separator(),
                expertRow, expertPane,
                launchRow);
        content.setPadding(new Insets(16));

        // Wrap in a ScrollPane so expert-mode fields stay reachable
        // without manual window resize.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        return new Scene(scroll, 660, 520);
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setHalignment(HPos.RIGHT);
        c0.setMinWidth(80);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setFillWidth(true);
        ColumnConstraints c2 = new ColumnConstraints();
        g.getColumnConstraints().addAll(c0, c1, c2);
        return g;
    }

    private static File initialDirOf(String currentPath, File fallback) {
        if (currentPath != null && !currentPath.isBlank()) {
            File f = new File(currentPath);
            File parent = f.getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return fallback.isDirectory() ? fallback : null;
    }

    private String[] assembleArgs() {
        List<String> a = new ArrayList<>();
        String iwad = iwadField.getText().trim();
        if (!iwad.isEmpty()) {
            a.add("-iwad");
            a.add(iwad);
        }
        if (!pwadList.getItems().isEmpty()) {
            a.add("-file");
            a.addAll(pwadList.getItems());
        }
        // Expert mode off → emit only the WAD args so the engine
        // boots the same way doom.exe does (title loop, no skill
        // / warp / mode override).
        if (!expertMode.isSelected()) {
            return a.toArray(new String[0]);
        }

        // Skill: the C parser reads `myargv[p+1].charAt(0) - '1'`,
        // so pass the leading digit only.
        String skill = skillBox.getValue();
        if (skill != null && !skill.isBlank()) {
            a.add("-skill");
            a.add(skill.substring(0, 1));
        }
        String e = warpE.getText().trim();
        String m = warpM.getText().trim();
        if (!e.isEmpty() && !m.isEmpty()) {
            a.add("-warp");
            a.add(e);
            a.add(m);
        } else if (!m.isEmpty()) {
            a.add("-warp");
            a.add(m);
        } else {
            Integer ep = episodeBox.getValue();
            if (ep != null && ep != 1) {
                a.add("-episode");
                a.add(String.valueOf(ep));
            }
        }
        if (noMonsters.isSelected()) a.add("-nomonsters");
        if (respawn.isSelected())    a.add("-respawn");
        if (fast.isSelected())       a.add("-fast");
        String mode = netMode.getValue();
        if ("Deathmatch".equals(mode)) a.add("-deathmatch");
        if ("Altdeath".equals(mode))   a.add("-altdeath");

        String cfg = configField.getText().trim();
        if (!cfg.isEmpty()) { a.add("-config");   a.add(cfg); }
        String rec = recordField.getText().trim();
        if (!rec.isEmpty()) { a.add("-record");   a.add(rec); }
        String pd  = playdemoField.getText().trim();
        if (!pd.isEmpty())  { a.add("-playdemo"); a.add(pd); }
        String td  = timedemoField.getText().trim();
        if (!td.isEmpty())  { a.add("-timedemo"); a.add(td); }
        Integer mx = maxdemo.getValue();
        if (mx != null && mx != 128) { a.add("-maxdemo"); a.add(String.valueOf(mx)); }

        return a.toArray(new String[0]);
    }
}
