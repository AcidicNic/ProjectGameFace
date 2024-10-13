# ADB setup and pulling log files
...


## Android SDK Tools Setup

1. Open Android Studio

1. Open the Android SDK Manager from the menu bar at the top.
    * `Tools` > `SDK Manager`

    <!-- 
        --- OR ---
        * via settings
            * Open the settings page with:  ⌘`command` + `,`
            * `Language & Frameworks` > `Android SDK`
    1. Check the path next to `Android SDK Location`
        * It should look something like this: `/Users/<your-username>/Library/Android/sdk`
    -->

1. Select the `SDK Tools` tab

1. Select `Android SDK Platform-Tools` and `Android SDK Command-line Tools`

1. Press `OK` to begin the download. Press `Finish` when the download is complete

1. Open your terminal.

1. Create Android SDK Home environment variable.

    ```bash
    echo 'export ANDROID_HOME="$HOME/Library/Android/sdk"' >> ~/.zprofile
    ```

1. Add platform and command-line tools to your PATH

    ```bash
    echo 'export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"' >> ~/.zprofile
    ```

1. Reload terminal

    ```bash
    source .zprofile
    ```

---

## Pulling log file

* Plug in your device and open your terminal

```bash
cd ~/Desktop ; adb pull /sdcard/Download/gameface-err.log && open gameface-err.log ; adb pull /sdcard/Download/gameface.log && open gameface.log
```
