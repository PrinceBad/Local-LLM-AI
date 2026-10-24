"""
Automated verification tool for AuraLocalAI model presets catalog.
Sends HEAD requests with User-Agent to Hugging Face commit endpoints,
intercepts HTTP 302 headers (X-Repo-Commit, X-Linked-ETag, X-Linked-Size),
and validates them against ModelPreset definitions.
"""
import urllib.request
import re
import sys

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def verify_catalog(preset_file_path):
    opener = urllib.request.build_opener(NoRedirectHandler)
    with open(preset_file_path, "r", encoding="utf-8") as f:
        code = f.read()

    presets = re.findall(r'ModelPreset\((.*?)\n            \)', code, re.DOTALL)
    print(f"Auditing {len(presets)} model presets against upstream Hugging Face repos...\n")

    all_passed = True
    for p in presets:
        id_m = re.search(r'id = "([^"]+)"', p)
        url_m = re.search(r'downloadUrl = "([^"]+)"', p)
        sha_m = re.search(r'expectedSha256 = "([^"]+)"', p)
        size_m = re.search(r'sizeBytes = (\d+)L', p)
        
        pid = id_m.group(1) if id_m else "unknown"
        url = url_m.group(1) if url_m else ""
        expected_sha = sha_m.group(1) if sha_m else ""
        expected_size = int(size_m.group(1)) if size_m else 0
        
        commit_in_url = url.split("/resolve/")[1].split("/")[0] if "/resolve/" in url else ""
        
        req = urllib.request.Request(url, method="HEAD")
        req.add_header("User-Agent", "AuraLocalAI-Auditor/1.0")
        try:
            resp = opener.open(req)
            headers = resp.headers
        except urllib.error.HTTPError as e:
            headers = e.headers
        except Exception as e:
            print(f"[FAIL] [{pid}] Network error: {e}")
            all_passed = False
            continue
        
        hf_commit = headers.get("X-Repo-Commit")
        hf_etag = headers.get("X-Linked-ETag", "").strip('"')
        hf_size_str = headers.get("X-Linked-Size")
        hf_size = int(hf_size_str) if hf_size_str else 0
        
        commit_ok = (hf_commit == commit_in_url)
        sha_ok = (hf_etag.lower() == expected_sha.lower())
        size_ok = (hf_size == expected_size)
        
        passed = commit_ok and sha_ok and size_ok
        status = "[OK]" if passed else "[FAIL]"
        if not passed:
            all_passed = False
            
        print(f"{status} [{pid}]")
        print(f"     Commit: {'PASS' if commit_ok else 'FAIL'} (Expected: {commit_in_url[:10]} | Upstream: {hf_commit[:10] if hf_commit else 'None'})")
        print(f"     SHA256: {'PASS' if sha_ok else 'FAIL'} (Expected: {expected_sha[:16]}... | Upstream: {hf_etag[:16]}...)")
        print(f"     Size:   {'PASS' if size_ok else 'FAIL'} (Expected: {expected_size} B | Upstream: {hf_size} B)")
        print()

    if all_passed:
        print("All presets verified with 100% upstream match.")
        return 0
    else:
        print("WARNING: Some presets failed upstream verification.")
        return 1

if __name__ == "__main__":
    preset_path = r"C:\Users\Badsiwal\Documents\Prince Works\01-AI-and-Machine-Learning\aura-local-ai\app\src\main\java\com\example\auralocalai\data\ModelPreset.kt"
    sys.exit(verify_catalog(preset_path))
