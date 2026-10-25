# TiktokPatchXposed (Optimized Edition)

[繁體中文](README.md) | [English](README_EN.md) | [简体中文](README_ZH-CN.md) | [日本語](README_JA.md) | [Русский](README_RU.md) | [Español](README_ES.md)

> This project is a deeply optimized and feature-expanded fork based on [hik0w/TiktokPatchXposed](https://github.com/hik0w/TiktokPatchXposed).  
> Sincere thanks to the original author **hik0w** for the outstanding module architecture and open-source foundation!

---

## 🌟 What's New & Optimized

This optimized edition brings comprehensive interface fixes, layout refinements, and new features tailored for modern TikTok releases (47.5+):

1. **Complete Black Bar Removal (True Full-Screen Immersive Playback)**
   - Eliminates top and bottom letterboxing/pillarboxing for seamless full-screen short video playback.
   - **Full Feed Tab Compatibility**: Seamlessly adapts across "For You", "Following", "Friends", "STEM", and all other tabs without black bars recurring when switching tabs.
   - Resolves the peeking top edge of subsequent longer videos.

2. **Android Immersive Full Screen**
   - **Hide System Navigation Bar**: Hides the Android 3-button navigation and gesture navigation pill to prevent pushing or obstructing video content.
   - **Hide System Status Bar**: Hides clock, battery level, and notification icons for an uninterrupted visual experience.

3. **Custom Top & Bottom Bar Transparency**
   - **Top Navigation Bar Transparency** (For You, Following, Friends): Smoothly adjustable from 0% to 100%.
   - **Bottom Navigation Bar Transparency** (Home, Friends, Inbox, Profile): Smoothly adjustable from 0% to 100%.
   - Fixes native TikTok behavior where exiting "Clear Display" resets the background back to solid black, keeping it permanently transparent.

4. **Independent Removal of Bottom Center "+" Upload Button**
   - Cleanly remove the center upload button with one click.
   - Remaining 4 tabs (Home, Friends, Inbox, Profile) automatically re-balance across the screen width (25% each) for a symmetrical layout.

5. **Fixed UI Reappearance When Scrubbing Seekbar**
   - In stock TikTok, manually dragging the bottom progress bar resets right-side action buttons (avatar, like, comment, share) and author text back to visible.
   - The optimized edition intercepts draw calls so that your hide and transparency settings remain strictly in effect during and after scrubbing.

6. **Customizable Top-Left LIVE Button**
   - Resolves the stock module issue where enabling hooks caused the top-left LIVE button to vanish and become unresponsive.
   - Fully restores native LIVE access and click actions by default, with an independent switch in module settings to toggle visibility at will.

7. **Dedicated "Hide & Transparency" Settings Page**
   - Extracted interface visibility toggles and transparency sliders from crowded sub-menus into a clean, modern dual-card interface (Hide Options / Transparency Sliders).

8. **Standalone Launcher Entry & In-App Credits**
   - Adds a standalone home screen launcher icon to launch module settings directly without having to navigate inside TikTok.
   - Built-in credits page acknowledging the original project and author.

9. **Multi-Language Support (14 Languages)**
   - Built-in real-time language switcher supporting Traditional Chinese, Simplified Chinese, English, Japanese, Korean, Spanish, German, French, Russian, and more.
   - Switch language anytime inside the module or automatically follow system default.

---

## 🛠️ Original Core Features Retained

- 🚫 **Complete Ad Blocking**: Removes feed video ads, splash ads, and search ads.
- 📥 **No-Watermark & High-Quality Downloads**: Unlocks original quality video downloads, separate audio extraction, and full-resolution batch photo carousel downloads.
- 🔄 **Global Auto-Scroll**: Automatically scrolls to the next video upon playback completion.
- 🎨 **Accent Color & Pure AMOLED Dark Theme**: Customizes the red primary theme color and delivers true AMOLED black for battery saving.
- 🌐 **Region Spoofing & Login Bypass**: Unlocks geo-restricted content and skips forced login popups.
- 💬 **Auto-Comment Translation & Unlimited Hashtags**: Auto-translates foreign comments and removes hashtag count restrictions when posting.
- 🛡️ **Stealth Mode**: Hides story viewing history and profile visit footprints.

---

## 📦 Building from Source

### Requirements
- **JDK**: OpenJDK 17 or higher
- **Android SDK**: API 34+ (Build-Tools 34.0.0+)
- **Gradle**: 8.7 (Gradle Wrapper included)

### Build Commands
Compile using the included Gradle Wrapper:

```bash
# Windows
.\gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

The output APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

---

## ❤️ Acknowledgments

- Original Project: [hik0w/TiktokPatchXposed](https://github.com/hik0w/TiktokPatchXposed)
- Thank you to **hik0w** and all contributors in the open-source Xposed / LSPosed ecosystem!

---

## ⚖️ Disclaimer

This project is intended strictly for educational and technical research purposes. Please adhere to local laws, regulations, and third-party terms of service.
