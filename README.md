# Yukkuri Sprite Builder (ysp)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![License](https://img.shields.io/badge/license-MIT-green)

ゆっくり立ち絵PSDを「高画質化」して、YMM4（YukkuriMovieMaker4）用の設定まで自動で作るGUIツールです。

* **作者:** KairaYK / ゆっくり廻螺
* **ライセンス:** [MIT License](#9-ライセンスmitと第三者ソフトウェア)

---

## 📑 目次

1. [このツールでできること](#1-このツールでできること)
2. [必要なもの](#2-必要なもの)
3. [初期設定（初回のみ）](#3-初期設定初回のみ)
4. [GUIの使い方](#4-guiの使い方)
5. [変換の仕様と注意点](#5-変換の仕様と注意点)
6. [よくある質問・トラブルシューティング](#6-よくある質問トラブルシューティング)
7. [関連ツール・謝辞](#7-関連ツール謝辞)
8. [免責事項](#8-免責事項)
9. [ライセンス（MIT）と第三者ソフトウェア](#9-ライセンスmitと第三者ソフトウェア)
10. [更新履歴](#10-更新履歴)

---

## 1. このツールでできること

Photoshop形式（`.psd`）の立ち絵を、レイヤー構造・名前・不透明度・合成モード・表示/非表示を保ったまま、まとめて高画質化します。

- **(1) PSDを分解する** — レイヤーごとのPNGと、レイヤー情報（JSON）に分解
- **(2) 高画質化する** — waifu2x-caffe で各PNGを拡大・ノイズ除去
- **(3) PSDを再構築する** — 元と同じ構造の高画質PSD（`○○_x2.psd`）を出力
- **(4) YMM4用の設定を作る** — 口パク・まばたきの設定ファイル（`○○_x2-ymm.json`）を出力

複数のPSDをまとめて指定して、同時に処理することもできます。元のPSDファイルは書き換えません。

---

## 2. 必要なもの

- **OS:** Windows (64bit)
- **Python:** Python 3 ([3-1を参照](#3-1-python-3-をインストールする))
- **Pythonライブラリ:** `psd-tools`, `Pillow` ([3-2を参照](#3-2-psd-tools-と-pillow-をインストールする))
- **waifu2x-caffe:** CLI版 `waifu2x-caffe-cui.exe` ([3-3を参照](#3-3-waifu2x-caffe-を用意する))
- **素材:** 立ち絵PSD

> **Note**
> - `ysp.exe` には Java の実行環境が含まれています。Javaを別途インストールする必要はありません。
> - **動作確認環境:** Windows / Python 3.14 / waifu2x-caffe（cuDNN処理）

---

## 3. 初期設定（初回のみ）

### 3-1. Python 3 をインストールする
1. [Python公式ダウンロードページ](https://www.python.org/downloads/) から Python 3 のインストーラーをダウンロードして実行します（おすすめは 3.14 です）。
2. インストーラーの最初の画面で、必ず **`Add python.exe to PATH`** （または「PATHに追加」）にチェックを入れてください。
   > ※ 新しいPythonのインストーラー (Python install manager) を使う場合は、画面の案内に従ってインストールしてください。
3. インストール後、コマンドプロンプトまたはPowerShellを開き、以下を実行してバージョンが表示されれば成功です。
   ```bash
   python --version
   # または
   py -3 --version
   ```
   > ※ `python` と入力して Microsoft Store が開く場合は、「アプリ実行エイリアス」の影響です。その場合は `py -3` を使ってください（yspも `py -3` を自動検索します）。

### 3-2. psd-tools と Pillow をインストールする
同梱の `quick_install_python_library.bat` を実行するか、ターミナルで以下を順に実行してください：

```bash
py -m pip install --upgrade pip
py -m pip install psd-tools Pillow
```

**動作確認:**
```bash
python -c "import psd_tools, PIL; print('OK')"
```
> ※ `psd-tools` は PSD の読み込み、`Pillow` は画像処理に使用します。
> ※ 権限エラーが出る場合は、末尾に `--user` を付けて実行してください。

### 3-3. waifu2x-caffe を用意する
1. [waifu2x-caffe Releases](https://github.com/lltcggie/waifu2x-caffe) から入手し、任意の場所に解凍します。
2. 展開したフォルダにある **`waifu2x-caffe-cui.exe`**（コンソール版）のパスを確認します。
   > ※ GUI版の `waifu2x-caffe.exe` だけでは動作しません。
3. NVIDIA製GPUを搭載している場合は、処理方式に **`cudnn`** を選ぶと高速に処理できます。利用できない環境では `gpu` または `cpu` を選択してください。

### 3-4. ysp を起動して「環境チェック」を行う
1. `ysp.exe` をダブルクリックして起動します。
   > ※ Windows SmartScreen の警告が出た場合は、「詳細情報」→「実行」を選択します。
2. **「ツール」** 欄の **「waifu2x-caffe-cui」** に、3-3 で用意した `waifu2x-caffe-cui.exe` を指定します。
3. Python 欄は通常空欄（自動検出）のままで問題ありません。見つからない場合のみ `python.exe` のパスを指定します。
4. 下部の **「環境チェック」** ボタンを押します。ログに以下のように表示されれば準備完了です。
   ```text
   [OK] Python と psd-tools / Pillow を確認しました
   [OK] waifu2x: C:\...\waifu2x-caffe-cui.exe
   ```
   > ※ waifu2xのパスは自動保存されるため、次回以降の入力は不要です。

---

## 4. GUIの使い方

### 4-1. 基本の流れ
1. **「処理するPSD」** エリアへ、PSDファイル（またはフォルダ）をドラッグ＆ドロップします（`ファイル追加...` / `フォルダ追加...` からも追加可能）。
2. 必要に応じて **「処理設定」** を調整します（初期設定のままでも動作します）。
3. **「実行」** ボタンを押すと処理が開始され、ログ欄に進捗が表示されます。
4. 「完了」と表示されたら終了です。元PSDと同じフォルダに結果が出力されます。

### 4-2. 各項目の説明

| 設定カテゴリ | 項目名 | 説明 |
| :--- | :--- | :--- |
| **処理するPSD** | - | フォルダを追加した場合は直下の `*.psd` を一括処理します（出力済みの `*_x2.psd` は自動除外されます）。 |
| **ツール** | `waifu2x-caffe-cui` | `waifu2x-caffe-cui.exe` または格納フォルダを指定。 |
| | `Python (空欄=自動)` | 通常は空欄でOK。見つからない場合のみ `python.exe` を指定。 |
| **処理設定** | `拡大率` | 既定: **2**（2倍）。1 に設定するとノイズ除去のみ行います。 |
| | `ノイズ除去` | **なし** / **0〜3**（数値が大きいほど強く適用）。 |
| | `処理方式` | **既定** / **cpu** / **gpu** / **cudnn** から選択。 |
| | `同時に処理するPSD数` | 既定: **2**。メモリ不足時は 1 に設定してください。 |
| | `同時に動かすwaifu2x数` | 既定: **1**。GPUが1枚の場合は 1 を推奨します。 |
| | `実行するステップ` | 通常は3つともONにします。<br>※「3 PSD再構築」のみに設定すると、手修正した画像やJSONからPSDの再作成が可能です。 |
| | `waifu2x追加引数` | 上級者向けオプション（通常は空欄）。 |
| **YMM4 立ち絵設定** | `生成する` | ONで `○○_x2-ymm.json` を出力。 |
| | `既存の設定を上書きする` | 既存ファイルがある場合の保護設定。再作成したい場合のみON。 |
| | `n番号` | **0** / **1** / **none**。YMM4側で切り替えがずれる場合に変更をお試しください。 |
| | `判定ルール(任意)` | レイヤー名の判定キーワードをカスタマイズするためのJSON。 |

### 4-3. 出力されるファイル構成
元のPSDが `D:\work\chara.psd` （拡大率2）の場合：

```text
D:\work\
├── chara.psd                # 元のPSD（変更されません）
├── chara_x2.psd             # 高画質化されたPSD
├── chara_x2-ymm.json        # YMM4用立ち絵設定ファイル
└── chara_part\              # 作業用キャッシュフォルダ
    ├── chara.json           # レイヤー構造情報
    ├── x1\                  # 分解された元画像
    └── x2\                  # 高画質化された画像
```
> **Warning**
> 作業フォルダ内の `x1`, `x2` フォルダは実行のたびに上書き・再生成されます。手修正した画像を保持したい場合は別の場所に退避させてください。（処理完了後、`chara_part` フォルダは削除しても問題ありません）

### 4-4. YMM4で使う
1. 出力された `○○_x2.psd` と `○○_x2-ymm.json` を同じフォルダに配置します。
2. YMM4側で `○○_x2.psd` を立ち絵として読み込むと、口パク・まばたき設定が自動反映されます。
3. **自動判定ルール:**
   - **口パク:** レイヤー/フォルダ名「口」配下
     - `開け` `あわわ` を含む $\rightarrow$ 開いた口
     - `閉じ` を含む $\rightarrow$ 閉じた口
     - `笑` を含む $\rightarrow$ 笑顔用
   - **まばたき:** レイヤー/フォルダ名「目」配下
     - `普通目` `通常目` $\rightarrow$ 開いた目
     - `半目` `半開` $\rightarrow$ 半目
     - `閉じ` $\rightarrow$ 閉じた目
4. ルールと異なる規則で作られた立ち絵の場合は、カスタムJSONルールを「判定ルール」に指定して読み込ませることができます。
   ```json
   {
     "mouth_groups": ["くち"],
     "eye_groups": ["め"],
     "mouth_closed": ["閉じ"],
     "smile": ["笑"],
     "mouth_open": ["開け"],
     "eye_open": ["普通目"],
     "eye_half": ["半目"],
     "eye_closed": ["閉じ"]
   }
   ```

---

## 5. 変換の仕様と注意点

- **レイヤーマスク:** 画像の透明度に統合して出力されます（出力PSDにマスク自体は残りません）。
- **特殊レイヤー:** テキスト、シェイプ、スマートオブジェクトなどはラスタライズ（画像化）して出力されます。調整レイヤー等の非画像レイヤーはスキップされます。
- **再再現性:** クリッピング、レイヤー効果、ロック状態などは再現されない場合があります。
- **プレビュー画像:** 出力PSDの合成プレビューは簡易的なものです。Photoshop等で開き直して保存すると正常に更新されます（YMM4等の利用には影響ありません）。
- **サイズ制限:** PSD規格の制限（1辺 30,000 px）を超える大きな画像はエラーとなります。
- **メモリ消費:** 大量のレイヤーや高精細な画像を扱う際はメモリを大きく消費します。動作が不安定な場合は「同時に処理するPSD数」を `1` に設定してください。

---

## 6. よくある質問・トラブルシューティング

<details>
<summary>Q. 「使用できるPythonが見つかりません」と表示される</summary>

Pythonが正しくインストールされているか確認し、「Add python.exe to PATH」を有効にして再インストールするか、`Python (空欄=自動)` 欄に `python.exe` のフルパスを直接指定してください。
</details>

<details>
<summary>Q. 「psd-tools / Pillow がありません」と表示される</summary>

指定しているPython環境に対してライブラリをインストールしてください。
```bash
"C:\Path\To\python.exe" -m pip install psd-tools Pillow
```
</details>

<details>
<summary>Q. waifu2xの実行でエラーになる</summary>

処理方式を `cudnn` $\rightarrow$ `gpu` $\rightarrow$ `cpu` の順に変更してお試しください。
</details>

<details>
<summary>Q. 口パク・まばたきがYMM4で正しく動かない</summary>

レイヤー命名規則を確認するか、設定の「n番号」を `1` または `none` に変更し、「既存の設定を上書きする」を有効にして再実行してください。
</details>

---

## 7. 関連ツール・謝辞

本ツールは以下の優れたオープンソースおよびサードパーティ製ソフトウェアを活用・連携しています。各権利者の皆様に感謝いたします。

- **[YukkuriMovieMaker4](https://manjubox.net/ym4/)** - ゆっくり動画制作ソフト
- **[Adobe Photoshop](https://www.adobe.com/jp/products/photoshop.html)** - 画像編集ソフトウェア
- **[waifu2x](https://github.com/nagadomi/waifu2x)** / **[waifu2x-caffe](https://github.com/lltcggie/waifu2x-caffe)** - ディープラーニング画像超解像ツール
- **[Claude](https://claude.ai/)** - AIアシスタント（開発補助）
- **[psd-tools](https://github.com/psd-tools/psd-tools)** / **[Pillow](https://python-pillow.org/)** - Python画像処理ライブラリ

---

## 8. 免責事項

- 本ソフトウェアは「現状のまま（AS IS）」で提供され、明示・黙示を問わずいかなる保証も行いません。
- 本ソフトウェアの使用によって生じた損害（データ消失・ファイル破損等）について、作者は一切の責任を負いません。事前にバックアップを取得した上でご利用ください。
- 本ツールで処理する素材は、利用権利および規約（二次創作ガイドライン・AI処理等の可否）を必ずご確認の上ご使用ください。
- 本ツールは各種公式・開発元とは関係のない非公式ツールです。

---

## 9. ライセンス（MIT）と第三者ソフトウェア

本ソフトウェアは **[MIT License](LICENSE.txt)** のもとで公開されています。

```text
Copyright (c) 2026 KairaYK (ゆっくり廻螺)
```

### 同梱ソフトウェア
- **Java実行環境:** OpenJDKライセンス（GPL v2 + Classpath Exception等）に準拠
- **[Jackson](https://github.com/FasterXML/jackson):** Apache License 2.0

---

## 10. 更新履歴

### v0.1.0 (2026-10-30)
- 初版リリース
- PSD分解 / waifu2xによる高画質化 / PSD再構築機能
- レイヤーマスク統合・グループ階層の維持機能
- YMM4用立ち絵設定（口パク・まばたき）自動生成
- 複数PSDの並行・一括処理対応
- GUI版 (`ysp.exe`) および CLI版 (`ysp-cli.exe`) の提供
