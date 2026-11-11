#!/usr/bin/env python3
"""Validate project pages, metadata, sitemap and documentation links without network access."""
from datetime import date
from html import unescape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import struct
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SITE = "https://weakmafaka.github.io/WeakBridge/"
REPO = "https://github.com/Weakmafaka/WeakBridge/"
RAW = "https://raw.githubusercontent.com/Weakmafaka/WeakBridge/main/"
ALTERNATES = {"en": SITE, "ru": SITE + "ru/", "x-default": SITE}
PAGES = {"en": ROOT / "docs/index.html", "ru": ROOT / "docs/ru/index.html"}
VOID = set("area base br col embed hr img input link meta param source track wbr".split())


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.stack = []
        self.ids = set()
        self.nodes = []
        self.json_blocks = []
        self.title = ""
        self.lang = ""
        self.headings = []
        self.doctype = False
        self.current_json = None
        self.feed(path.read_text(encoding="utf-8"))
        self.close()
        require(not self.stack, f"{path}: unclosed tags {self.stack}")

    def handle_decl(self, text):
        self.doctype = text.lower() == "doctype html"

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.nodes.append((tag, attrs))
        if attrs.get("id"):
            require(attrs["id"] not in self.ids, f"{self.path}: duplicate id {attrs['id']}")
            self.ids.add(attrs["id"])
        if tag == "html":
            self.lang = attrs.get("lang")
        if re.fullmatch(r"h[1-6]", tag):
            self.headings.append(int(tag[1]))
        if tag == "img":
            require(bool(attrs.get("alt")), f"{self.path}: missing descriptive image alt")
        if tag == "script" and attrs.get("type") == "application/ld+json":
            self.current_json = ""
        if tag not in VOID:
            self.stack.append(tag)

    def handle_endtag(self, tag):
        require(self.stack and self.stack[-1] == tag, f"{self.path}: unexpected </{tag}>")
        self.stack.pop()
        if tag == "script" and self.current_json is not None:
            self.json_blocks.append(json.loads(self.current_json))
            self.current_json = None

    def handle_data(self, text):
        if self.current_json is not None:
            self.current_json += text
        if self.stack and self.stack[-1] == "title":
            self.title += text

    def meta(self, key):
        values = [a.get("content", "") for t, a in self.nodes if t == "meta" and (a.get("name") == key or a.get("property") == key)]
        require(len(values) == 1 and bool(values[0]), f"{self.path}: missing/duplicate {key}")
        return values[0]

    def links(self, rel):
        return [a for t, a in self.nodes if t == "link" and a.get("rel") == rel]


def markdown_ids(path):
    text = re.sub(r"```.*?```", "", path.read_text(encoding="utf-8"), flags=re.S)
    counts, ids = {}, set()
    for title in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", text, re.M):
        title = re.sub(r"<[^>]+>", "", title)
        slug = re.sub(r"[^\w\s-]", "", unescape(title).lower())
        slug = re.sub(r"\s", "-", slug)
        occurrence = counts.get(slug, 0)
        ids.add(slug if not occurrence else f"{slug}-{occurrence}")
        counts[slug] = occurrence + 1
    return ids


def check_reference(reference, source, pages):
    link = urlsplit(unescape(reference))
    if reference.startswith(SITE):
        target = ROOT / "docs" / unquote(link.path[len('/WeakBridge/'):])
    elif reference.startswith(REPO + "blob/main/"):
        target = ROOT / unquote(link.path.split("/blob/main/", 1)[1])
    elif reference.startswith(RAW):
        target = ROOT / unquote(link.path.split("/main/", 1)[1])
    elif link.scheme or link.netloc:
        return
    else:
        target = source.parent / unquote(link.path) if link.path else source
    target = target.resolve()
    require(target.is_relative_to(ROOT), f"{source}: reference leaves repository: {reference}")
    if target.is_dir():
        target /= "index.html"
    require(target.is_file(), f"{source}: missing target {reference}")
    if link.fragment:
        if target.suffix == ".html":
            ids = pages.get(target) or Page(target)
            ids = ids.ids
        elif target.suffix == ".md":
            ids = markdown_ids(target)
        else:
            return
        require(unquote(link.fragment) in ids, f"{source}: missing fragment {reference}")


def main():
    version = re.search(r'versionName = "([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
    pages = {p.resolve(): Page(p) for p in PAGES.values()}
    canonicals, titles, descriptions = set(), set(), set()
    for lang, path in PAGES.items():
        page = pages[path.resolve()]
        require(page.doctype and page.lang == lang, f"{path}: doctype/language mismatch")
        require(page.headings.count(1) == 1 and page.headings[0] == 1, f"{path}: expected one leading h1")
        require(all(b <= a + 1 for a, b in zip(page.headings, page.headings[1:])), f"{path}: heading level skipped")
        canonical = page.links("canonical")
        require(len(canonical) == 1 and canonical[0]["href"] == ALTERNATES[lang], f"{path}: canonical mismatch")
        canonical = canonical[0]["href"]
        canonicals.add(canonical)
        alternates = page.links("alternate")
        require(len(alternates) == 3 and {a["hreflang"]: a["href"] for a in alternates} == ALTERNATES, f"{path}: hreflang mismatch")
        title, description = page.title.strip(), page.meta("description")
        require(title and description, f"{path}: empty title/description")
        titles.add(title); descriptions.add(description)
        require(page.meta("og:title") == page.meta("twitter:title") == title, f"{path}: social title mismatch")
        require(page.meta("og:description") == page.meta("twitter:description") == description, f"{path}: social description mismatch")
        require(page.meta("og:url") == canonical, f"{path}: og:url mismatch")
        require(page.meta("og:image") == page.meta("twitter:image") == SITE + "images/social-preview.png", f"{path}: social image mismatch")
        require(page.meta("og:image:alt") == page.meta("twitter:image:alt"), f"{path}: social alt mismatch")
        require(page.meta("twitter:card") == "summary_large_image", f"{path}: card type mismatch")
        require((page.meta("og:image:width"), page.meta("og:image:height")) == ("1200", "630"), f"{path}: social dimensions mismatch")
        require(page.meta("og:locale") == {"en": "en_US", "ru": "ru_RU"}[lang], f"{path}: social locale mismatch")
        require(page.meta("og:locale:alternate") == {"en": "ru_RU", "ru": "en_US"}[lang], f"{path}: alternate locale mismatch")
        require("index" in page.meta("robots").split(", ") and "noindex" not in page.meta("robots"), f"{path}: indexing disabled")
        require(len(page.json_blocks) == 1, f"{path}: expected one JSON-LD block")
        data = page.json_blocks[0]
        require(data["@context"] == "https://schema.org", f"{path}: invalid context")
        graph = {node["@type"]: node for node in data["@graph"]}
        require(set(graph) == {"WebPage", "SoftwareApplication"}, f"{path}: unexpected structured types")
        web, app = graph["WebPage"], graph["SoftwareApplication"]
        require(web["url"] == canonical and web["inLanguage"] == lang and web["name"] == title and web["description"] == description, f"{path}: WebPage/content mismatch")
        require(app["name"] == "WeakBridge" and app["softwareVersion"] == version, f"{path}: app name/version mismatch")
        require(web["mainEntity"]["@id"] == app["@id"] == SITE + "#app", f"{path}: app identity mismatch")
        require(app["downloadUrl"] == REPO + f"releases/download/v{version}/WeakBridge-{version}.apk", f"{path}: expected versioned binary download URL")
        require(app["license"] == REPO + "blob/main/LICENSE" and app["inLanguage"] == ["en", "ru"], f"{path}: license/language mismatch")
        require(not any(k in app for k in ("aggregateRating", "review", "downloadCount")), f"{path}: unsupported social proof")
        check_reference(app["installUrl"], path, pages)
        for tag, attrs in page.nodes:
            for key in ("href", "src"):
                if attrs.get(key): check_reference(attrs[key], path, pages)
    require(len(canonicals) == len(titles) == len(descriptions) == 2, "Localized metadata must be unique")
    png = (ROOT / "docs/images/social-preview.png").read_bytes()
    require(png[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II", png[16:24]) == (1200, 630), "Invalid social PNG dimensions")
    root = ET.parse(ROOT / "docs/sitemap.xml").getroot()
    ns = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9", "x": "http://www.w3.org/1999/xhtml"}
    urls = []
    for item in root.findall("s:url", ns):
        urls.append(item.findtext("s:loc", namespaces=ns))
        date.fromisoformat(item.findtext("s:lastmod", namespaces=ns))
        links = item.findall("x:link", ns)
        require(len(links) == 3 and {x.get("hreflang"): x.get("href") for x in links} == ALTERNATES, "Sitemap language alternates mismatch")
    require(len(urls) == 2 and set(urls) == canonicals, "Sitemap/canonical mismatch")
    documents = list(ROOT.glob("*.md")) + list((ROOT / "docs").rglob("*.md")) + [ROOT / "docs/llms.txt"]
    for path in documents:
        text = re.sub(r"```.*?```", "", path.read_text(encoding="utf-8"), flags=re.S)
        for reference in re.findall(r"\]\(([^)\s]+)\)", text):
            check_reference(reference, path, pages)
        for reference in re.findall(r'(?:href|src)="([^"]+)"', text):
            check_reference(reference, path, pages)
    overview = (ROOT / "docs/llms.txt").read_text()
    require(f"Current app release: {version}." in overview, "Project reference version mismatch")
    print(f"PASS: 2 HTML pages; EN/RU canonical and hreflang; Open Graph/Twitter; 4 structured entities; {len(documents)} documentation files; sitemap; 1200x630 social image; app version {version}.")
    print("Local structural/consistency checks only; not a Google rich-results or indexing certification.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, ET.ParseError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
