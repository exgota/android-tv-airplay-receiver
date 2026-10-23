# The EdDSA library looks up its curve and hash implementations reflectively.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.x509.**
# curve25519-java probes for an optional native provider by name.
-keep class org.whispersystems.curve25519.** { *; }
-dontwarn org.whispersystems.curve25519.**
