#!/usr/bin/env python3
"""One-time App Store Connect setup for the TestFlight workflow — no Mac or Xcode needed.

Using an App Store Connect API key it:
  1. registers the bundle ID (com.easyesuite.mobile) if it is missing,
  2. creates an "Apple Distribution" certificate from a key pair generated *here* and packs it as a .p12,
  3. creates (or recreates) the "EasyEsuite Mobile App Store" provisioning profile for that certificate,
  4. writes everything the GitHub Actions workflow needs as secrets, plus the `gh secret set` commands.

Needs: python3 and the `openssl` CLI (macOS, Linux, WSL all fine). No third-party packages.

    python3 ios/scripts/asc-bootstrap.py \
        --key-id ABC123DEFG --issuer-id 12345678-aaaa-bbbb-cccc-1234567890ab \
        --key ~/Downloads/AuthKey_ABC123DEFG.p8 --out ./asc-out

The API key must have the **Admin** or **App Manager** role (App Store Connect → Users and Access →
Integrations → App Store Connect API). Treat ./asc-out like a password: it contains the private key.
See docs/TESTFLIGHT.md for the full runbook.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com/v1"
WWDR_URLS = [f"https://www.apple.com/certificateauthority/AppleWWDRCA{g}.cer" for g in ("G3", "G4", "G5", "G6")]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def run(cmd, **kw):
    res = subprocess.run(cmd, capture_output=True, **kw)
    if res.returncode != 0:
        sys.exit(f"command failed: {' '.join(cmd)}\n{res.stderr.decode(errors='replace')}")
    return res.stdout


def der_ecdsa_to_raw(sig: bytes) -> bytes:
    """openssl emits DER SEQUENCE{INTEGER r, INTEGER s}; JWT ES256 wants r||s, 32 bytes each."""
    assert sig[0] == 0x30, "not a DER sequence"
    i = 2 if sig[1] < 0x80 else 2 + (sig[1] & 0x7F)
    out = b""
    for _ in range(2):
        assert sig[i] == 0x02, "expected INTEGER"
        ln = sig[i + 1]
        val = sig[i + 2 : i + 2 + ln].lstrip(b"\x00")
        out += val.rjust(32, b"\x00")
        i += 2 + ln
    return out


def make_jwt(key_id: str, issuer_id: str, key_path: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    payload = b64url(json.dumps({"iss": issuer_id, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}).encode())
    signing_input = f"{header}.{payload}".encode()
    der = run(["openssl", "dgst", "-sha256", "-sign", key_path], input=signing_input)
    return f"{header}.{payload}.{b64url(der_ecdsa_to_raw(der))}"


class ASC:
    def __init__(self, token: str):
        self.token = token

    def call(self, method: str, path: str, body=None, params: str = ""):
        url = f"{API}{path}{params}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                raw = res.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            try:
                errs = json.loads(detail).get("errors", [])
                detail = "; ".join(f"{x.get('title')}: {x.get('detail')}" for x in errs) or detail
            except Exception:
                pass
            sys.exit(f"App Store Connect API {method} {path} → HTTP {e.code}: {detail}")


def ensure_bundle_id(asc: ASC, identifier: str, name: str) -> dict:
    found = asc.call("GET", "/bundleIds", params=f"?filter[identifier]={identifier}&filter[platform]=IOS").get("data", [])
    for b in found:
        if b["attributes"]["identifier"] == identifier:
            print(f"✓ bundle ID {identifier} already registered (seed/team prefix {b['attributes'].get('seedId')})")
            return b
    created = asc.call("POST", "/bundleIds", {"data": {"type": "bundleIds", "attributes": {"identifier": identifier, "name": name, "platform": "IOS"}}})["data"]
    print(f"✓ registered bundle ID {identifier} (seed/team prefix {created['attributes'].get('seedId')})")
    return created


def list_distribution_certs(asc: ASC) -> list:
    data = asc.call("GET", "/certificates", params="?filter[certificateType]=DISTRIBUTION,IOS_DISTRIBUTION&limit=50").get("data", [])
    for c in data:
        a = c["attributes"]
        print(f"  · existing {a.get('certificateType')} cert {a.get('name')!r} #{a.get('serialNumber')} expires {a.get('expirationDate', '')[:10]}")
    return data


def create_certificate(asc: ASC, out: str, subject: str) -> tuple[dict, str]:
    key_pem = os.path.join(out, "distribution-key.pem")
    csr_pem = os.path.join(out, "distribution.csr")
    run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", key_pem, "-out", csr_pem, "-subj", subject])
    os.chmod(key_pem, 0o600)
    csr = open(csr_pem).read()
    cert = asc.call("POST", "/certificates", {"data": {"type": "certificates", "attributes": {"certificateType": "DISTRIBUTION", "csrContent": csr}}})["data"]
    der = base64.b64decode(cert["attributes"]["certificateContent"])
    cer_path = os.path.join(out, "distribution.cer")
    open(cer_path, "wb").write(der)
    pem_path = os.path.join(out, "distribution.pem")
    open(pem_path, "wb").write(run(["openssl", "x509", "-inform", "DER", "-in", cer_path]))
    print(f"✓ created Apple Distribution certificate {cert['attributes'].get('name')!r}, expires {cert['attributes'].get('expirationDate', '')[:10]}")
    return cert, key_pem


def download_wwdr(out: str) -> str | None:
    chain = b""
    for url in WWDR_URLS:
        try:
            with urllib.request.urlopen(url, timeout=30) as res:
                der = res.read()
            tmp = os.path.join(out, os.path.basename(url))
            open(tmp, "wb").write(der)
            chain += run(["openssl", "x509", "-inform", "DER", "-in", tmp])
        except Exception as e:  # the chain is a convenience for Keychain Access; the workflow imports WWDR itself
            print(f"  (could not fetch {url}: {e})")
    if not chain:
        return None
    path = os.path.join(out, "AppleWWDR-chain.pem")
    open(path, "wb").write(chain)
    return path


def export_p12(out: str, key_pem: str, password: str, chain: str | None) -> str:
    p12 = os.path.join(out, "distribution.p12")
    cmd = ["openssl", "pkcs12", "-export", "-inkey", key_pem, "-in", os.path.join(out, "distribution.pem"), "-out", p12, "-passout", f"pass:{password}"]
    if chain:
        cmd += ["-certfile", chain]
    version = subprocess.run(["openssl", "version"], capture_output=True, text=True).stdout
    if version.startswith("OpenSSL 3"):
        # OpenSSL 3 defaults (AES/PBKDF2) are rejected by older `security import`; -legacy matches what Keychain Access produces.
        res = subprocess.run(cmd + ["-legacy"], capture_output=True)
        if res.returncode != 0:
            run(cmd)
    else:
        run(cmd)
    os.chmod(p12, 0o600)
    return p12


def ensure_profile(asc: ASC, name: str, bundle: dict, cert: dict, out: str) -> str:
    existing = asc.call("GET", "/profiles", params=f"?filter[name]={urllib.parse.quote(name)}&filter[profileType]=IOS_APP_STORE").get("data", [])
    for p in existing:
        if p["attributes"]["name"] == name:
            asc.call("DELETE", f"/profiles/{p['id']}")
            print(f"  · replaced the previous {name!r} profile")
    body = {"data": {"type": "profiles", "attributes": {"name": name, "profileType": "IOS_APP_STORE"},
                     "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}},
                                       "certificates": {"data": [{"type": "certificates", "id": cert["id"]}]}}}}
    prof = asc.call("POST", "/profiles", body)["data"]
    path = os.path.join(out, "EasyEsuite-Mobile-App-Store.mobileprovision")
    open(path, "wb").write(base64.b64decode(prof["attributes"]["profileContent"]))
    print(f"✓ provisioning profile {name!r} created (UUID {prof['attributes'].get('uuid')}, expires {prof['attributes'].get('expirationDate', '')[:10]})")
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--key-id", required=True, help="App Store Connect API key ID (10 characters)")
    ap.add_argument("--issuer-id", required=True, help="App Store Connect API issuer ID (UUID)")
    ap.add_argument("--key", required=True, help="path to the downloaded AuthKey_<KEYID>.p8")
    ap.add_argument("--bundle-id", default="com.easyesuite.mobile")
    ap.add_argument("--app-name", default="EasyEsuite Mobile", help="name for the bundle ID record")
    ap.add_argument("--profile-name", default="EasyEsuite Mobile App Store", help="must match ios/ExportOptions.plist and the workflow")
    ap.add_argument("--subject", default="/CN=EasyEsuite Mobile Distribution/O=EasyEsuite/C=US", help="CSR subject")
    ap.add_argument("--out", default="./asc-out", help="output folder (keep it private; add to .gitignore)")
    ap.add_argument("--p12-password", default=None, help="password for the .p12 (generated when omitted)")
    args = ap.parse_args()

    if not shutil.which("openssl"):
        sys.exit("openssl not found on PATH")
    key_path = os.path.expanduser(args.key)
    if not os.path.exists(key_path):
        sys.exit(f"API key not found: {key_path}")
    os.makedirs(args.out, exist_ok=True)

    asc = ASC(make_jwt(args.key_id, args.issuer_id, key_path))
    print("Signed in to App Store Connect API as key", args.key_id)

    bundle = ensure_bundle_id(asc, args.bundle_id, args.app_name)
    print("Distribution certificates already on the team (Apple allows only a few — revoke unused ones in the portal if creation fails):")
    list_distribution_certs(asc)
    cert, key_pem = create_certificate(asc, args.out, args.subject)
    password = args.p12_password or secrets.token_urlsafe(18)
    chain = download_wwdr(args.out)
    p12 = export_p12(args.out, key_pem, password, chain)
    profile = ensure_profile(asc, args.profile_name, bundle, cert, args.out)

    p12_b64 = base64.b64encode(open(p12, "rb").read()).decode()
    team_hint = bundle["attributes"].get("seedId") or "<your Team ID — Membership page in developer.apple.com>"
    secrets_txt = os.path.join(args.out, "github-secrets.txt")
    with open(secrets_txt, "w") as f:
        f.write(f"APPLE_TEAM_ID={team_hint}\n")
        f.write(f"ASC_KEY_ID={args.key_id}\n")
        f.write(f"ASC_ISSUER_ID={args.issuer_id}\n")
        f.write(f"ASC_API_KEY_P8=<contents of {key_path}>\n")
        f.write(f"DIST_CERT_P12_BASE64={p12_b64}\n")
        f.write(f"DIST_CERT_PASSWORD={password}\n")
        f.write(f"PROVISIONING_PROFILE_BASE64={base64.b64encode(open(profile, 'rb').read()).decode()}\n")
    os.chmod(secrets_txt, 0o600)

    print(f"""
Done. Files in {args.out}/ (private — do not commit):
  distribution.p12 / distribution-key.pem / distribution.cer   the signing identity
  EasyEsuite-Mobile-App-Store.mobileprovision                   the App Store profile
  github-secrets.txt                                            every value below

Add these repository secrets (Settings → Secrets and variables → Actions), or run from the repo root:

  gh secret set APPLE_TEAM_ID --body "{team_hint}"
  gh secret set ASC_KEY_ID --body "{args.key_id}"
  gh secret set ASC_ISSUER_ID --body "{args.issuer_id}"
  gh secret set ASC_API_KEY_P8 < "{key_path}"
  gh secret set DIST_CERT_P12_BASE64 --body "$(base64 < {p12} | tr -d '\\n')"
  gh secret set DIST_CERT_PASSWORD --body "{password}"
  gh secret set PROVISIONING_PROFILE_BASE64 --body "$(base64 < {profile} | tr -d '\\n')"

APPLE_TEAM_ID above is the bundle ID's seed prefix, which is the Team ID for a company's primary team —
double-check it against developer.apple.com → Membership details.
Then: GitHub → Actions → "TestFlight" → Run workflow.""")


if __name__ == "__main__":
    main()
