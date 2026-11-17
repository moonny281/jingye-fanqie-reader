# 净页番茄墨水屏阅读器 (jingye-fanqie-reader) · v0.4.7

专为墨水屏（E-Ink）设备与极简阅读爱好者深度定制的 **番茄小说全本离线下载（含正文插图渲染） + 仿番茄 App 原生可折叠多级目录 + 微信读书风 1-bit 纯黑白线框阅读器** 一体化 Android 客户端（附带可选 Python 后端服务）。

---

## v0.4.7 版本核心更新说明 (Release Notes)

### 1. 旧版缓存补全正文插图（根因修复）
- 0.4.6 及以前下载的章节在解析时已丢弃 `<img>`，增量续传复用旧缓存，因此阅读器里永远没有插图。
- `CachedChapterEntry` 新增 `imgSchema`（缓存 JSON 字段 `img_v`，旧缓存缺省为 0）；`downloadBookIncremental` 遇到旧版章节（有 itemId 且未锁定）时不再复用，而是重新抓取并保留插图 `<img>`。
- 重抓失败/暂停时回填旧正文（断点与 TXT 不缺章），下次续下会继续尝试，不会丢书。
- 番茄书城详情页检测到旧缓存时弹出「补全正文插图」提示，可一键重新抓取（无需清空缓存重下）。

### 2. 排版期插图尺寸获取改为非阻塞
- 排版时不再在协程里同步下载图片（原先多候选 URL × 10s/12s 超时，慢网会卡住排版，失败后还会退化为行内小占位）。
- 新增 `ImageProvider.getImageSizeFast`：番茄书插图本地已缓存则直接读尺寸；未缓存则先用 4:3 占位尺寸排版并后台异步下载，下载完成后若真实比例与占位不同则防抖(800ms)重排；失败后 60 秒内不重复请求。

### 3. 插图下载可观测性与兜底（0.4.7 追加）
- 下载器不再静默吞错：每个候选 URL 的失败原因（HTTP 状态/异常/解码失败）写入 App 日志，并显示在阅读页图框内（“插图加载失败”+原因）；加载中显示“插图加载中…”。
- 下载增加 OkHttp（App 全局客户端）兜底与更多 byteimg 候选；失败后 60 秒内不重复请求。
- 解析兼容 `<image>`、`xlink:href`/`href` 属性；章节含图片线索但未解析出插图时，把原文片段写入 App 日志以便确认真实标签格式。

## v0.4.6 版本核心更新说明 (Release Notes)

### 1. 修复正文插图「只显示图片框框、不显示图片内容」问题
- **恢复并升级 `ImageColumn` 真实位图渲染**：
  - 修复了 `ImageColumn.kt` 此前仅绘制矩形占位框而未绘制 `Bitmap` 的问题，现通过 `ImageProvider.getImage()` 加载离线缓存的正文插图并按原始比例居中绘制，外围配以 `1px` 细黑线边框；
  - 若翻页瞬间某张插图尚未落盘，自动触发后台异步拉取并在完成后自动重绘当前阅读页；
  - 优化 `FanqieAppApi.parseChapterXhtml` 中 `<img ...>` 标签的换行拆分，避免 `<p><img .../></p>` 嵌套标签导致图片漏抓，并校验缓存图片文件大小（`> 128B`）防止空文件阻塞重试。

### 2. 彻底清理已删除书籍缓存，支持随时重新下载
- **全链路删书同步清理（`purgeFanqieBookData`）**：
  - 在 `Book.delete()`、`LocalBook.deleteBook()`、`BookshelfManageViewModel.deleteBook()` 与 `BookHelp.clearCache()` 中全面接入番茄数据清理钩子；
  - 从书架删除书籍时，同步物理删除 `filesDir/fanqie_chapter_cache/<bookId>.json`、`<bookId>.meta.json`、`fanqie_<bookId>.txt`、`.toc.json`、本地封面、正文插图目录以及内存中的下载状态；
- **番茄书城孤儿缓存自动识别与「重新下载」按钮**：
  - 在番茄书城打开任意书籍详情时，若检测到该书已不在书架中，会自动清除残留的旧缓存并将按钮恢复为 **「全本后台离线」**；
  - 详情卡片第二排新增 **「重新下载」** 按钮，支持随时一键清空旧缓存并从第 1 章重新全本下载。

### 3. 修复番茄书城按钮显示不全与文字裁切问题
- **详情卡片双排宽按钮布局（`activity_fanqie.xml`）**：
  - 第一排：`[ 立即阅读（秒开） ]` + `[ 全本后台离线 / 断点续下 ]` + `[ 暂停 ]`；
  - 第二排：`[ 目录预览 ]` + `[ 导出TXT/EPUB ]` + `[ 重新下载 ]` 三列等宽对齐；
- **全局按钮零裁切内边距规范（`activity_fanqie.xml` & `item_search_book.xml`）**：
  - 所有按钮统一配置 `includeFontPadding="false"`、`minWidth="0dp"`、`minHeight="0dp"`、`paddingVertical="0dp"`，彻底消除文字上下边缘裁切与左右挤压截断。

### 4. 原生可折叠目录与 1-bit 纯黑白线框无残影弹窗
- **100% 还原番茄官方分卷与章节命名**，阅读器主目录页支持直接点击分卷标题行（`▼ / ▶`）原地折叠/展开子目录，底部栏提供「折叠分卷 / 展开分卷」快捷按钮；
- **全局所有弹窗 1-bit 纯黑白线框化**，零动画、零阴影，弹出与关闭双向执行白场局刷消除残影。

---

## 构建与运行指南

### 1. Android 客户端编译
项目使用 **JDK 17** 与 **Android SDK 35** 构建：
```bash
chmod +x gradlew
./gradlew assembleAppRelease --no-daemon --stacktrace
```

### 2. GitHub Actions 手动构建与发布
1. 进入 GitHub 仓库的 **Actions** 页面；
2. 选择 **Android E-Ink Legado + Fanqie Auto Build & Release** 工作流并点击 **Run workflow**；
3. 留空版本号将自动读取根目录 `VERSION` 文件（`0.4.7`），自动完成 APK 签名构建与 GitHub Release 发布。
