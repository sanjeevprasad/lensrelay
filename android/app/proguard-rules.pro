# The MoQ native stack (dev.moq libmoq_ffi) is reached through uniffi-generated
# JNA interfaces whose abstract method names are resolved as Rust symbols at
# runtime. R8 shrinks them as unreachable, so the generated uniffi package must
# be kept verbatim; verified against the minified release dex (234/234 symbols).
-keep class uniffi.** { *; }
