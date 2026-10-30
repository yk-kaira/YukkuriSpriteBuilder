package ysp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** decompose.py が出力するJSONのモデル。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(
        int version,
        @JsonProperty("source_psd") String sourcePsd,
        Canvas canvas,
        List<LayerEntry> layers) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Canvas(int width, int height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LayerEntry(
            int id,
            @JsonProperty("parent_id") Integer parentId,
            String type,
            String name,
            String path,
            String png,
            int opacity,
            @JsonProperty("blend_mode") String blendMode,
            boolean visible,
            int x,
            int y,
            int width,
            int height,
            boolean rasterized,
            boolean clipping) {

        public boolean isGroup() {
            return "group".equals(type);
        }
    }
}
