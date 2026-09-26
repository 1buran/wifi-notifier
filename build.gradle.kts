// Since AGP 9 the Kotlin support is built into com.android.application,
// so the separate org.jetbrains.kotlin.android plugin is not applied anymore.
plugins {
    id("com.android.application") version "9.4.1" apply false
}
