// swift-tools-version:5.9
import PackageDescription

// CryptoKit on Apple platforms; swift-crypto (same API) on Linux so the protocol core and CLI
// can be built and tested in CI next to the relay and the Kotlin SDK.
let package = Package(
    name: "DeviceLinkKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "DeviceLinkKit", targets: ["DeviceLinkKit"]),
        .executable(name: "devicelink", targets: ["devicelink"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", "3.15.1"..<"5.0.0"),
    ],
    targets: [
        .target(
            name: "DeviceLinkKit",
            dependencies: [.product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]
        ),
        .executableTarget(name: "devicelink", dependencies: ["DeviceLinkKit"]),
        .testTarget(name: "DeviceLinkKitTests", dependencies: ["DeviceLinkKit"]),
    ]
)
