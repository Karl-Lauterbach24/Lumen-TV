# Installing Lumen TV

None of the apps is in an app store; they are installed in the developer mode of the TV.
The packages are on the [Releases page](https://github.com/Karl-Lauterbach24/Lumen-TV/releases).

If this is too much effort: open the TV's web browser and enter the address shown in Lumen's Cast
dialog (for example `http://192.168.1.20:47800`). The page that opens is the same receiver.

## Android TV, Google TV, Fire TV

`LumenTV-<version>-android.apk` is signed with a debug key (it installs, but it is not a store
build).

With a computer:

1. On the TV: Settings → System → About → click "Build" seven times, then enable
   "USB debugging" / "Network debugging" in the developer options.
2. `adb connect <address of the TV>` and confirm the prompt on the TV.
3. `adb install LumenTV-<version>-android.apk`

Without a computer: copy the APK to a USB stick or download it with a file manager app on the TV,
allow installing from that app, and open the file.

Lumen TV appears among the apps on the home screen.

## LG (webOS)

1. Create an account at [webostv.developer.lge.com](https://webostv.developer.lge.com) and install
   the "Developer Mode" app from the LG Content Store on the TV. Sign in, switch "Dev Mode Status"
   on and restart. Enable "Key Server".
2. On the computer: `npm install -g @webos-tools/cli`
3. `ares-setup-device` – add the TV (address, port 9922, user `prisoner`), then
   `ares-novacom --device <name> --getkey` and enter the passphrase the Developer Mode app shows.
4. `ares-install --device <name> io.github.lumen.tv_<version>_all.ipk`

LG's developer mode ends after a limited time unless it is extended in the Developer Mode app;
the app has to be installed again afterwards.

## Samsung (Tizen)

Samsung TVs only install apps signed with a certificate that contains the TV's identifier (DUID).
That certificate is free, but only you can create it, so the release contains the unsigned app.

1. Install [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download) with the
   "TV Extensions" and the "Samsung Certificate Extension".
2. On the TV: open "Apps", press `1` `2` `3` `4` `5` on the remote, switch developer mode on and
   enter the computer's IP address. Restart the TV.
3. In Tizen Studio's Device Manager, connect to the TV (Remote Device Manager → scan).
4. In the Certificate Manager, create a Samsung certificate profile (author and distributor
   certificate; the connected TV's DUID is added automatically). This needs a Samsung account.
5. Unpack `LumenTV-<version>-tizen-unsigned.wgt` (it is a ZIP archive) into a folder, or build
   `dist/tizen` yourself, then sign and install:

   ```bash
   tizen package -t wgt -s <certificate profile> -- <folder>
   tizen install -n "Lumen TV.wgt" -t <TV name as shown by: sdb devices> -- <folder>
   ```

## After installing

Open the cast dialog in Lumen on the computer, then start Lumen TV. See the
[README](../README.md#using-it).
