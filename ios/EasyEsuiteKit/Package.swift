// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "EasyEsuiteKit",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "EasyEsuiteKit", targets: ["EasyEsuiteKit"]),
    ],
    targets: [
        .target(
            name: "EasyEsuiteKit",
            path: "Sources/EasyEsuiteKit"
        ),
        .testTarget(
            name: "EasyEsuiteKitTests",
            dependencies: ["EasyEsuiteKit"],
            path: "Tests/EasyEsuiteKitTests",
            resources: [.copy("Fixtures")]
        ),
    ]
)
