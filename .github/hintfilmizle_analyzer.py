import base64
import json
import re
import urllib.request
import urllib.parse

def decrypt_kinescope_payload(p_encoded, key="RySdvcyu5iTUxn97vn4HwoniwgxaCynA"):
    try:
        reversed_str = ''.join(reversed(p_encoded))
        binary = base64.b64decode(reversed_str)
        key_bytes = key.encode('utf-8')
        out = bytearray(len(binary))
        for i in range(len(binary)):
            out[i] = binary[i] ^ key_bytes[i % len(key_bytes)]
        return out.decode('utf-8')
    except Exception as e:
        print(f"Decryption error: {e}")
        return None

def analyze_kinescope(video_id):
    print(f"[*] Analyzing Kinescope video ID: {video_id}")
    api_url = f"https://kinescope.io/api/v1/videos/{video_id}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer": f"https://kinescope.io/embed/{video_id}",
        "Origin": "https://kinescope.io",
        "X-Requested-With": "XMLHttpRequest"
    }

    req = urllib.request.Request(api_url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            print("[+] API Response received successfully.")

            if 'data' in data and 'files' in data['data']:
                hls = data['data']['files'].get('hls')
                if hls:
                    print(f"[+] Found HLS manifest: {hls}")
                    return hls

            if 'p' in data:
                decrypted = decrypt_kinescope_payload(data['p'])
                if decrypted:
                    print(f"[+] Decrypted payload: {decrypted[:200]}...")
                    parsed = json.loads(decrypted)
                    def find_m3u8(obj):
                        if isinstance(obj, str) and '.m3u8' in obj:
                            return obj
                        if isinstance(obj, dict):
                            for v in obj.values():
                                res = find_m3u8(v)
                                if res: return res
                        if isinstance(obj, list):
                            for item in obj:
                                res = find_m3u8(item)
                                if res: return res
                        return None
                    manifest = find_m3u8(parsed)
                    if manifest:
                        print(f"[+] Found manifest in decrypted payload: {manifest}")
                        return manifest
    except Exception as e:
        print(f"[-] API request failed: {e}")
    return None

if __name__ == "__main__":
    print("HintFilmİzle Kinescope Request Chain Analyzer initialized.")
