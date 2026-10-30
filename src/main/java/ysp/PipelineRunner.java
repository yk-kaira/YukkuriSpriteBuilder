package ysp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/** 1つのPSDについて 分解 -> 高画質化 -> 再構築(+YMM設定) を行う。複数PSDの並行処理時は1PSDにつき1インスタンス。 */
public final class PipelineRunner {
    private final PipelineConfig cfg;
    private final Path psd;
    private final List<String> pythonBase; // ステップ1/3で使用
    private final Path waifu2xExe;         // ステップ2で使用
    private final Semaphore waifu2xGate;   // waifu2xの同時実行数の制限
    private final ObjectMapper mapper = new ObjectMapper();
    private String tag;

    public PipelineRunner(PipelineConfig cfg, Path psd, List<String> pythonBase,
                          Path waifu2xExe, Semaphore waifu2xGate) {
        this.cfg = cfg;
        this.psd = psd.toAbsolutePath().normalize();
        this.pythonBase = pythonBase;
        this.waifu2xExe = waifu2xExe;
        this.waifu2xGate = waifu2xGate;
        this.tag = this.psd.getFileName().toString();
    }

    private void say(String msg) {
        System.out.println("[" + tag + "] " + msg);
    }

    public void run() throws IOException, InterruptedException {
        if (!Files.isRegularFile(psd)) {
            throw new IllegalArgumentException("PSD not found: " + psd);
        }
        Path dir = psd.getParent();
        String fileName = psd.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        tag = base;
        String label = formatScale(cfg.scale());

        Path partDir = dir.resolve(base + "_part");
        Path json = partDir.resolve(base + ".json");
        Path dirSrc = partDir.resolve("x1");
        // 拡大率1(ノイズ除去のみ)のとき x1 と衝突しないよう別名にする
        Path dirUp = partDir.resolve(label.equals("1") ? "x1_out" : "x" + label);
        String outBase = base + "_x" + label;
        Path outPsd = dir.resolve(outBase + ".psd");           // 参照PSDと同じフォルダ
        Path ymmJson = dir.resolve(outBase + "-ymm.json");     // YMM立ち絵設定(立ち絵PSDと同じ階層)

        say("start: " + psd);
        say("work dir: " + partDir);

        Path scriptDir = Files.createTempDirectory("ysp-");
        try {
            if (cfg.steps().contains(1)) {
                step1Decompose(scriptDir, psd, partDir, json, dirSrc);
            }
            if (cfg.steps().contains(2)) {
                if (!waifu2xGate.tryAcquire()) {
                    say("waiting for a free waifu2x slot...");
                    waifu2xGate.acquire();
                }
                try {
                    step2Upscale(json, dirSrc, dirUp);
                } finally {
                    waifu2xGate.release();
                }
            }
            if (cfg.steps().contains(3)) {
                step3Rebuild(scriptDir, json, dirUp, outPsd, ymmJson);
            }
        } finally {
            deleteRecursively(scriptDir);
        }
        say("done: " + outPsd);
    }

    // ---- Step 1 ----------------------------------------------------------
    private void step1Decompose(Path scriptDir, Path psdFile, Path partDir, Path json, Path dirSrc)
            throws IOException, InterruptedException {
        say("=== Step 1: PSD decompose ===");
        Files.createDirectories(partDir);
        deleteRecursively(dirSrc); // 古いPNGの混入を防ぐ
        Files.createDirectories(dirSrc);
        Path script = extractScript(scriptDir, "decompose.py");
        List<String> cmd = pythonCommand(List.of("-X", "utf8", script.toString(),
                "--psd", psdFile.toString(),
                "--json", json.toString(),
                "--out-dir", dirSrc.toString()));
        ProcessRunner.run(tag + "/decompose", cmd, null, StandardCharsets.UTF_8);
        if (!Files.isRegularFile(json)) {
            throw new IOException("JSON was not created: " + json);
        }
    }

    // ---- Step 2 ----------------------------------------------------------
    private void step2Upscale(Path json, Path dirSrc, Path dirUp)
            throws IOException, InterruptedException {
        say("=== Step 2: waifu2x upscale ===");
        Manifest manifest = readManifest(json);
        List<String> missingSrc = findMissing(manifest, dirSrc);
        if (!missingSrc.isEmpty()) {
            throw new IOException("source images missing (" + missingSrc.size() + "): " + missingSrc);
        }
        deleteRecursively(dirUp);
        Files.createDirectories(dirUp);

        // PSDの階層(サブフォルダ)ごとにwaifu2xを実行し、出力側にも同じ階層を作る
        Set<String> relDirs = new LinkedHashSet<>();
        for (Manifest.LayerEntry e : manifest.layers()) {
            if (e.png() != null) {
                Path parent = Path.of(e.png()).getParent();
                relDirs.add(parent == null ? "" : parent.toString());
            }
        }
        int i = 0;
        for (String rel : relDirs) {
            Path in = rel.isEmpty() ? dirSrc : dirSrc.resolve(rel);
            Path out = rel.isEmpty() ? dirUp : dirUp.resolve(rel);
            Files.createDirectories(out);
            i++;
            say("waifu2x folder " + i + "/" + relDirs.size() + ": " + (rel.isEmpty() ? "(root)" : rel));
            // modelsフォルダ等を相対参照するため、exeのあるフォルダを作業ディレクトリにする
            ProcessRunner.run(tag + "/waifu2x", waifu2xCommand(in, out), waifu2xExe.getParent(),
                    Charset.defaultCharset());
        }

        List<String> missingUp = findMissing(manifest, dirUp);
        if (!missingUp.isEmpty()) {
            throw new IOException("upscaled images missing (" + missingUp.size() + "): " + missingUp);
        }
    }

    private List<String> waifu2xCommand(Path in, Path out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(waifu2xExe.toString());
        cmd.add("-i");
        cmd.add(in.toString());
        cmd.add("-o");
        cmd.add(out.toString());
        cmd.add("-m");
        cmd.add(cfg.noise() == null ? "scale" : "noise_scale");
        cmd.add("-s");
        cmd.add(formatScale(cfg.scale()));
        if (cfg.noise() != null) {
            cmd.add("-n");
            cmd.add(String.valueOf(cfg.noise()));
        }
        if (cfg.process() != null) {
            cmd.add("-p");
            cmd.add(cfg.process());
        }
        cmd.addAll(cfg.extraWaifu2xArgs());
        return cmd;
    }

    // ---- Step 3 ----------------------------------------------------------
    private void step3Rebuild(Path scriptDir, Path json, Path dirUp, Path outPsd, Path ymmJson)
            throws IOException, InterruptedException {
        say("=== Step 3: PSD rebuild ===");
        Manifest manifest = readManifest(json);
        List<String> missing = findMissing(manifest, dirUp);
        if (!missing.isEmpty()) {
            throw new IOException("images missing in " + dirUp + " (" + missing.size() + "): " + missing);
        }
        Path script = extractScript(scriptDir, "rebuild.py");
        List<String> args = new ArrayList<>(List.of("-X", "utf8", script.toString(),
                "--json", json.toString(),
                "--img-dir", dirUp.toString(),
                "--scale", formatScale(cfg.scale()),
                "--out-psd", outPsd.toString()));
        PipelineConfig.YmmOptions ymm = cfg.ymm();
        if (ymm.enabled()) {
            args.add("--ymm-json");
            args.add(ymmJson.toString());
            args.add("--ymm-ids");
            args.add(ymm.ids());
            if (ymm.overwrite()) {
                args.add("--ymm-overwrite");
            }
            if (ymm.rules() != null) {
                args.add("--ymm-rules");
                args.add(ymm.rules().toAbsolutePath().normalize().toString());
            }
        }
        ProcessRunner.run(tag + "/rebuild", pythonCommand(args), null, StandardCharsets.UTF_8);
        if (!Files.isRegularFile(outPsd)) {
            throw new IOException("Output PSD was not created: " + outPsd);
        }
    }

    // ---- helpers ---------------------------------------------------------
    private List<String> pythonCommand(List<String> args) {
        List<String> cmd = new ArrayList<>(pythonBase);
        cmd.addAll(args);
        return cmd;
    }

    private Manifest readManifest(Path json) throws IOException {
        if (!Files.isRegularFile(json)) {
            throw new IOException("JSON not found (run step 1 first): " + json);
        }
        return mapper.readValue(json.toFile(), Manifest.class);
    }

    /**
     * JSONが参照するPNG(baseDirからの相対パス)が揃っているか。
     * waifu2xの接尾辞付き出力名も許容する(同一フォルダ内の前方一致)。
     */
    private List<String> findMissing(Manifest m, Path baseDir) {
        Map<Path, List<String>> stemCache = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (Manifest.LayerEntry e : m.layers()) {
            if (e.png() == null) {
                continue;
            }
            Path rel = Path.of(e.png());
            Path parent = rel.getParent();
            Path d = parent == null ? baseDir : baseDir.resolve(parent);
            String fn = rel.getFileName().toString();
            String stem = fn.substring(0, fn.length() - 4);
            List<String> stems = stemCache.computeIfAbsent(d, PipelineRunner::listPngStems);
            if (stems.stream().noneMatch(s -> s.startsWith(stem))) {
                missing.add(e.png());
            }
        }
        return missing;
    }

    private static List<String> listPngStems(Path dir) {
        List<String> stems = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase().endsWith(".png"))
                        .map(n -> n.substring(0, n.length() - 4))
                        .forEach(stems::add);
            } catch (IOException e) {
                // 読めない場合は空扱い(= 欠落として報告される)
            }
        }
        return stems;
    }

    private Path extractScript(Path scriptDir, String name) throws IOException {
        try (InputStream in = PipelineRunner.class.getResourceAsStream("/python/" + name)) {
            if (in == null) {
                throw new IOException("Bundled script not found: " + name);
            }
            Path dst = scriptDir.resolve(name);
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            return dst;
        }
    }

    static String formatScale(double scale) {
        if (scale == Math.rint(scale)) {
            return String.valueOf((long) scale);
        }
        return BigDecimal.valueOf(scale).stripTrailingZeros().toPlainString();
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(x);
            }
        }
    }
}
