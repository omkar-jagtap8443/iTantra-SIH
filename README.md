# iTantra — Offline Voice Communication App

An Android app for **offline voice communication** using Bluetooth transport and
on-device speech recognition (Vosk). Designed for situations where network
connectivity is unreliable or unavailable.

---

## ✨ Features

- Offline speech-to-text using Vosk (English-IN, Gujarati, Hindi, Telugu)
- Bluetooth peer-to-peer transport
- SOS alert activity
- Background message service
- No internet required at runtime

## 🛠 Tech Stack

| Layer | Tech |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Speech | [Vosk](https://alphacephei.com/vosk/) offline models |
| Transport | Bluetooth (RFCOMM) |
| Build | Gradle (Kotlin DSL), AGP 8.x |

---

## 🚨 IMPORTANT — Read Before Building

The Vosk speech models are **large (~40 MB each)** and are **NOT stored in this
repository**. They are listed in `.gitignore` because pushing them breaks GitHub
(GitHub rejects any single file over 100 MB).

**You MUST download and place them manually before the app will work.**

If you skip this step, the speech recognition feature will crash at runtime
with a "model not found" error.

---

## 🚀 Setup — Step by Step

### 1. Install required tools

- **Android Studio** (latest stable) — https://developer.android.com/studio
- **JDK 17+** — https://adoptium.net/
- **Git** — https://git-scm.com/downloads
- A **physical Android device** (Bluetooth transport won't work in emulator)

### 2. Clone the repository

```bash
git clone git@github.com:omkar-jagtap8443/iTantra-SIH.git
cd iTantra-SIH
```

If SSH isn't set up, use HTTPS instead:

```bash
git clone https://github.com/omkar-jagtap8443/iTantra-SIH.git
```

### 3. Switch to your branch

```bash
# Pick the branch assigned to you
git checkout dev-pritesh      # or dev-sakshi / dev-vishwas / dev-yogi / dev-omkar
```

If your local branch is out of sync (e.g. after a repo cleanup), reset it:

```bash
git fetch origin
git reset --hard origin/dev-<yourname>
```

### 4. Download the Vosk models (REQUIRED)

You need **4 models**. Each is about 40 MB — total ~160 MB.

| Model | Language |
|---|---|
| `vosk-model-small-en-in-0.4` | English (India) |
| `vosk-model-small-gu-0.42` | Gujarati |
| `vosk-model-small-hi-0.22` | Hindi |
| `vosk-model-small-te-0.42` | Telugu |

#### ✅ Easiest way — run the helper script

**Windows (PowerShell)** — from the project root:

```powershell
.\download-models.ps1
```

**macOS / Linux** — from the project root:

```bash
chmod +x download-models.sh
./download-models.sh
```

The script downloads and extracts all 4 models into `app/src/main/assets/`.

#### 🔧 Manual way — if you prefer

1. Go to https://alphacephei.com/vosk/models
2. Download these 4 zip files:
    - `vosk-model-small-en-in-0.4.zip`
    - `vosk-model-small-gu-0.42.zip`
    - `vosk-model-small-hi-0.22.zip`
    - `vosk-model-small-te-0.42.zip`
3. Extract each one. You get folders like `vosk-model-small-en-in-0.4/`.
4. Move those folders into `app/src/main/assets/` so the layout is:

```
app/src/main/assets/
├── vosk-model-small-en-in-0.4/
├── vosk-model-small-gu-0.42/
├── vosk-model-small-hi-0.22/
└── vosk-model-small-te-0.42/
```

#### ✅ Verify the models are in place

Run this from the project root:

```bash
ls app/src/main/assets/
```

You should see all 4 `vosk-model-*` folders. If you see `.zip` files instead
of folders, you forgot to extract them.

### 5. Open the project in Android Studio

1. Android Studio → **File → Open**
2. Select the cloned `iTantra-SIH` folder
3. Wait for Gradle sync to finish (may take a few minutes the first time)

If Gradle sync fails:

```bash
./gradlew --refresh-dependencies
```

### 6. Connect your Android device

1. On the phone: **Settings → About phone → tap "Build number" 7 times**
2. **Settings → Developer options → enable USB debugging**
3. Connect via USB, accept the "Allow USB debugging?" prompt on the phone

### 7. Run the app

1. In Android Studio, select your device from the dropdown
2. Click ▶️ **Run**
3. Wait for build + install

### 8. Test speech recognition

- Open the app
- Trigger the speech feature
- If you get "model not found", go back to Step 4

---

## 🌿 Branch Workflow

| Branch | Purpose |
|---|---|
| `main` | Stable / integration |
| `development` | Integration / staging |
| `dev-omkar` | Omkar's work |
| `dev-pritesh` | Pritesh's work |
| `dev-sakshi` | Sakshi's work |
| `dev-vishwas` | Vishwas's work |
| `dev-yogi` | Yogi's work |

**Rules:**

- Never commit directly to `main` or `development`
- Commit to your own `dev-<yourname>` branch
- Open a pull request when your work is ready to merge

---

## ⚠️ Never Commit These

The `.gitignore` handles these automatically:

- `.gradle/`, `build/`, `app/build/` — build caches
- `.idea/`, `*.iml`, `local.properties` — IDE / local machine config
- `app/src/main/assets/vosk-model-*/` — Vosk models (too large)
- `*.jks`, `*.keystore`, `keystore.properties`, `.env` — signing keys and secrets
- `__pycache__/`, `.venv/`, `venv/` — Python (if any)

### 🚫 Never commit a file larger than 100 MB

GitHub rejects any single file over 100 MB. If you need to add a large asset:

- **Git LFS** — https://git-lfs.github.com/
- **GitHub Releases** — attach the file to a release instead
- **External host** — Google Drive / S3, with a download script in the repo

Doing this wrong means the whole team has to do a history rewrite.

---

## 🧪 Troubleshooting

### ❌ `error: failed to push some refs ... file is 123.45 MB`

You have a file over 100 MB in your commit. Remove it from the commit,
add the path to `.gitignore`, and re-commit.

### ❌ `error: Your local changes would be overwritten by merge`

```bash
git stash               # save your changes
git pull                # update
git stash pop           # restore your changes
```

### ❌ Gradle sync fails

```bash
./gradlew --refresh-dependencies
```

If it still fails, install the exact Android SDK version listed in
`app/build.gradle.kts` via **SDK Manager → SDK Platforms**.

### ❌ App crashes with "Speech model not found"

You skipped **Step 4 — Download the Vosk models**. Go back and run the script.

### ❌ Bluetooth doesn't connect

- Confirm Bluetooth is enabled on both devices
- Bluetooth transport doesn't work on emulators — use physical devices
- Grant Bluetooth permissions when prompted

### ❌ `fatal: refusing to merge unrelated histories`

Your local branch is from an old, unrelated Git history (usually after a repo
cleanup). Reset your branch to the remote:

```bash
git fetch origin
git reset --hard origin/dev-<yourname>
```

⚠️ **This discards local commits on that branch.** Only do it if you have
nothing unpushed you want to keep. Back it up first if unsure:

```bash
git branch backup-<yourname>-$(date +%Y%m%d)
```

### ❌ `src refspec ... does not match any`

You typed a branch name Git can't find. Check what exists:

```bash
git branch -a
```

---

## 🤝 Contributing

1. `git fetch origin`
2. `git checkout dev-<yourname>`
3. `git reset --hard origin/dev-<yourname>` (only if needed to resync)
4. Make your changes
5. `git add .`
6. `git commit -m "Short description of what changed"`
7. `git push origin dev-<yourname>`
8. Open a Pull Request on GitHub when ready to merge

---

## 📁 Project Structure

```
app/src/main/java/com/example/itantra/
├── MainActivity.kt
├── data/model/          # Wire protocol models
├── service/             # Background services
├── speech/              # VoskSttManager — offline STT
├── transport/           # BluetoothServer, BluetoothTransport
└── ui/theme/            # Compose theme
```

---

## 👥 Contributors

- Omkar Jagtap (@omkar-jagtap8443)
- Pritesh, Sakshi, Vishwas, Yogi

---

## 📄 License

See `LICENSE` (if present). Otherwise all rights reserved by the project owners.