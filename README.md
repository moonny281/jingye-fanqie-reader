# 净页 (JingYe) · 海信 A7 墨水屏阅读器 + 番茄小说全本后端集成工程

本项目将 **[hisense-a7-eink-reader-shell v0.2](docs/hisense-a7-eink-reader-shell.md)**（海信 A7 水墨屏原生 Android 阅读器前端）与 **[Jees996/fanqie-novel-downloader](https://github.com/Jees996/fanqie-novel-downloader)**（番茄小说免登录 APP 网关全本下载后端）深度整合，支持直接上传至 GitHub 通过 **GitHub Actions** 自动编译生成 Android 安装包（APK）。

## 1. 核心架构与双模后端支持

| 模块 | 说明 |
| :--- | :--- |
| **前端阅读器壳子 (`app/`)** | 严格遵循海信 A7 墨水屏规范：纯黑白 (`#FFFFFF` / `#000000`)、全程零动画、点按反色、后台预渲染位图 (`RGB_565`)、字符偏移 (`charOffset`) 进度持久化、文字区局部闪烁清影 (`Tier 0`)、系统 EPD 属性感知 (`Tier 1`) 与墨水屏调试页（实验 E1–E6）。 |
| **模式 A：本机内置网关直连 (`app/.../backend/`)** | 将 `fanqie-novel-downloader` 的 `core/parser.py`、`core/decryptor.py`（362 项 PUA 字体映射）、`core/appapi.py`（`TTEncrypt` 匿名设备注册、`SM3` + `Simon-4-72` + `AES-CBC` 生成 `x-argus`、`SPECK-64` 生成 `x-ladon`、正文密钥交换与批量解密）及 `core/crawler.py` **1:1 完整移植为纯 Kotlin 代码**。手机无需额外部署 Python 服务端，粘贴分享链接即可在海信 A7 本机免登录下载全书（含 App 独占与会员章节）并自动导入书架。 |
| **模式 B：远程 FastAPI 后端 (`backend/` + `FanqieRemoteClient.kt`)** | 仓库 `backend/` 目录完整保留原版 `fanqie-novel-downloader` FastAPI 服务端代码 (`app.py`, `core/*`)。在净页「番茄小说下载」页点击顶栏【通道:本机直连】即可切换为【通道:远程FastAPI】，通过 `POST /api/parse`、`GET /api/download/progress` (SSE) 与 `GET /api/download/file/{name}` 对接局域网/云端服务端。 |

## 2. 上传至 GitHub 自动读取版本号构建与发布（零维护工作流）

`.github/workflows/android-build.yml` 已配置完整的**全自动版本号解析 + 自动构建重命名 + 自动发布 GitHub Releases**，后续任何代码迭代均**无需修改工作流文件**：

1. **四种更新版本号的方式（任选其一，自动生效）**：
   - **方式 A（修改根目录 `VERSION` 文件，最简单）**：直接编辑项目根目录的 `VERSION` 文件（例如改为 `0.3.0`）并 `git push`，工作流会自动读取该版本号构建 `jingye-reader-v0.3.0-release.apk`，并自动在仓库 **Releases** 页面创建/更新 `v0.3.0` 发行版。
   - **方式 B（推送 Git 标签）**：执行 `git tag v0.3.0 && git push --tags`，工作流自动提取标签中的 `0.3.0` 进行构建并发布 Release。
   - **方式 C（修改 `app/build.gradle.kts`）**：若删除了 `VERSION` 文件，工作流会自动回退解析 `app/build.gradle.kts` 中的 `versionName`。
   - **方式 D（GitHub 网页手动指定）**：在 GitHub Actions 页面点击 `Run workflow`，可在输入框中临时填入任意自定义版本号。
2. **智能递增 `versionCode`（免卸载直接覆盖安装）**：
   - 工作流会根据语义化版本号（`Major.Minor.Patch`）结合 GitHub Actions 的构建序号（`GITHUB_RUN_NUMBER`）自动计算单调递增的整数 `versionCode`（即便是同一个 `versionName` 下多次推送修复代码，`versionCode` 也会自动递增），确保海信 A7 实机始终可以直接覆盖安装新包。
3. **下载编译好的 APK**：
   - **直接从 Releases 下载（推荐）**：构建完成后，直接打开 GitHub 仓库右侧的 **Releases**，即可看到最新发布的 `jingye-reader-v<版本号>-release.apk`、`jingye-reader-v<版本号>-debug.apk` 以及 `SHA256SUMS.txt`。
   - **从 Actions 下载**：也可在 **Actions** 对应构建记录底部的 **Artifacts** 下载。

## 3. 工程目录结构

```text
jingye-fanqie-reader/
├── .github/workflows/
│   └── android-build.yml                # GitHub Actions 自动构建 APK 与后端校验工作流
├── app/
│   ├── build.gradle.kts                 # Android 模块构建配置 (minSdk 26, targetSdk 34)
│   └── src/main/
│       ├── AndroidManifest.xml          # 竖屏锁定、无动画主题、网络权限声明
│       ├── java/app/jingye/reader/
│       │   ├── JingyeApp.kt             # Application 单例容器
│       │   ├── backend/                 # 番茄小说后端模块 (1:1 Kotlin 移植 + 远程 FastAPI 客户端)
│       │   │   ├── FanqieModels.kt      # 元数据与下载进度模型
│       │   │   ├── FanqieDecryptor.kt   # 362 项 awesome-font PUA 字体映射解密
│       │   │   ├── FanqieParser.kt      # 分享文案 / changdunovel 短链 / book_id 解析
│       │   │   ├── FanqieCrypto.kt      # SM3 / Simon-4-72 / SPECK-64 / Protobuf / x-argus / x-ladon / TTEncrypt
│       │   │   ├── FanqieAppApi.kt      # 匿名设备注册、正文密钥交换与 batch_full/v 批量解密
│       │   │   ├── FanqieCrawler.kt     # 落地页 + APP 网关 + PC 阅读页兜底调度与 TXT 格式化
│       │   │   ├── FanqieRemoteClient.kt# 远程 FastAPI (app.py) HTTP + SSE 客户端
│       │   │   └── FanqieService.kt     # 本机直连 / 远程后端统一调度与自动入架服务
│       │   ├── data/
│       │   │   ├── BookSource.kt        # 章节内容来源接口
│       │   │   ├── LocalTxtSource.kt    # UTF-8/GB18030 解码、双格式分章正则（第X章 & 【章节名】）、缩进排版
│       │   │   ├── LibraryStore.kt      # 书架 JSON 持久化、本地 TXT 导入与番茄下载入架
│       │   │   └── ReaderPrefs.kt       # 阅读排版、清影间隔、节流与后端模式配置
│       │   ├── reader/
│       │   │   ├── ReaderStyle.kt       # 排版样式与 TextPaint 构造
│       │   │   ├── Paginator.kt         # StaticLayout 章节分页切片
│       │   │   ├── PageRenderer.kt      # RGB_565 位图预渲染与 LRU(3) 缓存
│       │   │   ├── ReaderEngine.kt      # 跨章翻页、字号重排定位（字符偏移锚定）
│       │   │   ├── PageView.kt          # 纯位图绘制、左中右三区点击、局部纯色闪烁
│       │   │   └── TurnThrottle.kt      # 翻页节流器 (默认 250ms)
│       │   ├── eink/
│       │   │   ├── RefreshController.kt # Tier 0 脏区绘制与黑→白→内容局部清影
│       │   │   ├── GhostPolicy.kt       # 翻页计数清影策略 (默认每 30 页)
│       │   │   └── EpdSettings.kt       # Tier 1 读取海信 A7 系统 EPD 设置与属性 + Tier 2 实验写入
│       │   └── ui/
│       │       ├── shelf/ShelfActivity.kt       # 书架页（番茄下载 / 本地导入 / 设置 / 长按删除）
│       │       ├── fanqie/FanqieActivity.kt     # 番茄小说解析与全本下载页
│       │       ├── reader/ReaderActivity.kt     # 沉浸式阅读页（音量键翻页、呼出菜单、onPause 保存进度）
│       │       ├── toc/TocActivity.kt           # 目录页（当前章 ▶ 标记、瞬时定位）
│       │       ├── settings/SettingsActivity.kt # 设置页（步进按钮与开关立即生效）
│       │       └── debug/                       # 海信 A7 墨水屏调试页与测试样张 (TestPatterns / EpdDebugActivity)
│       └── res/                         # 纯黑白无动画主题、按下反色 Drawable、自适应图标与布局
├── backend/                             # 原版 Python FastAPI 后端 (Jees996/fanqie-novel-downloader)
└── docs/                                # 前端开发规划文档 v0.2 与图标矢量源文件
```
