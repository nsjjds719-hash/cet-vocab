首页统计数字的可选艺术字（设置 → 首页样式 → 数字字体）

这些文件只含「/」和 0–9 共 11 个字形，由 scripts\extract_numerals.py 从 Google Fonts 提供的子集生成。
按 SIL Open Font License 1.1，子集属于修改版本，因此字体内部名称一律改为「Cijian Numeral …」，不使用原字体的保留名称。
各字体的许可证原文见同目录 OFL-*.txt。

bodoni.*     Bodoni Moda                 OFL-Bodoni.txt
cormorant.*  Cormorant Garamond 斜体     OFL-Cormorant.txt
abril.*      Abril Fatface               OFL-Abril.txt
cinzel.*     Cinzel Decorative           OFL-Cinzel.txt
italiana.*   Italiana                    OFL-Italiana.txt
fraunces.*   Fraunces 斜体               OFL-Fraunces.txt

默认的 Playfair Display Italic 是完整未改动的字体，见 ..\PlayfairDisplay-Italic.ttf 与 ..\OFL-Playfair.txt。
.woff2 供 Windows 界面使用，.ttf 由 build.ps1 复制进 Android 的 assets\fonts。
