#!/usr/bin/env python3
"""PSD再構築: JSON + 高画質化PNG群 -> PSD

psd-tools には依存せず、PSD(version 1, RGB 8bit)を直接書き出す。
 - 各レイヤーのRGBAは「RGB + 透明度チャンネル(id -1)」として保存する。
   レイヤーマスク(ユーザーマスク)のデータは一切書かないため、出力にマスクは存在しない。
 - グループは section divider (lsct) で表現する。
 - 座標・キャンバスは --scale 倍して配置する。レイヤー画像はリサイズしない。
 - 合成済み画像(プレビュー用)は、通常合成・不透明度・表示状態のみで簡易合成したもの。
 - --ymm-json を指定すると、YukkuriMovieMaker 用の立ち絵設定(口パク/まばたき)も生成する。
"""
import argparse
import json
import re
import struct
import sys
import unicodedata
from pathlib import Path

from PIL import Image

BLEND_KEYS = {
    "NORMAL": b"norm", "PASS_THROUGH": b"pass", "DISSOLVE": b"diss",
    "DARKEN": b"dark", "MULTIPLY": b"mul ", "COLOR_BURN": b"idiv",
    "LINEAR_BURN": b"lbrn", "DARKER_COLOR": b"dkCl",
    "LIGHTEN": b"lite", "SCREEN": b"scrn", "COLOR_DODGE": b"div ",
    "LINEAR_DODGE": b"lddg", "LIGHTER_COLOR": b"lgCl",
    "OVERLAY": b"over", "SOFT_LIGHT": b"sLit", "HARD_LIGHT": b"hLit",
    "VIVID_LIGHT": b"vLit", "LINEAR_LIGHT": b"lLit", "PIN_LIGHT": b"pLit",
    "HARD_MIX": b"hMix", "DIFFERENCE": b"diff", "EXCLUSION": b"smud",
    "SUBTRACT": b"fsub", "DIVIDE": b"fdiv",
    "HUE": b"hue ", "SATURATION": b"sat ", "COLOR": b"colr", "LUMINOSITY": b"lum ",
}

# 透明度チャンネルは id -1。PSDではチャンネル順は任意(idで識別される)。
CHANNEL_ORDER = ("A", "R", "G", "B")
CHANNEL_IDS = {"A": -1, "R": 0, "G": 1, "B": 2}


def log(msg: str) -> None:
    print(msg, flush=True)


# ---------------------------------------------------------------- RLE (PackBits)
_RUN = re.compile(rb"(.)\1{2,}", re.S)


def _pack_lit(b: bytes) -> bytes:
    return b"".join(bytes([len(b[i:i + 128]) - 1]) + b[i:i + 128] for i in range(0, len(b), 128))


def _pack_run(byte: bytes, n: int) -> bytes:
    parts = []
    while n >= 2:
        k = min(n, 128)
        parts.append(bytes([257 - k]) + byte)
        n -= k
    if n == 1:
        parts.append(b"\x00" + byte)
    return b"".join(parts)


def _pack_row(row: bytes) -> bytes:
    n = len(row)
    if n == 0:
        return b""
    first = row[:1]
    if row.count(first) == n:  # 1色の行(透明部分など)は高速パス
        return _pack_run(first, n)
    out, pos = [], 0
    for m in _RUN.finditer(row):
        s, e = m.span()
        if s > pos:
            out.append(_pack_lit(row[pos:s]))
        out.append(_pack_run(row[s:s + 1], e - s))
        pos = e
    if pos < n:
        out.append(_pack_lit(row[pos:]))
    return b"".join(out)


def rle_rows(data: bytes, w: int, h: int):
    rows = [_pack_row(data[i * w:(i + 1) * w]) for i in range(h)]
    return struct.pack(">%dH" % h, *[len(r) for r in rows]), b"".join(rows)


def channel_blob(data: bytes, w: int, h: int) -> bytes:
    """レイヤーのチャンネルデータ: 圧縮方式(1=RLE) + 行ごとのバイト数 + 行データ"""
    counts, body = rle_rows(data, w, h)
    return struct.pack(">H", 1) + counts + body


EMPTY_CHANNEL = struct.pack(">H", 0)  # 圧縮なし・画素なし(グループ用)


# ---------------------------------------------------------------- レコード生成
def _pad4(b: bytes) -> bytes:
    return b + b"\x00" * ((4 - len(b) % 4) % 4)


def _block(key: bytes, data: bytes) -> bytes:
    data = _pad4(data)
    return b"8BIM" + key + struct.pack(">I", len(data)) + data


def _pascal(name: str) -> bytes:
    raw = name.encode("cp932", errors="replace")[:255]  # 正式な名前は luni ブロックに入れる
    return _pad4(bytes([len(raw)]) + raw)


def _luni(name: str) -> bytes:
    u = name.encode("utf-16-be")
    return _block(b"luni", struct.pack(">I", len(name)) + u)


def _lsct(kind: int, blend: bytes) -> bytes:
    # kind: 1=開いたフォルダ, 2=閉じたフォルダ, 3=終端(bounding section divider)
    return _block(b"lsct", struct.pack(">I", kind) + b"8BIM" + blend)


def layer_record(e: dict) -> bytes:
    top, left, bottom, right = e["bbox"]
    chans = e["channels"]  # [(id, blob)]
    b = struct.pack(">iiiiH", top, left, bottom, right, len(chans))
    for cid, blob in chans:
        b += struct.pack(">hI", cid, len(blob))
    flags = 0x08 | (0x00 if e["visible"] else 0x02) | (0x10 if e["kind"] != "layer" else 0x00)
    b += b"8BIM" + e["blend"] + struct.pack(">BBBB", e["opacity"], 0, flags, 0)
    extra = struct.pack(">I", 0)   # レイヤーマスクデータ長 = 0  (マスクなし)
    extra += struct.pack(">I", 0)  # ブレンド範囲データ長 = 0
    extra += _pascal(e["name"])
    if e["kind"] == "layer":
        extra += _luni(e["name"])
    elif e["kind"] == "group":
        extra += _luni(e["name"]) + _lsct(1, e["blend"])
    else:  # divider
        extra += _lsct(3, b"norm")
    return b + struct.pack(">I", len(extra)) + extra


def write_psd(path: Path, cw: int, ch: int, entries: list, merged: Image.Image) -> None:
    """entries は下から上の順。"""
    if cw > 30000 or ch > 30000:
        raise ValueError(f"canvas too large for PSD: {cw}x{ch}")
    records = b"".join(layer_record(e) for e in entries)
    chan_data = b"".join(blob for e in entries for _, blob in e["channels"])
    layer_info = struct.pack(">h", -len(entries)) + records + chan_data  # 負数: 合成画像にアルファあり
    if len(layer_info) % 2:
        layer_info += b"\x00"
    lmi = struct.pack(">I", len(layer_info)) + layer_info + struct.pack(">I", 0)  # 末尾: グローバルマスク情報長 0

    bands = merged.split()  # R, G, B, A
    planes = [channel_rows for channel_rows in (rle_rows(b.tobytes(), cw, ch) for b in bands)]
    image_data = struct.pack(">H", 1)
    image_data += b"".join(c for c, _ in planes) + b"".join(r for _, r in planes)

    with open(path, "wb") as f:
        f.write(b"8BPS" + struct.pack(">H6xHIIHH", 1, 4, ch, cw, 8, 3))
        f.write(struct.pack(">I", 0))  # color mode data
        f.write(struct.pack(">I", 0))  # image resources
        f.write(struct.pack(">I", len(lmi)) + lmi)
        f.write(image_data)


# ---------------------------------------------------------------- 画像
def find_png(img_dir: Path, png_rel: str):
    """png_rel は "Group/Sub/0003_name.png" のような相対パス。
    waifu2xは出力名に "(L2)(x2.000000)" 等を付けることがあるため、
    同じフォルダ内で 完全一致 -> 前方一致 の順に探す。"""
    rel = Path(png_rel)
    d = img_dir / rel.parent
    if not d.is_dir():
        return None
    exact = d / rel.name
    if exact.exists():
        return exact
    stem = rel.stem
    cands = sorted(
        p for p in d.iterdir()
        if p.is_file() and p.suffix.lower() == ".png" and p.stem.startswith(stem)
    )
    return cands[0] if cands else None


def composite_onto(canvas: Image.Image, img: Image.Image, left: int, top: int, factor: float) -> None:
    W, H = canvas.size
    x0, y0 = max(left, 0), max(top, 0)
    x1, y1 = min(left + img.width, W), min(top + img.height, H)
    if x1 <= x0 or y1 <= y0:
        return
    part = img.crop((x0 - left, y0 - top, x1 - left, y1 - top))
    if factor < 1.0:
        a = part.getchannel("A").point(lambda v: int(v * factor + 0.5))
        part = part.copy()
        part.putalpha(a)
    canvas.alpha_composite(part, (x0, y0))


# ---------------------------------------------------------------- YukkuriMovieMaker 設定
DEFAULT_RULES = {
    "mouth_groups": ["口"],             # 口パク用レイヤーを含むグループ名 (!,* 接頭辞と " #N" 接尾辞は無視)
    "eye_groups": ["目"],               # まばたき用レイヤーを含むグループ名
    "mouth_closed": ["閉じ"],           # 口が閉じているレイヤー
    "smile": ["笑"],                    # 笑顔系(閉じ/開けの笑顔ペアを作る)
    "mouth_open": ["開け", "あわわ"],   # 口が開いているレイヤー(笑顔系は smile で判定)
    "eye_open": ["普通目", "通常目"],   # 目が開いているレイヤー
    "eye_half": ["半目", "半開"],       # 半開きレイヤー(あれば 開→半→閉 の3枚構成にする)
    "eye_closed": ["閉じ"],             # 目が閉じているレイヤー
}


def clean_name(name: str) -> str:
    """比較用の名前: NFKC正規化し、先頭の !/* と末尾の " #N" を除く。"""
    n = unicodedata.normalize("NFKC", name).strip()
    n = n.lstrip("!*").strip()
    n = re.sub(r"\s*#\d+$", "", n)
    return n.strip()


def _has(name: str, words) -> bool:
    return any(w in name for w in words)


def build_ymm(children: dict, index_of: dict, rules: dict, id_mode: str) -> dict:
    """children: parent_id -> [rec...] (下->上の順)。index_of: rec id -> 出力PSD内のレコード番号(0始まり)。"""
    byid = {rec["id"]: rec for lst in children.values() for rec in lst}

    # YMMのレイヤーパス: 階層ごとに "\x1c" + 名前 + "\x1d" + (同名の兄弟の中での番号)
    sib_index = {}
    for lst in children.values():
        seen = {}
        for rec in reversed(lst):  # 上->下
            k = seen.get(rec["name"], 0)
            seen[rec["name"]] = k + 1
            sib_index[rec["id"]] = k

    def path_of(rec) -> str:
        segs, cur = [], rec
        while cur is not None:
            segs.append("\x1c" + cur["name"] + "\x1d" + str(sib_index[cur["id"]]))
            cur = byid.get(cur.get("parent_id"))
        return "".join(reversed(segs))

    def node_id(rec) -> str:
        return "n%d" % (index_of[rec["id"]] + int(id_mode))

    def entry(recs) -> dict:
        ids = [] if id_mode == "none" else [node_id(r) for r in recs]
        return {"Layers": ids, "LayerPaths": [path_of(r) for r in recs]}

    def groups_top_down(pid):
        for rec in reversed(children.get(pid, [])):
            if rec["type"] == "group":
                yield rec
                yield from groups_top_down(rec["id"])

    def leaves(group):  # 下->上。画像が見つからずPSDに入らなかったものは除く
        return [r for r in children.get(group["id"], [])
                if r["type"] == "layer" and r["id"] in index_of]

    def pick_closed(cands):  # 名前が最も短い(=番号等の付かない)ものを優先
        return min(cands, key=lambda r: (len(clean_name(r["name"])), index_of[r["id"]])) if cands else None

    mouth, eye = [], []
    for g in groups_top_down(None):
        gname = clean_name(g["name"])
        if gname in rules["mouth_groups"]:
            ls = [(r, clean_name(r["name"])) for r in leaves(g)]
            smile = lambda n: _has(n, rules["smile"])
            closed = [r for r, n in ls if _has(n, rules["mouth_closed"])]
            closed_plain = [r for r, n in ls if _has(n, rules["mouth_closed"]) and not smile(n)]
            closed_smile = [r for r, n in ls if _has(n, rules["mouth_closed"]) and smile(n)]
            c_plain = pick_closed(closed_plain) or pick_closed(closed)
            c_smile = pick_closed(closed_smile) or c_plain
            if c_plain is None:
                log(f"[WARN] ymm: no closed-mouth layer in group '{g['name']}' (skipped)")
                continue
            for r, n in ls:
                if _has(n, rules["mouth_closed"]):
                    continue
                if smile(n):
                    mouth.append(entry([r, c_smile]))
                elif _has(n, rules["mouth_open"]):
                    mouth.append(entry([r, c_plain]))
        elif gname in rules["eye_groups"]:
            ls = [(r, clean_name(r["name"])) for r in leaves(g)]
            opens = [r for r, n in ls if _has(n, rules["eye_open"])]
            halves = [r for r, n in ls if _has(n, rules["eye_half"]) and not _has(n, rules["eye_closed"])]
            closed = pick_closed([r for r, n in ls if _has(n, rules["eye_closed"])])
            if not opens or closed is None:
                log(f"[INFO] ymm: eye group '{g['name']}' skipped (open={len(opens)}, closed={'yes' if closed else 'no'})")
                continue
            half = halves[0] if halves else None
            for o in opens:
                eye.append(entry([o, half, closed] if half else [o, closed]))
    return {"Presets": [], "MouthAnimations": mouth, "MouthVowelAnimations": [], "EyeAnimations": eye}


def write_ymm(path: Path, data: dict) -> None:
    """YMMのサンプルと同じ体裁: BOM付きUTF-8 / CRLF / インデント2 / 末尾改行なし。"""
    text = json.dumps(data, ensure_ascii=False, indent=2)
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        f.write(text.replace("\n", "\r\n"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", required=True, dest="json_path")
    ap.add_argument("--img-dir", required=True)
    ap.add_argument("--scale", required=True, type=float)
    ap.add_argument("--out-psd", required=True)
    ap.add_argument("--ymm-json", default=None, help="YMM立ち絵設定の出力先(省略時は生成しない)")
    ap.add_argument("--ymm-overwrite", action="store_true", help="既存のYMM設定を上書きする")
    ap.add_argument("--ymm-rules", default=None, help="判定ルールを上書きするJSON")
    ap.add_argument("--ymm-ids", default="0", choices=["0", "1", "none"],
                    help="Layers(nID)の番号の基点。none で空にする")
    args = ap.parse_args()

    manifest = json.loads(Path(args.json_path).read_text(encoding="utf-8"))
    img_dir = Path(args.img_dir)
    scale = args.scale
    cw = round(manifest["canvas"]["width"] * scale)
    ch = round(manifest["canvas"]["height"] * scale)

    order = manifest.get("stack_order", "unknown")
    log(f"[INFO] stack_order={order}")
    if order == "unknown":
        log("[WARN] stack order could not be detected; assuming bottom_to_top. "
            "If the layer order looks reversed, tell the developer.")

    children = {}
    for rec in manifest["layers"]:
        children.setdefault(rec.get("parent_id"), []).append(rec)
    if order == "top_to_bottom":
        for lst in children.values():
            lst.reverse()  # PSDのレコード順(下->上)に揃える

    entries = []
    index_of = {}  # JSONのid -> 出力PSD内のレコード番号(0始まり)
    canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    stats = {"layers": 0, "groups": 0, "missing": 0}

    def emit(pid, vis, op):
        for rec in children.get(pid, []):
            blend_name = rec.get("blend_mode", "NORMAL")
            if rec["type"] == "group":
                stats["groups"] += 1
                blend = BLEND_KEYS.get(blend_name, b"pass")
                empty = [(CHANNEL_IDS[c], EMPTY_CHANNEL) for c in CHANNEL_ORDER]
                entries.append(dict(kind="divider", name="</Layer group>", bbox=(0, 0, 0, 0),
                                    channels=list(empty), blend=b"norm", opacity=255, visible=True))
                emit(rec["id"], vis and bool(rec["visible"]), op * int(rec["opacity"]) / 255.0)
                index_of[rec["id"]] = len(entries)
                entries.append(dict(kind="group", name=rec["name"], bbox=(0, 0, 0, 0),
                                    channels=list(empty), blend=blend,
                                    opacity=int(rec["opacity"]), visible=bool(rec["visible"])))
                continue

            png = find_png(img_dir, rec["png"]) if rec.get("png") else None
            if png is None:
                stats["missing"] += 1
                log(f"[WARN] image not found, skipped: {rec['path']} ({rec.get('png')})")
                continue
            with Image.open(png) as im:
                img = im.convert("RGBA")
            left = round(rec["x"] * scale)
            top = round(rec["y"] * scale)
            w, h = img.size
            bands = dict(zip("RGBA", img.split()))
            chans = [(CHANNEL_IDS[c], channel_blob(bands[c].tobytes(), w, h)) for c in CHANNEL_ORDER]
            index_of[rec["id"]] = len(entries)
            entries.append(dict(kind="layer", name=rec["name"], bbox=(top, left, top + h, left + w),
                                channels=chans, blend=BLEND_KEYS.get(blend_name, b"norm"),
                                opacity=int(rec["opacity"]), visible=bool(rec["visible"])))
            stats["layers"] += 1
            if vis and rec["visible"] and blend_name in ("NORMAL", "PASS_THROUGH"):
                composite_onto(canvas, img, left, top, op * int(rec["opacity"]) / 255.0)

    emit(None, True, 1.0)

    out = Path(args.out_psd)
    out.parent.mkdir(parents=True, exist_ok=True)
    write_psd(out, cw, ch, entries, canvas)
    log(f"[OK] saved {out} (layers={stats['layers']}, groups={stats['groups']}, "
        f"missing={stats['missing']}, masks_in_output=0, canvas={cw}x{ch})")


    if args.ymm_json:
        ymm_path = Path(args.ymm_json)
        if ymm_path.exists() and not args.ymm_overwrite:
            log(f"[INFO] ymm settings already exist, not overwritten: {ymm_path}")
        else:
            rules = dict(DEFAULT_RULES)
            if args.ymm_rules:
                rules.update(json.loads(Path(args.ymm_rules).read_text(encoding="utf-8-sig")))
            ymm = build_ymm(children, index_of, rules, args.ymm_ids)
            write_ymm(ymm_path, ymm)
            log(f"[OK] ymm settings: mouth={len(ymm['MouthAnimations'])} eye={len(ymm['EyeAnimations'])} -> {ymm_path}")
            if not ymm["MouthAnimations"] and not ymm["EyeAnimations"]:
                log("[WARN] ymm: no mouth/eye entries were detected (group names must be '口' / '目')")
    return 0


if __name__ == "__main__":
    sys.exit(main())
