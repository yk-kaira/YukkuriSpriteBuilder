package ysp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 外部ツール(Python / waifu2x)の場所を解決する。複数PSDの処理でも1回だけ実行する。 */
final class ToolResolver {
    private ToolResolver() {}

    /**
     * Pythonを解決する。explicit 指定があればそれのみ、無ければ
     * python / py -3 / python3 を順に試し、psd-tools と Pillow がimportできるものを採用する。
     */
    static List<String> resolvePython(String explicit) {
        List<List<String>> candidates = new ArrayList<>();
        if (explicit != null) {
            candidates.add(List.of(explicit));
        } else {
            candidates.add(List.of("python"));
            candidates.add(List.of("py", "-3"));
            candidates.add(List.of("python3"));
        }
        List<String> notes = new ArrayList<>();
        for (List<String> cand : candidates) {
            List<String> probe = new ArrayList<>(cand);
            probe.addAll(List.of("-c", "import psd_tools, PIL"));
            Integer code = ProcessRunner.probe(probe);
            if (code == null) {
                notes.add("  - '" + String.join(" ", cand) + "': 起動できません");
            } else if (code != 0) {
                notes.add("  - '" + String.join(" ", cand) + "': 起動しましたが psd-tools / Pillow がありません"
                        + "（pip install -r requirements.txt）");
            } else {
                System.out.println("[INFO] python: " + String.join(" ", cand));
                return cand;
            }
        }
        throw new IllegalStateException("使用できるPythonが見つかりません。\n" + String.join("\n", notes)
                + "\n--python \"C:\\path\\to\\python.exe\" でフルパスを指定してください。");
    }

    /** --waifu2x 指定があればそれを使い保存する。無ければ保存済みのパスを使う。 */
    static Path resolveWaifu2x(Path given) throws IOException {
        Path cached = ConfigStore.loadWaifu2x();
        Path chosen = given != null ? given : cached;
        if (chosen == null) {
            throw new IllegalArgumentException(
                    "waifu2x-caffe-cui.exe のパスが未設定です。初回は --waifu2x で指定してください"
                            + "（指定したパスは保存され、次回以降は省略できます）");
        }

        Path exe = chosen.toAbsolutePath().normalize();
        if (Files.isDirectory(exe)) {
            exe = exe.resolve("waifu2x-caffe-cui.exe");
        }
        if (exe.getFileName().toString().equalsIgnoreCase("waifu2x-caffe.exe")) { // GUI版 -> CLI版
            Path cui = exe.resolveSibling("waifu2x-caffe-cui.exe");
            if (Files.isRegularFile(cui)) {
                System.out.println("[INFO] using CLI version: " + cui);
                exe = cui;
            } else {
                System.out.println("[WARN] waifu2x-caffe.exe is the GUI version; "
                        + "waifu2x-caffe-cui.exe was not found next to it.");
            }
        }
        if (!Files.isRegularFile(exe)) {
            if (given == null) {
                throw new IllegalArgumentException("保存済みのwaifu2xパスが無効です: " + exe
                        + "\n--waifu2x で指定し直してください");
            }
            throw new IOException("waifu2x executable not found: " + exe);
        }

        if (given == null) {
            System.out.println("[INFO] waifu2x (saved path): " + exe);
        } else if (!exe.equals(cached)) {
            ConfigStore.saveWaifu2x(exe); // 保存失敗でも処理は続行
        }
        return exe;
    }
}
