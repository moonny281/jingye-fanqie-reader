# 净页 (JingYe) · 海信 A7 墨水屏番茄小说阅读器 (`v0.3.0`)

专为 **海信 A7 墨水屏手机** 打造的极简黑白高对比度阅读器，深度集成 **[Jees996/fanqie-novel-downloader](https://github.com/Jees996/fanqie-novel-downloader)** 后端，采用 **微信读书墨水屏版 (WeRead E-Ink)** 视觉与精排体系。

---

## ✨ v0.3.0 核心升级亮点

### 1. 支持「书名 / 作者关键字」直接搜书 (`FanqieActivity` + `FanqieCrawler.searchBooks`)
- **告别只能粘贴链接**：在「番茄搜书」页面直接输入**小说名**或**作者名**关键字（同时兼容原有的分享短链与 19 位 Book ID 解析）。
- **多通道实时检索**：内置 3 条番茄官方搜索通道自动容灾（`novel.snssdk.com`、`api-lf.fanqiesdk.com`、`fanqienovel.com`），展示书名、作者、分类、字数、评分与简介，点击任意搜索结果即可一键全本下载并加入书架阅读。

### 2. 内置多款墨水屏专属字体 + 支持导入本地 `.ttf`/`.otf` (`FontManager`)
- **内置 6 款墨水屏优化字体切换 + 1 键导入自定义字体**：
  1. **思源宋体 (`serif`)**：传统书卷衬线，长篇沉浸阅读首选（GitHub Actions 构建时自动打包开源字库）。
  2. **霞鹜文楷 (`wenkai`)**：温润手写楷体，古风与文学佳作绝配（GitHub Actions 构建时自动打包 `LXGWWenKaiLite`）。
  3. **思源黑体 (`sans`)**：现代无衬线黑体，字形干净利落。
  4. **墨水屏凝黑 (`eink_bold`)**：针对 E-Ink 墨水粒子专门加粗字重，边缘锐利不发虚。
  5. **古籍刻本 (`fangsong`)**：高衬线对比与舒朗字怀，还原纸书质感。
  6. **护眼柔黑 (`rounded`)**：笔画柔和舒适，适合长时间速读。
  7. **自定义字体 (`custom`)**：支持从手机存储直接选择任意 `.ttf` / `.otf` 字体文件并持久化应用。

### 3. 五套微信读书墨水屏版「预设排版」+ 精细排版微调 (`LayoutPreset` + `ReaderStyle`)
- **一键切换 5 套预设排版**：
  - **微信精排**：22sp 思源宋体 · 1.45倍行距 · 10dp段距 · 22dp边距
  - **紧凑速读**：19sp 思源黑体 · 1.30倍行距 · 6dp段距 · 16dp边距
  - **舒朗大字**：26sp 墨水屏凝黑 · 1.55倍行距 · 14dp段距 · 24dp边距
  - **古籍雅致**：23sp 霞鹜文楷 · 1.60倍行距 · 12dp段距 · 28dp边距
  - **高对比黑**：22sp 墨水屏凝黑 · 1.45倍行距 · 10dp段距 · 20dp边距
- **专业级正文精排渲染**：
  - 章节首屏标题自动 1.28 倍加粗提亮，段落之间拥有真实的物理段距（`ParagraphSpacingSpan`），首行自动二字缩进，底部配备 2dp 墨水屏阅读进度条、实时时钟与电量显示。

---

## 🚀 自动版本号与 GitHub Actions 构建说明

- 根目录 `VERSION` 文件当前为 `0.3.0`。
- 推送到 GitHub 后，`.github/workflows/android-build.yml` 会自动：
  1. 读取 `VERSION` 计算 `versionName` 与 `versionCode`；
  2. 自动拉取开源中文字库（霞鹜文楷 + 思源宋体）注入 `app/src/main/assets/fonts/`；
  3. 编译生成 Debug 与 Signed Release APK，并自动发布 GitHub Release。
