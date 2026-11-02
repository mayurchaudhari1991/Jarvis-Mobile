import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.veg.inference"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_CURL=OFF",
                    // ggml sets its own per-target compile flags for the
                    // vendored subdirectory, so our cppFlags -O3 never reaches
                    // it — under a Debug build type it compiles -O0 with
                    // assertions, which is 20-50x slower on the quantised
                    // matmul hot loop that is essentially all of inference.
                    // The Kotlin side stays debuggable; only the C++ is
                    // optimised.
                    "-DCMAKE_BUILD_TYPE=Release",
                    // Android NDK cross-compilation is not CMake "native"
                    // (GGML_NATIVE's -mcpu=native autodetection never fires
                    // cross-compiling), and GGML_CPU_ALL_VARIANTS (runtime
                    // multi-kernel dispatch) is OFF by default — so without
                    // this, ggml-cpu/CMakeLists.txt adds no -march at all and
                    // falls back to the NDK's baseline armv8-a, leaving
                    // dotprod/i8mm/fp16-vector-arithmetic kernels unused.
                    // armv8.7-a is llama.cpp's own documented "most
                    // performant options for modern devices" cross-compile
                    // recipe (docs/android.md) and is a strict baseline
                    // subset of this device's ARMv9.2 Snapdragon 8 Gen 3.
                    "-DGGML_CPU_ARM_ARCH=armv8.7a"
                )
                cppFlags += "-O3"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":core-device"))
    api(project(":core-model"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}
