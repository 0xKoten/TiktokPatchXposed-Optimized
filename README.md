# TiktokPatchXposed 優化版 (Optimized Edition)

> 本專案基於 [hik0w/TiktokPatchXposed](https://github.com/hik0w/TiktokPatchXposed) 開源專案進行深度優化與功能擴充。
> 由衷感謝原作者 **hik0w** 提供的優秀模組架構與開源基礎！

---

## 🌟 優化版特色功能 (What's New & Optimized)

本優化版針對最新版 TikTok (47.5+) 進行全方位介面修復、版面優化與全新功能擴展：

1. **徹底消除上下黑邊 (全屏沉浸播放)**
   - 消除影片播放時的上下留白與黑邊，讓短影片全螢幕無縫鋪滿。
   - **全面適配全 Feed 分頁**：包括「為您推薦」、「關注中」、「好友」、「社群」等所有頁面，切換分頁時黑邊不復發。
   - 修復下一個較長影片頂部邊緣在底部露出（Peeking edge）的問題。

2. **Android 沉浸式全螢幕**
   - **隱藏系統導航欄**：支援隱藏 Android 虛擬三鍵及底部白色手勢小橫條（Gesture Pill），防止推擠或遮擋畫面。
   - **隱藏系統狀態欄**：支援隱藏頂部電量、時間及通知圖示，帶來極致視覺體驗。

3. **頂欄與底欄自定義透明度**
   - **頂部導航標籤透明度**（推薦、關注中、朋友等）：支援 0% 至 100% 任意平滑調節。
   - **底部導航欄透明度**（首頁、朋友、收件箱、個人資料）：支援 0% 至 100% 任意平滑調節。
   - 解決原生「簡易顯示（清屏模式）」退出時背景重置回純黑的問題，常駐保持透明。

4. **獨立移除底欄中間「+」發布按鈕**
   - 可一鍵刪除中間的建立/發布按鈕。
   - 剩餘 4 個標籤（首頁、朋友、收件箱、個人資料）自動重新均分螢幕寬度（各佔 25%），版面平衡協調。

5. **修復拖動影片進度條時 UI 復發問題**
   - 原版在手動拖動底部進度條時，右側按鈕（頭像、點讚、評論、分享）與作者文案會被官方邏輯重置顯現。
   - 優化版透過 `OnPreDraw` 與屬性攔截，確保拖動進度條期間及釋放後，隱藏與透明度設定持續生效。

6. **自訂頂部左上角 LIVE 直播按鈕**
   - 原版模塊因設定衝突，會導致開啟模塊時首頁左上角 LIVE 直播圖示被強制隱藏且按鈕失效。
   - 優化版徹底解耦修復，預設完整保留 TikTok 原生 LIVE 入口與點擊功能；並在設定內提供獨立開關，方便自由選擇是否隱藏。

7. **獨立「隱藏與透明度」專屬設定頁**
   - 將介面隱藏選項與透明度滑桿從複雜的次級選單中抽離，獨立成整齊乾淨的雙卡片介面（隱藏設定 / 透明度調節）。

8. **獨立 Launcher 桌面入口與模組內感謝名單**
   - 桌面提供獨立圖示直接開啟模組設定，無需依賴 TikTok 內部路徑。
   - 設定介面內建感謝名單與原專案連結。

---

## 🛠️ 原版經典核心特性保留

- 🚫 **全面阻擋廣告**：Feed 影片串流廣告、啟動開屏廣告、搜尋廣告全面淨化。
- 📥 **無水印與全畫質下載**：強制解鎖原畫下載、音訊單獨儲存、輪播圖集原圖批量下載。
- 🔄 **全局自動滾動**：影片播放結束後自動滑動至下一部。
- 🎨 **主題強調色與 AMOLED 純黑模式**：替換 TikTok 紅色主色調，純黑 AMOLED 背景更省電。
- 🌐 **多國區域偽裝與免登入瀏覽**：解鎖區域內容限制，繞過強制登入彈窗。
- 💬 **評論自動翻譯與無限制標籤**：評論自動翻譯，解除發布影片標籤數量限制。
- 🛡️ **隱身模式**：隱藏觀看限時動態紀錄、隱藏個人主頁造訪紀錄。

---

## 📦 編譯指南 (Building from Source)

### 環境需求
- **JDK**：OpenJDK 17 或以上
- **Android SDK**：API 34+ (Build-Tools 34.0.0+)
- **Gradle**：8.7 (已包含 Gradle Wrapper)

### 本地編譯
使用專案內建的 Gradle Wrapper 進行編譯：

```bash
# Windows
.\gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

編譯生成的 APK 位於：
`app/build/outputs/apk/release/app-release.apk`

---

## 📌 上傳至 GitHub 注意事項 (GitHub Upload Checklist)

若您打算將此資料夾上傳至 GitHub 建立自己的倉庫，請務必留意以下幾點：

1. **嚴禁上傳 build 產物與快取（已由 `.gitignore` 保護）**：
   - 請勿上傳 `.gradle/`、`build/`、`app/build/`、`.idea/`、`local.properties` 等本機快取與編譯暫存檔。
   - 本資料夾已為您建立好標準的 `.gitignore`，請確保不要將這些檔案加入 git 追蹤。

2. **請勿提交私有金鑰（Keystore）**：
   - 若未來產生專用的發布金鑰（`*.jks`、`*.keystore`）或包含密碼的 `key.properties`，切勿提交到公開倉庫。

3. **保留原作者署名與開源授權**：
   - 本專案基於 **hik0w/TiktokPatchXposed** 衍生而來，請保留 README 中的致謝與原始倉庫連結，遵守開源社群禮節。

4. **建議的上傳步驟（使用 Git 命令列）**：
   ```bash
   cd c:\Users\AoxiangDD\Documents\antigravity\TiktokPatchXposed-Optimized
   git init
   git add .
   git commit -m "feat: initial commit for TiktokPatchXposed Optimized Edition"
   git branch -M main
   git remote add origin https://github.com/您的用戶名/您的倉庫名.git
   git push -u origin main
   ```
   *(亦可直接使用 GitHub Desktop 選擇「Add Existing Repository」後發布至 GitHub)*

---

## ❤️ 鳴謝 (Acknowledgments)

- 原版專案：[hik0w/TiktokPatchXposed](https://github.com/hik0w/TiktokPatchXposed)
- 感謝原作者 **hik0w** 以及所有為開源 Xposed / LSPosed 生態做出貢獻的開發者！

---

## ⚖️ 免責聲明 (Disclaimer)

本專案僅供程式技術研究與學習交流使用。請遵守相關法律法規以及第三方平台的使用者服務協議。
