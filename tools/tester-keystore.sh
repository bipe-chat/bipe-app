#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
# Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
# Copyright 2026 Bipe contributors
#
# Tester APK signing key (bipe-chat/bipe#575). Runs on MZ's Mac, once. Safe to run again: it refuses to
# replace a key that exists, because a new key stops every installed tester APK from updating.
# 1. Makes one PKCS12 keystore (alias bipe-tester, RSA 4096) with a random password.
# 2. Keeps it in the Keychain: service bipe-android-tester-keystore (the keystore, base64) and
#    bipe-android-tester-keystore-password, account bipe. This is the only backup.
# 3. Sets the bipe-app secrets TESTER_KEYSTORE_B64 and TESTER_KEYSTORE_PASSWORD through stdin.
# Nothing is printed except the SHA-256 fingerprint of the certificate.
set -eu

if security find-generic-password -s bipe-android-tester-keystore -a bipe >/dev/null 2>&1; then
  echo "The tester key exists in the Keychain. Copying it to GitHub again."
else
  dir=$(mktemp -d)
  trap 'rm -rf "$dir"' EXIT
  umask 077
  pw=$(openssl rand -hex 24)
  keytool -genkeypair -keystore "$dir/tester.keystore" -storetype PKCS12 -alias bipe-tester -keyalg RSA \
    -keysize 4096 -validity 10000 -storepass "$pw" -keypass "$pw" -dname "CN=Bipe tester APK, O=Bipe, C=BR" >/dev/null 2>&1
  security add-generic-password -s bipe-android-tester-keystore -a bipe -w "$(base64 -i "$dir/tester.keystore" | tr -d '\n')"
  security add-generic-password -s bipe-android-tester-keystore-password -a bipe -w "$pw"
  unset pw
fi

security find-generic-password -s bipe-android-tester-keystore -a bipe -w | gh secret set TESTER_KEYSTORE_B64 -R bipe-chat/bipe-app
security find-generic-password -s bipe-android-tester-keystore-password -a bipe -w | gh secret set TESTER_KEYSTORE_PASSWORD -R bipe-chat/bipe-app

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
security find-generic-password -s bipe-android-tester-keystore -a bipe -w | base64 -d > "$dir/k"
keytool -list -v -keystore "$dir/k" -storepass "$(security find-generic-password -s bipe-android-tester-keystore-password -a bipe -w)" 2>/dev/null \
  | grep -m1 "SHA256:" | sed 's/^ */Tester certificate /'
echo "Done: TESTER_KEYSTORE_B64 and TESTER_KEYSTORE_PASSWORD are set in bipe-chat/bipe-app."
