# **LiveSplit for Android**

I built this app because I wanted a way to keep track of speedrun splits on my phone without needing to be tethered to a PC. It's a native Android app designed to run as a floating timer over your games or emulators.

If you’re a speedrunner who wants to practice on the go, this should help you track your PBs and segments right from your device.

## **🚀 Key Features**

* **Floating Timer Overlay:** It sits on top of your other apps. You can resize it, change the opacity, and move it wherever it's out of the way.  
* **.lss Support:** It’s compatible with standard LiveSplit files. You can import your splits from your PC and they'll work right out of the box.  
* **Hardware Keybinds:** I added support for mapping physical buttons (like volume up/down) to control the timer, so you don't have to fiddle with touch controls while playing.  
* **Customizable:** You can tweak font sizes and styles to make sure it's readable, even on smaller screens.  
* **Auto-Save:** It keeps a local autosave so if you accidentally swipe the app away, your current run isn't lost.

## **🏗 How to build it**

1. Clone the repo.  
2. Open it in Android Studio (I've been using Flamingo).  
3. Build and install it on an Android 13+ device.  
4. **Important:** You’ll need to grant the app "Display over other apps" and "Accessibility Service" permissions in your Android settings so it can show the timer and listen for physical button presses.


## **🛠 Troubleshooting (Restricted Settings)**

Since this app isn't from the Play Store, Android 13+ might block you from enabling Accessibility permissions for security reasons. If you get an "App was denied access" error, here is how to fix it:

1. Open your phone's System Settings.

2. Go to Apps.

3. Find AndroidLiveSplit in the app list and tap it.

4. Tap the three-dot menu in the top-right corner.

5. Tap "Allow restricted settings".

6. Confirm with your screen lock/PIN.

7.Go back to the app, tap the "Grant Accessibility Access" button, and it should work perfectly!


## **🤝 Credits**

* [**LiveSplit**](https://livesplit.org/)**:** The original desktop timer. This app wouldn't exist without their format and inspiration.  
* **Jetpack Compose:** Used for the UI.  
* **The community:** Thanks to everyone who keeps speedrunning alive.

Built with ❤️ by ShadowCid
