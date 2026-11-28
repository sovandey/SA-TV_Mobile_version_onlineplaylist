# SA-TV – Update guide (playlist, app update, broken channel)

Ei guide-e 4 ta jinish ache: (1) ek bar setup, (2) channel add/remove, (3) app update sobar kache pathano, (4) channel kharap hole ki hoy.

---

## 1. Ek bar-er setup (shudhu prothombar)

1. GitHub repository **Public** rakho (nahole app playlist porte parbe na).
   Repo → Settings → General → neeche **Change visibility → Public**.
2. Repo → **Settings → Actions → General → Workflow permissions** → **Read and write permissions** select kore **Save**.
3. Signing key (app update-er jonno must):
   `SA-TV-SECRETS-GITHUB.txt` file-e 2 ta secret ache. Repo → **Settings → Secrets and variables → Actions → New repository secret**
   - `KEYSTORE_PASSWORD`
   - `KEYSTORE_BASE64`
   Ei file **kokhono repo-te upload korbe na**. Backup rakho. Key harale pore ar update install hobe na.
4. Ei zip-er shob file repo-te upload koro (purano file replace).
5. **Actions → SA-TV Android APK Build → Run workflow** (publish tick chhara) → APK banabe → **Artifacts → SA-TV-apk** theke download kore phone-e install koro / bondhuder dao.
   Ei holo tomar **prothom version**. Ekhon theke sobai update pabe.

---

## 2. Channel add / remove – sobai automatic pabe (APK update lagbe na)

File: **`playlist/channels.m3u`**  (GitHub-e file khule ✏️ pencil → edit → **Commit changes**)

Notun channel add:

```
#EXTINF:-1 tvg-logo="https://.../logo.png" group-title="Indian Bengali",Channel Name
https://example.com/live/index.m3u8
```

- `group-title` ei 6 ta-r ekta hote hobe: `Indian Bengali`, `Bangladesh`, `Hindi`, `Sports`, `FM Radio`, `Kids`
- Channel remove: oi 2 line (`#EXTINF...` ar link) delete kore commit.
- Backup link: **same naam-e** aro ekta `#EXTINF` + link niche add koro. Prothom link na chollle app nijei porer link try kore.
- Sports / Kids / Hindi / FM Radio-te app nijeo internet theke kichu channel ane. Tumi chaile specific kono channel hide korte `playlist/config.json`-er `"blocked"` list-e naam likho: `"blocked": ["Channel Name"]`. Shob auto channel bondho korte `"autoSources": false`.

Users-er app jokhon khule (ba ⟳ chape) tokhon notun list pay. Commit korar pore 5 minute-er moddhe (GitHub cache).

---

## 3. App update sobar kache pathano

1. Code change (ba notun file upload) koro.
2. **Actions → SA-TV Android APK Build → Run workflow**
   - **publish** ✔ tick koro
   - **notes** e likho notun ki ache (user-er popup-e dekhabe)
3. 3-5 minute pore: **Releases**-e notun version, ar `playlist/config.json` auto update hoye jabe.
4. Users app khulle popup dekhbe: **"Update available → Update now"**. Button chaple APK download hoy, install korte hoy.

**Zoruri update (force):** `playlist/config.json`-e `"minVersionCode"` ke latest version code-er soman kore dile purano app-e popup bondho kora jay na — update chhara cholbe na.

Note: Android-e "unknown source" install-er permission user ke dite hobe (prothombar).

---

## 4. Channel kharap hole ki hoy (automatic)

**A. App nijei backup link try kore** – channel-er ekadhik link thakle.

**B. Protiti 6 ghonta-y GitHub nijei shob link test kore** (`Check channels` workflow):
- cholche emon link age rakhe
- sob link fail hole public iptv-org list theke same `tvg-id`-r notun link khuje ber kore prothom link banay
- `playlist/STATUS.md`-e dekhay kon channel ekhono kaj korche na
- Tumi manually **Actions → Check channels → Run workflow** chalate paro.

**C. User report korle:** player-e **Report** (ba "Report broken channel") chaple GitHub Issue page khole (title `Broken: Channel Name`). Submit korle workflow auto channel check kore, comment kore, issue close kore.

### Sima (jana dorkar)
- GitHub server USA-te. India/Bangladesh-only channel oikhan theke "dead" dekhte pare, tai amra **kokhono channel auto-delete ba hide kori na** – shudhu STATUS.md-e dekhai. Remove korar siddhanto tomar.
- Report button-e user-er GitHub account lagbe. Account chhara report-er jonno alada server lagbe (Google Form / Telegram bot) – pore korte pari.
- Public repo-te playlist shobai dekhte pabe.

---

## 5. Update option kaj korche ki na – kibhabe check korbe

**Sohoj test (APK banate hobe na):**
1. App-er ekdom niche **"SA-TV v1.0.N (build N) – Check for updates"** ache. Version number header-eo (SA-TV-r niche) dekha jay.
2. GitHub-e `playlist/config.json` kholo, ✏️ edit kore likho:
   `"latestVersionCode": 999, "latestVersionName": "9.9.9", "apkUrl": "https://example.com/test.apk", "notes": "test"`
   → Commit. 5 minute opekkha koro (GitHub cache).
3. App-e **Check for updates** chaplei popup ashbe: **"Update available"**. Test shesh hole config.json ager moto (`1`) kore dao.
4. Update na thakle app likhe dekhay: **"You have the latest version"**.

**Asol test:**
1. Build #N APK install koro (phone-e).
2. Kono ekta choto change kore **Run workflow → publish ✔** chalao (build #N+1 hobe).
3. Purano app (#N) khulo → popup ashbe → **Update now** → APK nambe → install. Install-er por version number #N+1 dekhabe.

---

## 6. Android TV-te install kora

1. TV-r Play Store theke **Downloader** (by AFTVnews) app install koro.
2. TV **Settings → Apps → Security & restrictions → Install unknown apps → Downloader → ON**.
3. Downloader khule URL box-e likho (tomar repo-r nam diye):
   `https://github.com/OWNER/REPO/releases/latest/download/SA-TV.apk`
   → **Go** → download hole **Install**. (Age ekbar **publish ✔** diye release banate hobe.)
   Onno upay: pen drive-e `SA-TV.apk` rekhe TV-r file manager diye install, ba "Send Files to TV" app.
4. SA-TV TV-r app list-e banner soho dekhabe.

**TV remote:**
- **Up** = porer channel, **Down** = ager channel (CH+ / CH- button-o kaj kore)
- **OK** = controls dekhao / play-pause, **Left/Right** = button-e jao, **Back** = bahire
- Home screen-e arrow key diye category ar channel select kore **OK** chaplei chole.

## 7. Notun ja ja ache (ei version)
- Lock screen / screen off holeo **FM radio chole** (notification-e play/pause dekhay). TV channel screen lock-e pause hoy.
- Video **resolution** menu (AUTO, 1080p, 720p…): stream ja ja quality dey shudhu setai dekhabe.
- **Pop-up video (PiP)**: player-er upor-e pop-up button, ba Home chaple video chhoto window-e chole.
