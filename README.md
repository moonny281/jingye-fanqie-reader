# 净页番茄墨水屏阅读器 (jingye-fanqie-reader) · v0.4.6

专为墨水屏（E-Ink）设备与极简阅读爱好者深度定制的 **番茄小说全本离线下载（含正文插图渲染） + 仿番茄 App 原生可折叠多级目录 + 微信读书风 1-bit 纯黑白线框阅读器** 一体化 Android 客户端（附带可选 Python 后端服务）。

---

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
3. 留空版本号将自动读取根目录 `VERSION` 文件（`0.4.6`），自动完成 APK 签名构建与 GitHub Release 发布。
