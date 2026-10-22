#!/bin/bash
# This is a script used by the devcontainer to build the project

# install dependencies
yarn install

# Build Server Dependencies
yarn cloud-notes @cloud-notes/server-native build

# Create database
yarn cloud-notes @cloud-notes/server prisma migrate reset -f
