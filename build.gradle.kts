plugins {
    // AGP 9 bringt die Kotlin-Unterstuetzung bereits mit - ein zusaetzliches
    // org.jetbrains.kotlin.android kollidiert mit der eingebauten "kotlin"-Extension
    id("com.android.application") version "9.4.1" apply false
}
