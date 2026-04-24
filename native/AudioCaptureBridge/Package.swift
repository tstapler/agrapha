// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AudioCaptureBridge",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "AudioCaptureBridge",
            type: .dynamic,
            targets: ["AudioCaptureBridge"]
        ),
    ],
    targets: [
        .target(
            name: "AudioCaptureBridge",
            path: "Sources/AudioCaptureBridge",
            linkerSettings: [
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreGraphics"),
            ]
        ),
    ]
)
