// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
  name: "CloudNotesResources",
  products: [
    .library(
      name: "CloudNotesResources",
      targets: ["CloudNotesResources"]
    ),
  ],
  targets: [
    .target(
      name: "CloudNotesResources",
      resources: [
        .process("Resources/Icons.xcassets"),
        .process("Resources/Colors.xcassets"),
      ]
    ),
  ]
)
