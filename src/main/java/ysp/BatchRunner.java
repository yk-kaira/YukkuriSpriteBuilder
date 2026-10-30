package ysp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** 複数PSDを同時並行で処理する。1つが失敗しても残りは処理を続け、最後に結果をまとめて表示する。 */
public final class BatchRunner {
    private final PipelineConfig cfg;

    public BatchRunner(PipelineConfig cfg) {
        this.cfg = cfg;
    }

    /** 実行中の処理を中止する(外部プロセスを停止。待機中のPSDも開始されず失敗扱いになる)。 */
    public static void cancel() {
        ProcessRunner.cancelAll();
    }

    private record Result(Path psd, boolean ok, String message, long millis) {}

    /** @return 失敗が1件でもあれば1、全て成功なら0 */
    public int run() throws IOException, InterruptedException {
        ProcessRunner.resetCancel();
        List<Path> psds = InputExpander.expand(cfg.psdInputs(), cfg.scale());
        if (psds.isEmpty()) {
            throw new IllegalArgumentException("処理対象のPSDが見つかりません");
        }
        System.out.println("Targets (" + psds.size() + "):");
        for (Path p : psds) {
            System.out.println("  " + p);
        }

        // 外部ツールの解決は全PSD共通で1回だけ行う
        boolean needPython = cfg.steps().contains(1) || cfg.steps().contains(3);
        boolean needWaifu2x = cfg.steps().contains(2);
        final List<String> python = needPython ? ToolResolver.resolvePython(cfg.python()) : null;
        final Path waifu2x = needWaifu2x ? ToolResolver.resolveWaifu2x(cfg.waifu2x()) : null;

        int jobs = Math.max(1, Math.min(cfg.jobs(), psds.size()));
        final Semaphore gate = new Semaphore(Math.max(1, cfg.waifu2xJobs()), true);
        System.out.println("Parallel jobs: " + jobs + " (waifu2x: " + Math.max(1, cfg.waifu2xJobs()) + ")");

        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<Result>> futures = new ArrayList<>();
        for (Path psd : psds) {
            futures.add(pool.submit(() -> runOne(psd, python, waifu2x, gate)));
        }
        pool.shutdown();

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                results.add(new Result(psds.get(i), false, String.valueOf(e.getCause()), 0L));
            }
        }
        return printSummary(results);
    }

    private Result runOne(Path psd, List<String> python, Path waifu2x, Semaphore gate) {
        long t0 = System.currentTimeMillis();
        try {
            new PipelineRunner(cfg, psd, python, waifu2x, gate).run();
            return new Result(psd, true, "OK", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("[" + psd.getFileName() + "] [ERROR] " + msg);
            return new Result(psd, false, msg, System.currentTimeMillis() - t0);
        }
    }

    private int printSummary(List<Result> results) {
        int failed = 0;
        for (Result r : results) {
            if (!r.ok()) {
                failed++;
            }
        }
        System.out.println();
        System.out.println("=== Summary: " + results.size() + " file(s), "
                + (results.size() - failed) + " OK, " + failed + " FAILED ===");
        for (Result r : results) {
            String sec = String.format("%.1fs", r.millis() / 1000.0);
            if (r.ok()) {
                System.out.println("  OK      " + r.psd().getFileName() + " (" + sec + ")");
            } else {
                System.out.println("  FAILED  " + r.psd().getFileName() + ": " + r.message());
            }
        }
        return failed == 0 ? 0 : 1;
    }
}
