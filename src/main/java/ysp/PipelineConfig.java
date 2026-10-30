package ysp;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 実行時に指定される設定。 */
public record PipelineConfig(
        List<String> psdInputs,      // ファイル / フォルダ / ワイルドカード(*.psd)
        Path waifu2x,                // null = 保存済みのパスを使う
        double scale,
        String python,               // null = 自動検出
        Integer noise,               // null = ノイズ除去なし
        String process,              // null = waifu2xの既定 (cpu / gpu / cudnn)
        Set<Integer> steps,          // 1=分解, 2=高画質化, 3=再構築(+YMM設定)
        List<String> extraWaifu2xArgs,
        int jobs,                    // 同時に処理するPSDの数
        int waifu2xJobs,             // 同時に動かすwaifu2xの数
        YmmOptions ymm) {

    /** YukkuriMovieMaker 立ち絵設定(口パク/まばたき)の生成設定。 */
    public record YmmOptions(boolean enabled, boolean overwrite, Path rules, String ids) {}
}
