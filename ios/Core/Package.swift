// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MutawwifCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "MutawwifCore", targets: ["MutawwifCore"]),
    ],
    targets: [
        .target(name: "MutawwifCore", path: "Sources/MutawwifCore"),
        .testTarget(
            name: "MutawwifCoreTests",
            dependencies: ["MutawwifCore"],
            path: "Tests/MutawwifCoreTests"
        ),
    ]
)
