# Install

## Initial Setup

This only needs to be done the first time

1. Flash device with AOSP @ [flash.android.com](https://flash.android.com)
2. Enable developer mode
   * Open settings app
   * Select `About phone` at the bottom
   * Scroll down, then tap on `Build number` until developer mode is enabled
   * *NOTE: If you flashed your device properly, the build number should look something like: `aosp_...-userdebug ... ... ... test-keys`*
3. Enable USB debugging
   * Go back to the main settings page
   * `System` > `Developer options`
   * Scroll down and enable `USB debugging`
4. [Download `gameface.zip` from the releases page](https://github.com/AcidicNic/project-gameface/releases/tag/debug-latest)
5. Unzip `gameface.zip` and open your terminal inside of the new `/gameface/` directory

6. Plug in your device

7. Restart adb with root perms
    ``` bash
	adb root
	```

8. Remount partitions with read & write access
 	``` bash
	adb remount
	```

9. Create new directory in /system/priv-app/
	``` bash
	adb shell mkdir /system/priv-app/GameFace
	```

10. Push APK to the device
	``` bash
	adb push gameface.apk /system/priv-app/GameFace/GameFace.apk
	```

11. Set permissions for apk
	``` bash
	adb shell chmod 644 /system/priv-app/GameFace/GameFace.apk
	```

12. Pull `privapp-permissions-platform.xml` file
	``` bash
	adb pull /etc/permissions/privapp-permissions-platform.xml .
	```

	* Open `privapp-permissions-platform.xml` using your text editor of choice.
	* Add the following to the bottom of the file inside of `<permissions> ... </permissions>`
		``` xml
		<permissions>
			...

			<privapp-permissions package="com.google.projectgameface">
				<permission name="android.permission.INJECT_EVENTS"/>
			</privapp-permissions>

		</permissions>
		```
	* Save `privapp-permissions-platform.xml`

13. Push modified `privapp-permissions-platform.xml` file to device
	``` bash
	adb push privapp-permissions-platform.xml /etc/permissions/
	```

14. Install additional apks
	``` bash
	adb install gboard.apk
	```
	``` bash
	adb install logseq.apk
	```

15. Reboot device
	``` bash
	adb reboot
	```

## Additional Setup

* Open the Gboard app > `Enable in settings` > Enable `Gboard` and disable `Android Keyboard (AOSP)`
    * You can modify these settings anytime in the settings app:
	* `Settings` > `System` > `Keyboard` > `On-screen Keyboard`

* Connect your bluetooth input device

## Updating app after initial setup is complete

1.  ``` bash
	adb root
	```

2.  ``` bash
	adb remount
	```

3.  ``` bash
	adb push gameface.apk /system/priv-app/GameFace/GameFace.apk
	```

4.  ``` bash
	adb shell chmod 644 /system/priv-app/GameFace/GameFace.apk
	```

5.  ``` bash
	adb reboot
	```
