/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
  repositories {
    mavenCentral()
    google()
  }

  dependencies {
    classpath(libs.android.gradle.plugin)
    classpath(libs.kotlin.gradle.plugin)
    classpath(libs.dokka.gradle.plugin)
    classpath(libs.jacoco.core)
  }
}

allprojects {
  repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
  }
}

// Run unit tests in a JDK 11 JVM. Gradle 8.5 requires JDK 17+ to run, but
// PowerMock/Robolectric can't handle JDK 17 class files. The toolchain launcher
// forks test execution into a separate JDK 11 process.
subprojects {
  tasks.withType<Test>().configureEach {
    javaLauncher.set(
      project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(11))
      }
    )
  }
}
