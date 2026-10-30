package ysp;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** --psd に指定された ファイル / フォルダ / ワイルドカード を、処理対象のPSD一覧に展開する。 */
final class InputExpander {
    private InputExpander() {}

    static List<Path> expand(List<String> inputs, double scale) throws IOException {
        // 本ツールの出力(<name>_x2.psd 等)を再処理しないよう、フォルダ/ワイルドカード展開では除外する
        String outSuffix = ("_x" + PipelineRunner.formatScale(scale) + ".psd").toLowerCase(Locale.ROOT);
        Set<Path> result = new LinkedHashSet<>();

        for (String raw : inputs) {
            String in = raw.trim();
            if (in.isEmpty()) {
                continue;
            }
            if (in.indexOf('*') >= 0 || in.indexOf('?') >= 0) {
                int cut = Math.max(in.lastIndexOf('\\'), in.lastIndexOf('/'));
                String dirPart = cut > 0 ? in.substring(0, cut) : (cut == 0 ? "/" : ".");
                String pattern = cut >= 0 ? in.substring(cut + 1) : in;
                Path dir = Path.of(dirPart);
                if (!Files.isDirectory(dir)) {
                    throw new IllegalArgumentException("folder not found: " + dir);
                }
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                for (Path p : list(dir)) {
                    if (Files.isRegularFile(p) && matcher.matches(p.getFileName()) && isTarget(p, outSuffix)) {
                        result.add(p.toAbsolutePath().normalize());
                    }
                }
                continue;
            }

            Path p = Path.of(in);
            if (Files.isDirectory(p)) {
                for (Path c : list(p)) {
                    if (Files.isRegularFile(c) && isTarget(c, outSuffix)) {
                        result.add(c.toAbsolutePath().normalize());
                    }
                }
            } else if (Files.isRegularFile(p)) {
                result.add(p.toAbsolutePath().normalize()); // 明示指定されたファイルはそのまま対象
            } else {
                throw new IllegalArgumentException("PSD not found: " + p);
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean isTarget(Path p, String outSuffix) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".psd") && !n.endsWith(outSuffix);
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().collect(Collectors.toList());
        }
    }
}
