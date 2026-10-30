package ysp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** GUIの入力内容。PipelineConfig への変換と入力チェックを担当する(画面部品に依存しないのでテストしやすい)。 */
final class GuiSettings {
    List<String> psds = new ArrayList<>();
    String waifu2x = "";          // 空 = 保存済みのパス
    String python = "";           // 空 = 自動検出
    double scale = 2.0;
    String noise = "なし";        // なし / 0 / 1 / 2 / 3
    String process = "既定";      // 既定 / cpu / gpu / cudnn
    int jobs = 2;
    int waifu2xJobs = 1;
    boolean step1 = true;
    boolean step2 = true;
    boolean step3 = true;
    boolean ymmEnabled = true;
    boolean ymmOverwrite = false;
    String ymmIds = "0";          // 0 / 1 / none
    String ymmRules = "";
    String extraArgs = "";        // waifu2xへの追加引数(空白区切り)

    /** 空欄ならnull、前後の空白と引用符("...")を除いた文字列を返す。 */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.isEmpty() ? null : t;
    }

    PipelineConfig toConfig() {
        if (psds.isEmpty()) {
            throw new IllegalArgumentException("処理するPSDを追加してください");
        }
        Set<Integer> steps = new TreeSet<>();
        if (step1) {
            steps.add(1);
        }
        if (step2) {
            steps.add(2);
        }
        if (step3) {
            steps.add(3);
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("実行するステップを1つ以上選んでください");
        }
        if (scale < 1.0) {
            throw new IllegalArgumentException("拡大率は1以上にしてください");
        }

        String w = clean(waifu2x);
        String py = clean(python);
        String rulesText = clean(ymmRules);
        Path rules = null;
        if (rulesText != null) {
            rules = Path.of(rulesText);
            if (!Files.isRegularFile(rules)) {
                throw new IllegalArgumentException("YMM判定ルールのファイルが見つかりません: " + rules);
            }
        }
        Integer noiseValue = "なし".equals(noise) ? null : Integer.valueOf(noise);
        String proc = "既定".equals(process) ? null : process;

        List<String> extra = new ArrayList<>();
        String extraText = clean(extraArgs);
        if (extraText != null) {
            for (String token : extraText.split("\\s+")) {
                if (!token.isEmpty()) {
                    extra.add(token);
                }
            }
        }

        List<String> inputs = new ArrayList<>();
        for (String p : psds) {
            String c = clean(p);
            if (c != null) {
                inputs.add(c);
            }
        }
        return new PipelineConfig(inputs, w == null ? null : Path.of(w), scale, py, noiseValue, proc,
                steps, extra, Math.max(1, jobs), Math.max(1, waifu2xJobs),
                new PipelineConfig.YmmOptions(ymmEnabled, ymmOverwrite, rules, ymmIds));
    }
}
