#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse

import requests

ROOT_DIR = Path(__file__).resolve().parent
DOMAINS_PATH = ROOT_DIR / "domains.json"
DEFAULT_REPORT_PATH = ROOT_DIR / "domain_check_report.json"
SKIP_DIRS = {".git", ".github", "gradle", "build", "__Temel"}
ALT_TLDS = ("com", "net", "org", "live", "tv", "co", "cc", "de", "io", "xyz", "site")


@dataclass
class DomainUpdate:
    plugin: str
    old_main_url: str
    new_main_url: str
    old_domain: str
    new_domain: str
    source: str
    files: List[str]


class DomainAutoFixer:
    def __init__(self, root_dir: Path, domains_path: Path, retries: int, timeout: int, commit: bool, report_file: Path):
        self.root_dir = root_dir
        self.domains_path = domains_path
        self.retries = max(1, retries)
        self.timeout = max(1, timeout)
        self.commit = commit
        self.report_file = report_file

        self.session = requests.Session()
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (compatible; CloudStreamDomainAutoFix/1.0)"
        })

        self.domain_mappings = self._load_domain_mappings()
        self.updates: List[DomainUpdate] = []
        self.checked_plugins = 0
        self.reachable_plugins = 0
        self.errors: List[str] = []

    def _log(self, message: str) -> None:
        print(message)

    def _load_domain_mappings(self) -> Dict[str, str]:
        if not self.domains_path.exists():
            return {}
        try:
            data = json.loads(self.domains_path.read_text(encoding="utf-8"))
            mappings = data.get("mappings", {}) if isinstance(data, dict) else {}
            return {
                self._normalize_domain(old): new
                for old, new in mappings.items()
                if self._normalize_domain(old) and isinstance(new, str) and new.strip()
            }
        except Exception as exc:
            self._log(f"[!] domains.json okunamadı: {exc}")
            return {}

    @staticmethod
    def _normalize_domain(value: str) -> str:
        value = (value or "").strip().lower()
        if not value:
            return ""
        if "://" in value:
            value = urlparse(value).netloc
        value = value.split("/")[0]
        if value.startswith("www."):
            value = value[4:]
        return value

    def _plugin_dirs(self) -> List[Path]:
        plugins: List[Path] = []
        for gradle_file in sorted(self.root_dir.glob("*/build.gradle.kts")):
            plugin_dir = gradle_file.parent
            if plugin_dir.name in SKIP_DIRS:
                continue
            plugins.append(plugin_dir)
        return plugins

    @staticmethod
    def _clean_url(url: str) -> str:
        return (url or "").rstrip("/")

    def _request_with_retry(self, url: str) -> Optional[str]:
        for attempt in range(1, self.retries + 1):
            for method in ("head", "get"):
                try:
                    response = self.session.request(
                        method=method,
                        url=url,
                        allow_redirects=True,
                        timeout=self.timeout,
                    )
                    if response.status_code < 400:
                        return self._clean_url(response.url)
                except requests.RequestException:
                    pass
            if attempt < self.retries:
                time.sleep(min(attempt, 3))
        return None

    def _verify_url(self, raw_url: str) -> Optional[str]:
        if not raw_url:
            return None

        parsed = urlparse(raw_url)
        candidates = []

        if parsed.scheme and parsed.netloc:
            candidates.append(raw_url)
            alternate_scheme = "https" if parsed.scheme == "http" else "http"
            candidates.append(raw_url.replace(f"{parsed.scheme}://", f"{alternate_scheme}://", 1))
        else:
            host = parsed.path or raw_url
            host = host.replace("https://", "").replace("http://", "").split("/")[0]
            candidates.append(f"https://{host}")
            candidates.append(f"http://{host}")

        checked = set()
        for candidate in candidates:
            normalized = candidate.strip()
            if not normalized or normalized in checked:
                continue
            checked.add(normalized)
            final_url = self._request_with_retry(normalized)
            if final_url:
                return final_url

        return None

    def _find_main_url_file(self, plugin_dir: Path) -> Optional[Path]:
        preferred = plugin_dir / "src" / "main" / "kotlin"
        if preferred.exists():
            matches = list(preferred.rglob(f"{plugin_dir.name}.kt"))
            if matches:
                return matches[0]

        for kt_file in plugin_dir.rglob("*.kt"):
            try:
                text = kt_file.read_text(encoding="utf-8")
                if re.search(r'override\s+var\s+mainUrl\s*=\s*"[^"]+"', text):
                    return kt_file
            except OSError:
                continue

        return None

    @staticmethod
    def _extract_main_url(kt_file: Path) -> Optional[str]:
        try:
            content = kt_file.read_text(encoding="utf-8")
        except OSError:
            return None

        match = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', content)
        return match.group(1) if match else None

    @staticmethod
    def _extract_icon_url(build_gradle: Path) -> Optional[str]:
        try:
            content = build_gradle.read_text(encoding="utf-8")
        except OSError:
            return None
        match = re.search(r'iconUrl\s*=\s*"([^"]+)"', content)
        return match.group(1) if match else None

    def _mapping_candidates(self, old_domain: str) -> List[str]:
        candidates: List[str] = []
        normalized = self._normalize_domain(old_domain)
        if not normalized:
            return candidates

        direct = self.domain_mappings.get(normalized)
        if direct:
            candidates.append(direct)

        token = re.sub(r"\d+$", "", normalized.split(".")[0])
        if token and len(token) >= 5 and not direct:
            for source_key, target in self.domain_mappings.items():
                if source_key.startswith(token):
                    candidates.append(target)

        unique = []
        seen = set()
        for candidate in candidates:
            cleaned = self._clean_url(candidate)
            if cleaned and cleaned not in seen:
                seen.add(cleaned)
                unique.append(cleaned)
        return unique

    def _heuristic_candidates(self, old_url: str) -> List[str]:
        parsed = urlparse(old_url)
        host = self._normalize_domain(parsed.netloc or old_url)
        if not host:
            return []

        parts = host.split(".")
        if len(parts) < 2:
            return []

        base = re.sub(r"\d+$", "", parts[0])
        candidates: List[str] = []

        for tld in ALT_TLDS:
            for prefix in ("", "www."):
                if not base:
                    continue
                candidates.append(f"https://{prefix}{base}.{tld}")

        return candidates

    def _resolve_replacement(self, old_url: str) -> Tuple[Optional[str], str]:
        old_domain = self._normalize_domain(urlparse(old_url).netloc or old_url)

        for mapped in self._mapping_candidates(old_domain):
            final_url = self._verify_url(mapped)
            if final_url:
                return final_url, "mapping"

        for candidate in self._heuristic_candidates(old_url):
            final_url = self._verify_url(candidate)
            if final_url:
                return final_url, "heuristic"

        return None, "none"

    @staticmethod
    def _replace_main_url(kt_file: Path, new_url: str) -> bool:
        try:
            content = kt_file.read_text(encoding="utf-8")
        except OSError:
            return False

        pattern = re.compile(r'(override\s+var\s+mainUrl\s*=\s*")([^"]+)(")')
        new_content, count = pattern.subn(lambda m: f'{m.group(1)}{new_url}{m.group(3)}', content, count=1)

        if count and new_content != content:
            kt_file.write_text(new_content, encoding="utf-8")
            return True
        return False

    @staticmethod
    def _update_icon_url(build_gradle: Path, new_host: str) -> bool:
        try:
            content = build_gradle.read_text(encoding="utf-8")
        except OSError:
            return False

        match = re.search(r'iconUrl\s*=\s*"([^"]+)"', content)
        if not match:
            return False

        icon_url = match.group(1)
        parsed = urlparse(icon_url)
        updated_icon = icon_url

        if "domain=" in parsed.query:
            query = parse_qs(parsed.query, keep_blank_values=True)
            query["domain"] = [new_host]
            updated_query = urlencode(query, doseq=True)
            updated_icon = urlunparse(parsed._replace(query=updated_query))
        elif parsed.scheme and parsed.netloc:
            updated_icon = urlunparse(parsed._replace(netloc=new_host))

        if updated_icon == icon_url:
            return False

        new_content = content.replace(icon_url, updated_icon, 1)
        if new_content != content:
            build_gradle.write_text(new_content, encoding="utf-8")
            return True
        return False

    @staticmethod
    def _increment_version(build_gradle: Path) -> bool:
        try:
            content = build_gradle.read_text(encoding="utf-8")
        except OSError:
            return False

        match = re.search(r"version\s*=\s*(\d+)", content)
        if not match:
            return False

        old_version = int(match.group(1))
        new_version = old_version + 1
        new_content = re.sub(r"version\s*=\s*\d+", f"version = {new_version}", content, count=1)

        if new_content != content:
            build_gradle.write_text(new_content, encoding="utf-8")
            return True
        return False

    def _commit_changes(self) -> bool:
        if not self.updates or not self.commit:
            return False

        commit_message = f"chore(domains): auto-fix {len(self.updates)} plugin domain(s)"
        commit_lines = [f"- {u.plugin}: {u.old_domain} -> {u.new_domain}" for u in self.updates]

        try:
            subprocess.run(["git", "add", "-A"], cwd=self.root_dir, check=True)
            subprocess.run(
                ["git", "commit", "-m", commit_message, "-m", "\n".join(commit_lines)],
                cwd=self.root_dir,
                check=True,
            )
            return True
        except subprocess.CalledProcessError as exc:
            self._log(f"[!] Otomatik commit başarısız: {exc}")
            return False

    def _write_report(self, committed: bool) -> None:
        report = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "checked_plugins": self.checked_plugins,
            "reachable_plugins": self.reachable_plugins,
            "updated_plugins": [
                {
                    "plugin": u.plugin,
                    "old_main_url": u.old_main_url,
                    "new_main_url": u.new_main_url,
                    "old_domain": u.old_domain,
                    "new_domain": u.new_domain,
                    "source": u.source,
                    "files": u.files,
                }
                for u in self.updates
            ],
            "errors": self.errors,
            "committed": committed,
        }
        self.report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    def run(self) -> int:
        plugins = self._plugin_dirs()
        self._log(f"[*] {len(plugins)} eklenti dizini taranıyor...")

        for plugin_dir in plugins:
            plugin = plugin_dir.name
            build_gradle = plugin_dir / "build.gradle.kts"
            kt_file = self._find_main_url_file(plugin_dir)
            if not kt_file:
                continue

            main_url = self._extract_main_url(kt_file)
            if not main_url:
                continue

            self.checked_plugins += 1
            self._log(f"[~] {plugin}: {main_url} kontrol ediliyor")

            verified = self._verify_url(main_url)
            source = "reachable"

            if not verified:
                replacement, source = self._resolve_replacement(main_url)
                verified = replacement

            if not verified:
                self.errors.append(f"{plugin}: alan adı doğrulanamadı ({main_url})")
                self._log(f"[!] {plugin}: alan adı doğrulanamadı")
                continue

            if source == "reachable":
                self.reachable_plugins += 1
            verified = self._clean_url(verified)
            old_clean = self._clean_url(main_url)

            if verified == old_clean:
                self._log(f"[✓] {plugin}: güncel")
                continue

            old_domain = self._normalize_domain(old_clean)
            new_domain = self._normalize_domain(verified)
            new_host = urlparse(verified).netloc or new_domain
            changed_files: List[str] = []

            if self._replace_main_url(kt_file, verified):
                changed_files.append(str(kt_file.relative_to(self.root_dir)))

            if self._update_icon_url(build_gradle, new_host):
                changed_files.append(str(build_gradle.relative_to(self.root_dir)))

            if self._increment_version(build_gradle):
                gradle_path = str(build_gradle.relative_to(self.root_dir))
                if gradle_path not in changed_files:
                    changed_files.append(gradle_path)

            update = DomainUpdate(
                plugin=plugin,
                old_main_url=old_clean,
                new_main_url=verified,
                old_domain=old_domain,
                new_domain=new_domain,
                source=source,
                files=changed_files,
            )
            self.updates.append(update)

            self._log(f"[✓] {plugin}: {old_clean} -> {verified} (kaynak: {source})")

        committed = self._commit_changes()
        self._write_report(committed=committed)

        self._log("\n" + "=" * 70)
        self._log(f"Kontrol edilen: {self.checked_plugins}")
        self._log(f"Erişilebilir:   {self.reachable_plugins}")
        self._log(f"Güncellenen:    {len(self.updates)}")
        self._log(f"Hata:           {len(self.errors)}")
        self._log(f"Rapor:          {self.report_file}")
        self._log("=" * 70)

        return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="CloudStream eklentileri için otomatik domain düzeltici")
    parser.add_argument("--root", default=str(ROOT_DIR), help="Depo kök dizini")
    parser.add_argument("--domains", default=str(DOMAINS_PATH), help="Domain mapping JSON dosyası")
    parser.add_argument("--retries", type=int, default=3, help="Retry sayısı")
    parser.add_argument("--timeout", type=int, default=10, help="İstek timeout (saniye)")
    parser.add_argument("--report-file", default=str(DEFAULT_REPORT_PATH), help="JSON rapor dosyası")
    parser.add_argument("--no-commit", action="store_true", help="Script içinden commit oluşturma")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    fixer = DomainAutoFixer(
        root_dir=Path(args.root).resolve(),
        domains_path=Path(args.domains).resolve(),
        retries=args.retries,
        timeout=args.timeout,
        commit=not args.no_commit,
        report_file=Path(args.report_file).resolve(),
    )
    return fixer.run()


if __name__ == "__main__":
    sys.exit(main())
