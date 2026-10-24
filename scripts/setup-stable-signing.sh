#!/usr/bin/env bash
# Run locally in Termux/Linux. NEVER commit the generated keystore.
set -euo pipefail
REPO="MohamedTaltlo/mobile_ping"
DIR="${HOME}/.mttools-signing"
KEY="${DIR}/mttools.jks"
mkdir -p "$DIR"
chmod 700 "$DIR"
if ! command -v keytool >/dev/null 2>&1; then
  echo "يرجى تثبيت Java/OpenJDK 17 (في Termux: pkg install openjdk-17)" >&2
  exit 1
fi
if [[ ! -f "$KEY" ]]; then
  printf 'اختر كلمة مرور قوية لمفتاح توقيع MT_TOOLS (احتفظ بها): '
  read -rs PASSWORD
  echo
  [[ "${#PASSWORD}" -ge 8 ]] || { echo 'كلمة المرور يجب أن تكون 8 أحرف على الأقل' >&2; exit 1; }
  keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$KEY" \
    -alias mttools -keyalg RSA -keysize 3072 -validity 12000 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -dname "CN=MT_TOOLS, OU=MT_RADIUS, O=MT_RADIUS, C=SY"
else
  printf 'أدخل كلمة مرور مفتاح التوقيع الموجود: '
  read -rs PASSWORD
  echo
  keytool -list -storepass "$PASSWORD" -keystore "$KEY" >/dev/null
fi
chmod 600 "$KEY"
ENCODED="$(base64 < "$KEY" | tr -d '\n')"
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  printf '%s' "$ENCODED" | gh secret set MT_TOOLS_KEYSTORE_BASE64 --repo "$REPO"
  printf '%s' "$PASSWORD" | gh secret set MT_TOOLS_KEYSTORE_PASSWORD --repo "$REPO"
  echo 'تم إعداد أسرار التوقيع الدائم للمستودع. أعد تشغيل GitHub Actions.'
else
  echo
  echo 'يلزم إعداد سرَّين في GitHub Repository Settings > Secrets and variables > Actions:'
  echo '1) MT_TOOLS_KEYSTORE_BASE64'
  echo '2) MT_TOOLS_KEYSTORE_PASSWORD'
  echo
  echo 'سر MT_TOOLS_KEYSTORE_BASE64 (انسخ السطر التالي إلى GitHub، ولا تشاركه):'
  printf '%s\n' "$ENCODED"
  echo
  echo 'سر MT_TOOLS_KEYSTORE_PASSWORD هو كلمة المرور التي أدخلتها الآن.'
  echo 'يمكن أيضاً تثبيت GitHub CLI وإعداد gh auth login لتخزين السرَّين آلياً.'
fi
unset PASSWORD ENCODED
echo 'احتفظ بمفتاح التوقيع بأمان داخل مجلد ~/.mttools-signing واحتفظ بنسخة احتياطية خاصة.'
