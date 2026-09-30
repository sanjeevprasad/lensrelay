// AGP 9 ships Kotlin built-in (KGP 2.2.10); moq-ffi 0.3.x artifacts carry
// Kotlin 2.4 metadata, so the KGP version must be raised per the official
// AGP 9 procedure: a buildscript classpath override, not the kotlin-android
// plugin (which AGP 9 forbids).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
}
