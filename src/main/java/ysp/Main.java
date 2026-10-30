package ysp;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class Main {
    static final String VERSION = "0.1.0";

    private Main() {}

    public static void main(String[] args) {
        // 引数なしで起動(exe / jar のダブルクリック)した場合はGUIを開く
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            Gui.launch();
            return;
        }
        try {
            PipelineConfig cfg = parse(args);
            if (cfg == null) {
                return; // --help / --clear-cache のみ
            }
            System.out.println("Yukkuri Sprite Builder (ysp) v" + VERSION);
            int code = new BatchRunner(cfg).run();
            if (code != 0) {
                System.exit(code);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    private static PipelineConfig parse(String[] a) throws IOException {
        List<String> psdInputs = new ArrayList<>();
        Path waifu = null;
        double scale = 2.0;
        String python = null; // null = 自動検出
        Integer noise = null;
        String process = null;
        Set<Integer> steps = new TreeSet<>(Set.of(1, 2, 3));
        boolean clearCache = false;
        List<String> extra = new ArrayList<>();
        int jobs = 2;
        int waifuJobs = 1;
        boolean ymmEnabled = true;
        boolean ymmOverwrite = false;
        Path ymmRules = null;
        String ymmIds = "0";

        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "-h", "--help" -> {
                    printUsage();
                    return null;
                }
                case "-p", "--psd" -> psdInputs.add(value(a, ++i, "--psd"));
                case "--psd-list" -> {
                    Path listFile = Path.of(value(a, ++i, "--psd-list"));
                    for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.startsWith("\uFEFF")) {
                            t = t.substring(1).trim(); // BOM
                        }
                        if (t.isEmpty() || t.startsWith("#")) {
                            continue;
                        }
                        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                            t = t.substring(1, t.length() - 1);
                        }
                        psdInputs.add(t);
                    }
                }
                case "-w", "--waifu2x" -> waifu = Path.of(value(a, ++i, "--waifu2x"));
                case "-s", "--scale" -> {
                    try {
                        scale = Double.parseDouble(value(a, ++i, "--scale"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--scale must be a number");
                    }
                }
                case "--python" -> python = value(a, ++i, "--python");
                case "--noise" -> {
                    try {
                        noise = Integer.parseInt(value(a, ++i, "--noise"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--noise must be an integer (0-3)");
                    }
                }
                case "--process" -> process = value(a, ++i, "--process");
                case "--steps" -> {
                    steps = new TreeSet<>();
                    for (String s : value(a, ++i, "--steps").split(",")) {
                        int n = Integer.parseInt(s.trim());
                        if (n < 1 || n > 3) {
                            throw new IllegalArgumentException("--steps accepts 1,2,3");
                        }
                        steps.add(n);
                    }
                }
                case "-j", "--jobs" -> jobs = positiveInt(value(a, ++i, "--jobs"), "--jobs");
                case "--waifu2x-jobs" -> waifuJobs = positiveInt(value(a, ++i, "--waifu2x-jobs"), "--waifu2x-jobs");
                case "--no-ymm" -> ymmEnabled = false;
                case "--ymm-overwrite" -> ymmOverwrite = true;
                case "--ymm-rules" -> ymmRules = Path.of(value(a, ++i, "--ymm-rules"));
                case "--ymm-ids" -> {
                    ymmIds = value(a, ++i, "--ymm-ids");
                    if (!ymmIds.equals("0") && !ymmIds.equals("1") && !ymmIds.equals("none")) {
                        throw new IllegalArgumentException("--ymm-ids accepts 0, 1 or none");
                    }
                }
                case "--clear-cache" -> clearCache = true;
                case "--waifu2x-arg" -> extra.add(value(a, ++i, "--waifu2x-arg"));
                default -> throw new IllegalArgumentException("Unknown option: " + a[i]);
            }
        }
        if (clearCache) {
            try {
                ConfigStore.clear();
            } catch (IOException e) {
                throw new IllegalStateException("could not clear cache: " + e.getMessage(), e);
            }
            System.out.println("[INFO] cached waifu2x path cleared (" + ConfigStore.configFile() + ")");
            if (psdInputs.isEmpty()) {
                return null;
            }
        }
        if (psdInputs.isEmpty()) {
            throw new IllegalArgumentException("--psd is required");
        }
        if (scale < 1.0) {
            throw new IllegalArgumentException("--scale must be >= 1");
        }
        if (ymmRules != null && !Files.isRegularFile(ymmRules)) {
            throw new IllegalArgumentException("--ymm-rules file not found: " + ymmRules);
        }
        return new PipelineConfig(psdInputs, waifu, scale, python, noise, process, steps, extra,
                jobs, waifuJobs, new PipelineConfig.YmmOptions(ymmEnabled, ymmOverwrite, ymmRules, ymmIds));
    }

    private static int positiveInt(String v, String opt) {
        try {
            int n = Integer.parseInt(v);
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException e) {
            // 下で統一して報告
        }
        throw new IllegalArgumentException(opt + " must be an integer >= 1");
    }

    private static String value(String[] a, int i, String opt) {
        if (i >= a.length) {
            throw new IllegalArgumentException(opt + " requires a value");
        }
        return a[i];
    }

    private static void printUsage() {
        System.out.println("""
                Yukkuri Sprite Builder (ysp)

                Usage: java -jar ysp-0.1.0-all.jar --psd <file|folder|*.psd> [--psd ...] [options]

                Required:
                  -p, --psd <path>         入力PSD。複数回指定可。ファイル / フォルダ(直下の*.psd) / ワイルドカード("D:\\work\\*.psd")
                                           出力PSDは各PSDと同じフォルダ、作業ファイルは [name]_part/ に作られる
                      --psd-list <file>    PSDのパスを1行1件で書いたテキストファイル(#でコメント)

                Options:
                  -w, --waifu2x <path>     waifu2x-caffe-cui.exe (またはそのフォルダ)。省略時は前回のパスを使用
                  -s, --scale <n>          拡大率 (既定: 2)
                  -j, --jobs <n>           同時に処理するPSDの数 (既定: 2)
                      --waifu2x-jobs <n>   同時に動かすwaifu2xの数 (既定: 1。GPUが1枚なら1推奨)
                      --python <cmd>       Python実行ファイル (既定: python / py -3 / python3 を自動検出)
                      --noise <0-3>        ノイズ除去レベル (指定時は noise_scale モード)
                      --process <p>        cpu / gpu / cudnn
                      --steps <list>       実行ステップ 例: 1,2,3 (既定) / 3 (再構築のみ)
                      --waifu2x-arg <a>    waifu2xへ渡す追加引数 (繰り返し可)
                      --clear-cache        保存済みのwaifu2xパスを削除

                YukkuriMovieMaker 立ち絵設定 ([出力PSD名]-ymm.json を出力PSDと同じフォルダに生成):
                      --no-ymm             生成しない
                      --ymm-overwrite      既存の設定ファイルを上書きする (既定は上書きしない)
                      --ymm-rules <file>   口/目・開閉の判定キーワードを上書きするJSON
                      --ymm-ids <0|1|none> Layers の n番号の基点 (既定: 0)。none で番号を出力しない
                  -h, --help
                """);
    }
}
