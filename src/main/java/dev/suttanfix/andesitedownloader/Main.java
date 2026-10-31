package dev.suttanfix.andesitedownloader;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Tooltip;

import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import java.nio.charset.StandardCharsets;

public class Main {

    private final Core core = new Core();

    private Stage stage;
    private StackPane host;
    private Label footerLabel;
    private Label footerSpeed;
    private Label toastLabel;
    private PauseTransition toastTimer;

    private final Map<String, Pane> pages = new LinkedHashMap<>();
    private final Map<String, ToggleButton> navMap = new HashMap<>();
    
    private static final String ABOUT_US =
            "安山岩下载器是一款独立运行的 Minecraft 资源下载工具，为 PCL、HMCL 等启动器"
          + "提供版本补全能力。\n\n"
          + "仅读取版本清单、下载资源文件、把结果写入本地目录。"
          + "不带账号系统、不带游戏启动功能、不碰存档、不修改游戏本体。\n\n"
          + "程序本身开源，所有下载请求都由你主动发起，作者不架设任何服务器，"
          + "不中转任何数据。";

    private static final String CREDITS =
            "感谢以下项目与服务让这个工具成为可能：\n\n"
          + "· BMCLAPI —— 社区维护的 Minecraft 镜像加速服务，本项目默认下载源\n"
          + "· FabricMC —— 提供简洁清晰的加载器元数据接口\n"
          + "· MinecraftForge / NeoForged —— 提供公开的 Maven 构件仓库\n"
          + "· OpenJFX —— 跨平台的 Java 图形界面框架\n"
          + "· Gson —— 轻量的 JSON 解析库\n"
          + "· Mojang Studios —— Minecraft 的创造者\n\n"
          + "以及 PCL、HMCL 等先行者，为本项目的界面与交互提供了大量参考。";

  
    private final List<String[]> allVersions = new ArrayList<>();
    private final ObservableList<String[]> versions = FXCollections.observableArrayList();
    private final Set<String> installed = new HashSet<>();
    private ListView<String[]> versionList;
    private ComboBox<String> typeFilter;
    private ComboBox<String> loaderBox;
    private ComboBox<String> loaderVerBox;
    private TextField searchField;
    private Label instanceLabel;


    private final ObservableList<Task> taskView = FXCollections.observableArrayList();
    private ListView<Task> taskList;



    public static void main(String[] args) {
   
        Platform.startup(() -> {
            try {
                new Main().boot();
            } catch (Throwable t) {
                t.printStackTrace();
                Platform.exit();
            }
        });
    }

    private void boot() {
        core.init();
        buildUi();
        loadVersionsAsync();
        startTicker();
    }



    private void buildUi() {

        Label title = new Label(Core.APP_NAME);
        title.getStyleClass().add("app-title");
        Label sub = new Label("ANDESITE DOWNLOADER · v" + Core.APP_VERSION);
        sub.getStyleClass().add("app-sub");

        VBox brand = new VBox(1, title, sub);
        brand.setAlignment(Pos.CENTER_LEFT);

        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);

        Button minBtn = windowButton("—", () -> stage.setIconified(true));
        Button closeBtn = windowButton("✕", () -> {
            core.save();
            Platform.exit();
        });
        closeBtn.getStyleClass().add("win-close");

        HBox header = new HBox(8, brand, headSpacer, minBtn, closeBtn);
        header.getStyleClass().add("header");
        header.setPadding(new Insets(14, 16, 14, 22));
        header.setAlignment(Pos.CENTER);
        attachDrag(header);


        VBox nav = new VBox(6);
        nav.getStyleClass().add("nav");
        nav.setPadding(new Insets(16, 12, 16, 12));
        nav.setPrefWidth(178);
        nav.setMinWidth(178);

        ToggleGroup navGroup = new ToggleGroup();
        addNav(nav, navGroup, "下载", "download");
        addNav(nav, navGroup, "任务", "tasks");
        addNav(nav, navGroup, "设置", "settings");
        addNav(nav, navGroup, "关于", "about");


        pages.put("download", buildDownloadPage());
        pages.put("tasks", buildTasksPage());
        pages.put("settings", buildSettingsPage());
        pages.put("about", buildAboutPage());

        host = new StackPane(pages.get("download"));
        host.getStyleClass().add("host");
        HBox.setHgrow(host, Priority.ALWAYS);

        HBox body = new HBox(nav, host);
        VBox.setVgrow(body, Priority.ALWAYS);


        footerLabel = new Label("就绪");
        footerLabel.getStyleClass().add("footer-text");
        toastLabel = new Label();
        toastLabel.getStyleClass().add("toast");
        footerSpeed = new Label();
        footerSpeed.getStyleClass().add("footer-speed");

        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);

        HBox footer = new HBox(12, footerLabel, toastLabel, footSpacer, footerSpeed);
        footer.getStyleClass().add("footer");
        footer.setAlignment(Pos.CENTER_LEFT);


        VBox shell = new VBox(header, body, footer);
        shell.getStyleClass().add("shell");
        shell.setMaxWidth(1120);
        shell.setMaxHeight(762);

        StackPane root = new StackPane(shell);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: transparent; -fx-font-family: '" + pickFont() + "';");

        Scene scene = new Scene(root, 1152, 800);
        scene.setFill(Color.TRANSPARENT);
        var css = Main.class.getResource("/app.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setScene(scene);
        stage.setTitle(Core.APP_NAME);
        stage.setResizable(false);
        stage.show();

        selectNav("download");
    }

    private Button windowButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("win-btn");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }


    private void attachDrag(Node node) {
        final double[] offset = new double[2];
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
    }



    private void addNav(VBox nav, ToggleGroup group, String text, String key) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("nav-btn");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setToggleGroup(group);
        b.setUserData(key);
        b.setFocusTraversable(false);
        b.setOnAction(e -> {
            b.setSelected(true);
            showPage(key);
        });
        nav.getChildren().add(b);
        navMap.put(key, b);
    }

    private void selectNav(String key) {
        ToggleButton b = navMap.get(key);
        if (b != null) b.setSelected(true);
        showPage(key);
    }

    private void showPage(String key) {
        Pane target = pages.get(key);
        if (target == null) return;
        if (!host.getChildren().isEmpty() && host.getChildren().get(0) == target) return;

        host.getChildren().setAll(target);

        FadeTransition fade = new FadeTransition(Duration.millis(200), target);
        fade.setFromValue(0d);
        fade.setToValue(1d);
        fade.play();

        TranslateTransition slide = new TranslateTransition(Duration.millis(200), target);
        slide.setFromY(10d);
        slide.setToY(0d);
        slide.play();
    }


    private Pane buildDownloadPage() {
        Label heading = new Label("游戏版本");
        heading.getStyleClass().add("h2");

        searchField = new TextField();
        searchField.setPromptText("搜索版本号");
        searchField.setPrefWidth(190);
        searchField.textProperty().addListener((o, a, b) -> applyFilter());

        typeFilter = new ComboBox<>(
                FXCollections.observableArrayList("正式版", "快照", "全部"));
        typeFilter.setValue("正式版");
        typeFilter.setPrefWidth(110);
        typeFilter.setOnAction(e -> applyFilter());

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        HBox top = new HBox(10, heading, topSpacer, searchField, typeFilter);
        top.setAlignment(Pos.CENTER_LEFT);

        versionList = new ListView<>(versions);
        versionList.getStyleClass().add("vlist");
        versionList.setPlaceholder(new Label("正在获取版本列表…"));
        versionList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String[] item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }

                Label id = new Label(item[0]);
                id.getStyleClass().add("v-id");

                String date = item[2] != null && item[2].length() >= 10
                        ? item[2].substring(0, 10) : item[2];
                Label meta = new Label(typeName(item[1]) + " · " + date);
                meta.getStyleClass().add("v-meta");

                VBox left = new VBox(2, id, meta);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                HBox box = new HBox(10, left, spacer);
                box.setAlignment(Pos.CENTER_LEFT);

                if (installed.contains(item[0])) {
                    Label tag = new Label("已安装");
                    tag.getStyleClass().add("tag");
                    box.getChildren().add(tag);
                }

                setGraphic(box);
                setText(null);
            }
        });
        VBox.setVgrow(versionList, Priority.ALWAYS);
        versionList.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> updateInstanceLabel());

        Pane card = buildInstallCard();

        VBox page = new VBox(14, top, versionList, card);
        page.setPadding(new Insets(4, 18, 18, 18));
        VBox.setVgrow(versionList, Priority.ALWAYS);
        return page;
    }

    private Pane buildInstallCard() {
        Label loaderLabel = new Label("加载器");
        loaderLabel.getStyleClass().add("field-label");

        loaderBox = new ComboBox<>(
                FXCollections.observableArrayList("原版", "Fabric", "Forge", "NeoForge"));
        loaderBox.setValue("原版");
        loaderBox.setPrefWidth(120);
        loaderBox.setOnAction(e -> refreshLoaderVersions());

        loaderVerBox = new ComboBox<>();
        loaderVerBox.setPrefWidth(180);
        loaderVerBox.setDisable(true);
        loaderVerBox.setPromptText("加载器版本");
        loaderVerBox.setOnAction(e -> updateInstanceLabel());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button startBtn = new Button("开始下载");
        startBtn.getStyleClass().add("primary");
        startBtn.setOnAction(e -> startInstall());

        HBox row1 = new HBox(10, loaderLabel, loaderBox, loaderVerBox, spacer, startBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        Label instLabel = new Label("实例目录");
        instLabel.getStyleClass().add("field-label");
        instanceLabel = new Label("—");
        instanceLabel.getStyleClass().add("v-meta");

        HBox row2 = new HBox(10, instLabel, instanceLabel);
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, row1, row2);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(14, 16, 14, 16));
        return card;
    }

    private static String typeName(String type) {
        if ("release".equals(type)) return "正式版";
        if ("snapshot".equals(type)) return "快照";
        if ("old_beta".equals(type)) return "远古 Beta";
        if ("old_alpha".equals(type)) return "远古 Alpha";
        return type;
    }



    private void loadVersionsAsync() {
        Thread th = new Thread(() -> {
            try {
                List<String[]> all = core.loadVersions();
                Platform.runLater(() -> {
                    allVersions.clear();
                    allVersions.addAll(all);
                    applyFilter();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    versionList.setPlaceholder(new Label("版本列表获取失败：" + e.getMessage()));
                    toast("版本列表获取失败");
                });
            }
        }, "manifest");
        th.setDaemon(true);
        th.start();
    }

    private void applyFilter() {
        String mode = typeFilter == null ? "正式版" : typeFilter.getValue();
        String keyword = searchField == null ? "" : searchField.getText().trim().toLowerCase();

        installed.clear();
        versions.clear();

        for (String[] v : allVersions) {
            String type = v[1];
            boolean typeOk = "全部".equals(mode)
                    || ("正式版".equals(mode) && "release".equals(type))
                    || ("快照".equals(mode) && "snapshot".equals(type));

            boolean keywordOk = keyword.isEmpty()
                    || v[0].toLowerCase().contains(keyword);

            if (!typeOk || !keywordOk) continue;

            versions.add(v);
            if (new File(core.gameDir, "versions/" + v[0]).isDirectory()) installed.add(v[0]);
        }

        if (versionList != null) versionList.refresh();
        updateInstanceLabel();
    }


    private void refreshLoaderVersions() {
        String loader = loaderBox.getValue();
        String selected = versionList == null ? null : versionId(versionList.getSelectionModel().getSelectedItem());

        if ("原版".equals(loader) || selected == null) {
            loaderVerBox.getItems().clear();
            loaderVerBox.setDisable(true);
            loaderVerBox.setPromptText("加载器版本");
            updateInstanceLabel();
            return;
        }

        loaderVerBox.setDisable(true);
        loaderVerBox.setPromptText("加载中…");

        Thread th = new Thread(() -> {
            try {
                List<String> list;
                if ("Fabric".equals(loader)) list = core.listFabric(selected);
                else if ("Forge".equals(loader)) list = core.listForge(selected);
                else list = core.listNeoForge(selected);

                Platform.runLater(() -> {
                    loaderVerBox.setItems(FXCollections.observableArrayList(list));
                    loaderVerBox.setDisable(false);
                    loaderVerBox.setPromptText("加载器版本");
                    if (!list.isEmpty()) loaderVerBox.setValue(list.get(0));
                    if (list.isEmpty()) {
                        loaderVerBox.setPromptText("该版本无可用 " + loader);
                        toast(selected + " 没有可用的 " + loader + " 版本");
                    }
                    updateInstanceLabel();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loaderVerBox.setDisable(false);
                    loaderVerBox.setPromptText("获取失败");
                    toast(loader + " 版本获取失败");
                });
            }
        }, "loader-list");
        th.setDaemon(true);
        th.start();
    }

    private static String versionId(String[] v) {
        return v == null ? null : v[0];
    }

    private void updateInstanceLabel() {
        if (instanceLabel == null) return;

        String[] sel = versionList == null
                ? null : versionList.getSelectionModel().getSelectedItem();
        String mc = versionId(sel);
        if (mc == null) {
            instanceLabel.setText("—");
            return;
        }

        String loader = loaderBox.getValue();

        if ("原版".equals(loader)) {
            instanceLabel.setText("versions/" + mc);
            return;
        }

        String lver = loaderVerBox.getValue();
        if (lver == null || lver.isEmpty()) {
            instanceLabel.setText("versions/" + mc + " · 待选择加载器版本");
            return;
        }

        instanceLabel.setText("versions/" + Core.instanceName(mc, loader, lver));
    }

    private void startInstall() {
        String[] sel = versionList.getSelectionModel().getSelectedItem();
        if (sel == null) {
            toast("请先选择一个游戏版本");
            return;
        }

        String mc = sel[0];
        String loader = loaderBox.getValue();
        String lver = "原版".equals(loader) ? null : loaderVerBox.getValue();

        if (!"原版".equals(loader) && (lver == null || lver.isEmpty())) {
            toast("请选择加载器版本");
            return;
        }

        Task task = core.install(mc, loader, lver);
        taskView.add(task);
        toast("已加入任务：" + task.instance);
        selectNav("tasks");
    }



    private Pane buildTasksPage() {
        Label heading = new Label("任务");
        heading.getStyleClass().add("h2");

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        Button clearBtn = new Button("清理已完成");
        clearBtn.getStyleClass().add("ghost");
        clearBtn.setOnAction(e -> {
            List<Task> remove = new ArrayList<>();
            for (Task t : core.tasks) {
                if (t.finished()) remove.add(t);
            }
            core.tasks.removeAll(remove);
            taskView.removeAll(remove);
            toast("已清理 " + remove.size() + " 个任务");
        });

        HBox top = new HBox(10, heading, topSpacer, clearBtn);
        top.setAlignment(Pos.CENTER_LEFT);

        taskList = new ListView<>(taskView);
        taskList.getStyleClass().add("vlist");
        taskList.setPlaceholder(new Label("还没有任务，去「下载」页挑个版本吧"));
        VBox.setVgrow(taskList, Priority.ALWAYS);

        taskList.setCellFactory(lv -> new ListCell<Task>() {
            private final Label titleLbl = new Label();
            private final Label stageLbl = new Label();
            private final Label infoLbl = new Label();
            private final ProgressBar bar = new ProgressBar(0);
            private final Button stopBtn = new Button("停止");
            private final VBox root;

            private Task current;

            {
                titleLbl.getStyleClass().add("t-title");
                stageLbl.getStyleClass().add("t-stage");
                infoLbl.getStyleClass().add("t-info");

                stopBtn.getStyleClass().add("ghost");
                stopBtn.setFocusTraversable(false);


                stopBtn.setOnAction(e -> {
                    Task task = current;
                    if (task == null || task.finished()) return;

                    task.cancel();              
                    stopBtn.setText("取消中…");
                    stopBtn.setDisable(true);
                    Main.this.refresh();        
                });

                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setPrefHeight(8);

                Region headSpacer = new Region();
                HBox.setHgrow(headSpacer, Priority.ALWAYS);
                HBox head = new HBox(8, titleLbl, headSpacer, stageLbl);
                head.setAlignment(Pos.CENTER_LEFT);

                Region footSpacer = new Region();
                HBox.setHgrow(footSpacer, Priority.ALWAYS);
                HBox foot = new HBox(8, infoLbl, footSpacer, stopBtn);
                foot.setAlignment(Pos.CENTER_LEFT);

                root = new VBox(7, head, bar, foot);
                root.setPadding(new Insets(4, 2, 4, 2));
            }

            @Override
            protected void updateItem(Task t, boolean empty) {
                super.updateItem(t, empty);

                if (empty || t == null) {
                    current = null;
                    setGraphic(null);
                    setText(null);
                    setTooltip(null);
                    return;
                }
                current = t;

                titleLbl.setText(t.instance);
                stageLbl.setText(t.stage + "  " + t.displayLoader());

                double p = t.progress();
                bar.setProgress(t.indeterminate ? ProgressBar.INDETERMINATE_PROGRESS : p);

                StringBuilder info = new StringBuilder();
                info.append((int) (p * 100)).append("%");
                long bt = t.bytesTotal.get();
                if (bt > 0) {
                    info.append("  ").append(Task.size(t.bytesDone.get()))
                        .append(" / ").append(Task.size(bt));
                }
                if (t.state == Task.RUNNING && t.speed > 0) {
                    info.append("  ").append(Task.size((long) t.speed)).append("/s");
                }
                info.append("  ").append(t.filesDone.get()).append("/")
                    .append(t.filesTotal.get()).append(" 个文件");
                infoLbl.setText(info.toString());

                switch (t.state) {
                    case Task.RUNNING:
                        stageLbl.setStyle("-fx-text-fill:#2E9E96;");
                        break;
                    case Task.DONE:
                        stageLbl.setStyle("-fx-text-fill:#2E9E96;");
                        break;
                    case Task.FAILED:
                        stageLbl.setStyle("-fx-text-fill:#D9534F;");
                        infoLbl.setText(t.error);
                        break;
                    case Task.CANCELLED:
                        stageLbl.setStyle("-fx-text-fill:#B07A2E;");
                        break;
                    default:
                        stageLbl.setStyle("-fx-text-fill:#9AA7A6;");
                        break;
                }


                stopBtn.setText(t.cancelFlag ? "取消中…" : "停止");
                stopBtn.setDisable(t.cancelFlag);
                stopBtn.setVisible(!t.finished());
                stopBtn.setManaged(!t.finished());

                int from = Math.max(0, t.log.size() - 12);
                StringBuilder logText = new StringBuilder();
                for (int i = from; i < t.log.size(); i++) {
                    logText.append(t.log.get(i)).append('\n');
                }
                setTooltip(logText.length() == 0 ? null : new Tooltip(logText.toString()));

                setGraphic(root);
                setText(null);
            }
        });

        VBox page = new VBox(14, top, taskList);
        page.setPadding(new Insets(4, 18, 18, 18));
        VBox.setVgrow(taskList, Priority.ALWAYS);
        return page;
    }



    private Pane buildSettingsPage() {
        Label heading = new Label("设置");
        heading.getStyleClass().add("h2");


        Label dirLabel = new Label("游戏目录");
        dirLabel.getStyleClass().add("field-label");

        TextField dirField = new TextField(core.gameDir.getAbsolutePath());
        dirField.setPrefWidth(400);
        HBox.setHgrow(dirField, Priority.ALWAYS);

        Button browseBtn = new Button("浏览");
        browseBtn.getStyleClass().add("ghost");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择 .minecraft 目录");
            File init = new File(dirField.getText().trim());
            if (init.isDirectory()) chooser.setInitialDirectory(init);
            File picked = chooser.showDialog(stage);
            if (picked != null) dirField.setText(picked.getAbsolutePath());
        });

        Button openBtn = new Button("打开");
        openBtn.getStyleClass().add("ghost");
        openBtn.setOnAction(e -> openDir(new File(dirField.getText().trim())));

        HBox dirRow = new HBox(10, dirLabel, dirField, browseBtn, openBtn);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        Label threadLabel = new Label("并发线程");
        threadLabel.getStyleClass().add("field-label");

        Spinner<Integer> threadSpinner = new Spinner<>(1, 32, core.threads);
        threadSpinner.setPrefWidth(90);
        threadSpinner.setEditable(true);

        Label threadHint = new Label("默认 8；网络较差可降到 4");
        threadHint.getStyleClass().add("t-info");

        HBox threadRow = new HBox(10, threadLabel, threadSpinner, threadHint);
        threadRow.setAlignment(Pos.CENTER_LEFT);


        CheckBox mirrorBox = new CheckBox("使用 bmclAPI 镜像（失败自动回落官方源）");
        mirrorBox.setSelected(core.mirror);

        CheckBox shaBox = new CheckBox("下载后校验 SHA1（更安全，重复安装也更快跳过）");
        shaBox.setSelected(core.checkSha1);

        Button saveBtn = new Button("保存设置");
        saveBtn.getStyleClass().add("primary");
        saveBtn.setOnAction(e -> {
            String path = dirField.getText().trim();
            if (!path.isEmpty()) {
                File dir = new File(path);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    toast("目录不可用：" + path);
                    return;
                }
                core.gameDir = dir;
            }
            core.threads = threadSpinner.getValue() == null ? 8 : threadSpinner.getValue();
            core.mirror = mirrorBox.isSelected();
            core.checkSha1 = shaBox.isSelected();
            core.save();
            applyFilter();
            toast("设置已保存");
        });

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox btnRow = new HBox(10, btnSpacer, saveBtn);

        Label hint = new Label(
                "说明：本程序仅有下载功能，不会尝试启动游戏\n"
              + "安装完成后自动兼容其他启动器的游戏目录\n"
              + "强制版本隔离");
        hint.getStyleClass().add("t-info");
        hint.setWrapText(true);

        VBox card = new VBox(12, dirRow, threadRow, mirrorBox, shaBox, btnRow, hint);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        VBox page = new VBox(14, heading, card);
        page.setPadding(new Insets(4, 18, 18, 18));
        return page;
    }
    


    private Pane buildAboutPage() {

        Node logo = loadLogo();
        StackPane logoBox = new StackPane(logo);
        logoBox.setMinHeight(116);


        Label name = new Label(Core.APP_NAME);
        name.getStyleClass().add("about-name");

        Label latin = new Label("AndesiteDownloader");
        latin.getStyleClass().add("about-latin");


        Label author = new Label("作者：Suttanfix · GitHub @SutaNeya · v" + Core.APP_VERSION);
        author.getStyleClass().add("about-author");

        VBox header = new VBox(6, logoBox, name, latin, author);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(6, 0, 10, 0));

        VBox content = new VBox(16,
                header,
                textCard("关于我们", ABOUT_US),
                textCard("鸣谢", CREDITS),
                agreementCard());
        content.setPadding(new Insets(4, 20, 26, 4));
        content.setAlignment(Pos.TOP_CENTER);
        content.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("about-scroll");

        StackPane page = new StackPane(scroll);
        return page;
    }


    private Pane textCard(String title, String body) {
        Label h = new Label(title);
        h.getStyleClass().add("sec-title");

        Label b = new Label(body);
        b.getStyleClass().add("sec-body");
        b.setWrapText(true);
        b.setLineSpacing(4);

        VBox card = new VBox(10, h, b);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16, 22, 18, 22));
        return card;
    }


    private Pane agreementCard() {
        Label h = new Label("用户协议与隐私政策");
        h.getStyleClass().add("sec-title");

        VBox body = new VBox(7);
        body.setFillWidth(true);

        String text = loadResourceText("/agreement.txt");
        if (text == null || text.isBlank()) {
            Label miss = new Label("未找到 resources/agreement.txt，请确认文件已放入 src/main/resources/");
            miss.getStyleClass().add("sec-body");
            miss.setWrapText(true);
            body.getChildren().add(miss);
        } else {
            for (String[] block : parseAgreement(text)) {
                Label l = new Label(block[1]);
                l.setWrapText(true);
                if ("H".equals(block[0])) {
                    l.getStyleClass().add("sec-h3");
                    VBox.setMargin(l, new Insets(12, 0, 2, 0));
                } else {
                    l.getStyleClass().add("sec-body");
                    l.setLineSpacing(4);
                }
                body.getChildren().add(l);
            }
        }

        VBox card = new VBox(10, h, body);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16, 22, 20, 22));
        return card;
    }


    private static List<String[]> parseAgreement(String text) {
        List<String[]> out = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        boolean started = false;

        for (String line : text.split("\r?\n")) {
            String t = line.trim();

            if (t.isEmpty()) {
                if (started) flushPara(out, para);
                else para.setLength(0);
                continue;
            }

            if (t.startsWith("## ")) {
                if (started) flushPara(out, para);
                started = true;
                out.add(new String[]{"H", t.substring(3).trim()});
                continue;
            }

            if (!started) continue;   

            if (para.length() > 0 && needsSpace(para.charAt(para.length() - 1), t.charAt(0))) {
                para.append(' ');
            }
            para.append(t);
        }
        if (started) flushPara(out, para);
        return out;
    }

    private static void flushPara(List<String[]> out, StringBuilder para) {
        if (para.length() == 0) return;
        out.add(new String[]{"P", para.toString()});
        para.setLength(0);
    }


    private static boolean needsSpace(char a, char b) {
        return a < 128 && b < 128;
    }


    private Node loadLogo() {
        String[] candidates = {"/logo.png", "/logo.jpg", "/logo.jpeg"};
        for (String path : candidates) {
            var url = Main.class.getResource(path);
            if (url == null) continue;
            Image img = new Image(url.toExternalForm(), 128, 128, true, true, false);
            if (img.isError()) continue;
            ImageView iv = new ImageView(img);
            iv.setFitWidth(104);
            iv.setFitHeight(104);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            return iv;
        }


        StackPane ph = new StackPane();
        ph.getStyleClass().add("logo-ph");
        ph.setMinSize(104, 104);
        ph.setMaxSize(104, 104);
        Label t = new Label("安");
        t.getStyleClass().add("logo-ph-text");
        ph.getChildren().add(t);
        return ph;
    }

    private static String loadResourceText(String path) {
        try (var in = Main.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }



    private void startTicker() {
        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(333), e -> refresh()));
        ticker.setCycleCount(Animation.INDEFINITE);
        ticker.play();
    }

    private void refresh() {
        List<Task> snapshot = new ArrayList<>(core.tasks);
        Task running = null;
        long now = System.currentTimeMillis();
        List<Task> remove = new ArrayList<>();

        for (Task t : snapshot) {
            t.tick();

            if (t.state == Task.RUNNING) {
                if (running == null) running = t;
            } else if (t.state == Task.CANCELLED
                    && t.finishedAt > 0L
                    && now - t.finishedAt > 1200L) {
                remove.add(t);      
            }
        }

        if (!remove.isEmpty()) {
            core.tasks.removeAll(remove);
            taskView.removeAll(remove);
            snapshot.removeAll(remove);
        }

        if (taskList != null) taskList.refresh();

        if (running != null) {
            footerLabel.setText("正在安装 " + running.instance + " · " + running.stage);

            long bt = running.bytesTotal.get();
            StringBuilder sb = new StringBuilder();
            sb.append((int) (running.progress() * 100)).append("%");
            if (running.speed > 0) sb.append("  ").append(Task.size((long) running.speed)).append("/s");
            if (bt > 0) {
                sb.append("  ").append(Task.size(running.bytesDone.get()))
                  .append(" / ").append(Task.size(bt));
            }
            footerSpeed.setText(sb.toString());
        } else {
            int done = 0;
            int failed = 0;
            for (Task t : snapshot) {
                if (t.state == Task.DONE) done++;
                if (t.state == Task.FAILED) failed++;
            }

            if (snapshot.isEmpty()) {
                footerLabel.setText("就绪");
            } else {
                footerLabel.setText("已完成 " + done + " 个任务"
                        + (failed > 0 ? "，失败 " + failed + " 个" : ""));
            }
            footerSpeed.setText("");
        }
    }



    private void toast(String msg) {
        if (toastLabel == null) return;
        toastLabel.setText("· " + msg);
        if (toastTimer != null) toastTimer.stop();
        toastTimer = new PauseTransition(Duration.seconds(3));
        toastTimer.setOnFinished(e -> toastLabel.setText(""));
        toastTimer.play();
    }


    private static String pickFont() {
        List<String> families = Font.getFamilies();
        String[] prefer = {
                "Microsoft YaHei UI", "Microsoft YaHei",
                "Noto Sans CJK SC", "Source Han Sans SC",
                "WenQuanYi Micro Hei", "PingFang SC"
        };
        for (String f : prefer) {
            if (families.contains(f)) return f;
        }
        return Font.getDefault().getFamily();
    }

    static void openDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer", dir.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", dir.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
            }
        } catch (Exception ignored) {
        }
    }
}
