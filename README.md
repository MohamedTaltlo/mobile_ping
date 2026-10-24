# MT_PING — المراقبة الاحترافية للاتصال

Native Android ICMP monitoring app, inspired by Termux ping output. Foreground background monitor, per-packet history, outage timer, local CSV log and Android notification.

**Android:** 8.0+ · **Target:** Android 15 · **Build:** GitHub Actions `Android APK` produces `MT_PING.apk` in GitHub Releases and workflow artifacts.

See the source and [build workflow](.github/workflows/android.yml). The APK is a debug-signed sideload build; it is not a Play Store release.

## التحديث 1.1.2

- تحسين الترويسة على Android 15+ باحترام مساحات شريط الحالة والقطع العلوي.
- رسم بياني أكثر وضوحاً لآخر 60 نبضة مع محور ms وقياس ديناميكي وإظهار البينغ الأخير.
- عداد فصل من نظام Android نفسه (Notification Chronometer) يظهر أثناء الانقطاع حتى لو تأخر تحديث إشعار التطبيق.
- دون تغيير محرك ICMP أو قاعدة بيانات سجل الانقطاعات.

## التحديث 1.1.0

- واجهة عربية بالكامل باتجاه من اليمين إلى اليسار (RTL).
- إرسال تنبيه عالي الأولوية عند فقد الاتصال لأول مرة في كل فترة انقطاع.
- تحديث مدة الفصل كل ثانية في الإشعار الدائم وتنبيه الفصل.
- عرض مدة آخر فصل بعد استعادة الاتصال بدون فتح التطبيق.
- حفظ كل مرة انقطاع وتاريخها ومدتها ونتيجتها في قاعدة SQLite داخل التطبيق.
- إظهار سجل الفصل وصدّره بتنسيق CSV عربي (UTF-8).
- فصل واضح بين عودة الاتصال وأخطاء أدوات الفحص أو توقف المراقبة.
- الإشعارات تتطلب السماح بها على Android 13+ وقد تمنع إعدادات البطارية عمل التطبيق بالخلفية على بعض الأجهزة.

## Behavior

- 1 ICMP echo per selected interval (1, 2 or 5 seconds), reporting RTT/timeout/error per pulse.
- Elapsed outage starts at first timed-out echo and ends on first successful echo.
- A foreground `specialUse` monitoring service continues while UI is closed with a persistent notification and partial wake lock.
- CSV files rotate in internal storage. UI can export records using the Android file picker.
- Some manufacturers may terminate foreground services because of aggressive battery optimization. Allow unrestricted battery use for reliable monitoring; Android cannot promise absolute uptime.

## Build

Requires Android SDK 35, Java 17+, Gradle 8.10.2.

```bash
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
