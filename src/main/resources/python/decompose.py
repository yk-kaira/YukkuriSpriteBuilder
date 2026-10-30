#!/usr/bin/env python3
"""PSD分解: PSD -> JSON + PNG群 (x1)

- PNGはPSDのグループ階層を維持したサブフォルダに保存する (out-dir/GroupA/Sub/0003_name.png)
- レイヤーマスク(自身+親グループ)は、PNGのアルファへ統合(焼き込み)する

JSONスキーマ(version 1):
{
  "version": 1,
  "source_psd": "name.psd",
  "stack_order": "bottom_to_top" | "top_to_bottom" | "unknown",  # layers配列の兄弟順の向き
  "canvas": {"width": W, "height": H},
  "layers": [   # psd-toolsの走査順。親(グループ)は子より前に出力される
    {
      "id": 1, "parent_id": null,
      "type": "layer" | "group",
      "name": "...", "path": "Group/Layer",
      "png": "Group/0002_Layer.png" | null,     # out-dir からの相対パス(/区切り)
      "opacity": 0-255, "blend_mode": "NORMAL", "visible": true,
      "x": left, "y": top, "width": w, "height": h,
      "rasterized": false, "clipping": false, "mask_baked": false
    }
  ]
}
"""
import argparse
import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageChops
from psd_tools import PSDImage

ILLEGAL = re.compile(r'[\\/:*?"<>|()\x00-\x1f]')


def sanitize(name: str, maxlen: int = 60) -> str:
    s = ILLEGAL.sub("_", name).strip(" .")
    s = s[:maxlen].strip(" .")
    return s or "layer"


def log(msg: str) -> None:
    print(msg, flush=True)


def is_clipping(layer) -> bool:
    # psd-tools新版は `clipping`、旧版は `clipping_layer`
    for attr in ("clipping", "clipping_layer"):
        try:
            return bool(getattr(layer, attr))
        except Exception:
            continue
    return False


def get_mask(owner):
    """有効なピクセルマスクを返す。無い/無効(disabled)ならNone。"""
    try:
        m = owner.mask
    except Exception:
        return None
    if m is None or getattr(m, "disabled", False):
        return None
    return m


def bake_mask(img, left, top, mask):
    """maskをimgのアルファに掛ける。imgはキャンバス座標(left, top)に置かれる画像。"""
    try:
        bg = int(getattr(mask, "background_color", 0))
    except Exception:
        bg = 0
    try:
        m = mask.topil()
    except Exception:
        m = None
    full = Image.new("L", img.size, bg)  # マスク範囲外は background_color
    if m is not None:
        mb = mask.bbox
        full.paste(m.convert("L"), (int(mb[0]) - left, int(mb[1]) - top))
    img = img.convert("RGBA")
    img.putalpha(ImageChops.multiply(img.getchannel("A"), full))
    return img


def detect_stack_order(psd) -> str:
    """psd-toolsの走査順が、PSD内部のレコード順(ファイル上は下->上)と同じかを判定する。
    "bottom_to_top": 走査順の先頭が最背面 / "top_to_bottom": 先頭が最前面 / "unknown": 判定不能"""
    try:
        raw = list(psd._record.layer_and_mask_information.layer_info.layer_records)
        index = {id(r): i for i, r in enumerate(raw)}

        def first_pair(container):
            kids = list(container)
            if len(kids) >= 2:
                a = index.get(id(kids[0]._record))
                b = index.get(id(kids[1]._record))
                if a is not None and b is not None and a != b:
                    return "bottom_to_top" if a < b else "top_to_bottom"
            for k in kids:
                if k.is_group():
                    r = first_pair(k)
                    if r:
                        return r
            return None

        return first_pair(psd) or "unknown"
    except Exception:  # noqa: BLE001
        return "unknown"


def has_vector_mask(layer) -> bool:
    try:
        return bool(layer.has_vector_mask())
    except Exception:
        return False


def render_layer(layer):
    """(PIL.Image | None, rasterized, own_mask_baked)"""
    if layer.kind == "pixel" and not has_vector_mask(layer):
        img = layer.topil()
        if img is None:
            return None, False, False
        mask = get_mask(layer)
        if mask is not None:
            return bake_mask(img, int(layer.left), int(layer.top), mask), False, True
        return img, False, False

    # テキスト/シェイプ/スマートオブジェクト/ベクターマスク付き等はラスタライズ。
    # composite() は自身のマスクも反映する。不透明度は再構築側で適用するため、
    # 合成中だけ100%にして二重適用を避ける。
    saved = None
    try:
        saved = layer.opacity
        layer.opacity = 255
    except Exception:
        saved = None
    try:
        return layer.composite(), True, get_mask(layer) is not None
    finally:
        if saved is not None:
            try:
                layer.opacity = saved
            except Exception:
                pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--psd", required=True)
    ap.add_argument("--json", required=True, dest="json_path")
    ap.add_argument("--out-dir", required=True)
    args = ap.parse_args()

    psd_path = Path(args.psd)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    Path(args.json_path).parent.mkdir(parents=True, exist_ok=True)

    psd = PSDImage.open(psd_path)
    records = []
    counter = [0]
    stats = {"layers": 0, "groups": 0, "rasterized": 0, "skipped": 0, "masks_baked": 0}

    def walk(container, parent_id, parent_path, rel_dir, masked_groups):
        for layer in container:
            counter[0] += 1
            lid = counter[0]
            path = f"{parent_path}/{layer.name}" if parent_path else layer.name
            rec = {
                "id": lid,
                "parent_id": parent_id,
                "type": "group" if layer.is_group() else "layer",
                "name": layer.name,
                "path": path,
                "png": None,
                "opacity": int(layer.opacity),
                "blend_mode": layer.blend_mode.name,
                "visible": bool(layer.visible),
                "x": int(layer.left),
                "y": int(layer.top),
                "width": int(layer.width),
                "height": int(layer.height),
                "rasterized": False,
                "clipping": is_clipping(layer),
                "mask_baked": False,
            }
            if layer.is_group():
                stats["groups"] += 1
                child_masked = masked_groups
                if get_mask(layer) is not None:
                    child_masked = masked_groups + [layer]
                    log(f"[INFO] group mask will be baked into children: {path}")
                records.append(rec)
                walk(layer, lid, path, rel_dir + [sanitize(layer.name, 40)], child_masked)
                continue

            try:
                img, rasterized, baked = render_layer(layer)
                if img is not None and img.width > 0 and img.height > 0:
                    for g in masked_groups:  # 親グループのマスクも統合
                        img = bake_mask(img, int(layer.left), int(layer.top), get_mask(g))
                        baked = True
            except Exception as e:  # noqa: BLE001
                img, rasterized, baked = None, False, False
                log(f"[WARN] skip (render failed): {path} ({e})")
            if img is None or img.width == 0 or img.height == 0:
                stats["skipped"] += 1
                log(f"[WARN] skip (no pixels / adjustment etc.): {path} [{layer.kind}]")
                continue

            png_name = f"{lid:04d}_{sanitize(layer.name)}.png"
            target_dir = out_dir.joinpath(*rel_dir)
            target_dir.mkdir(parents=True, exist_ok=True)
            img.convert("RGBA").save(target_dir / png_name)
            rec["png"] = "/".join(rel_dir + [png_name])
            rec["rasterized"] = rasterized
            rec["mask_baked"] = baked
            rec["width"], rec["height"] = img.width, img.height
            stats["layers"] += 1
            if rasterized:
                stats["rasterized"] += 1
            if baked:
                stats["masks_baked"] += 1
                log(f"[INFO] mask baked into alpha: {path}")
            if rec["clipping"]:
                log(f"[WARN] clipping flag is recorded but not restored in v1: {path}")
            records.append(rec)

    walk(psd, None, "", [], [])

    stack_order = detect_stack_order(psd)
    log(f"[INFO] stack_order={stack_order}")
    manifest = {
        "version": 1,
        "source_psd": psd_path.name,
        "stack_order": stack_order,
        "canvas": {"width": int(psd.width), "height": int(psd.height)},
        "layers": records,
    }
    Path(args.json_path).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    log(
        f"[OK] layers={stats['layers']} groups={stats['groups']} "
        f"rasterized={stats['rasterized']} masks_baked={stats['masks_baked']} "
        f"skipped={stats['skipped']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
