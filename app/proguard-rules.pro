# KayX rules
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.android.apksig.**
-dontwarn org.jf.**
-dontwarn org.antlr.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
-dontwarn javax.naming.**
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# Rust JNI bridge: native method names must survive minification
-keep class com.kayx.manager.core.NativeCore { *; }

# Shizuku user service is instantiated by class name inside the Shizuku process
-keep class com.kayx.manager.data.advanced.ShizukuUserService { *; }
-keep class com.kayx.manager.IUserService { *; }
-keep class com.kayx.manager.IUserService$Stub { *; }

# Mbassador's optional EL expression-filter integration references the Java EE
# Expression Language API (javax.el), which is intentionally not shipped on Android.
# KayX does not configure EL expression filters; keep the core event bus usable without EL.
-dontwarn javax.el.**

# SMBJ includes optional Kerberos/SPNEGO authentication support. KayX currently
# authenticates SMB shares with username/password (NTLM); Android does not ship
# the Java GSSAPI (org.ietf.jgss) module. Do not package a desktop Kerberos stack.
-dontwarn org.ietf.jgss.**
