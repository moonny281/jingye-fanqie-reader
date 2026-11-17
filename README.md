# 净页番茄墨水屏阅读器 (jingye-fanqie-reader) · v0.4.5

专为墨水屏（E-Ink）设备与极简阅读爱好者深度定制的 **番茄小说全本离线下载（含正文插图） + 仿番茄 App 原生可折叠多级目录 + 微信读书风 1-bit 纯黑白线框阅读器** 一体化 Android 客户端（附带可选 Python 后端服务）。

---

## v0.4.5 版本核心更新说明 (Release Notes)

### 1. 番茄小说正文插图离线下载与墨水屏精排渲染
- **XHTML 正文插图与图注完整提取**：
  - `FanqieAppApi.parseChapterXhtml` 与 `FanqieCrawler` 支持完整解析章节内的 `<img ...>` 标签（自动识别 `src`、`data-src`、`data-original` 并规范化 CDN 链接）及 `alt` 图注；
- **全本下载期自动离线缓存正文图片**：
  - 在「立即阅读」与「后台全本离线」过程中，自动将章节内的插图下载至本地持久化目录 `filesDir/fanqie_books/images/`，并同步写入阅读器 `BookHelp` 图片缓存池，离线断网状态下阅读也能毫秒级加载正文插图；
- **墨水屏自适应居中排版与 EPUB 内嵌导出**：
  - 正文插图独立成段居中排版，根据墨水屏可视宽度等比例缩放，跨页高度不足时自动换页不截断；导出 `.epub` 时自动将正文插图打包进 `OEBPS/images/` 并生成高对比度居中边框样式。

### 2. 严格还原番茄官方原生目录命名 + 仿番茄 App 分卷子目录折叠
- **100% 还原番茄小说原生章节与分卷命名**：
  - 移除强制拼接的 `第X章` / `第X卷` 人工前缀，严格按照番茄目录原始命名展示分卷与章节（如「楔子」「番外：十年后」「001 惊蛰」「第一卷 风起青萍」）；
- **阅读器主目录页支持分卷点击折叠/展开子目录（仿番茄 App 交互）**：
  - 阅读器目录页（`ChapterListFragment` + `ChapterListAdapter`）中，点击任意一级分卷标题行（`▼ / ▶`）即可原地秒级折叠或展开该卷下的全部子章节；
  - 打开目录时自动展开当前阅读章节所在分卷并精准定位，底部栏新增 **「折叠分卷 / 展开分卷」** 一键切换按钮。

### 3. 全局所有弹出窗口重绘为 1-bit 纯黑白线框（零动画 + 弹出/关闭双向防残影）
- **全类型弹窗统一 1-bit 黑白线框化**：
  - 覆盖 `BaseDialogFragment`、`BasePrefDialogFragment`、`AlertDialog`、`WaitDialog`、`PopupAction`、`SliderPopup` 及全部 65 个 `dialog_*.xml` / `popup_*.xml` 布局；
  - 彻底移除半透明灰黑背景遮罩（`dimAmount = 0f`）、移除阴影（`elevation = 0dp`）、移除圆角（`0dp` 直角实线黑框）、禁用全部进出场动画（`Animation.EInk.None`）；
- **弹出与关闭双向白场局刷清残影**：
  - 默认开启墨水屏局部刷新清残影（可在顶栏设置中自由开关），在弹窗显示瞬间（`scrubDialogOnShow`）与关闭瞬间（`scrubAfterOverlayDismiss`）自动执行无黑闪白场局刷脉冲，彻底杜绝弹窗底影与边框残影。

---

## 核心功能一览

| 功能模块 | 特性说明 |
| :--- | :--- |
| **原生可折叠多级目录** | 严格还原番茄官方分卷与章节命名，阅读器目录页与番茄详情页均支持点击分卷折叠/展开子目录及一键全折/全展 |
| **正文插图离线与排版** | 自动提取并离线缓存章节内 `<img src>` 插图，阅读器内等比居中排版，导出 EPUB 自动内嵌插图资源 |
| **精确更新对账** | 剔除 `serial_count` 幽灵章节，基于有效 `itemId` 集合精确核对远端新增章节，支持旧版假待续状态自动治愈 |
| **1-bit 线框无残影弹窗** | 全局所有对话框、底部面板与弹出菜单均采用纯白底 + 纯黑线框绘制，零动画、零阴影，弹出与关闭自动清残影 |
| **墨水屏刷新独立控制** | 顶栏内置「局部刷新清残影」「全局刷新清残影」独立开关与全刷页数间隔（5 / 10 / 15 / 20 / 30 页）调节 |

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
3. 留空版本号将自动读取根目录 `VERSION` 文件（`0.4.5`），自动完成 APK 签名构建与 GitHub Release 发布。
