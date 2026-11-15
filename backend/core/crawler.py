# -*- coding: utf-8 -*-
"""章节抓取调度器（支持原生分卷目录还原与结构化排版导出）。"""

from __future__ import annotations

import html as html_lib
import os
import random
import re
import sys
import time
from pathlib import Path
from typing import Callable, Iterable, Optional

import requests

try:  # 作为包导入（app.py / python -m core.crawler）
    from .appapi import AppApiError, FanqieAppApi, parse_chapter_xhtml
    from .decryptor import decrypt_text, remaining_pua
    from .parser import ParseError, resolve_book_id
except ImportError:  # 兼容 `python core/crawler.py` 直接运行自测
    import pathlib as _pathlib

    _sys_path = str(_pathlib.Path(__file__).resolve().parent.parent)
    if _sys_path not in sys.path:
        sys.path.insert(0, _sys_path)
    from core.appapi import AppApiError, FanqieAppApi, parse_chapter_xhtml
    from core.decryptor import decrypt_text, remaining_pua
    from core.parser import ParseError, resolve_book_id

# --------------------------------------------------------------------------- #
# 常量
# --------------------------------------------------------------------------- #

LANDING_API = "https://api.fqnovel.com/novel_ug/share/landing_page"
DIRECTORY_API = "https://fanqienovel.com/api/reader/directory/detail"
APP_DIRECTORY_API = "https://api.fqnovel.com/reading/bookapi/directory/all_items/v/"
READER_PAGE = "https://fanqienovel.com/reader/{item_id}"
PC_PAGE = "https://fanqienovel.com/page/{book_id}"

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 "
    "Mobile/15E148 Safari/604.1"
)
DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DOWNLOAD_DIR = PROJECT_ROOT / "downloads"

# Windows 文件名非法字符
_ILLEGAL_FILENAME_RE = re.compile(r'[\\/:*?"<>|\r\n\t]+')
_TAG_RE = re.compile(r"<[^>]+>")
_P_RE = re.compile(r"<p(?:\s[^>]*)?>(.*?)</p>", re.IGNORECASE | re.DOTALL)
_WS_RE = re.compile(r"[ \t\r\f\v\u00a0]+")
_ZERO_WIDTH_RE = re.compile(r"[\u200b\u200c\u200d\u2060\ufeff]")

ProgressCallback = Callable[[int, int, str], None]


class CrawlerError(Exception):
    """抓取过程中的可展示错误。"""


class ChapterUnavailable(CrawlerError):
    """单章不可抓取（App 独占、付费锁定、已下架等）。"""


class CaptchaError(CrawlerError):
    """触发了番茄的风控验证码中间页，需要冷却后再试。"""


_CAPTCHA_BYTES = (
    "验证码中间页".encode("utf-8"),
    b"middle_page_loading",
    b"sec_sdk_build",
)


def _is_captcha_page(content: bytes) -> bool:
    """判断响应是否为风控验证码中间页。"""
    if not content or len(content) > 80_000:
        return False
    if _CAPTCHA_BYTES[0] in content:
        return True
    lowered = content.lower()
    hits = sum(1 for marker in _CAPTCHA_BYTES[1:] if marker in lowered)
    return hits >= 2 and b"muye-reader-content" not in content


# --------------------------------------------------------------------------- #
# HTML 工具与原生排版还原
# --------------------------------------------------------------------------- #

def _strip_tags(fragment: str) -> str:
    fragment = re.sub(r"<br\s*/?>", "\n", fragment, flags=re.IGNORECASE)
    fragment = _TAG_RE.sub("", fragment)
    return html_lib.unescape(fragment)


def normalize_chapter_title(raw_title: str, fallback_index: int) -> str:
    cleaned = _ZERO_WIDTH_RE.sub("", raw_title or "")
    cleaned = _WS_RE.sub(" ", cleaned.replace("\r", " ").replace("\n", " ")).strip()
    return cleaned or f"第{fallback_index}章"


def normalize_volume_title(raw_volume: str, volume_index: int) -> str:
    cleaned = _ZERO_WIDTH_RE.sub("", raw_volume or "")
    cleaned = _WS_RE.sub(" ", cleaned.replace("\r", " ").replace("\n", " ")).strip()
    if not cleaned:
        return ""
    if cleaned.startswith("第") and any(u in cleaned for u in ("卷", "部", "集", "篇")):
        return cleaned
    return f"第{volume_index + 1}卷 {cleaned}"


def clean_paragraphs(html_fragment: str, expected_title: str = "") -> list[str]:
    """把正文 HTML 片段拆成干净的结构化段落列表（保留场景分割、副标题、引文与作者有话说）。"""
    if not html_fragment:
        return []
    _, structured = parse_chapter_xhtml(html_fragment, expected_title=expected_title)
    if structured:
        return structured

    paragraphs: list[str] = []
    for raw in _P_RE.findall(html_fragment):
        for line in _strip_tags(raw).splitlines():
            text = _ZERO_WIDTH_RE.sub("", line).replace("\u3000", " ").strip()
            text = _WS_RE.sub(" ", text).strip()
            if text:
                paragraphs.append(text)
    if paragraphs:
        return paragraphs

    plain = _strip_tags(html_fragment)
    for line in plain.splitlines():
        line = _ZERO_WIDTH_RE.sub("", line).replace("\u3000", " ").strip()
        if line:
            paragraphs.append(line)
    return paragraphs


def extract_reader_paragraphs(page_html: str, expected_title: str = "") -> list[str]:
    """从 PC reader 页面 HTML 中提取正文段落。"""
    if not page_html:
        return []
    marker = "muye-reader-content noselect"
    index = page_html.find(marker)
    if index < 0:
        index = page_html.find("muye-reader-content")
    if index < 0:
        return []

    segment = page_html[index:index + 600_000]
    end = segment.find("</div></div>")
    if end > 0:
        segment = segment[:end + len("</div>")]
    return clean_paragraphs(segment, expected_title=expected_title)


# --------------------------------------------------------------------------- #
# 抓取器
# --------------------------------------------------------------------------- #

class FanqieCrawler:
    """番茄小说抓取调度器（支持原生分卷目录抓取与精排导出）。"""

    def __init__(self, timeout: int = 20, retries: int = 3, delay: float = 0.3,
                 use_app_api: bool = True, app_api_interval: float = 6.0,
                 app_api: Optional[FanqieAppApi] = None):
        self.timeout = timeout
        self.retries = max(1, retries)
        self.delay = max(0.0, delay)
        self.use_app_api = bool(use_app_api)
        self.app_api_interval = max(0.0, float(app_api_interval))
        self._app_api: Optional[FanqieAppApi] = app_api
        self._app_api_error: Optional[str] = None
        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": DESKTOP_UA,
            "Referer": "https://fanqienovel.com/",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
            "Accept": ("text/html,application/xhtml+xml,application/xml;q=0.9,"
                       "image/avif,image/webp,*/*;q=0.8"),
        })
        cookie = (os.environ.get("FANQIE_COOKIE") or "").strip()
        if cookie:
            self.session.headers["Cookie"] = cookie
        self._book_cache: dict[str, dict] = {}
        self._pc_available_cache: dict[str, bool] = {}

    def _request(self, method: str, url: str, *, mobile: bool = False,
                 **kwargs) -> requests.Response:
        headers = dict(kwargs.pop("headers", {}) or {})
        headers.setdefault("User-Agent", MOBILE_UA if mobile else DESKTOP_UA)
        if mobile:
            headers.setdefault("Referer", "https://changdunovel.com/")
        last_error: Optional[Exception] = None
        for attempt in range(self.retries):
            try:
                response = self.session.request(
                    method, url, timeout=self.timeout, headers=headers, **kwargs)
                if _is_captcha_page(response.content):
                    raise CaptchaError("触发番茄风控验证码，请稍后重试")
                if response.status_code in (429, 500, 502, 503, 504):
                    last_error = CrawlerError(
                        f"服务端返回 {response.status_code}")
                    time.sleep(0.6 * (attempt + 1))
                    continue
                return response
            except CaptchaError:
                raise
            except requests.exceptions.RequestException as exc:
                last_error = exc
                time.sleep(0.5 * (attempt + 1))
        raise CrawlerError(f"网络请求失败：{last_error}") from None

    def _get_json(self, url: str, *, mobile: bool = False, **kwargs) -> dict:
        response = self._request("GET", url, mobile=mobile, **kwargs)
        try:
            return response.json()
        except ValueError:
            raise CrawlerError("接口返回了非 JSON 数据，可能触发了风控") from None

    def _get_app_api(self) -> FanqieAppApi:
        if self._app_api is None:
            self._app_api = FanqieAppApi(timeout=max(20, self.timeout),
                                         interval=self.app_api_interval)
        return self._app_api

    def _fetch_landing_data(self, book_id: str) -> dict:
        if book_id in self._book_cache:
            return self._book_cache[book_id]
        params = {
            "aid": 1967,
            "series_id": 0,
            "performance_optimization": 1,
            "share_type": 11,
            "book_id": book_id,
        }
        payload = self._get_json(LANDING_API, mobile=True, params=params)
        err_no = payload.get("err_no")
        if err_no not in (None, 0):
            raise CrawlerError(
                f"书籍信息获取失败（err_no={err_no}）：{payload.get('err_tips') or ''}")
        book_data = (payload.get("data") or {}).get("book_data")
        if not book_data or not book_data.get("title"):
            raise CrawlerError("没有找到这本书，可能已下架或 book_id 不正确")
        self._book_cache[book_id] = book_data
        return book_data

    def check_pc_available(self, book_id: str) -> bool:
        if book_id in self._pc_available_cache:
            return self._pc_available_cache[book_id]
        available = False
        try:
            response = self._request("GET", PC_PAGE.format(book_id=book_id))
            available = (response.status_code == 200
                         and "chapter-item" in response.text)
        except CrawlerError:
            available = False
        self._pc_available_cache[book_id] = available
        return available

    def fetch_metadata(self, book_id: str) -> dict:
        book_data = self._fetch_landing_data(book_id)
        author = book_data.get("author") or {}
        try:
            chapter_count = int(book_data.get("serial_count") or 0)
        except (TypeError, ValueError):
            chapter_count = 0
        try:
            word_count = int(book_data.get("word_count") or 0)
        except (TypeError, ValueError):
            word_count = 0
        creation_status = book_data.get("creation_status")
        serial_status = {0: "已完结", 1: "连载中"}.get(creation_status, "未知状态")
        first_chapters = book_data.get("first_chapters_content") or []
        chapters = self.fetch_chapter_list(book_id)
        chapter_count = max(chapter_count, len(chapters))
        available_chapters = chapter_count if (self.use_app_api and chapter_count) \
            else len(first_chapters)
        chapter_titles = [c["title"] for c in chapters] if chapters else list(book_data.get("item_names") or [])
        return {
            "book_id": book_id,
            "title": book_data.get("title") or "未知书名",
            "author": author.get("name") or "未知作者",
            "score": str(book_data.get("score") or "暂无"),
            "cover_url": book_data.get("cover_url") or "",
            "intro": (book_data.get("intro") or "").strip(),
            "word_count": word_count,
            "chapter_count": chapter_count,
            "serial_status": serial_status,
            "available_chapters": available_chapters,
            "pc_available": self.check_pc_available(book_id),
            "chapter_titles": chapter_titles,
            "category_tags": list(book_data.get("category_tags") or []),
            "reading_count_text": book_data.get("reading_count_text") or "",
        }

    def fetch_chapter_list(self, book_id: str) -> list[dict]:
        """获取全书原生分卷与章节列表，元素：``{index, item_id, title, locked, volume_index, volume_name, word_count}``。"""
        try:
            payload = self._get_json(DIRECTORY_API, params={"bookId": book_id})
        except CrawlerError:
            payload = {}

        data = payload.get("data") or {}
        vol_names = data.get("volumeNameList") or []
        chapters: list[dict] = []
        for v_idx, volume in enumerate(data.get("chapterListWithVolume") or []):
            vol_name = (vol_names[v_idx] if v_idx < len(vol_names) else "") or ""
            vol_name = str(vol_name).strip()
            for item in volume or []:
                item_id = str(item.get("itemId") or item.get("item_id") or "")
                if not item_id:
                    continue
                item_vol = str(item.get("volume_name") or "").strip() or vol_name
                c_idx = len(chapters) + 1
                chapters.append({
                    "index": c_idx,
                    "item_id": item_id,
                    "title": normalize_chapter_title(item.get("title") or "", c_idx),
                    "locked": bool(item.get("isChapterLock")
                                   or item.get("needPay")
                                   or item.get("isPaidPublication")),
                    "volume_index": v_idx,
                    "volume_name": item_vol,
                    "word_count": int(item.get("chapterWordNumber") or item.get("wordCount") or 0),
                })

        # APP 原生目录接口兜底
        if not chapters:
            try:
                app_dir = self._get_json(
                    APP_DIRECTORY_API,
                    mobile=True,
                    params={"book_id": book_id, "need_version": "true", "aid": 1967},
                )
                item_list = (app_dir.get("data") or {}).get("item_data_list") or []
                vol_map: dict[str, int] = {}
                for item in item_list:
                    item_id = str(item.get("item_id") or item.get("itemId") or "")
                    if not item_id:
                        continue
                    v_name = str(item.get("volume_name") or "").strip()
                    v_idx = vol_map.setdefault(v_name, len(vol_map)) if v_name else 0
                    c_idx = len(chapters) + 1
                    chapters.append({
                        "index": c_idx,
                        "item_id": item_id,
                        "title": normalize_chapter_title(item.get("title") or "", c_idx),
                        "locked": False,
                        "volume_index": v_idx,
                        "volume_name": v_name,
                        "word_count": int(item.get("chapter_word_number") or 0),
                    })
            except Exception:
                pass

        if not chapters:
            all_ids = data.get("allItemIds") or []
            landing = self._book_cache.get(book_id) or {}
            titles = landing.get("item_names") or []
            for i, item_id in enumerate(all_ids):
                raw_t = titles[i] if i < len(titles) else f"第{i + 1}章"
                chapters.append({
                    "index": i + 1,
                    "item_id": str(item_id),
                    "title": normalize_chapter_title(raw_t, i + 1),
                    "locked": False,
                    "volume_index": 0,
                    "volume_name": "",
                    "word_count": 0,
                })

        if not chapters:
            landing = self._fetch_landing_data(book_id)
            for i, title in enumerate(landing.get("item_names") or []):
                chapters.append({
                    "index": i + 1,
                    "item_id": None,
                    "title": normalize_chapter_title(title, i + 1),
                    "locked": False,
                    "volume_index": 0,
                    "volume_name": "",
                    "word_count": 0,
                })
        return chapters

    def fetch_chapter_text(self, item_id: str, expected_title: str = "") -> str:
        if not item_id:
            raise ChapterUnavailable("缺少章节 item_id")
        url = READER_PAGE.format(item_id=item_id)
        response = self._request("GET", url)
        if response.status_code == 404:
            raise ChapterUnavailable("PC 端未上架（可能为 App 独占章节）")
        if response.status_code != 200:
            raise CrawlerError(f"章节页返回 {response.status_code}")

        if ("muye-to-vip" in response.text
                or "扫码下载APP免费读" in response.text
                or "会员登录后" in response.text):
            raise ChapterUnavailable("会员/付费章节，免登录模式无法获取全文")

        paragraphs = extract_reader_paragraphs(response.text, expected_title=expected_title)
        if not paragraphs:
            if "no-content" in response.text:
                raise ChapterUnavailable("PC 端暂无正文（App 独占）")
            raise CrawlerError("章节正文为空")

        plain = decrypt_text("\n".join(paragraphs))
        leftover = remaining_pua(plain)
        if leftover:
            raise CrawlerError(
                f"字体映射未覆盖 {len(leftover)} 个字符，可能是字体已更新")
        return plain

    def download_book(self, book_id: str,
                      progress_callback: Optional[ProgressCallback] = None,
                      output_dir: Optional[str | os.PathLike] = None) -> dict:
        metadata = self.fetch_metadata(book_id)
        title = metadata["title"]
        book_data = self._fetch_landing_data(book_id)
        landing_html = book_data.get("first_chapters_content") or []
        chapters = self.fetch_chapter_list(book_id)
        landing_chapters = [
            clean_paragraphs(fragment, expected_title=(chapters[i]["title"] if i < len(chapters) else ""))
            for i, fragment in enumerate(landing_html)
        ]
        landing_available = len(landing_chapters)

        total = max(len(chapters), landing_available, metadata["chapter_count"], 1)
        if len(chapters) < total:
            titles = book_data.get("item_names") or []
            for i in range(len(chapters), total):
                raw_t = titles[i] if i < len(titles) else f"第{i + 1}章"
                chapters.append({
                    "index": i + 1,
                    "item_id": None,
                    "title": normalize_chapter_title(raw_t, i + 1),
                    "locked": False,
                    "volume_index": 0,
                    "volume_name": "",
                    "word_count": 0,
                })

        pc_available = metadata["pc_available"]

        if progress_callback:
            try:
                progress_callback(0, total, "正在准备原生分卷目录与排版…")
            except Exception:
                pass

        app_paragraphs: dict[str, list[str]] = {}
        app_titles: dict[str, str] = {}
        app_emitted: set[str] = set()
        app_used = False
        app_error: Optional[str] = None

        if self.use_app_api:
            item_ids = [c["item_id"] for c in chapters if c.get("item_id")]
            chapter_by_id = {c["item_id"]: c for c in chapters if c.get("item_id")}
            if item_ids:
                try:
                    api = self._get_app_api()

                    def on_batch(batch: list[str], got: dict[str, str]) -> None:
                        for item_id in batch:
                            chapter = chapter_by_id.get(item_id)
                            if not chapter or item_id not in got:
                                continue
                            title_text, paragraphs = parse_chapter_xhtml(
                                got[item_id], expected_title=chapter.get("title") or ""
                            )
                            if not paragraphs:
                                continue
                            app_paragraphs[item_id] = paragraphs
                            if title_text:
                                app_titles[item_id] = title_text
                            if progress_callback and item_id not in app_emitted:
                                app_emitted.add(item_id)
                                try:
                                    progress_callback(chapter["index"], total,
                                                      chapter["title"] or title_text)
                                except Exception:
                                    pass

                    raw = api.fetch_chapters(item_ids, book_id, on_batch=on_batch)
                    for item_id, xhtml in raw.items():
                        if item_id in app_paragraphs:
                            continue
                        ch_obj = chapter_by_id.get(item_id) or {}
                        title_text, paragraphs = parse_chapter_xhtml(
                            xhtml, expected_title=ch_obj.get("title") or ""
                        )
                        if paragraphs:
                            app_paragraphs[item_id] = paragraphs
                            if title_text:
                                app_titles[item_id] = title_text
                    app_used = bool(app_paragraphs)
                except AppApiError as exc:
                    app_error = str(exc)
                    self._app_api_error = app_error
                except Exception as exc:
                    app_error = f"{type(exc).__name__}: {exc}"
                    self._app_api_error = app_error

        app_only_skipped = False
        pc_404_streak = 0
        vip_gate_streak = 0
        vip_gate_detected = False
        captcha_hits = 0
        captcha_stopped = False

        downloaded_entries: list[dict] = []
        failed: list[dict] = []
        downloaded = 0
        locked_failed = 0

        for chapter in chapters:
            index = chapter["index"]
            chapter_title = normalize_chapter_title(chapter.get("title") or "", index)
            item_id = chapter.get("item_id")
            paragraphs: list[str] = []
            error: Optional[str] = None

            if item_id and item_id in app_paragraphs:
                paragraphs = app_paragraphs[item_id]
                if not chapter.get("title") and app_titles.get(item_id):
                    chapter_title = normalize_chapter_title(app_titles[item_id], index)
            elif index <= landing_available and landing_chapters[index - 1]:
                paragraphs = landing_chapters[index - 1]
            elif captcha_stopped:
                error = "触发番茄风控验证码，已跳过（请稍后重试）"
            elif app_only_skipped:
                error = "该书为 App 独占，PC 端无正文"
            elif vip_gate_detected and chapter.get("locked"):
                error = "会员/付费章节，免登录模式无法获取全文"
                locked_failed += 1
            elif item_id:
                if not pc_available:
                    pc_404_streak += 1
                    if pc_404_streak >= 2:
                        app_only_skipped = True
                try:
                    text = self.fetch_chapter_text(item_id, expected_title=chapter_title)
                    paragraphs = [line for line in text.split("\n") if line.strip()]
                    pc_404_streak = 0
                    vip_gate_streak = 0
                except ChapterUnavailable as exc:
                    error = str(exc)
                    if "会员" in error or "付费" in error:
                        locked_failed += 1
                        vip_gate_streak += 1
                        if vip_gate_streak >= 3:
                            vip_gate_detected = True
                    if not pc_available:
                        pc_404_streak += 1
                        if pc_404_streak >= 2:
                            app_only_skipped = True
                except CaptchaError as exc:
                    captcha_hits += 1
                    if captcha_hits >= 2:
                        captcha_stopped = True
                        error = "触发番茄风控验证码，已停止下载，请稍后重试"
                    else:
                        error = str(exc)
                        time.sleep(8.0)
                except CrawlerError as exc:
                    error = str(exc)
                time.sleep(self.delay + random.uniform(0, 0.06))
            else:
                error = "该章节没有可用的正文来源"

            if paragraphs:
                downloaded += 1
                downloaded_entries.append({
                    "index": index,
                    "title": chapter_title,
                    "volume_index": int(chapter.get("volume_index") or 0),
                    "volume_name": str(chapter.get("volume_name") or "").strip(),
                    "paragraphs": paragraphs,
                })
            else:
                failed.append({"index": index, "title": chapter_title,
                               "reason": error or "内容为空"})

            if progress_callback and item_id not in app_emitted:
                try:
                    progress_callback(index, total, chapter_title)
                except Exception:
                    pass

        filename = self._safe_filename(title) + ".txt"
        target_dir = Path(output_dir) if output_dir else DEFAULT_DOWNLOAD_DIR
        target_dir.mkdir(parents=True, exist_ok=True)
        path = target_dir / filename

        content = self._format_book_structured(
            metadata, downloaded_entries, downloaded, total, failed
        )
        path.write_text(content, encoding="utf-8-sig", newline="\n")

        if captcha_stopped:
            message = (f"下载中断：触发番茄风控验证码，已导出 {downloaded}/{total} 章，"
                       f"请等待几分钟后重试")
            success = downloaded > 0
        elif downloaded == 0:
            if app_error:
                message = f"APP 接口不可用（{app_error}），网页接口也没有抓到正文，请稍后重试"
            else:
                message = "没有抓到任何章节正文，请稍后重试或换一本书"
            success = False
        elif downloaded >= total:
            message = f"下载完成，共 {downloaded} 章（含原生分卷目录与排版）"
            success = True
        elif app_used:
            message = (f"已导出 {downloaded}/{total} 章，"
                       f"{len(failed)} 章未获取，可稍后重试失败章节")
            success = True
        elif not pc_available:
            message = (f"该书为 App 独占，PC 端仅提供前 {landing_available} 章试读，"
                       f"已导出可获取的 {downloaded} 章")
            success = True
        elif locked_failed and locked_failed == len(failed):
            message = (f"已导出 {downloaded}/{total} 章，"
                       f"{locked_failed} 章为会员/付费章节（免登录无法获取）")
            success = True
        elif locked_failed:
            message = (f"已导出 {downloaded}/{total} 章，"
                       f"{len(failed)} 章不可用（其中 {locked_failed} 章为会员/付费章节）")
            success = True
        else:
            message = f"下载完成 {downloaded}/{total} 章，{len(failed)} 章失败"
            success = True

        if app_error and downloaded > 0 and not captcha_stopped:
            message = f"APP 接口不可用（{app_error}），已回退网页接口：{message}"

        return {
            "success": success,
            "filename": filename,
            "path": str(path),
            "title": title,
            "total": total,
            "downloaded": downloaded,
            "failed": len(failed),
            "failed_chapters": failed[:50],
            "pc_available": pc_available,
            "app_api_used": app_used,
            "app_api_error": app_error,
            "message": message,
        }

    # ---------------------------------------------------------------- 排版
    @staticmethod
    def _format_chapter(title: str, paragraphs: Iterable[str]) -> str:
        body = "\n\n".join(f"\u3000\u3000{para.strip()}" for para in paragraphs
                           if para.strip())
        return f"【{title}】\n\n{body}\n\n"

    @classmethod
    def _format_book_structured(cls, metadata: dict, entries: list[dict],
                                downloaded: int, total: int,
                                failed: list[dict]) -> str:
        word_count = metadata.get("word_count") or 0
        if word_count >= 100_000_000:
            word_text = f"{word_count / 100_000_000:.2f} 亿字"
        elif word_count >= 10_000:
            word_text = f"{word_count / 10_000:.1f} 万字"
        else:
            word_text = f"{word_count} 字"

        exported = f"{downloaded}/{total} 章"
        if failed and downloaded < total:
            exported += f"（{len(failed)} 章不可用）"

        lines = [
            "=" * 46,
            f"书名：{metadata.get('title', '未知书名')}",
            f"作者：{metadata.get('author', '未知作者')}",
            f"评分：{metadata.get('score', '暂无')}",
            f"字数：{word_text}",
            f"状态：{metadata.get('serial_status', '未知')}",
            f"章节：共 {total} 章，已导出 {exported}",
            "=" * 46,
            "",
        ]
        intro = (metadata.get("intro") or "").strip()
        if intro:
            lines.extend(["〖内容简介〗", intro, "", "-" * 46, ""])

        distinct_vols = {
            e.get("volume_name", "").strip()
            for e in entries
            if e.get("volume_name", "").strip()
        }
        has_vols = len(distinct_vols) > 1 or any(
            v not in ("", "正文", "默认卷", "第一卷") for v in distinct_vols
        )

        if entries:
            lines.append("〖原生目录索引〗")
            last_vol = ""
            for e in entries:
                v_raw = (e.get("volume_name") or "").strip()
                v_idx = int(e.get("volume_index") or 0)
                if has_vols and v_raw and v_raw != last_vol:
                    last_vol = v_raw
                    lines.append("")
                    lines.append(f"◆ {normalize_volume_title(v_raw, v_idx)}")
                lines.append(f"  · {e['title']}")
            lines.extend(["", "=" * 46, ""])

        cur_vol = ""
        for e in entries:
            v_raw = (e.get("volume_name") or "").strip()
            v_idx = int(e.get("volume_index") or 0)
            norm_vol = normalize_volume_title(v_raw, v_idx) if v_raw else ""
            if has_vols and norm_vol and norm_vol != cur_vol:
                cur_vol = norm_vol
                lines.append(f"【{norm_vol}】")
            lines.append(cls._format_chapter(e["title"], e["paragraphs"]))

        lines.append("=" * 46)
        lines.append("全书导出完毕")
        lines.append("=" * 46)
        return "\n".join(lines)

    @staticmethod
    def _safe_filename(name: str) -> str:
        cleaned = _ILLEGAL_FILENAME_RE.sub("_", name or "未命名").strip(" .")
        cleaned = cleaned[:80] or "未命名"
        return cleaned


def download_book(book_id: str,
                  progress_callback: Optional[ProgressCallback] = None,
                  output_dir: Optional[str | os.PathLike] = None) -> dict:
    return FanqieCrawler().download_book(
        book_id, progress_callback=progress_callback, output_dir=output_dir)


def download_by_input(text: str,
                      progress_callback: Optional[ProgressCallback] = None,
                      output_dir: Optional[str | os.PathLike] = None) -> dict:
    book_id = resolve_book_id(text)
    return download_book(book_id, progress_callback, output_dir)
