// swift-tools-version:5.9

import PackageDescription

let package = Package(
  name: "CloudNotesGraphQL",
  platforms: [
    .iOS(.v12),
    .macOS(.v10_14),
    .tvOS(.v12),
    .watchOS(.v5),
  ],
  products: [
    .library(name: "CloudNotesGraphQL", targets: ["CloudNotesGraphQL"]),
  ],
  dependencies: [
    .package(url: "https://github.com/apollographql/apollo-ios", exact: "1.25.7"),
  ],
  targets: [
    .target(
      name: "CloudNotesGraphQL",
      dependencies: [
        .product(name: "ApolloAPI", package: "apollo-ios"),
      ],
      path: "./Sources"
    ),
  ]
)
