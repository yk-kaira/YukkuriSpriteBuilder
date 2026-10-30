package ysp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 外部プロセスを起動し、出力を標準出力へ流す。GUIからの中止(cancelAll)にも対応する。 */
final class ProcessRunner {
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();
    private static volatile boolean cancelled = false;

    private ProcessRunner() {}

    /** 新しい実行を始める前に呼ぶ。 */
    static void resetCancel() {
        cancelled = false;
    }

    /** 実行中のプロセスを停止し、以降の起動を拒否する(resetCancelまで)。 */
    static void cancelAll() {
        cancelled = true;
        for (Process p : RUNNING) {
            kill(p);
        }
    }

    private static void kill(Process p) {
        // py.exe 等のランチャー経由の子プロセスも止める
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    static void run(String tag, List<String> cmd, Path workDir, Charset charset)
            throws IOException, InterruptedException {
        if (cancelled) {
            throw new IOException("cancelled");
        }
        System.out.println("[" + tag + "] $ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start '" + cmd.get(0) + "': " + e.getMessage(), e);
        }
        RUNNING.add(p);
        try {
            if (cancelled) { // 起動の直前に中止された場合
                kill(p);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), charset))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        System.out.println("[" + tag + "] " + line);
                    }
                }
            }
            int code = p.waitFor();
            if (cancelled) {
                throw new IOException("cancelled");
            }
            if (code != 0) {
                throw new IOException(tag + " exited with code " + code);
            }
        } finally {
            RUNNING.remove(p);
        }
    }

    /** 起動できるか確認するための静かな実行。起動自体に失敗した場合は null を返す。 */
    static Integer probe(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
