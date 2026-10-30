package ysp;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.LayoutManager;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

/** Yukkuri Sprite Builder のGUI(Swing)。処理本体は CLI と同じ BatchRunner を使う。 */
@SuppressWarnings("serial")
public final class Gui extends JFrame {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Gui.class);

    private final DefaultListModel<String> psdModel = new DefaultListModel<>();
    private final JList<String> psdList = new JList<>(psdModel);

    private final JTextField waifuField = new JTextField();
    private final JTextField pythonField = new JTextField();
    private final JSpinner scaleSpin = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 16.0, 0.5));
    private final JComboBox<String> noiseBox = new JComboBox<>(new String[] {"なし", "0", "1", "2", "3"});
    private final JComboBox<String> processBox = new JComboBox<>(new String[] {"既定", "cpu", "gpu", "cudnn"});
    private final JSpinner jobsSpin = new JSpinner(new SpinnerNumberModel(2, 1, 16, 1));
    private final JSpinner waifuJobsSpin = new JSpinner(new SpinnerNumberModel(1, 1, 8, 1));
    private final JCheckBox step1Box = new JCheckBox("1 PSD分解", true);
    private final JCheckBox step2Box = new JCheckBox("2 高画質化(waifu2x)", true);
    private final JCheckBox step3Box = new JCheckBox("3 PSD再構築", true);
    private final JTextField extraField = new JTextField();
    private final JCheckBox ymmBox = new JCheckBox("YMM立ち絵設定(-ymm.json)を生成する", true);
    private final JCheckBox ymmOverwriteBox = new JCheckBox("既存の設定を上書きする", false);
    private final JComboBox<String> ymmIdsBox = new JComboBox<>(new String[] {"0", "1", "none"});
    private final JTextField rulesField = new JTextField();

    private final JTextArea logArea = new JTextArea();
    private final JButton runButton = new JButton("実行");
    private final JButton stopButton = new JButton("中止");
    private final JButton checkButton = new JButton("環境チェック");
    private final JButton aboutButton = new JButton("このアプリについて");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel statusLabel = new JLabel("待機中");

    private boolean running = false;

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 既定の見た目のまま続行
            }
            new Gui().setVisible(true);
        });
    }

    public static void main(String[] args) {
        launch();
    }

    public Gui() {
        super("Yukkuri Sprite Builder (ysp)");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });

        // 処理ログを画面へ流す
        PrintStream ps = new PrintStream(new LogStream(), true, StandardCharsets.UTF_8);
        System.setOut(ps);
        System.setErr(ps);

        loadWindowIcon();
        buildUi();
        loadPrefs();
        setRunning(false);
        setMinimumSize(new Dimension(760, 640));
        setSize(860, 900);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        top.add(buildPsdPanel());
        top.add(buildToolPanel());
        top.add(buildOptionPanel());
        top.add(buildYmmPanel());

        logArea.setEditable(false);
        logArea.setLineWrap(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("ログ"));
        logScroll.setPreferredSize(new Dimension(700, 220));

        stopButton.addActionListener(e -> {
            BatchRunner.cancel();
            statusLabel.setText("中止しています...");
        });
        runButton.addActionListener(e -> onRun());
        checkButton.addActionListener(e -> onCheck());
        aboutButton.addActionListener(e -> showAbout());
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(160, 16));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(runButton);
        buttons.add(stopButton);
        buttons.add(checkButton);
        buttons.add(aboutButton);
        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        status.add(statusLabel);
        status.add(progress);
        bottom.add(buttons, BorderLayout.WEST);
        bottom.add(status, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(logScroll, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
    }

    private JPanel buildPsdPanel() {
        JPanel p = titled("処理するPSD (ファイル / フォルダをここへドラッグ&ドロップできます)", new BorderLayout(6, 0));
        psdList.setVisibleRowCount(6);
        JScrollPane scroll = new JScrollPane(psdList);

        TransferHandler dnd = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        addPsd(f.getAbsolutePath());
                    }
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
        psdList.setTransferHandler(dnd);
        scroll.setTransferHandler(dnd);

        JButton addFiles = new JButton("ファイル追加...");
        JButton addFolder = new JButton("フォルダ追加...");
        JButton remove = new JButton("選択を削除");
        JButton clear = new JButton("全て削除");
        addFiles.addActionListener(e -> {
            File[] fs = choose("PSDファイルを選択", JFileChooser.FILES_ONLY, true,
                    new FileNameExtensionFilter("PSDファイル (*.psd)", "psd"));
            for (File f : fs) {
                addPsd(f.getAbsolutePath());
            }
        });
        addFolder.addActionListener(e -> {
            for (File f : choose("PSDが入っているフォルダを選択", JFileChooser.DIRECTORIES_ONLY, true, null)) {
                addPsd(f.getAbsolutePath());
            }
        });
        remove.addActionListener(e -> {
            for (String s : psdList.getSelectedValuesList()) {
                psdModel.removeElement(s);
            }
        });
        clear.addActionListener(e -> psdModel.clear());

        JPanel side = new JPanel(new java.awt.GridLayout(4, 1, 0, 4));
        side.add(addFiles);
        side.add(addFolder);
        side.add(remove);
        side.add(clear);
        JPanel sideWrap = new JPanel(new BorderLayout());
        sideWrap.add(side, BorderLayout.NORTH);

        p.add(scroll, BorderLayout.CENTER);
        p.add(sideWrap, BorderLayout.EAST);
        return p;
    }

    private JPanel buildToolPanel() {
        JPanel p = titled("ツール", new BorderLayout());
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));

        waifuField.setToolTipText("waifu2x-caffe-cui.exe (またはそのフォルダ)。一度指定すると次回以降は自動で使われます");
        JButton waifuBrowse = new JButton("参照...");
        waifuBrowse.addActionListener(e -> browseInto(waifuField, JFileChooser.FILES_AND_DIRECTORIES,
                new FileNameExtensionFilter("waifu2x-caffe-cui.exe", "exe")));
        rows.add(pathRow("waifu2x-caffe-cui", waifuField, waifuBrowse));
        rows.add(javax.swing.Box.createVerticalStrut(4));

        pythonField.setToolTipText("空欄 = 自動検出 (python / py -3 / python3)。見つからない場合は python.exe を指定してください");
        JButton pyBrowse = new JButton("参照...");
        pyBrowse.addActionListener(e -> browseInto(pythonField, JFileChooser.FILES_ONLY,
                new FileNameExtensionFilter("python.exe", "exe")));
        rows.add(pathRow("Python (空欄=自動)", pythonField, pyBrowse));
        p.add(rows, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildOptionPanel() {
        JPanel p = titled("処理設定", new BorderLayout());
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));

        rows.add(flow(new JLabel("拡大率"), scaleSpin, new JLabel("   ノイズ除去"), noiseBox,
                new JLabel("   処理方式"), processBox));
        rows.add(flow(new JLabel("同時に処理するPSD数"), jobsSpin, new JLabel("   同時に動かすwaifu2x数"), waifuJobsSpin));
        waifuJobsSpin.setToolTipText("GPUが1枚の場合は 1 を推奨します");
        rows.add(flow(new JLabel("実行するステップ"), step1Box, step2Box, step3Box));
        rows.add(pathRow("waifu2x追加引数", extraField));
        p.add(rows, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildYmmPanel() {
        JPanel p = titled("YukkuriMovieMaker 立ち絵設定 (口パク / まばたき)", new BorderLayout());
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        ymmIdsBox.setToolTipText("Layers の n番号の基点。ずれる場合は 1 か none を試してください");
        rows.add(flow(ymmBox, ymmOverwriteBox, new JLabel("   n番号"), ymmIdsBox));
        JButton rulesBrowse = new JButton("参照...");
        rulesBrowse.addActionListener(e -> browseInto(rulesField, JFileChooser.FILES_ONLY,
                new FileNameExtensionFilter("JSON (*.json)", "json")));
        rulesField.setToolTipText("口/目や開閉の判定キーワードを上書きするJSON (空欄=既定)");
        rows.add(pathRow("判定ルール(任意)", rulesField, rulesBrowse));
        p.add(rows, BorderLayout.CENTER);
        return p;
    }

    private static JPanel titled(String title, LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static JPanel flow(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component c : cs) {
            p.add(c);
        }
        return p;
    }

    private static JPanel pathRow(String label, JTextField field, JButton... buttons) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(140, 24));
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : buttons) {
            east.add(b);
        }
        row.add(east, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    // ------------------------------------------------------------------ 操作
    private void addPsd(String path) {
        if (!psdModel.contains(path)) {
            psdModel.addElement(path);
        }
    }

    private File[] choose(String title, int mode, boolean multi, FileNameExtensionFilter filter) {
        JFileChooser fc = new JFileChooser(PREFS.get("lastDir", System.getProperty("user.home")));
        fc.setDialogTitle(title);
        fc.setFileSelectionMode(mode);
        fc.setMultiSelectionEnabled(multi);
        if (filter != null) {
            fc.setFileFilter(filter);
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return new File[0];
        }
        File[] selected = multi ? fc.getSelectedFiles() : new File[] {fc.getSelectedFile()};
        if (selected.length > 0 && selected[0] != null) {
            File dir = selected[0].isDirectory() ? selected[0] : selected[0].getParentFile();
            if (dir != null) {
                PREFS.put("lastDir", dir.getAbsolutePath());
            }
        }
        return selected;
    }

    private void browseInto(JTextField field, int mode, FileNameExtensionFilter filter) {
        File[] fs = choose("選択", mode, false, filter);
        if (fs.length > 0 && fs[0] != null) {
            field.setText(fs[0].getAbsolutePath());
        }
    }

    private GuiSettings collect() {
        GuiSettings s = new GuiSettings();
        for (int i = 0; i < psdModel.size(); i++) {
            s.psds.add(psdModel.get(i));
        }
        s.waifu2x = waifuField.getText();
        s.python = pythonField.getText();
        s.scale = ((Number) scaleSpin.getValue()).doubleValue();
        s.noise = String.valueOf(noiseBox.getSelectedItem());
        s.process = String.valueOf(processBox.getSelectedItem());
        s.jobs = ((Number) jobsSpin.getValue()).intValue();
        s.waifu2xJobs = ((Number) waifuJobsSpin.getValue()).intValue();
        s.step1 = step1Box.isSelected();
        s.step2 = step2Box.isSelected();
        s.step3 = step3Box.isSelected();
        s.ymmEnabled = ymmBox.isSelected();
        s.ymmOverwrite = ymmOverwriteBox.isSelected();
        s.ymmIds = String.valueOf(ymmIdsBox.getSelectedItem());
        s.ymmRules = rulesField.getText();
        s.extraArgs = extraField.getText();
        return s;
    }

    private void onRun() {
        GuiSettings s = collect();
        PipelineConfig parsed;
        try {
            parsed = s.toConfig();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "入力を確認してください", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final PipelineConfig cfg = parsed;
        savePrefs(s);
        logArea.setText("");
        setRunning(true);
        statusLabel.setText("処理中...");

        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return new BatchRunner(cfg).run();
            }

            @Override
            protected void done() {
                setRunning(false);
                try {
                    int code = get();
                    statusLabel.setText(code == 0 ? "完了" : "失敗したPSDがあります (ログを確認してください)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    String msg = c.getMessage() == null ? c.toString() : c.getMessage();
                    System.out.println("[ERROR] " + msg);
                    statusLabel.setText("エラー: " + msg);
                }
            }
        }.execute();
    }

    private void onCheck() {
        final GuiSettings s = collect();
        setRunning(true);
        statusLabel.setText("環境を確認中...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                System.out.println("=== 環境チェック ===");
                try {
                    ToolResolver.resolvePython(GuiSettings.clean(s.python));
                    System.out.println("[OK] Python と psd-tools / Pillow を確認しました");
                } catch (RuntimeException e) {
                    System.out.println("[NG] " + e.getMessage());
                }
                try {
                    String w = GuiSettings.clean(s.waifu2x);
                    Path exe = ToolResolver.resolveWaifu2x(w == null ? null : Path.of(w));
                    System.out.println("[OK] waifu2x: " + exe);
                } catch (IllegalArgumentException | IOException e) {
                    System.out.println("[NG] " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void done() {
                setRunning(false);
                statusLabel.setText("環境チェック完了");
            }
        }.execute();
    }

    private void setRunning(boolean r) {
        running = r;
        runButton.setEnabled(!r);
        checkButton.setEnabled(!r);
        stopButton.setEnabled(r);
        progress.setVisible(r);
    }

    private void onClose() {
        if (running) {
            int answer = JOptionPane.showConfirmDialog(this, "処理中です。中止して終了しますか?", "終了の確認",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
            BatchRunner.cancel();
        }
        savePrefs(collect());
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------ アイコン / About
    /** ウィンドウ(タスクバー)のアイコン。リソースの /icon.png を使う(差し替え方法は README 参照)。 */
    private void loadWindowIcon() {
        try (InputStream in = Gui.class.getResourceAsStream("/icon.png")) {
            if (in != null) {
                setIconImage(ImageIO.read(in));
            }
        } catch (IOException ignored) {
            // アイコンが読めなくても動作に影響しない
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "Yukkuri Sprite Builder (ysp)  v" + Main.VERSION + "\n"
                        + "作者: KairaYK / ゆっくり廻螺\n"
                        + "ライセンス: MIT License\n\n"
                        + "PSDの分解 → waifu2xで高画質化 → PSD再構築、\n"
                        + "およびYMM4用の立ち絵設定(口パク/まばたき)の生成を行うツールです。\n\n"
                        + "使い方・免責事項・ライセンスの詳細は、同梱の README.txt を参照してください。",
                "このアプリについて", JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------ 設定の保存
    private void loadPrefs() {
        Path cached = ConfigStore.loadWaifu2x();
        waifuField.setText(cached != null ? cached.toString() : "");
        pythonField.setText(PREFS.get("python", ""));
        scaleSpin.setValue(PREFS.getDouble("scale", 2.0));
        noiseBox.setSelectedItem(PREFS.get("noise", "なし"));
        processBox.setSelectedItem(PREFS.get("process", "既定"));
        jobsSpin.setValue(PREFS.getInt("jobs", 2));
        waifuJobsSpin.setValue(PREFS.getInt("waifuJobs", 1));
        step1Box.setSelected(PREFS.getBoolean("step1", true));
        step2Box.setSelected(PREFS.getBoolean("step2", true));
        step3Box.setSelected(PREFS.getBoolean("step3", true));
        ymmBox.setSelected(PREFS.getBoolean("ymm", true));
        ymmOverwriteBox.setSelected(PREFS.getBoolean("ymmOverwrite", false));
        ymmIdsBox.setSelectedItem(PREFS.get("ymmIds", "0"));
        rulesField.setText(PREFS.get("rules", ""));
        extraField.setText(PREFS.get("extra", ""));
    }

    private void savePrefs(GuiSettings s) {
        PREFS.put("python", s.python);
        PREFS.putDouble("scale", s.scale);
        PREFS.put("noise", s.noise);
        PREFS.put("process", s.process);
        PREFS.putInt("jobs", s.jobs);
        PREFS.putInt("waifuJobs", s.waifu2xJobs);
        PREFS.putBoolean("step1", s.step1);
        PREFS.putBoolean("step2", s.step2);
        PREFS.putBoolean("step3", s.step3);
        PREFS.putBoolean("ymm", s.ymmEnabled);
        PREFS.putBoolean("ymmOverwrite", s.ymmOverwrite);
        PREFS.put("ymmIds", s.ymmIds);
        PREFS.put("rules", s.ymmRules);
        PREFS.put("extra", s.extraArgs);
    }

    // ------------------------------------------------------------------ ログ
    private void appendLog(String s) {
        logArea.append(s);
        int len = logArea.getDocument().getLength();
        if (len > 300_000) {
            try {
                logArea.getDocument().remove(0, len - 200_000);
            } catch (BadLocationException ignored) {
                // 古いログの削除に失敗しても無視
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** System.out / err への出力を1行ずつログ欄へ流す(UTF-8)。 */
    private final class LogStream extends OutputStream {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buf.write(b);
            if (b == '\n') {
                String line = buf.toString(StandardCharsets.UTF_8);
                buf.reset();
                SwingUtilities.invokeLater(() -> appendLog(line));
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }
    }
}
