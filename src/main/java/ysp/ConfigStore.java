package ysp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 実行間で保持する設定(waifu2xのパス)。
 * 保存先: Windowsは %APPDATA%\ysp\config.json、それ以外は ~/.config/ysp/config.json
 */
final class ConfigStore {
    private static final String KEY_WAIFU2X = "waifu2x";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigStore() {}

    static Path configFile() {
        String appData = System.getenv("APPDATA");
        Path base = (appData != null && !appData.isBlank())
                ? Path.of(appData, "ysp")
                : Path.of(System.getProperty("user.home"), ".config", "ysp");
        return base.resolve("config.json");
    }

    private static Map<String, String> read() {
        Path f = configFile();
        if (!Files.isRegularFile(f)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            System.out.println("[WARN] could not read config (ignored): " + f + " (" + e.getMessage() + ")");
            return new LinkedHashMap<>();
        }
    }

    private static void write(Map<String, String> map) throws IOException {
        Path f = configFile();
        Files.createDirectories(f.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), map);
    }

    /** キャッシュ済みのwaifu2xパス。無ければ null。 */
    static Path loadWaifu2x() {
        String v = read().get(KEY_WAIFU2X);
        return (v == null || v.isBlank()) ? null : Path.of(v);
    }

    /** 保存に失敗しても処理は止めない。 */
    static void saveWaifu2x(Path exe) {
        try {
            Map<String, String> m = read();
            String v = exe.toString();
            if (v.equals(m.get(KEY_WAIFU2X))) {
                return;
            }
            m.put(KEY_WAIFU2X, v);
            write(m);
            System.out.println("[INFO] waifu2x path cached: " + configFile());
        } catch (IOException e) {
            System.out.println("[WARN] could not save config: " + e.getMessage());
        }
    }

    static void clear() throws IOException {
        Map<String, String> m = read();
        if (m.remove(KEY_WAIFU2X) != null) {
            write(m);
        }
    }
}
