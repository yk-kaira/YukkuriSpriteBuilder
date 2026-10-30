# Yukkuri Sprite Builder (ysp)

**Yukkuri Sprite Builder**（略称: `ysp`）は、PSD → (分解) → waifu2x高画質化 → (再構築) → `[name]_x2.psd` を一気通貫で行うCUIツール。

## 準備
```
pip install -r src/main/resources/python/requirements.txt
gradle wrapper       
   # 初回のみ(gradlew生成)
gradle installDist  
    # build/install/ysp/bin/ysp(.bat) でも起動可
gradle fatJar      
     # build/libs/ysp-0.1.0-all.jar
```
要件: Java 17+, Python 3.x (python / py -3 / python3 を自動検出。見つからない場合は `--python <フルパス>`), waifu2x-caffe (`waifu2x-caffe-cui.exe`)

## GUIで使う (ysp.exe)
`ysp.exe` を起動（引数なしで起動した場合はGUIが開きます）。CLIと同じ処理を画面から操作できます。

- **処理するPSD**: ファイル / フォルダを一覧へドラッグ&ドロップ、または「ファイル追加」「フォルダ追加」で追加します（複数可）。
- **ツール**: waifu2x-caffe-cui.exe の場所を指定します（一度指定すると次回以降は自動）。Pythonは空欄で自動検出します。
  「環境チェック」で、PythonとPSD用ライブラリ(psd-tools / Pillow)、waifu2xが使えるかを確認できます。
- **処理設定**: 拡大率・ノイズ除去・処理方式、同時に処理するPSD数、実行するステップ、waifu2xへの追加引数。
- **YMM立ち絵設定**: 生成の有無、既存設定の上書き、n番号の基点、判定ルール(任意)。
- **実行 / 中止**: 処理中の外部プロセスも含めて中止できます。ログは画面下部に表示されます。
- 設定内容は次回起動時に引き継がれます（PSD一覧は引き継ぎません）。
- コマンドラインで使うときは `ysp-cli.exe`（またはjar）に引数を付けて実行してください。

### exeの作り方 (Windows、初回のみ)
1. JDK 17以上（`jpackage` 付き）と Gradle を用意します。
2. `build-exe.bat` を実行します。
3. `build\dist\ysp\` に `ysp.exe`（GUI）と `ysp-cli.exe`（コンソール）ができます。**フォルダごと**配布・移動してください（Java実行環境が同梱されます）。

### アイコンの変更
- **exe / ショートカットのアイコン**: `packaging\ysp.ico` を差し替えて `build-exe.bat` を実行します（`ysp.exe` と `ysp-cli.exe` の両方に反映）。
  16〜256pxを含む複数サイズのicoを推奨します。ファイルが無い場合はJava標準のアイコンになります。
- **ウィンドウ / タスクバーのアイコン**: `src\main\resources\icon.png`（256x256のPNG推奨）を差し替えて、`gradle fatJar` から再ビルドします。
- 同梱のアイコンは `packaging\ysp-icon.png`（元画像）から作成しています。

### 配布用ドキュメント
- `dist-docs\README.txt`（使い方・初期設定・関連ツール・免責・ライセンス）と `dist-docs\LICENSE.txt`（MIT）は、`build-exe.bat` が `build\dist\ysp\` へ自動でコピーします。
- 作者名・バージョンを変えるときは、`README.txt` / `LICENSE.txt` と、`Main.VERSION`、`build-exe.bat`、`build.gradle` を合わせて更新してください。

※ exeにはJavaは含まれますが、**Pythonは含まれません**。別途Python 3 と `pip install psd-tools Pillow` が必要です。
※ exeを作らずに試す場合は、`gradle fatJar` の後に `run-gui.bat` でGUIを起動できます。

## 使い方
```
java -jar build/libs/ysp-0.1.0-all.jar ^
  --psd "D:\work\chara.psd" ^
  --waifu2x "C:\tools\waifu2x-caffe\waifu2x-caffe-cui.exe" ^   (2回目以降は省略可)
  --scale 2 --noise 1 --process cudnn
```
再構築だけやり直す場合 (JSON/画像を手で編集した後など): `--steps 3`

## 複数PSDの一括・並行処理
`--psd` は何度でも指定でき、ファイル / フォルダ / ワイルドカードを混在できます。
```
--psd "D:\work"                  フォルダ直下の *.psd すべて
--psd "D:\work\*.psd"           ワイルドカード (* と ? に対応)
--psd a.psd --psd b.psd           個別指定
--psd-list list.txt               1行1件のパス一覧 (空行と # で始まる行は無視、"..." で囲んでもよい)
```
- フォルダ/ワイルドカード指定では、本ツールの出力（`*_x2.psd`）は対象から除外します（ファイルを直接指定した場合は処理します）。
- `--jobs N`: 同時に処理するPSDの数 (既定 2)。PythonでのPSD分解・再構築はPSDごとに並行して動きます。
- `--waifu2x-jobs N`: 同時に動かすwaifu2xの数 (既定 1)。GPUが1枚の場合は1のままを推奨します。
  他のPSDがwaifu2xを使用中の間は、順番待ち（`waiting for a free waifu2x slot...`）になります。
- ログの行頭に `[PSD名]` が付きます。1つのPSDが失敗しても残りは処理を続け、最後に結果一覧を表示します（失敗があれば終了コード1）。
- 同時処理が多いほどメモリを使います。大きなPSDでは `--jobs 1` から試してください。

## 出力
```
D:\work\                    (参照PSDと同じフォルダ)
├─ chara.psd
├─ chara_x2.psd             再構築結果
├─ chara_x2-ymm.json        YMM立ち絵設定(口パク/まばたき)
└─ chara_part\              作業フォルダ
   ├─ chara.json            レイヤー情報
   ├─ x1\                   分解した画像 (PSDのグループ階層をサブフォルダで維持)
   │   ├─ 0001_背景.png
   │   └─ 体\
   │       ├─ 0003_腕.png
   │       └─ 0004_顔.png
   └─ x2\                   高画質化後 (x1と同じ階層。拡大率に応じて x3, x4 ...)
```
※ `x1` と `x2` は実行のたびに削除して作り直します。拡大率1の場合は `x1_out` に出力します。
※ waifu2xはPSDのフォルダ(階層)ごとに1回ずつ起動します。

## waifu2xパスのキャッシュ
`--waifu2x` を指定して実行し、実在を確認できたパスは設定ファイルに保存され、次回以降は省略できます。
保存先: `%APPDATA%\ysp\config.json` (Windows)。`--clear-cache` で削除。

## YukkuriMovieMaker 立ち絵設定 (`[出力PSD名]-ymm.json`)
再構築したPSDと同じフォルダに、YMM用の口パク/まばたき設定を生成します（例: `chara_x2.psd` → `chara_x2-ymm.json`）。
形式はYMMが出力するものと同じ（BOM付きUTF-8 / CRLF）で、`Layers`(n番号) と `LayerPaths`(レイヤーパス) の両方を書きます。

**判定のしかた** — レイヤー名から判定します（先頭の `!` `*` と末尾の ` #1` などは無視）。
| 対象 | 条件 |
|---|---|
| 口パク | 名前が `口` のグループ内。`開け` / `あわわ` を含む = 開き、`閉じ` を含む = 閉じ、`笑` を含む = 笑顔系。 開き→閉じ、笑顔の開き→笑顔の閉じ のペアを作る |
| まばたき | 名前が `目` のグループ内。`普通目` / `通常目` = 開き、`閉じ` = 閉じ、`半目` / `半開` があれば 開→半→閉 の3枚構成 |

- 閉じレイヤーが複数ある場合は、名前が最も短いもの（番号が付かないもの）を使います。
- 該当グループ・レイヤーが見つからない表情グループは、設定を作らずにログへ出力します。
- 命名が違う場合は `--ymm-rules rules.json` でキーワードを上書きできます。
  ```json
  { "mouth_groups": ["くち"], "eye_groups": ["め"], "mouth_closed": ["閉じ"], "smile": ["笑"],
    "mouth_open": ["開け"], "eye_open": ["普通目"], "eye_half": ["半目"], "eye_closed": ["閉じ"] }
  ```
- **既存の設定ファイルは上書きしません**（YMMで調整した内容を守るため）。作り直すときは `--ymm-overwrite`、生成しないときは `--no-ymm`。

**n番号について**: YMM内部のレイヤー番号は、PSD内のレイヤーレコード順（フォルダの開始/終了も1つずつ数える）に
一致するとサンプルから判断し、出力PSDの並びから `n番号` を計算しています（0始まり）。
YMMで開いて口パク/まばたきの対象がずれる場合は、`--ymm-ids 1`（1始まり）を試すか、`--ymm-ids none` で番号を省いてください
（`LayerPaths` が使われます）。

## レイヤーマスク
- 分解時: レイヤー自身のマスクと親グループのマスクは、PNGのアルファに統合(焼き込み)します。
  ログの `masks_baked=` が統合した数です。
- 再構築時: PSDの書き出しは psd-tools を使わず自前で行い、レイヤーマスクのデータを一切書きません。
  各レイヤーの透明度は、画像自身の透明度チャンネルとして保存されます(出力に `masks_in_output=0`)。
- 親グループのマスクは各子レイヤーに掛けるため、通常合成以外の合成モードの子では見た目が僅かに変わる場合があります。

## 再構築PSDについて
- 形式は PSD (RGB / 8bit)。グループ構造・名前・不透明度・合成モード・表示状態を保持します。
- PSD内の「合成済み画像」(プレビュー用)は、通常合成・不透明度・表示状態のみで簡易合成したものです。
  Photoshopなどレイヤーを読むアプリでは影響しません。サムネイルが必要な場合は一度開いて保存し直してください。
- 重なり順は JSON の `stack_order` で決まります(ログに `stack_order=` と表示)。`unknown` の場合は警告が出ます。

## JSON項目
id, parent_id, type(layer/group), name, path, png(x1/x2からの相対パス), opacity(0-255), blend_mode, visible,
x, y, width, height, rasterized, clipping, mask_baked + 先頭に stack_order, canvas{width,height}

## v1の制限
- テキスト/シェイプ/スマートオブジェクト/マスク付きはラスタライズ、調整レイヤー等はスキップ(ログに[WARN])
- クリッピング・レイヤー効果・ロック状態は未復元(clippingはJSONに記録のみ)
