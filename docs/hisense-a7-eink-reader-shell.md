# 净页(JingYe)· 海信 A7 墨水屏阅读器:前端开发规划 v0.2

> 本文档可直接作为写代码的依据:包含系统探测结论、工程结构、逐屏界面规格、数据模型、核心模块可粘贴代码、刷新策略分层、实验清单和里程碑验收标准。
> 范围:**只做阅读器壳子**(书架 / 阅读页 / 目录 / 设置 / 墨水屏调试页)。内容来源先用本地 TXT,通过 `BookSource` 接口隔离,不含任何网络代码。

**v0.2 相比 v0.1 的变化**:新增 A7 实机探测结论;命名为「净页」并附图标;新增 `ReaderEngine`(分页/跨章/进度的纯逻辑层);进度改为"字符偏移"存储;TXT 增加编码识别与分章兜底;新增目录页、墨水屏调试页;刷新控制层分为 4 档;补全逐屏规格与验收标准。

---

## 1. 产品定位与目标

| 目标 | 做法 |
|---|---|
| 翻页流畅 | 后台分页 + 预渲染位图,翻页只做一次 `drawBitmap` |
| 尽量无残影 | 全程无动画、纯黑白、只更新变化区域,必要时做**文字区局部清影** |
| 不做大范围全刷 | 默认不做整屏全刷;局部清影间隔可调,可设"从不" |
| 护眼 | 界面静态、无定时刷新(时钟只在翻页时更新)、隐藏系统栏 |

**物理现实**:墨水屏残影来自粒子残余位移,"完全不全刷且完全无残影"做不到。目标是把清影压到低频并限制在文字区。

**非目标(本阶段)**:网络/书源、云同步、EPUB/PDF、朗读、彩墨版专用主题。

---

## 2. A7 实机探测结论

来源:你在 A7 上执行 adb 探测的输出。**置信度**列说明我的解读有多可靠,"中/低"的都需要用第 3 节的实验验证。

| 探测项 | 值 | 解读 | 置信度 | 对设计的影响 |
|---|---|---|---|---|
| `EInkService`(dumpsys -l)、`init.svc.einkservice` | running | 系统有专门的墨水屏服务 | 高 | 刷新层保留"调用系统服务"档位(Tier 3),待 `dumpsys EInkService` 结果 |
| `current_epd_mode` / `sys.hmct.epd_mode` | 3 / 515 | 当前刷新模式;低位 3 很可能是模式编号 | 中(推测) | 只读取并展示,不依赖其含义 |
| `ink_mode`、`dynamic_refresh_mode` | 1、0 | 含义未知 | 低 | 仅展示 |
| `eink_automatic_clearance_key` | 0 | 系统自动清屏当前关闭 | 中 | 残影只能靠应用清影或用户手动处理,应用内清影有价值 |
| `hmct_epd_boldsize=12`、`hmct_epd_darkthreshold=88`、`hmct_epd_bleach_mode=1` | | 像是系统级加粗、暗部阈值、漂白 | 低 | 应用内严格纯黑白绘制,不叠加灰度效果;字重做成应用内可调 |
| `persist.sys.epd.black/white/contrast` | 100/20/100 | 黑白点与对比度,系统属性 | 中 | 应用无权修改,仅展示 |
| `memory_clear_white_list*` | 仅列了几款常见阅读应用 | 系统会清理不在名单内的后台应用 | 中 | 净页不在名单内:`onPause` 必须保存进度,不能依赖进程存活 |
| `app_with_stable_notify` | 含 `com.ghostbuster.hisensea7`、`com.eink.precision` | 存在第三方去残影/精度工具,说明第三方应用有办法影响刷新 | 中 | 值得研究它们如何调用(见实验 E3) |
| `healthyscreen_*` | 名单若干 | 海信护眼/使用时长限制名单 | 高 | 与刷新无关;用户若开启使用限制,净页可能受限 |

---

## 3. 待做实验(决定刷新层走到哪一档)

| # | 实验 | 做法 | 判定 |
|---|---|---|---|
| E1 | 模式 ↔ 数值对照 | 系统设置里依次切换各刷新模式,每次重跑 `adb shell settings list system`/`global`/`secure`,diff | 得到模式值映射,以及每个键所属命名空间 |
| E2 | 查看系统服务 | `adb shell dumpsys EInkService`、`adb shell dumpsys epd` | 看是否暴露可调用的方法/状态 |
| E3 | 研究第三方去残影应用 | `adb shell pm path com.ghostbuster.hisensea7`,看其权限与调用方式 | 判断第三方能否触发清屏、是否需要特殊权限 |
| E4 | 通用方案是否局部刷新 | 用本应用调试页做局部闪烁/页脚更新,慢动作观察 | 判断局部清影能否成立 |
| E5 | 硬件加速与抗锯齿的残影差异 | 调试页内切换对比 | 决定默认值 |
| E6 | 系统设置写入是否生效 | 调试页"尝试写入"(默认关闭,见 Tier 2) | 判断能否在应用内切换刷新模式 |

**不要盲目执行 `service call EInkService ...`**:参数不明的调用可能导致黑屏或卡死。

---

## 4. 刷新控制分层(Tier)

| Tier | 名称 | 能力 | 状态 |
|---|---|---|---|
| 0 | 通用 View 层 | 脏区重绘 + 文字区"黑→白→内容"局部闪烁 | **先实现,所有设备可用** |
| 1 | 只读感知 | 读取 `current_epd_mode` 等设置并展示;可据模式调整清影间隔建议 | 实现(只读,无风险) |
| 2 | 设置写入(实验) | 写入刷新相关设置键,需 `WRITE_SETTINGS`(System 键)或经 adb 授权的 `WRITE_SECURE_SETTINGS`;**不保证系统服务会响应** | 仅调试页开关,默认关闭,依赖 E1/E6 |
| 3 | 系统服务调用(实验) | 直接调用 `EInkService` | 依赖 E2/E3 结果,暂不实现 |

工厂规则:默认 Tier 0 + Tier 1。Tier 2/3 只有在实验验证有效后才在设置页暴露。

---

## 5. 技术栈与工程配置

- Kotlin + 传统 View 体系(**不用 Compose**,便于完全控制重绘与动画关闭)
- AndroidX AppCompat / RecyclerView / Lifecycle KTX、Kotlin 协程
- **不用** Room、Glide、Navigation 等:壳子体量小,SharedPreferences + 文件即可
- 包名:`app.jingye.reader`,应用名:净页

`app/build.gradle.kts`(节选)

```kotlin
android {
    namespace = "app.jingye.reader"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.jingye.reader"
        minSdk = 26            // 以 A7 实际 Android 版本为准,可再下调
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

`AndroidManifest.xml`(节选)

```xml
<application
    android:name=".JingyeApp"
    android:label="净页"
    android:icon="@mipmap/ic_launcher"
    android:theme="@style/Theme.Jingye">

    <activity android:name=".ui.shelf.ShelfActivity" android:exported="true"
        android:screenOrientation="portrait">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
    </activity>
    <activity android:name=".ui.reader.ReaderActivity" android:screenOrientation="portrait"
        android:configChanges="keyboardHidden|screenSize|orientation"/>
    <activity android:name=".ui.toc.TocActivity" android:screenOrientation="portrait"/>
    <activity android:name=".ui.settings.SettingsActivity" android:screenOrientation="portrait"/>
    <activity android:name=".ui.debug.EpdDebugActivity" android:screenOrientation="portrait"/>
</application>
```

锁定竖屏,避免旋转触发重新排版与整屏刷新。

常用 import 速查(各文件按需取用):

```kotlin
import android.app.Activity; import android.content.Context; import android.graphics.*
import android.os.*; import android.provider.Settings; import android.text.*
import android.util.LruCache; import android.view.*; import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity; import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
```

---

## 6. 工程结构与文件职责

```
app/src/main/java/app/jingye/reader/
├── JingyeApp.kt                    Application;持有 LibraryStore / BookSource 单例
├── data/
│   ├── BookSource.kt               内容来源接口 + BookMeta
│   ├── LocalTxtSource.kt           本地 TXT:编码识别、分章、段落缩进
│   ├── LibraryStore.kt             书架存储(JSON in SharedPreferences)+ 导入
│   └── ReaderPrefs.kt              阅读设置(字号/行距/边距/字重/清影间隔…)
├── reader/
│   ├── ReaderStyle.kt              排版样式 + Paint 构造
│   ├── Paginator.kt                章节文本 → 页切片
│   ├── PageRenderer.kt             页切片 → 位图(LRU 缓存)
│   ├── ReaderEngine.kt             阅读核心:跨章翻页、重新排版、定位(纯逻辑,无 UI)
│   ├── PageView.kt                 只画一张位图的 View + 点击区域 + 局部闪烁
│   └── TurnThrottle.kt             翻页节流
├── eink/
│   ├── RefreshController.kt        接口 + DefaultRefreshController(Tier 0)+ 工厂
│   ├── GhostPolicy.kt              清影计数策略
│   └── EpdSettings.kt              读取系统墨水屏设置/属性(Tier 1)
└── ui/
    ├── shelf/ShelfActivity.kt      书架
    ├── reader/ReaderActivity.kt    阅读页(含呼出菜单)
    ├── toc/TocActivity.kt          目录
    ├── settings/SettingsActivity.kt 设置
    └── debug/EpdDebugActivity.kt   墨水屏调试页

app/src/main/res/
├── values/themes.xml               无动画主题
├── drawable/ink_button_bg.xml      按钮背景(按下反色)
├── color/ink_text.xml              按钮文字色(按下变白)
├── drawable/ink_divider.xml        1dp 黑线
├── layout/activity_shelf.xml / activity_reader.xml / activity_toc.xml / activity_settings.xml / activity_epd_debug.xml
└── mipmap-*/ic_launcher*           图标(见 jingye-icon 目录)
```

实现顺序(依赖关系):`data/*` → `reader/*` → `eink/*` → `ReaderActivity` → `ShelfActivity` → `TocActivity` → `SettingsActivity` → `EpdDebugActivity`。

---

## 7. 界面规格

### 7.1 全局规则

| 项 | 规则 |
|---|---|
| 颜色 | 仅 `#FFFFFF` 与 `#000000`,不使用灰色大面积色块、阴影、渐变、半透明 |
| 动画 | 全部关闭:窗口动画 `@null`、`itemAnimator = null`、菜单用 `visibility` 瞬时显隐 |
| 点按反馈 | 反色(黑底白字),不使用 ripple |
| 触控区域 | 可点元素高度 ≥ 48dp |
| 线条 | 描边 2dp,分隔线 1dp,纯黑 |
| 字号 | 页面标题 22sp 粗体;列表主文字 18sp;次要文字 14sp |
| 系统栏 | 阅读页隐藏;其他页保留状态栏但用浅色(白底黑字) |

### 7.2 书架(ShelfActivity)

```
┌─────────────────────────────┐
│ 净页                [导入][设置] │  顶栏 56dp,下方 2dp 黑线
├─────────────────────────────┤
│ 《书名一》                    │  行高 72dp
│ 第 12 章 · 已读 35%          │  次要文字 14sp
├─────────────────────────────┤
│ 《书名二》                    │
│ 未开始                       │
├─────────────────────────────┤
│              …              │
└─────────────────────────────┘
```

| 交互 | 行为 |
|---|---|
| 点击行 | 进入阅读页(传 `bookId`) |
| 长按行 | 弹出确认框"删除本书?"(确认/取消两个反色按钮),删除本地副本与进度 |
| 点击【导入】 | 系统文件选择器选 `.txt`,后台复制到应用私有目录,完成后刷新列表 |
| 点击【设置】 | 进入设置页 |
| 空状态 | 居中文字"书架是空的,点【导入】添加 TXT" |
| 排序 | 按最近阅读时间倒序 |
| 导入失败 | 在顶栏下方一行静态文字提示失败原因,不用 Toast 动画 |

### 7.3 阅读页(ReaderActivity)

```
┌─────────────────────────────┐
│                             │
│                             │
│          正文区              │  PageView,占满除页脚外的全部空间
│                             │
│                             │
├─────────────────────────────┤
│ 第3章 · 12/48        14:32  │  页脚 28dp,12sp,仅翻页时更新
└─────────────────────────────┘
```

| 输入 | 行为 |
|---|---|
| 点按左 1/3 | 上一页 |
| 点按右 1/3 | 下一页 |
| 点按中 1/3 | 显示/隐藏菜单 |
| 音量下 / PageDown | 下一页(忽略长按重复事件) |
| 音量上 / PageUp | 上一页 |
| 翻页节流 | 两次翻页最小间隔默认 250ms,过快的输入直接丢弃 |
| 书末 | 页脚显示"— 全书完 —",不再翻页 |
| 书首 | 页脚显示"— 第一页 —" |
| 返回键 | 菜单打开时先关菜单;否则退出并保存进度 |
| 退后台(`onPause`) | 立即保存 `chapter + charOffset` |

**菜单面板**(底部覆盖在页脚上方,白底 2dp 黑框,高度约 120dp,瞬时显隐):

```
┌─────────────────────────────────┐
│ [目录] [A−] 20 [A+] [行距] [设置] │
│ [返回书架]                        │
└─────────────────────────────────┘
```

| 按钮 | 行为 |
|---|---|
| 目录 | 打开 `TocActivity`,选中后跳章 |
| A− / A+ | 字号 ±1sp,范围 14–40,改完调用 `engine.relayout` 保持当前位置 |
| 行距 | 在 1.2 / 1.5 / 1.8 / 2.0 间循环 |
| 设置 | 打开设置页 |
| 返回书架 | `finish()` |

### 7.4 目录(TocActivity)

- 顶栏:"目录" + 【关闭】;列表每行 56dp,仅章节标题;当前章标题前加"▶ "标记
- 进入时滚动到当前章;点击某章 → `setResult(RESULT_OK, intent.putExtra("chapter", i))` 并关闭
- 章节数很多时用 RecyclerView,`itemAnimator = null`

### 7.5 设置(SettingsActivity)

| 设置项 | 控件 | 范围 / 选项 | 默认 |
|---|---|---|---|
| 字号 | 步进按钮 | 14–40 sp | 20 |
| 行距 | 步进按钮 | 1.2–2.0,步长 0.1 | 1.5 |
| 页边距 | 步进按钮 | 8–48 dp,步长 4 | 24 |
| 字重 | 开关"加粗(Medium)" | 开/关 | 关 |
| 抗锯齿 | 开关 | 开/关 | 开 |
| 局部清影间隔 | 步进按钮 | 从不 / 10 / 20 / 30 / 50 / 80 页 | 30 |
| 翻页最小间隔 | 步进按钮 | 150–600 ms,步长 50 | 250 |
| 左右点击互换 | 开关 | 开/关 | 关 |
| 墨水屏调试 | 按钮 | 进入调试页 | — |

所有步进按钮为 `[−] 当前值 [+]` 形式,点击立即写入 `ReaderPrefs`。

### 7.6 墨水屏调试页(EpdDebugActivity)

用于完成第 3 节实验,上线前可保留但放在设置页最底部。

| 区块 | 内容 |
|---|---|
| 系统值 | 列表展示 `EpdSettings.read()` 的每个键、所属命名空间、当前值,以及 `sys.hmct.epd_mode` 等系统属性 |
| 测试图 | 按钮:全白 / 全黑 / 棋盘格 / 灰阶条 / 文字样张,显示在占满屏幕的 `PageView` 上 |
| 局部清影测试 | 按钮:对当前测试图执行一次 `cleanRegion` |
| 翻页压力测试 | 按钮:连续切换 20 张测试图(每张间隔可选 250/400ms),观察拖影 |
| 开关 | 硬件加速(`setLayerType`)/ 抗锯齿,即时生效,用于实验 E5 |
| 实验写入 | 默认折叠且关闭,仅在 E1/E6 前提下使用(Tier 2) |

---

## 8. 数据模型与存储

### 8.1 书架

```kotlin
data class Book(
    val id: String,            // UUID
    val title: String,         // 取文件名(去掉 .txt)
    val path: String,          // filesDir/books/<id>.txt
    val chapter: Int = 0,      // 当前章索引
    val charOffset: Int = 0,   // 章内字符偏移(页起点)
    val lastReadAt: Long = 0L
)
```

存储:`SharedPreferences("library")`,键 `books` 为 JSON 数组。TXT 文件复制到 `filesDir/books/`。

### 8.2 进度:存字符偏移而不是页码

页码会随字号、行距、边距变化而失效,字符偏移不会。恢复时在新的分页结果里找"起点 ≤ 偏移"的最后一页即可(见 `ReaderEngine.loadChapter`)。

### 8.3 设置键

| 键 | 类型 | 默认 |
|---|---|---|
| `text_size_sp` | Int | 20 |
| `line_spacing_x10` | Int | 15(表示 1.5) |
| `margin_dp` | Int | 24 |
| `bold` | Boolean | false |
| `anti_alias` | Boolean | true |
| `clean_every_n_pages` | Int | 30(0 = 从不) |
| `min_turn_interval_ms` | Int | 250 |
| `swap_tap_zones` | Boolean | false |

---

## 9. 核心代码(可直接粘贴)

> 以下代码按第 6 节的文件划分。细节(尤其刷新相关)以 A7 实测为准。

### 9.1 `data/BookSource.kt`

```kotlin
interface BookSource {
    suspend fun chapterCount(bookId: String): Int
    suspend fun chapterTitle(bookId: String, index: Int): String
    suspend fun chapterText(bookId: String, index: Int): CharSequence
}
```

### 9.2 `data/LocalTxtSource.kt`

```kotlin
class LocalTxtSource(private val library: LibraryStore) : BookSource {

    private val chapterRegex =
        Regex("""^[ \t　]*第[0-9零〇一二三四五六七八九十百千两]+[章节回卷集部篇].{0,40}$""", RegexOption.MULTILINE)
    private val cache = HashMap<String, List<Pair<String, String>>>()

    private suspend fun chapters(bookId: String) = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[bookId] }?.let { return@withContext it }
        val book = library.get(bookId) ?: error("book not found")
        val text = decode(java.io.File(book.path).readBytes()).replace("\r\n", "\n").replace('\r', '\n')
        val list = split(text)
        synchronized(cache) { cache[bookId] = list }
        list
    }

    override suspend fun chapterCount(bookId: String) = chapters(bookId).size
    override suspend fun chapterTitle(bookId: String, index: Int) = chapters(bookId)[index].first
    override suspend fun chapterText(bookId: String, index: Int): CharSequence = chapters(bookId)[index].second

    /** UTF-8(含 BOM)优先,严格解码失败则按 GB18030。 */
    private fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(b, java.nio.charset.Charset.forName("GB18030"))
        }
    }

    private fun split(text: String): List<Pair<String, String>> {
        val marks = chapterRegex.findAll(text).toList()
        if (marks.size < 2) return chunk(text)
        val out = ArrayList<Pair<String, String>>()
        val head = text.substring(0, marks[0].range.first).trim()
        if (head.isNotEmpty()) out += "前言" to indent(head)
        marks.forEachIndexed { i, m ->
            val end = marks.getOrNull(i + 1)?.range?.first ?: text.length
            out += m.value.trim() to indent(text.substring(m.range.last + 1, end))
        }
        return out
    }

    /** 找不到章节标记时,按约 12000 字在换行处切段,避免单章过大。 */
    private fun chunk(text: String, size: Int = 12000): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var start = 0
        var n = 1
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val nl = text.indexOf('\n', end)
                end = if (nl == -1) text.length else nl
            }
            out += "第 $n 段" to indent(text.substring(start, end))
            start = end; n++
        }
        if (out.isEmpty()) out += "全文" to ""
        return out
    }

    /** 去空行,每段首行两个全角空格缩进。 */
    private fun indent(s: String): String =
        s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n") { "　　$it" }
}
```

### 9.3 `data/LibraryStore.kt`

```kotlin
data class Book(
    val id: String, val title: String, val path: String,
    val chapter: Int = 0, val charOffset: Int = 0, val lastReadAt: Long = 0L
)

class LibraryStore(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val dir = java.io.File(ctx.filesDir, "books").apply { mkdirs() }

    fun all(): List<Book> = read().sortedByDescending { it.lastReadAt }
    fun get(id: String): Book? = read().firstOrNull { it.id == id }

    /** 在 IO 线程调用。 */
    fun importTxt(uri: android.net.Uri): Book {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "未命名.txt"
        val id = java.util.UUID.randomUUID().toString()
        val file = java.io.File(dir, "$id.txt")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val book = Book(id, name.removeSuffix(".txt").removeSuffix(".TXT"), file.absolutePath, lastReadAt = System.currentTimeMillis())
        write(read() + book)
        return book
    }

    fun saveProgress(id: String, chapter: Int, charOffset: Int) {
        write(read().map {
            if (it.id == id) it.copy(chapter = chapter, charOffset = charOffset, lastReadAt = System.currentTimeMillis()) else it
        })
    }

    fun remove(id: String) {
        get(id)?.let { java.io.File(it.path).delete() }
        write(read().filter { it.id != id })
    }

    private fun read(): List<Book> {
        val arr = org.json.JSONArray(sp.getString("books", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Book(o.getString("id"), o.getString("title"), o.getString("path"),
                o.optInt("chapter"), o.optInt("charOffset"), o.optLong("lastReadAt"))
        }
    }

    private fun write(list: List<Book>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject()
                .put("id", it.id).put("title", it.title).put("path", it.path)
                .put("chapter", it.chapter).put("charOffset", it.charOffset).put("lastReadAt", it.lastReadAt))
        }
        sp.edit().putString("books", arr.toString()).apply()
    }
}
```

### 9.4 `data/ReaderPrefs.kt`

```kotlin
class ReaderPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val dm = ctx.resources.displayMetrics

    var textSizeSp: Int get() = sp.getInt("text_size_sp", 20); set(v) = sp.edit().putInt("text_size_sp", v.coerceIn(14, 40)).apply()
    var lineSpacingX10: Int get() = sp.getInt("line_spacing_x10", 15); set(v) = sp.edit().putInt("line_spacing_x10", v.coerceIn(12, 20)).apply()
    var marginDp: Int get() = sp.getInt("margin_dp", 24); set(v) = sp.edit().putInt("margin_dp", v.coerceIn(8, 48)).apply()
    var bold: Boolean get() = sp.getBoolean("bold", false); set(v) = sp.edit().putBoolean("bold", v).apply()
    var antiAlias: Boolean get() = sp.getBoolean("anti_alias", true); set(v) = sp.edit().putBoolean("anti_alias", v).apply()
    var cleanEveryNPages: Int get() = sp.getInt("clean_every_n_pages", 30); set(v) = sp.edit().putInt("clean_every_n_pages", v).apply()
    var minTurnIntervalMs: Int get() = sp.getInt("min_turn_interval_ms", 250); set(v) = sp.edit().putInt("min_turn_interval_ms", v.coerceIn(150, 600)).apply()
    var swapTapZones: Boolean get() = sp.getBoolean("swap_tap_zones", false); set(v) = sp.edit().putBoolean("swap_tap_zones", v).apply()

    fun style() = ReaderStyle(
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat(), dm),
        lineSpacingMult = lineSpacingX10 / 10f,
        marginPx = (marginDp * dm.density).toInt(),
        antiAlias = antiAlias,
        weight = if (bold) 500 else 400
    )
}
```

### 9.5 `reader/ReaderStyle.kt` 与 `Paginator.kt`

```kotlin
data class ReaderStyle(
    val textSizePx: Float,
    val lineSpacingMult: Float,
    val marginPx: Int,
    val antiAlias: Boolean,
    val weight: Int = 400
) {
    fun newPaint() = TextPaint().apply {
        color = Color.BLACK
        textSize = textSizePx
        isAntiAlias = antiAlias
        typeface = when {
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(Typeface.DEFAULT, weight, false)
            weight >= 600 -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }
}

data class PageSlice(val start: Int, val end: Int)

fun buildLayout(text: CharSequence, start: Int, end: Int, paint: TextPaint, width: Int, mult: Float): StaticLayout =
    StaticLayout.Builder.obtain(text, start, end, paint, width)
        .setLineSpacing(0f, mult)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()

class Paginator(private val style: ReaderStyle, private val viewW: Int, private val viewH: Int) {
    fun paginate(text: CharSequence): List<PageSlice> {
        if (text.isEmpty()) return listOf(PageSlice(0, 0))
        val contentW = viewW - style.marginPx * 2
        val contentH = viewH - style.marginPx * 2
        val layout = buildLayout(text, 0, text.length, style.newPaint(), contentW, style.lineSpacingMult)

        val pages = ArrayList<PageSlice>()
        var startLine = 0
        var pageTop = layout.getLineTop(0)
        for (i in 0 until layout.lineCount) {
            if (i > startLine && layout.getLineBottom(i) - pageTop > contentH) {
                pages += PageSlice(layout.getLineStart(startLine), layout.getLineStart(i))
                startLine = i
                pageTop = layout.getLineTop(i)
            }
        }
        pages += PageSlice(layout.getLineStart(startLine), text.length)
        return pages
    }
}
```

### 9.6 `reader/PageRenderer.kt`

```kotlin
class PageRenderer(
    private val text: CharSequence,
    private val style: ReaderStyle,
    private val viewW: Int,
    private val viewH: Int
) {
    private val paint = style.newPaint()
    private val cache = LruCache<Int, Bitmap>(3)   // 当前页 + 前后各一页

    fun render(index: Int, slice: PageSlice): Bitmap =
        cache.get(index) ?: draw(slice).also { cache.put(index, it) }

    private fun draw(s: PageSlice): Bitmap {
        val bmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val layout = buildLayout(text, s.start, s.end, paint, viewW - style.marginPx * 2, style.lineSpacingMult)
        c.translate(style.marginPx.toFloat(), style.marginPx.toFloat())
        layout.draw(c)
        return bmp
    }
}
```

### 9.7 `reader/ReaderEngine.kt`(阅读核心,无 UI 依赖)

```kotlin
class ReaderEngine(private val source: BookSource, private val bookId: String) {

    data class Frame(
        val bitmap: Bitmap, val title: String,
        val chapter: Int, val chapterCount: Int,
        val pageIndex: Int, val pageCount: Int,
        val charOffset: Int
    )

    private var w = 0
    private var h = 0
    private lateinit var style: ReaderStyle
    private var chapterCount = 0
    private var chapter = 0
    private var title = ""
    private var pages: List<PageSlice> = emptyList()
    private var renderer: PageRenderer? = null
    private var index = 0

    suspend fun open(w: Int, h: Int, style: ReaderStyle, chapter: Int, charOffset: Int): Frame {
        this.w = w; this.h = h; this.style = style
        chapterCount = source.chapterCount(bookId)
        loadChapter(chapter.coerceIn(0, chapterCount - 1), charOffset)
        return frame()
    }

    /** 字号/行距/边距变化后重新排版,保持当前阅读位置。 */
    suspend fun relayout(newStyle: ReaderStyle): Frame {
        val offset = pages[index].start
        style = newStyle
        loadChapter(chapter, offset)
        return frame()
    }

    suspend fun jump(chapter: Int): Frame {
        loadChapter(chapter.coerceIn(0, chapterCount - 1), 0)
        return frame()
    }

    /** 返回 null 表示已到全书末尾。 */
    suspend fun next(): Frame? {
        when {
            index + 1 < pages.size -> index++
            chapter + 1 < chapterCount -> loadChapter(chapter + 1, 0)
            else -> return null
        }
        return frame()
    }

    /** 返回 null 表示已在全书第一页。 */
    suspend fun prev(): Frame? {
        when {
            index > 0 -> index--
            chapter > 0 -> loadChapter(chapter - 1, Int.MAX_VALUE)   // 落在上一章最后一页
            else -> return null
        }
        return frame()
    }

    /** 显示完当前页后调用,预渲染前后页。 */
    suspend fun prefetch() = withContext(Dispatchers.Default) {
        val r = renderer ?: return@withContext
        listOf(index + 1, index - 1).filter { it in pages.indices }.forEach { r.render(it, pages[it]) }
    }

    private suspend fun loadChapter(c: Int, charOffset: Int) {
        val text = source.chapterText(bookId, c)
        val t = source.chapterTitle(bookId, c)
        val p = withContext(Dispatchers.Default) { Paginator(style, w, h).paginate(text) }
        chapter = c; title = t; pages = p
        renderer = PageRenderer(text, style, w, h)
        index = p.indexOfLast { it.start <= charOffset }.coerceAtLeast(0)
    }

    private suspend fun frame(): Frame = withContext(Dispatchers.Default) {
        val bmp = renderer!!.render(index, pages[index])
        Frame(bmp, title, chapter, chapterCount, index, pages.size, pages[index].start)
    }
}
```

### 9.8 `reader/PageView.kt`

```kotlin
class PageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Zone { LEFT, CENTER, RIGHT }

    var onTapZone: ((Zone) -> Unit)? = null
    var swapZones = false

    private var page: Bitmap? = null
    private var flashColor: Int? = null
    private val flashPaint = Paint()

    init { isClickable = true; background = null }

    fun show(bitmap: Bitmap) { page = bitmap; flashColor = null; invalidate() }

    /** 在整个视图(=文字区)内闪一帧纯色,用于局部清影。 */
    fun flash(color: Int) { flashColor = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        flashColor?.let {
            flashPaint.color = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            return
        }
        page?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            var zone = when {
                e.x < width / 3f -> Zone.LEFT
                e.x > width * 2f / 3f -> Zone.RIGHT
                else -> Zone.CENTER
            }
            if (swapZones) zone = when (zone) { Zone.LEFT -> Zone.RIGHT; Zone.RIGHT -> Zone.LEFT; else -> zone }
            onTapZone?.invoke(zone)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
```

### 9.9 `reader/TurnThrottle.kt` 与 `eink/GhostPolicy.kt`

```kotlin
class TurnThrottle(private val minIntervalMs: () -> Int) {
    private var last = 0L
    fun tryAcquire(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - last < minIntervalMs()) return false
        last = now
        return true
    }
}

class GhostPolicy(private var everyNPages: () -> Int) {
    private var counter = 0
    /** 每次翻页调用;返回 true 表示这次需要做局部清影。0 表示从不。 */
    fun onPageTurned(): Boolean {
        val n = everyNPages()
        if (n <= 0) return false
        if (++counter >= n) { counter = 0; return true }
        return false
    }
}
```

### 9.10 `eink/RefreshController.kt`

```kotlin
interface RefreshController {
    val name: String
    fun showPage(view: PageView, bitmap: Bitmap)
    /** 局部清影:黑 → 白 → 内容,仅限文字区。 */
    fun cleanRegion(view: PageView, bitmap: Bitmap)
    fun onReaderEnter(activity: Activity) {}
    fun onReaderExit(activity: Activity) {}
}

/** Tier 0:通用 View 层。 */
class DefaultRefreshController : RefreshController {
    override val name = "default"
    private val handler = Handler(Looper.getMainLooper())

    override fun showPage(view: PageView, bitmap: Bitmap) {
        handler.removeCallbacksAndMessages(null)
        view.show(bitmap)
    }

    override fun cleanRegion(view: PageView, bitmap: Bitmap) {
        handler.removeCallbacksAndMessages(null)
        view.flash(Color.BLACK)
        handler.postDelayed({ view.flash(Color.WHITE) }, 120)
        handler.postDelayed({ view.show(bitmap) }, 240)
    }
}

object RefreshControllers {
    /** 默认返回 Tier 0。Tier 2/3 经实验验证有效后,在此按设置切换。 */
    fun create(ctx: Context): RefreshController = DefaultRefreshController()
}
```

### 9.11 `eink/EpdSettings.kt`(Tier 1 只读)

```kotlin
object EpdSettings {
    val KEYS = listOf(
        "current_epd_mode", "ink_mode", "dynamic_refresh_mode", "eink_automatic_clearance_key",
        "hmct_epd_bleach_mode", "hmct_epd_boldsize", "hmct_epd_darkthreshold"
    )
    val PROPS = listOf("sys.hmct.epd_mode", "persist.sys.epd.black", "persist.sys.epd.white", "persist.sys.epd.contrast")

    data class Item(val key: String, val namespace: String?, val value: String?)

    /** 依次在 system / global / secure 三个命名空间里找键。 */
    fun read(ctx: Context): List<Item> {
        val cr = ctx.contentResolver
        return KEYS.map { k ->
            val finders = listOf<Pair<String, () -> String?>>(
                "system" to { Settings.System.getString(cr, k) },
                "global" to { Settings.Global.getString(cr, k) },
                "secure" to { Settings.Secure.getString(cr, k) }
            )
            for ((ns, f) in finders) {
                val v = try { f() } catch (e: Throwable) { null }
                if (v != null) return@map Item(k, ns, v)
            }
            Item(k, null, null)
        }
    }

    /** 反射读取系统属性;被限制时返回 null。 */
    fun prop(key: String): String? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    } catch (e: Throwable) { null }
}
```

### 9.12 `JingyeApp.kt`

```kotlin
class JingyeApp : android.app.Application() {
    lateinit var library: LibraryStore
    lateinit var source: BookSource
    lateinit var prefs: ReaderPrefs

    override fun onCreate() {
        super.onCreate()
        library = LibraryStore(this)
        source = LocalTxtSource(library)
        prefs = ReaderPrefs(this)
    }
}
```

### 9.13 阅读页:`res/layout/activity_reader.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="#FFFFFF">

    <LinearLayout android:layout_width="match_parent" android:layout_height="match_parent"
        android:orientation="vertical">

        <app.jingye.reader.reader.PageView
            android:id="@+id/pageView"
            android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" />

        <TextView android:id="@+id/footer"
            android:layout_width="match_parent" android:layout_height="28dp"
            android:gravity="center_vertical" android:paddingHorizontal="24dp"
            android:textColor="#000000" android:textSize="12sp" />
    </LinearLayout>

    <!-- 菜单面板:visibility 瞬时显隐,不做动画 -->
    <LinearLayout android:id="@+id/menu"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_gravity="bottom" android:layout_marginBottom="28dp"
        android:background="@drawable/ink_panel_bg" android:orientation="vertical"
        android:padding="8dp" android:visibility="gone">

        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal">
            <Button android:id="@+id/btnToc" style="@style/InkButton" android:text="目录"/>
            <Button android:id="@+id/btnSmaller" style="@style/InkButton" android:text="A−"/>
            <TextView android:id="@+id/sizeLabel" android:layout_width="48dp" android:layout_height="48dp"
                android:gravity="center" android:textColor="#000000" android:textSize="16sp"/>
            <Button android:id="@+id/btnBigger" style="@style/InkButton" android:text="A+"/>
            <Button android:id="@+id/btnSpacing" style="@style/InkButton" android:text="行距"/>
            <Button android:id="@+id/btnSettings" style="@style/InkButton" android:text="设置"/>
        </LinearLayout>
        <Button android:id="@+id/btnBack" style="@style/InkButton" android:text="返回书架"/>
    </LinearLayout>
</FrameLayout>
```

### 9.14 样式资源

`res/values/themes.xml`

```xml
<resources>
    <style name="Theme.Jingye" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="android:windowAnimationStyle">@null</item>
        <item name="android:windowBackground">@android:color/white</item>
        <item name="android:colorBackground">@android:color/white</item>
        <item name="android:textColorPrimary">@android:color/black</item>
        <item name="android:windowContentTransitions">false</item>
        <item name="android:windowActivityTransitions">false</item>
    </style>

    <style name="InkButton" parent="Widget.AppCompat.Button.Borderless">
        <item name="android:layout_width">wrap_content</item>
        <item name="android:layout_height">48dp</item>
        <item name="android:minWidth">56dp</item>
        <item name="android:background">@drawable/ink_button_bg</item>
        <item name="android:textColor">@color/ink_text</item>
        <item name="android:textSize">16sp</item>
        <item name="android:stateListAnimator">@null</item>
        <item name="android:layout_margin">2dp</item>
    </style>
</resources>
```

`res/drawable/ink_button_bg.xml`(按下反色)

```xml
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape><solid android:color="#000000"/><stroke android:width="2dp" android:color="#000000"/></shape>
    </item>
    <item>
        <shape><solid android:color="#FFFFFF"/><stroke android:width="2dp" android:color="#000000"/></shape>
    </item>
</selector>
```

`res/color/ink_text.xml`

```xml
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true" android:color="#FFFFFF"/>
    <item android:color="#000000"/>
</selector>
```

`res/drawable/ink_panel_bg.xml`

```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#FFFFFF"/>
    <stroke android:width="2dp" android:color="#000000"/>
</shape>
```

### 9.15 `ui/reader/ReaderActivity.kt`

```kotlin
class ReaderActivity : AppCompatActivity() {

    private lateinit var pageView: PageView
    private lateinit var footer: TextView
    private lateinit var menu: View
    private lateinit var sizeLabel: TextView

    private lateinit var app: JingyeApp
    private lateinit var book: Book
    private lateinit var engine: ReaderEngine
    private lateinit var refresh: RefreshController
    private lateinit var ghost: GhostPolicy
    private lateinit var throttle: TurnThrottle

    private var busy = false
    private var lastFrame: ReaderEngine.Frame? = null

    private val tocLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            val c = r.data?.getIntExtra("chapter", -1) ?: -1
            if (c >= 0) run(turn = false) { engine.jump(c) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        hideSystemBars()

        app = application as JingyeApp
        book = app.library.get(intent.getStringExtra("bookId")!!)!!
        engine = ReaderEngine(app.source, book.id)
        refresh = RefreshControllers.create(this)
        ghost = GhostPolicy { app.prefs.cleanEveryNPages }
        throttle = TurnThrottle { app.prefs.minTurnIntervalMs }

        pageView = findViewById(R.id.pageView)
        footer = findViewById(R.id.footer)
        menu = findViewById(R.id.menu)
        sizeLabel = findViewById(R.id.sizeLabel)
        pageView.swapZones = app.prefs.swapTapZones

        pageView.onTapZone = { zone ->
            if (menu.visibility == View.VISIBLE) setMenu(false)
            else when (zone) {
                PageView.Zone.LEFT -> turn(-1)
                PageView.Zone.RIGHT -> turn(+1)
                PageView.Zone.CENTER -> setMenu(true)
            }
        }
        findViewById<View>(R.id.btnToc).setOnClickListener {
            tocLauncher.launch(Intent(this, TocActivity::class.java)
                .putExtra("bookId", book.id).putExtra("chapter", lastFrame?.chapter ?: 0))
        }
        findViewById<View>(R.id.btnSmaller).setOnClickListener { changeSize(-1) }
        findViewById<View>(R.id.btnBigger).setOnClickListener { changeSize(+1) }
        findViewById<View>(R.id.btnSpacing).setOnClickListener { cycleSpacing() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        pageView.post {
            run(turn = false) { engine.open(pageView.width, pageView.height, app.prefs.style(), book.chapter, book.charOffset) }
        }
    }

    override fun onResume() { super.onResume(); refresh.onReaderEnter(this) }

    override fun onPause() {
        super.onPause()
        refresh.onReaderExit(this)
        lastFrame?.let { app.library.saveProgress(book.id, it.chapter, it.charOffset) }  // 系统会清理后台,务必在此保存
    }

    // ---- 翻页与显示 ----

    private fun turn(delta: Int) {
        if (!throttle.tryAcquire()) return
        run(turn = true, boundaryMsg = if (delta > 0) "— 全书完 —" else "— 第一页 —") {
            if (delta > 0) engine.next() else engine.prev()
        }
    }

    private fun run(turn: Boolean, boundaryMsg: String? = null, action: suspend () -> ReaderEngine.Frame?) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val f = action()
                if (f == null) footer.text = boundaryMsg ?: ""
                else show(f, clean = turn && ghost.onPageTurned())
            } finally { busy = false }
        }
    }

    private fun show(f: ReaderEngine.Frame, clean: Boolean) {
        lastFrame = f
        if (clean) refresh.cleanRegion(pageView, f.bitmap) else refresh.showPage(pageView, f.bitmap)
        updateFooter(f)
        sizeLabel.text = app.prefs.textSizeSp.toString()
        lifecycleScope.launch { engine.prefetch() }
    }

    /** 页脚只在翻页时更新,不开定时器。 */
    private fun updateFooter(f: ReaderEngine.Frame) {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        footer.text = "${f.title} · ${f.pageIndex + 1}/${f.pageCount}    $time"
    }

    // ---- 菜单与设置 ----

    private fun setMenu(show: Boolean) { menu.visibility = if (show) View.VISIBLE else View.GONE }

    private fun changeSize(delta: Int) {
        app.prefs.textSizeSp += delta
        run(turn = false) { engine.relayout(app.prefs.style()) }
    }

    private fun cycleSpacing() {
        val steps = listOf(12, 15, 18, 20)
        val cur = app.prefs.lineSpacingX10
        app.prefs.lineSpacingX10 = steps[(steps.indexOfFirst { it >= cur }.coerceAtLeast(0) + 1) % steps.size]
        run(turn = false) { engine.relayout(app.prefs.style()) }
    }

    // ---- 按键 ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> +1
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0) turn(dir)   // 丢弃长按重复,避免连续刷新
        return true
    }

    override fun onBackPressed() {
        if (menu.visibility == View.VISIBLE) setMenu(false) else super.onBackPressed()
    }

    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
```

### 9.16 书架导入流程(`ShelfActivity` 关键片段)

```kotlin
private val picker = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri == null) return@registerForActivityResult
    lifecycleScope.launch {
        runCatching { withContext(Dispatchers.IO) { app.library.importTxt(uri) } }
            .onSuccess { reload() }
            .onFailure { errorLine.text = "导入失败:${it.message}" }   // 静态文字,不用 Toast
    }
}

// 点击【导入】:
picker.launch(arrayOf("text/plain"))

// 列表:
recyclerView.itemAnimator = null
recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
```

行背景使用 `ink_button_bg`(按下反色),行内主/次文字颜色使用 `@color/ink_text`,保证按下时整行反色。

### 9.17 调试页测试图(`EpdDebugActivity` 用)

```kotlin
object TestPatterns {
    fun solid(w: Int, h: Int, color: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { Canvas(it).drawColor(color) }

    fun checker(w: Int, h: Int, cell: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp); val p = Paint()
        for (y in 0 until h step cell) for (x in 0 until w step cell) {
            p.color = if (((x / cell) + (y / cell)) % 2 == 0) Color.BLACK else Color.WHITE
            c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), p)
        }
        return bmp
    }

    fun grayRamp(w: Int, h: Int, steps: Int = 16): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp); val p = Paint(); val bw = w / steps.toFloat()
        for (i in 0 until steps) {
            val g = i * 255 / (steps - 1); p.color = Color.rgb(g, g, g)
            c.drawRect(i * bw, 0f, (i + 1) * bw, h.toFloat(), p)
        }
        return bmp
    }
}
```

---

## 10. 异常与边界情况

| 情况 | 处理 |
|---|---|
| 章节正文为空 | `Paginator` 返回单个空页,正常显示空白页 |
| 单行高于页面高度 | `Paginator` 里 `i > startLine` 保证至少一行入页,不会死循环 |
| TXT 无章节标记 | 按约 12000 字切段,标题"第 n 段" |
| 章节标记之前有内容 | 作为"前言"章保留,不丢失 |
| TXT 为 GBK/GB18030 | UTF-8 严格解码失败后回退 GB18030 |
| 改字号后进度 | 用字符偏移重新定位,不依赖页码 |
| 进程被系统清理 | `onPause` 已保存;重新进入从进度恢复 |
| 翻页过快 | 节流 + `busy` 标志位双保险,丢弃多余输入 |
| 菜单打开时翻页键 | 仍响应翻页;点按则先关闭菜单 |
| 导入大文件 | IO 线程复制;解码与分章在首次打开时的 IO 线程完成,期间页面保持空白(可在页脚显示"加载中…") |

---

## 11. 里程碑与验收标准

| 阶段 | 任务 | 验收标准 |
|---|---|---|
| **M0** 空壳 | 工程、主题、清单、`JingyeApp`、图标 | 在 A7 上启动,无窗口动画,竖屏锁定 |
| **M1** 渲染 | `ReaderStyle/Paginator/PageRenderer/ReaderEngine/PageView` + 硬编码样张文本 | 能正确分页;翻页只触发一次重绘;改字号后位置不丢 |
| **M2** 阅读 | `LocalTxtSource`、`LibraryStore`、`ReaderActivity`、节流、页脚、进度保存 | 导入 GBK 与 UTF-8 的 TXT 都不乱码;退出再进恢复到同一位置;音量键翻页可用 |
| **M3** 完整壳子 | `ShelfActivity`、`TocActivity`、`SettingsActivity`、菜单面板 | 所有设置立即生效;全程无动画、点按反色 |
| **M4** 刷新验证 | `EpdSettings`、`EpdDebugActivity`、实验 E1–E6 | 回填第 3 节实验结论;确定默认清影间隔、抗锯齿、硬件加速 |
| **M5** 刷新定型 | 视实验结果决定是否实现 Tier 2/3,并写入设置页 | 默认设置下连续阅读 30 分钟无明显残影累积 |

后续(壳子稳定之后):EPUB 支持、阅读统计、更多 `BookSource` 实现。

---

## 12. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 系统对小区域重绘自动升级为整屏刷新 | 局部清影失去意义 | 实验 E4 判断;若成立,改为"只用快速模式 + 低频提示用户手动清屏" |
| 系统设置键含义与推测不符 | Tier 1/2 解读错误 | 所有解读标注置信度;只读展示,写入功能默认关闭 |
| 应用无权限控制系统刷新 | Tier 2/3 不可行 | 保底方案 Tier 0 已足够做一个好用的阅读器 |
| 后台被系统清理 | 丢失进度 | `onPause` 保存,不依赖进程存活 |
| 海信护眼/使用限制 | 使用时长受限 | 属系统设置,用户自行配置;文档中提示 |
