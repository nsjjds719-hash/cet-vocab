# 词间

大学英语四级、六级背单词软件，有 **Android** 和 **Windows** 两个版本。先学一组新词，再打乱顺序默写，记不牢的放进生词本，按自己的节奏复习。全程离线，不用注册，学习记录只存在自己的设备上。

当前版本：**0.1.8**

## 下载

到 [Releases](../../releases) 页面下载最新版本：

| 平台 | 文件 | 要求 |
|---|---|---|
| Android | `cijian-0.1.8.apk` | Android 8.0 及以上 |
| Windows | `Cijian-Windows-x64-0.1.8.zip` | Windows 10 / 11 64 位（x64），需 WebView2 |

安装方法、使用方法和常见问题见 **[使用说明.txt](使用说明.txt)**。Windows 压缩包里也附带了这份说明。

## 功能

- **两本词书**：四级 4,951 词，六级 6,148 词，按 2016 年修订版考试大纲补全，两本分别记进度。
- **先学后默写**：学习卡展示拼写、词性、中文释义、发音、词形变化和同根词。学完一组打乱顺序默写，可选「依义默写」（看中文写英文）或「闻声默写」（听发音写英文）。
- **掌握程度自己定**：学习时和答题后都可以标「熟记」或「待复习」，以后可改。
- **每日定量**：默认每天 35 个新词，可调。待复习的词从次日零点起到期，漏做顺延。周末可以学新词，也可以只复习。
- **五轮学习**：第一轮学完整本，第二到第五轮重点复习前一轮不熟的词，数量自定。
- **临考**：「真题高频」列出四级、六级真题各前 1,500 个高频词；「生词本」可浏览、默写，或用「实时测试」随机抽词循环练习。
- **离线词典**：中英互查，查词形或同根词也能找到原词。
- **外观可调**：浅色 / 深色主题、按键颜色、背景照片、数字字体、字号、动效开关。

## 项目结构

```
app/            Android 客户端（原生 Java，无 Gradle）
windows/        Windows 客户端（WPF + WebView2 外壳）
shared/ui/      Windows 使用的网页界面（HTML / CSS / JS）与字体
data/study/     两端共用的词库 catalog.json 和离线词典 dictionary.json
data/wordbooks/ 原始词书与考试大纲数据
scripts/        词库生成和打包脚本
tests/          学习规则回归测试与词库校验
build.ps1       Android 构建脚本
产品说明.txt     产品需求
开发进度.txt     开发记录与各版本改动
使用说明.txt     面向用户的安装与使用说明
```

学习规则在 Android（Java）和共享界面（JS）各有一份实现，改规则时需要两边同步。

## 构建

**Android**（需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0）：

```powershell
.\build.ps1
```

可用 `-SdkRoot` 指定 SDK 目录。产物为 `build\cijian-<版本>.apk`，用本地调试证书 `build\local-debug.jks` 签名（首次构建自动生成）。

**Windows**（需要 .NET 10 SDK）：

```powershell
python scripts\build_ports.py windows
```

产物为 `build\Cijian-Windows-x64-<版本>.zip`，已附带 `使用说明.txt`。

## 测试

```powershell
python tests\verify_catalog.py 0.1.8
```

校验词库覆盖考试大纲，并核对 APK 与 Windows 包里的词库、词典和源数据完全一致。`tests\StudyRulesTest.java` 是学习规则的回归测试（周末开关、零点配额、到期顺延、复习排序、五轮上限、拼写判定等）。

## 数据来源与许可

- 基础词书：[kajweb/dict](https://github.com/kajweb/dict)
- 补充词目：教育部教育考试院 2016 年修订版《大学英语四、六级考试大纲》
- 真题词频榜：[english-exam.lazynote.cn](https://english-exam.lazynote.cn/cet4/words/stats/frequency/)
- 词形与补充释义：[ECDICT](https://github.com/skywind3000/ECDICT)，许可见 `data/study/ECDICT-LICENSE.txt`
- 字体：Playfair Display 及 `shared/ui/fonts` 下的数字字体子集，均为 SIL Open Font License 1.1，许可证随字体文件附带

数据生成步骤和具体版本见 [开发进度.txt](开发进度.txt) 的「词库来源」一节。
