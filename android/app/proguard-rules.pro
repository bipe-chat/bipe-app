# SPDX-License-Identifier: AGPL-3.0-only
# Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
# Copyright 2026 Bipe contributors

# R8 rules for the release build. Native code finds these classes by name, so R8 must not remove or rename them.

# matrix-rust-sdk: uniffi bindings over JNA. JNA reads fields and callback methods by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**

# The SDK's TLS check calls the platform verifier through JNI (bipe-chat/bipe#626).
-keep class org.rustls.platformverifier.** { *; }

# Calls: LiveKit's uniffi bindings and the prefixed WebRTC build call Java from native code.
-keep class io.livekit.android.** { *; }
-keep class livekit.** { *; }
# WebRTC's jni_zero generates JniZeroJni only for some builds; the LiveKit build has none (bipe-chat/bipe#892).
-dontwarn livekit.org.jni_zero.JniZeroJni
