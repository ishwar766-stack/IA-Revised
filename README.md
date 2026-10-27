# IA (Expenses) – Android
1. Create a PUBLIC GitHub repo named `ia-expenses`, push this folder (keep keystore/debug.keystore).
2. Release: `git tag v1.4.0 && git push --tags` -> Actions builds IA-Expenses.apk and attaches it to the Release.
3. Install the APK on each family phone (allow "Install unknown apps"); allow SMS permission when asked.
4. Update: change code, `git tag v1.4.0 && git push --tags`. In app: Family -> Check for update.
Sync: first phone registers; other phones tap "Join by Wi-Fi" on the first screen (same Wi-Fi/hotspot, app open on both).
Backup: first launch asks to allow storage access; data is mirrored to Internal storage/IA-Expenses/. After clearing data or reinstalling, open the app, tap "Restore from backup".
