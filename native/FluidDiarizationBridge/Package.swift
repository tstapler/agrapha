// swift-tools-version:5.9
// The build task passes JAVA_HOME include paths via -Xcc flags (see composeApp/build.gradle.kts).

import PackageDescription

let package = Package(
    name: "FluidDiarizationBridge",
    platforms: [.macOS(.v14)],
    products: [
        .library(
            name: "FluidDiarizationBridge",
            type: .dynamic,
            targets: ["FluidDiarizationBridge"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/fluidinference/FluidAudio", from: "0.14.5"),
    ],
    targets: [
        // C shim that wraps the JNI function table behind plain C helpers so
        // that Swift code never needs to deal with JNI's double-pointer env layout.
        // jni.h is supplied at build time via:
        //   swift build -Xcc -I$JAVA_HOME/include -Xcc -I$JAVA_HOME/include/darwin
        .target(
            name: "CJNIBridge",
            path: "Sources/CJNIBridge",
            publicHeadersPath: "include"
        ),

        // The main bridge target that exports @_cdecl JNI functions.
        .target(
            name: "FluidDiarizationBridge",
            dependencies: [
                "CJNIBridge",
                .product(name: "FluidAudio", package: "FluidAudio"),
            ],
            path: "Sources/FluidDiarizationBridge"
        ),
    ]
)
