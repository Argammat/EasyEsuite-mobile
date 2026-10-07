# Shipping the iOS app to TestFlight

Two ways to get a build onto testers' phones. **A** needs no Mac and is the one the team should use going
forward; **B** is the classic Xcode route if someone has a Mac handy today.

Both need an **Apple Developer Program** membership for EasyEsuite (Organization enrollment at
<https://developer.apple.com/programs/enroll/> — company legal entity, a D‑U‑N‑S number, US$99/year; Apple's
approval usually takes 1–3 business days). The Account Holder then adds teammates in App Store Connect →
Users and Access.

---

## A. From GitHub Actions (no Mac)

### 1. Create an App Store Connect API key (once)

App Store Connect → **Users and Access → Integrations → App Store Connect API → Team Keys → ⊕**
Name `GitHub TestFlight`, access **App Manager** (Admin also works). Download the `AuthKey_XXXXXXXXXX.p8`
file — Apple lets you download it exactly once — and note the **Key ID** and the **Issuer ID** shown above
the table.

### 2. Run the bootstrap script (once; needs python3 + openssl — any Mac, Linux or WSL shell)

```bash
git clone https://github.com/Argammat/EasyEsuite-mobile.git && cd EasyEsuite-mobile
python3 ios/scripts/asc-bootstrap.py \
  --key-id XXXXXXXXXX \
  --issuer-id 00000000-0000-0000-0000-000000000000 \
  --key ~/Downloads/AuthKey_XXXXXXXXXX.p8 \
  --out ./asc-out
```

It registers the bundle ID `com.easyesuite.mobile`, creates an **Apple Distribution** certificate whose private
key is generated locally (packed as `distribution.p12`), creates the **"EasyEsuite Mobile App Store"**
provisioning profile, and prints the seven secrets the workflow needs together with ready-to-paste
`gh secret set …` commands. `asc-out/` holds a private key — keep it somewhere safe (a password manager),
never in git (it is git-ignored).

> Apple allows only a few distribution certificates per team. If the script fails with a "maximum number of
> certificates" error, revoke an unused one at developer.apple.com → Certificates and run it again.
> Revoking a distribution certificate does **not** affect builds already in TestFlight or the App Store.

### 3. Create the app record (once, manual — App Store Connect has no API for this)

App Store Connect → **My Apps → ⊕ → New App**: Platforms *iOS*, Name `EasyEsuite`, Primary language,
Bundle ID `com.easyesuite.mobile` (it is in the list after step 2), SKU `easyesuite-mobile`, User access *Full*.

### 4. Add the GitHub secrets (once)

Repository → Settings → Secrets and variables → Actions → *New repository secret*, or run the `gh secret set`
lines the script printed:

| Secret | Value |
|---|---|
| `APPLE_TEAM_ID` | 10-character Team ID (developer.apple.com → Membership details; the script prints the likely value) |
| `ASC_KEY_ID` / `ASC_ISSUER_ID` | from step 1 |
| `ASC_API_KEY_P8` | the full contents of the `.p8` file |
| `DIST_CERT_P12_BASE64` / `DIST_CERT_PASSWORD` | from `asc-out/github-secrets.txt` |
| `PROVISIONING_PROFILE_BASE64` | from `asc-out/github-secrets.txt` |

Optional *variables* (`vars.`): `EASYESUITE_API_ROOT` (defaults to production), `EASYESUITE_FIREBASE_TENANT_ID`;
optional secret `EASYESUITE_FIREBASE_API_KEY` if the team switches sign-in to Firebase (see `API_MAP.md` → Auth).

### 5. Ship a build

**Actions → TestFlight → Run workflow** (optionally type the version, e.g. `0.1.0`), or tag a commit:

```bash
git tag ios-v0.1.0 && git push origin ios-v0.1.0
```

The job (~15 min on Apple's side of GitHub) archives the Release build, signs it with the distribution
certificate + profile, and uploads it. The build number is the workflow run number, so every upload is unique.
App Store Connect then *processes* the build for 10–30 minutes (you get an email) and it appears under
**TestFlight → iOS builds**.

### 6. Testers

* **Internal testing** (what we want first): TestFlight → Internal Testing → ⊕ group "EasyEsuite team" → add
  people. They must be members of the App Store Connect team (Users and Access → ⊕, any role, with
  *TestFlight* ticked for the app). Up to 100 people, **no Apple review**, every new build is pushed to them
  automatically. Each tester installs the *TestFlight* app from the App Store and accepts the email invite.
* **External testing** (warehouse staff, customers): up to 10,000 testers via a public link, but the first build
  of each version goes through *Beta App Review* (usually < 24 h) and needs a demo login in *Test Information*.
  Do this once sign-in and scanning are confirmed on real devices.

---

## B. From Xcode on a Mac

1. Xcode 15 or newer from the App Store; sign in with your Apple ID (Xcode → Settings → Accounts) — the
   account must be on the EasyEsuite team.
2. `brew install xcodegen`, then in the repo: `cd ios && xcodegen generate && open EasyEsuite.xcodeproj`.
3. Target *EasyEsuite* → **Signing & Capabilities** → tick *Automatically manage signing*, pick the EasyEsuite team.
4. Plug in an iPhone once and run the app on it (⌘R). This registers the device so Xcode can create the
   development profile, and it is the first real-device test of sign-in and the barcode scanner.
5. Product → **Archive** → in the Organizer **Distribute App → TestFlight & App Store → Upload** (keep the
   defaults: upload symbols, automatic signing). Xcode creates the distribution certificate and App Store
   profile for you on first use.
6. Same as A5/A6 from here: wait for processing, add internal testers.

The App Store Connect app record (A3) must exist before the upload either way.

---

## What is already in the repo for this

| Item | Where |
|---|---|
| 1024×1024 App Store icon (the web app's icon mark) | `ios/EasyEsuite/Resources/Assets.xcassets/AppIcon.appiconset/` |
| Privacy manifest (UserDefaults reason CA92.1; email + photos declared) — required since May 2024 | `ios/EasyEsuite/Resources/PrivacyInfo.xcprivacy` |
| Export compliance answered (`ITSAppUsesNonExemptEncryption = NO`, HTTPS only) — no "Missing Compliance" prompt | `ios/project.yml` |
| Camera / photo-library usage strings | `ios/project.yml` |
| Export options (App Store Connect upload, manual signing with the bootstrap profile) | `ios/ExportOptions.plist` |
| The workflow | `.github/workflows/testflight.yml` |
| One-time App Store Connect setup script | `ios/scripts/asc-bootstrap.py` |
| Version `0.1.0`; build number = workflow run number | `ios/project.yml`, workflow |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Workflow stops at *Check secrets* | add the missing secret(s) from `asc-out/github-secrets.txt` |
| `No signing certificate "iOS Distribution" found` / identity "not trusted" | the .p12 or password secret is wrong, or the WWDR import failed — re-run the bootstrap and update `DIST_CERT_P12_BASE64` + `DIST_CERT_PASSWORD` |
| `Provisioning profile … doesn't include signing certificate` | the certificate was revoked/replaced: re-run the bootstrap (it recreates the profile) and update `PROVISIONING_PROFILE_BASE64` |
| `Maximum number of certificates generated` | revoke an unused distribution certificate at developer.apple.com → Certificates |
| Upload error `ITMS-90xxx` about the bundle ID / app record | create the app record (A3) with exactly `com.easyesuite.mobile` |
| `The bundle version must be higher than the previously uploaded version` | only happens if someone uploads from Xcode with a higher build number — re-run the workflow (run numbers keep increasing) or bump `CURRENT_PROJECT_VERSION` |
| Build "processing" for more than an hour | normal on busy days; Apple emails when done or if it was rejected (ITMS-91053 = privacy manifest, ITMS-90717 = icon) |
| Certificate or profile expired (after one year) | re-run the bootstrap, update the three cert/profile secrets |

## Before external TestFlight or the App Store

* A **demo login** for Apple's reviewers (a workspace with sample data, non-production), entered in TestFlight →
  Test Information and App Store → App Review Information.
* **App Privacy** answers in App Store Connect — Email Address and Photos (app functionality, linked to the user,
  not used for tracking) — matching `PrivacyInfo.xcprivacy`.
* Privacy policy URL, support URL, category (Business), age rating, 6.7" and 6.1" iPhone screenshots
  (the design canvas screens can be exported for a first set).
