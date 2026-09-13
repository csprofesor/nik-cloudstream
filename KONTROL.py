from Kekik.cli import konsol
from cloudscraper import CloudScraper
from urllib.parse import quote_plus, urlparse, parse_qs, unquote
import os, re


class MainUrlUpdater:
    def __init__(self, base_dir="."):
        self.base_dir = base_dir
        self.oturum = CloudScraper()
        self.oturum.headers.update({
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
        })

    @property
    def eklentiler(self):
        return sorted([
            dosya for dosya in os.listdir(self.base_dir)
            if os.path.isdir(os.path.join(self.base_dir, dosya))
            and not dosya.startswith(".")
            and dosya not in {"gradle", "CanliTV", "OxAx", "__Temel", "SineWix", "YouTube", "NetflixMirror", "HQPorner"}
        ])

    def _kt_dosyasini_bul(self, dizin, dosya_adi):
        for kok, alt_dizinler, dosyalar in os.walk(dizin):
            if dosya_adi in dosyalar:
                return os.path.join(kok, dosya_adi)
        return None

    @property
    def kt_dosyalari(self):
        return [
            kt_dosya_yolu for eklenti in self.eklentiler
            if (kt_dosya_yolu := self._kt_dosyasini_bul(eklenti, f"{eklenti}.kt"))
        ]

    def _mainurl_bul(self, kt_dosya_yolu):
        with open(kt_dosya_yolu, "r", encoding="utf-8") as file:
            icerik = file.read()
            if mainurl := re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', icerik):
                return mainurl[1]
        return None

    def _mainurl_guncelle(self, kt_dosya_yolu, eski_url, yeni_url):
        if not eski_url or not yeni_url:
            return False
        eski_url = eski_url.strip()
        yeni_url = yeni_url.strip()
        if not eski_url or not yeni_url or eski_url == yeni_url:
            return False
        if not yeni_url.startswith(("http://", "https://")):
            return False
        with open(kt_dosya_yolu, "r+", encoding="utf-8") as file:
            icerik = file.read()
            if eski_url not in icerik:
                return False
            yeni_icerik = icerik.replace(eski_url, yeni_url)
            if yeni_icerik == icerik:
                return False
            file.seek(0)
            file.write(yeni_icerik)
            file.truncate()
        return True

    def _versiyonu_artir(self, build_gradle_yolu):
        if not os.path.exists(build_gradle_yolu):
            return None
        with open(build_gradle_yolu, "r+", encoding="utf-8") as file:
            icerik = file.read()
            if version_match := re.search(r'version\s*=\s*(\d+)', icerik):
                eski_versiyon = int(version_match[1])
                yeni_versiyon = eski_versiyon + 1
                yeni_icerik = icerik.replace(f"version = {eski_versiyon}", f"version = {yeni_versiyon}", 1)
                file.seek(0)
                file.write(yeni_icerik)
                file.truncate()
                return yeni_versiyon
        return None

    def _rectv_ver(self):
        istek = self.oturum.post(
            url="https://firebaseremoteconfig.googleapis.com/v1/projects/791583031279/namespaces/firebase:fetch",
            headers={
                "X-Goog-Api-Key": "AIzaSyBbhpzG8Ecohu9yArfCO5tF13BQLhjLahc",
                "X-Android-Package": "com.rectv.shot",
                "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 12)",
            },
            json={
                "appBuild": "81",
                "appInstanceId": "evON8ZdeSr-0wUYxf0qs68",
                "appId": "1:791583031279:android:1",
            }
        )
        return istek.json().get("entries", {}).get("api_url", "").replace("/api/", "")

    def _arama_adayi_url(self, sonuc_url):
        """DuckDuckGo sonucundaki gerçek URL'yi çıkar."""
        try:
            parsed = urlparse(sonuc_url)
            if parsed.netloc.endswith("duckduckgo.com"):
                hedef = parse_qs(parsed.query).get("uddg", [None])[0]
                return unquote(hedef) if hedef else None
            return sonuc_url
        except Exception:
            return None

    def _arama_motorundan_adaylar(self, site_adi):
        """Mevcut domain ölü ise ücretsiz DuckDuckGo HTML sonuçlarından aday domainler bulur."""
        sorgular = [
            f'"{site_adi}" "yeni adres"',
            f'"{site_adi}" "güncel adres"',
            f'"{site_adi}" site',
        ]
        adaylar = []
        for sorgu in sorgular:
            try:
                url = "https://html.duckduckgo.com/html/?q=" + quote_plus(sorgu)
                cevap = self.oturum.get(url, timeout=20)
                if cevap.status_code != 200:
                    continue
                # DDG HTML sonuçlarındaki gerçek URL'leri al.
                bulunan = re.findall(r'nofollow" class="result__a" href="([^"]+)"', cevap.text)
                if not bulunan:
                    bulunan = re.findall(r'class="result__a"[^>]+href="([^"]+)"', cevap.text)
                for sonuc in bulunan:
                    aday = self._arama_adayi_url(unquote(sonuc))
                    if aday and aday.startswith(("http://", "https://")):
                        parsed = urlparse(aday)
                        domain = parsed.netloc.lower().split(":")[0]
                        if domain.startswith("www."):
                            domain = domain[4:]
                        # Arama motorunun kendisini veya açıkça dosya/medya linklerini alma.
                        if domain in {"duckduckgo.com", "google.com", "bing.com", "youtube.com", "facebook.com", "instagram.com", "x.com"}:
                            continue
                        temiz = f"{parsed.scheme}://{parsed.netloc}"
                        if temiz not in adaylar:
                            adaylar.append(temiz)
            except Exception as hata:
                konsol.log(f"[!] Arama başarısız : {type(hata).__name__} : {hata}")
        return adaylar[:15]

    def _aday_dogrula(self, aday, site_adi):
        """Aday domaini açıp gerçek bir site olduğuna dair basit sinyaller arar."""
        try:
            cevap = self.oturum.get(aday, allow_redirects=True, timeout=20)
            if cevap.status_code >= 400:
                return None
            final_url = cevap.url[:-1] if cevap.url.endswith("/") else cevap.url
            html = cevap.text[:500000].lower()
            baslik = re.search(r'<title[^>]*>(.*?)</title>', html, re.I | re.S)
            baslik = re.sub(r'<[^>]+>', ' ', baslik.group(1)) if baslik else ""
            baslik = re.sub(r'\s+', ' ', baslik).strip()
            anahtarlar = [x for x in re.findall(r'[a-z0-9çğıöşü]+', site_adi.lower()) if len(x) >= 3]
            skor = sum(1 for anahtar in anahtarlar if anahtar in baslik or anahtar in html[:100000])
            # Arama sonucunun tamamen alakasız bir siteye yönlendirmesini engelle.
            if anahtarlar and skor == 0:
                return None
            return final_url
        except Exception:
            return None

    def _yeni_domain_bul(self, eklenti_adi, eski_url):
        site_adi = eklenti_adi
        adaylar = self._arama_motorundan_adaylar(site_adi)
        konsol.log(f"[?] Arama sonucu : {len(adaylar)} aday bulundu")
        eski_domain = urlparse(eski_url).netloc.lower()
        for aday in adaylar:
            aday_domain = urlparse(aday).netloc.lower()
            if aday_domain == eski_domain:
                continue
            dogrulanmis = self._aday_dogrula(aday, site_adi)
            if dogrulanmis:
                konsol.log(f"[+] Yeni domain bulundu : {dogrulanmis}")
                return dogrulanmis
        return None

    @property
    def mainurl_listesi(self):
        return {dosya: self._mainurl_bul(dosya) for dosya in self.kt_dosyalari}

    def guncelle(self):
        for dosya, mainurl in self.mainurl_listesi.items():
            try:
                if not mainurl:
                    continue
                eklenti_adi = dosya.split("/")[0]
                print("\n")
                konsol.log(f"[~] Kontrol Ediliyor : {eklenti_adi}")

                if eklenti_adi == "RecTV":
                    try:
                        final_url = self._rectv_ver()
                        konsol.log(f"[+] Kontrol Edildi   : {mainurl}")
                    except Exception as hata:
                        konsol.log(f"[!] Kontrol Edilemedi : {mainurl}")
                        konsol.log(f"[!] {type(hata).__name__} : {hata}")
                        continue
                else:
                    try:
                        istek = self.oturum.get(mainurl, allow_redirects=True, timeout=20)
                        if istek.status_code >= 400:
                            raise RuntimeError(f"HTTP {istek.status_code}")
                        konsol.log(f"[+] Kontrol Edildi   : {mainurl}")
                        final_url = istek.url[:-1] if istek.url.endswith("/") else istek.url
                    except Exception as hata:
                        konsol.log(f"[!] Mevcut domain çalışmıyor : {mainurl}")
                        konsol.log(f"[!] {type(hata).__name__} : {hata}")
                        try:
                            final_url = self._yeni_domain_bul(eklenti_adi, mainurl)
                        except Exception as bulma_hatasi:
                            konsol.log(f"[!] Domain arama hatası : {type(bulma_hatasi).__name__} : {bulma_hatasi}")
                            final_url = None
                        if not final_url:
                            konsol.log(f"[-] Yeni domain bulunamadı : {eklenti_adi}")
                            continue

                if not isinstance(final_url, str):
                    konsol.log(f"[-] Geçersiz yeni URL (string değil) : {eklenti_adi}")
                    continue

                final_url = final_url.strip()
                if not final_url.startswith(("http://", "https://")):
                    konsol.log(f"[-] Geçersiz yeni URL : {eklenti_adi} -> {final_url}")
                    continue

                if mainurl == final_url:
                    continue

                build_gradle_yolu = f"{eklenti_adi}/build.gradle.kts"
                eski_gradle_icerik = None
                if os.path.exists(build_gradle_yolu):
                    try:
                        with open(build_gradle_yolu, "r", encoding="utf-8") as build_file:
                            eski_gradle_icerik = build_file.read()
                    except Exception as gradle_okuma_hatasi:
                        konsol.log(f"[!] build.gradle okuma hatası : {type(gradle_okuma_hatasi).__name__} : {gradle_okuma_hatasi}")
                        konsol.log(f"[-] Güvenli geri alma sağlanamadı, eklenti atlandı : {eklenti_adi}")
                        continue

                if not self._mainurl_guncelle(dosya, mainurl, final_url):
                    konsol.log(f"[-] mainUrl güncellemesi atlandı : {eklenti_adi}")
                    continue

                if not self._versiyonu_artir(build_gradle_yolu):
                    geri_alindi = self._mainurl_guncelle(dosya, final_url, mainurl)
                    if eski_gradle_icerik is not None:
                        try:
                            with open(build_gradle_yolu, "w", encoding="utf-8") as build_file:
                                build_file.write(eski_gradle_icerik)
                        except Exception as gradle_geri_al_hatasi:
                            konsol.log(f"[!] build.gradle geri alma hatası : {type(gradle_geri_al_hatasi).__name__} : {gradle_geri_al_hatasi}")
                    if not geri_alindi:
                        konsol.log(f"[!] mainUrl geri alma başarısız : {eklenti_adi}")
                    konsol.log(f"[-] Versiyon artırılamadı, değişiklik geri alındı : {eklenti_adi}")
                    continue

                konsol.log(f"[»] {mainurl} -> {final_url}")
            except Exception as genel_hata:
                konsol.log(f"[!] Beklenmeyen hata : {dosya}")
                konsol.log(f"[!] {type(genel_hata).__name__} : {genel_hata}")
                continue


if __name__ == "__main__":
    updater = MainUrlUpdater()
    updater.guncelle()
