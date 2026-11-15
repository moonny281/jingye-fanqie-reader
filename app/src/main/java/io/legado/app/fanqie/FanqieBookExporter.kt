package io.legado.app.fanqie

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 番茄小说原生目录与精排导出引擎：
 * 支持：
 * 1. 导出含【原生分卷与章节目录索引】及结构化排版的精排 TXT（.txt）
 * 2. 导出含【原生 NCX/Nav 多级分卷树状目录】、封面、书籍详情页与墨水屏专用 CSS 排版的标准 EPUB（.epub）
 * 3. 一键重建书架内已下载番茄小说的原生分卷目录（修复旧版本无目录书籍）
 */
object FanqieBookExporter {

    private val ILLEGAL_FILENAME_RE = Regex("[\\\\/:*?\"<>|\\r\\n\\t]+")

    fun sanitizeFileName(raw: String): String {
        val cleaned = ILLEGAL_FILENAME_RE.replace(raw.trim(), "_").trim('.', ' ')
        return cleaned.take(72).ifBlank { "番茄小说" }
    }

    private fun xmlEscape(text: String): String {
        return buildString(text.length + 16) {
            for (ch in text) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
                        append(ch)
                    }
                }
            }
        }
    }

    /**
     * 准备导出的完整书籍元数据、原生分卷目录结构与已缓存章节映射。
     * 若旧版书籍尚未缓存分卷目录结构，会自动联网拉取一次原生分卷目录并同步回本地缓存与书架。
     */
    suspend fun prepareBookData(
        fanqieBookId: String,
        metadataHint: FanqieMetadata? = null
    ): Triple<FanqieMetadata, List<FanqieChapterItem>, LinkedHashMap<String, CachedChapterEntry>> =
        withContext(Dispatchers.IO) {
            val cleanId = FanqieParser.resolveBookId(fanqieBookId)
            val cacheMap = FanqieChapterCacheStore.loadCachedMap(cleanId)
            if (cacheMap.isEmpty()) {
                throw FanqieException("本地暂无已下载章节，请先点击「立即阅读」或「全本后台离线」下载章节后再导出")
            }

            val crawler = FanqieLegadoBridge.newLocalCrawler()
            var meta = FanqieChapterCacheStore.loadMetadata(cleanId) ?: metadataHint

            // 若本地缺少分卷目录结构或章节列表，联网拉取一次原生分卷目录
            if (meta == null || meta.structuredChapters.isEmpty()) {
                meta = try {
                    crawler.fetchMetadata(cleanId, forceRefresh = true)
                } catch (_: Exception) {
                    meta
                }
            }

            val chapters = when {
                meta != null && meta.structuredChapters.isNotEmpty() -> meta.structuredChapters
                else -> {
                    val fetched = try {
                        crawler.fetchChapterList(cleanId)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (fetched.isNotEmpty()) {
                        fetched
                    } else {
                        cacheMap.values.sortedBy { it.index }.map { entry ->
                            FanqieChapterItem(
                                index = entry.index,
                                itemId = entry.key.takeUnless { it.startsWith("idx_") },
                                title = entry.title,
                                locked = false,
                                volumeIndex = entry.volumeIndex,
                                volumeName = entry.volumeName,
                                wordCount = entry.resolvedWordCount()
                            )
                        }
                    }
                }
            }

            // 将原生目录中的章节标题与所属分卷同步回 cacheMap（修复旧版缓存无分卷名的问题）
            val syncedCache = LinkedHashMap<String, CachedChapterEntry>(cacheMap.size)
            val cacheByIndex = cacheMap.values.associateBy { it.index }
            for (ch in chapters) {
                val existing = cacheMap[ch.cacheKey()] ?: cacheByIndex[ch.index] ?: continue
                val paras = existing.resolvedParagraphs()
                val normTitle = FanqieCrawler.normalizeChapterTitle(
                    ch.title.ifBlank { existing.title },
                    ch.index
                )
                val updatedEntry = existing.copy(
                    key = ch.cacheKey(),
                    index = ch.index,
                    title = normTitle,
                    volumeIndex = ch.volumeIndex,
                    volumeName = ch.volumeName.ifBlank { existing.volumeName },
                    paragraphs = paras,
                    formattedText = if (paras.isNotEmpty()) {
                        FanqieCrawler.formatChapter(normTitle, paras)
                    } else {
                        existing.formattedText
                    },
                    wordCount = if (ch.wordCount > 0) ch.wordCount else existing.resolvedWordCount()
                )
                syncedCache[ch.cacheKey()] = updatedEntry
            }
            if (syncedCache.isEmpty()) {
                syncedCache.putAll(cacheMap)
            } else {
                FanqieChapterCacheStore.saveCachedMap(cleanId, syncedCache)
            }

            val resolvedMeta = (meta ?: FanqieMetadata(
                bookId = cleanId,
                title = "番茄小说_$cleanId",
                author = "未知作者",
                score = "暂无",
                coverUrl = "",
                intro = "",
                wordCount = syncedCache.values.sumOf { it.resolvedWordCount() },
                chapterCount = maxOf(chapters.size, syncedCache.size),
                serialStatus = "连载中",
                availableChapters = syncedCache.size,
                pcAvailable = true,
                chapterTitles = chapters.map { it.title },
                latestChapterTitle = chapters.lastOrNull()?.title.orEmpty()
            )).copy(
                structuredChapters = chapters,
                chapterCount = maxOf(chapters.size, syncedCache.size)
            )
            FanqieChapterCacheStore.saveMetadata(resolvedMeta)

            // 同步重建书架内的 TXT 与精确字节偏移目录表，确保阅读器内打开也有完整原生目录
            val downloadedCount = chapters.count { it.cacheKey() in syncedCache }
            val totalCount = maxOf(chapters.size, resolvedMeta.chapterCount, downloadedCount)
            val (internalTxtBytes, builtToc) = FanqieCrawler.buildBookTxtWithToc(
                metadata = resolvedMeta,
                chapters = chapters,
                cacheMap = syncedCache,
                downloaded = downloadedCount,
                total = totalCount,
                failedCount = (totalCount - downloadedCount).coerceAtLeast(0),
                includeTocIndexInText = false
            )
            FanqieLegadoBridge.saveOrUpdateLegadoBook(
                fanqieBookId = cleanId,
                title = resolvedMeta.title,
                author = resolvedMeta.author,
                coverUrl = resolvedMeta.coverUrl,
                intro = resolvedMeta.intro,
                wordCountStr = resolvedMeta.formattedWordCount(),
                serialStatus = resolvedMeta.serialStatus,
                downloadedChapters = downloadedCount,
                remoteTotalChapters = totalCount,
                latestChapterTitle = resolvedMeta.effectiveLatestChapter(),
                isPartial = downloadedCount < totalCount,
                txtBytes = internalTxtBytes,
                builtToc = builtToc
            )

            Triple(resolvedMeta, chapters, syncedCache)
        }

    /**
     * 构建带【原生分卷与章节目录索引】与精排正文的 TXT 导出字节流。
     */
    suspend fun buildExportTxtBytes(
        fanqieBookId: String,
        metadataHint: FanqieMetadata? = null
    ): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val (meta, chapters, cacheMap) = prepareBookData(fanqieBookId, metadataHint)
        val downloaded = chapters.count { it.cacheKey() in cacheMap }
        val total = maxOf(chapters.size, meta.chapterCount, downloaded)
        val (txtBytes, _) = FanqieCrawler.buildBookTxtWithToc(
            metadata = meta,
            chapters = chapters,
            cacheMap = cacheMap,
            downloaded = downloaded,
            total = total,
            failedCount = (total - downloaded).coerceAtLeast(0),
            includeTocIndexInText = true
        )
        val fileName = "${sanitizeFileName("《${meta.title}》作者：${meta.author}")}.txt"
        fileName to txtBytes
    }

    /**
     * 构建含【原生分卷树状目录 (NCX + Nav)】、封面、简介页与墨水屏高对比度 CSS 排版的标准 EPUB 字节流。
     */
    suspend fun buildExportEpubBytes(
        fanqieBookId: String,
        metadataHint: FanqieMetadata? = null
    ): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val (meta, chapters, cacheMap) = prepareBookData(fanqieBookId, metadataHint)

        val availableEntries = ArrayList<Pair<FanqieChapterItem, CachedChapterEntry>>()
        for (ch in chapters) {
            val cached = cacheMap[ch.cacheKey()]
            if (cached != null && (cached.formattedText.isNotBlank() || cached.paragraphs.isNotEmpty())) {
                availableEntries.add(ch to cached)
            }
        }
        if (availableEntries.isEmpty()) {
            throw FanqieException("没有可导出的章节正文")
        }

        // 尝试下载封面图片（超时 5 秒，失败不阻断导出）
        val coverBytes: ByteArray? = if (meta.coverUrl.startsWith("http")) {
            try {
                val conn = (URL(meta.coverUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", FanqieParser.MOBILE_UA)
                }
                val bytes = if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
                conn.disconnect()
                bytes?.takeIf { it.size > 256 }
            } catch (_: Exception) {
                null
            }
        } else null

        val out = ByteArrayOutputStream(512 * 1024)
        ZipOutputStream(out).use { zos ->
            // 1. EPUB 规范要求第一个条目必须是不压缩 (STORED) 的 mimetype
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimeBytes) }
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            fun putUtf8Entry(entryPath: String, content: String) {
                val entry = ZipEntry(entryPath)
                zos.putNextEntry(entry)
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // 2. META-INF/container.xml
            putUtf8Entry(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
            )

            // 3. OEBPS/style.css（专为墨水屏优化的高对比度原生排版样式表）
            putUtf8Entry(
                "OEBPS/style.css",
                """@charset "UTF-8";
body {
  margin: 0;
  padding: 4% 5%;
  color: #000000;
  background-color: #ffffff;
  font-family: "Noto Serif CJK SC", "Songti SC", "SimSun", serif;
  line-height: 1.78;
  text-align: justify;
}
h1.book-title {
  font-size: 1.6em;
  font-weight: bold;
  text-align: center;
  margin: 1.2em 0 0.4em 0;
  text-indent: 0;
}
p.book-meta {
  font-size: 0.92em;
  text-align: center;
  margin: 0.25em 0;
  text-indent: 0;
}
div.book-intro {
  margin-top: 1.4em;
  padding-top: 0.8em;
  border-top: 1px solid #000000;
}
h1.volume-title {
  font-size: 1.5em;
  font-weight: bold;
  text-align: center;
  margin: 3.5em 0 1em 0;
  padding-bottom: 0.5em;
  border-bottom: 2px solid #000000;
  text-indent: 0;
}
h2.chapter-title {
  font-size: 1.28em;
  font-weight: bold;
  text-align: left;
  margin: 1.2em 0 1em 0;
  padding-bottom: 0.35em;
  border-bottom: 1px solid #000000;
  text-indent: 0;
}
h3.sub-title {
  font-size: 1.08em;
  font-weight: bold;
  margin: 1em 0 0.5em 0;
  text-indent: 0;
}
p {
  margin: 0 0 0.65em 0;
  text-indent: 2em;
  line-height: 1.78;
}
p.quote-line {
  margin: 0.4em 1.2em;
  text-indent: 0;
  font-weight: 500;
}
hr.scene-break {
  border: none;
  text-align: center;
  margin: 1.1em 0;
}
hr.scene-break::after {
  content: "* * *";
  letter-spacing: 0.3em;
}
div.author-say {
  margin-top: 1.6em;
  padding-top: 0.7em;
  border-top: 1px dashed #000000;
  font-size: 0.92em;
}
p.author-say-title {
  font-weight: bold;
  text-indent: 0;
  margin-bottom: 0.4em;
}"""
            )

            val manifestItems = ArrayList<String>()
            val spineItems = ArrayList<String>()

            manifestItems += """<item id="css" href="style.css" media-type="text/css"/>"""
            manifestItems += """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
            manifestItems += """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""

            if (coverBytes != null) {
                val imgEntry = ZipEntry("OEBPS/images/cover.jpg")
                zos.putNextEntry(imgEntry)
                zos.write(coverBytes)
                zos.closeEntry()
                manifestItems += """<item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>"""
                putUtf8Entry(
                    "OEBPS/cover.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>封面</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body style="text-align:center;padding:0;margin:0;">
  <div style="text-align:center;"><img src="images/cover.jpg" alt="${xmlEscape(meta.title)}" style="max-width:100%;max-height:98vh;"/></div>
</body>
</html>"""
                )
                manifestItems += """<item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>"""
                spineItems += """<itemref idref="cover-page"/>"""
            }

            // 4. 书籍信息与简介页 intro.xhtml
            val introParasHtml = meta.intro.trim().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "    <p>${xmlEscape(it)}</p>" }
                .ifBlank { "    <p>暂无简介</p>" }

            putUtf8Entry(
                "OEBPS/intro.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <title>${xmlEscape(meta.title)} - 书籍信息</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h1 class="book-title">${xmlEscape(meta.title)}</h1>
  <p class="book-meta">作者：${xmlEscape(meta.author)}</p>
  <p class="book-meta">状态：${xmlEscape(meta.serialStatus)} · 字数：${xmlEscape(meta.formattedWordCount())} · 已收录：${availableEntries.size} 章</p>
  <div class="book-intro">
    <h3 class="sub-title">内容简介</h3>
$introParasHtml
  </div>
</body>
</html>"""
            )
            manifestItems += """<item id="intro" href="intro.xhtml" media-type="application/xhtml+xml"/>"""
            spineItems += """<itemref idref="intro"/>"""

            // 5. 生成分卷与章节 XHTML，并构建多级目录树
            data class EpubChapterNode(
                val id: String,
                val href: String,
                val title: String
            )

            data class EpubVolumeGroup(
                val volumeId: String?,
                val volumeHref: String?,
                val volumeTitle: String,
                val chapters: MutableList<EpubChapterNode> = ArrayList()
            )

            val hasMultipleVolumes = availableEntries.map { (ch, cached) ->
                ch.volumeName.ifBlank { cached.volumeName }.trim()
            }.filter { it.isNotEmpty() }.distinct().size > 1 ||
                availableEntries.any { (ch, cached) ->
                    val vn = ch.volumeName.ifBlank { cached.volumeName }.trim()
                    vn.isNotEmpty() && vn != "正文" && vn != "默认卷" && vn != "第一卷"
                }

            val volumeGroups = ArrayList<EpubVolumeGroup>()
            var curGroup: EpubVolumeGroup? = null
            var volCounter = 0

            for ((seqIdx, pair) in availableEntries.withIndex()) {
                val (ch, cached) = pair
                val rawVol = ch.volumeName.ifBlank { cached.volumeName }.trim()
                val volIdx = if (ch.volumeIndex > 0) ch.volumeIndex else cached.volumeIndex
                val normVol = if (hasMultipleVolumes && rawVol.isNotEmpty()) {
                    FanqieCrawler.normalizeVolumeTitle(rawVol, volIdx)
                } else ""

                if (curGroup == null || (normVol.isNotEmpty() && normVol != curGroup.volumeTitle)) {
                    if (normVol.isNotEmpty()) {
                        volCounter++
                        val volId = String.format(Locale.US, "vol_%02d", volCounter)
                        val volHref = "$volId.xhtml"
                        putUtf8Entry(
                            "OEBPS/$volHref",
                            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <title>${xmlEscape(normVol)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h1 class="volume-title">${xmlEscape(normVol)}</h1>
</body>
</html>"""
                        )
                        manifestItems += """<item id="$volId" href="$volHref" media-type="application/xhtml+xml"/>"""
                        spineItems += """<itemref idref="$volId"/>"""
                        curGroup = EpubVolumeGroup(volId, volHref, normVol)
                    } else {
                        curGroup = EpubVolumeGroup(null, null, "")
                    }
                    volumeGroups.add(curGroup)
                }

                val chNum = seqIdx + 1
                val chId = String.format(Locale.US, "chapter_%04d", chNum)
                val chHref = "$chId.xhtml"
                val cTitle = FanqieCrawler.normalizeChapterTitle(
                    cached.title.ifBlank { ch.title },
                    ch.index
                )
                val paras = cached.resolvedParagraphs()

                val bodyBuilder = StringBuilder()
                var inAuthorSay = false
                for (p in paras) {
                    val trimmed = p.trim()
                    if (trimmed.isEmpty()) continue
                    when {
                        trimmed == FanqieAppApi.SCENE_BREAK_MARKER || trimmed == "* * *" -> {
                            bodyBuilder.append("  <hr class=\"scene-break\"/>\n")
                        }
                        trimmed == "—— 作家的话 ——" -> {
                            if (!inAuthorSay) {
                                inAuthorSay = true
                                bodyBuilder.append("  <div class=\"author-say\">\n")
                                bodyBuilder.append("    <p class=\"author-say-title\">作家的话</p>\n")
                            }
                        }
                        trimmed.startsWith("〖") && trimmed.endsWith("〗") -> {
                            val sub = trimmed.removeSurrounding("〖", "〗")
                            bodyBuilder.append("  <h3 class=\"sub-title\">${xmlEscape(sub)}</h3>\n")
                        }
                        trimmed.startsWith("「") && trimmed.endsWith("」") -> {
                            bodyBuilder.append("  <p class=\"quote-line\">${xmlEscape(trimmed)}</p>\n")
                        }
                        else -> {
                            bodyBuilder.append("  <p>${xmlEscape(trimmed)}</p>\n")
                        }
                    }
                }
                if (inAuthorSay) {
                    bodyBuilder.append("  </div>\n")
                }

                putUtf8Entry(
                    "OEBPS/$chHref",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <title>${xmlEscape(cTitle)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h2 class="chapter-title">${xmlEscape(cTitle)}</h2>
$bodyBuilder</body>
</html>"""
                )
                manifestItems += """<item id="$chId" href="$chHref" media-type="application/xhtml+xml"/>"""
                spineItems += """<itemref idref="$chId"/>"""
                curGroup.chapters.add(EpubChapterNode(chId, chHref, cTitle))
            }

            // 6. 生成 EPUB 3 nav.xhtml 与 EPUB 2 toc.ncx（完整保留分卷嵌套目录树）
            val navSb = StringBuilder()
            val ncxSb = StringBuilder()
            var playOrder = 1

            navSb.append("      <li><a href=\"intro.xhtml\">书籍信息与简介</a></li>\n")
            ncxSb.append(
                """    <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
      <navLabel><text>书籍信息与简介</text></navLabel>
      <content src="intro.xhtml"/>
    </navPoint>
"""
            )
            playOrder++

            for (group in volumeGroups) {
                if (group.volumeHref != null && group.volumeTitle.isNotEmpty()) {
                    navSb.append("      <li>\n")
                    navSb.append("        <a href=\"${group.volumeHref}\">${xmlEscape(group.volumeTitle)}</a>\n")
                    navSb.append("        <ol>\n")

                    ncxSb.append(
                        """    <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
      <navLabel><text>${xmlEscape(group.volumeTitle)}</text></navLabel>
      <content src="${group.volumeHref}"/>
"""
                    )
                    playOrder++

                    for (chNode in group.chapters) {
                        navSb.append("          <li><a href=\"${chNode.href}\">${xmlEscape(chNode.title)}</a></li>\n")
                        ncxSb.append(
                            """      <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
        <navLabel><text>${xmlEscape(chNode.title)}</text></navLabel>
        <content src="${chNode.href}"/>
      </navPoint>
"""
                        )
                        playOrder++
                    }

                    navSb.append("        </ol>\n")
                    navSb.append("      </li>\n")
                    ncxSb.append("    </navPoint>\n")
                } else {
                    for (chNode in group.chapters) {
                        navSb.append("      <li><a href=\"${chNode.href}\">${xmlEscape(chNode.title)}</a></li>\n")
                        ncxSb.append(
                            """    <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
      <navLabel><text>${xmlEscape(chNode.title)}</text></navLabel>
      <content src="${chNode.href}"/>
    </navPoint>
"""
                        )
                        playOrder++
                    }
                }
            }

            putUtf8Entry(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN">
<head>
  <title>目录</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h2 class="chapter-title">目录</h2>
    <ol>
$navSb    </ol>
  </nav>
</body>
</html>"""
            )

            val bookUuid = "urn:uuid:${UUID.nameUUIDFromBytes("fanqie_${meta.bookId}".toByteArray())}"
            putUtf8Entry(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$bookUuid"/>
    <meta name="dtb:depth" content="${if (hasMultipleVolumes) 2 else 1}"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${xmlEscape(meta.title)}</text></docTitle>
  <docAuthor><text>${xmlEscape(meta.author)}</text></docAuthor>
  <navMap>
$ncxSb  </navMap>
</ncx>"""
            )

            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val coverMetaXml = if (coverBytes != null) """<meta name="cover" content="cover-image"/>""" else ""
            putUtf8Entry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="BookId">$bookUuid</dc:identifier>
    <dc:title>${xmlEscape(meta.title)}</dc:title>
    <dc:creator>${xmlEscape(meta.author)}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:description>${xmlEscape(meta.intro)}</dc:description>
    <dc:date>$dateStr</dc:date>
    $coverMetaXml
  </metadata>
  <manifest>
    ${manifestItems.joinToString("\n    ")}
  </manifest>
  <spine toc="ncx">
    ${spineItems.joinToString("\n    ")}
  </spine>
</package>"""
            )
        }

        val fileName = "${sanitizeFileName("《${meta.title}》作者：${meta.author}")}.epub"
        fileName to out.toByteArray()
    }

    /**
     * 将导出的小说文件直接写入系统公共 `Download/FanqieNovels/` 目录，返回用户可读的保存路径说明。
     */
    fun saveToPublicDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val relPath = "${Environment.DIRECTORY_DOWNLOADS}/FanqieNovels"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return "Download/FanqieNovels/$fileName"
                }
            } catch (_: Exception) {
            }
        }

        // Android 9 及以下或 MediaStore 不可用时的文件路径兜底
        return try {
            @Suppress("DEPRECATION")
            val pubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "FanqieNovels"
            )
            if (!pubDir.exists()) pubDir.mkdirs()
            val target = File(pubDir, fileName)
            target.writeBytes(bytes)
            target.absolutePath
        } catch (_: Exception) {
            val fallbackDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                "FanqieNovels"
            )
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val target = File(fallbackDir, fileName)
            target.writeBytes(bytes)
            target.absolutePath
        }
    }

    /**
     * 将导出的字节流写入用户通过 SAF 文件选择器指定的 Uri。
     */
    fun saveToDocumentUri(context: Context, targetUri: Uri, bytes: ByteArray) {
        val os = context.contentResolver.openOutputStream(targetUri)
            ?: throw FanqieException("无法打开目标文件输出流")
        os.use { it.write(bytes) }
    }
}
