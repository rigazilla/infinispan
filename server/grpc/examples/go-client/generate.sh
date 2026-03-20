#!/bin/bash
set -e

echo "==> Copying proto files from server..."
mkdir -p proto
cp ../../src/main/resources/proto/*.proto proto/

echo "==> Generating Go code from proto files..."
export PATH=$PATH:$(go env GOPATH)/bin

# Check if protoc-gen-go is installed
if ! command -v protoc-gen-go &> /dev/null; then
    echo "Installing protoc-gen-go..."
    go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
fi

# Check if protoc-gen-go-grpc is installed
if ! command -v protoc-gen-go-grpc &> /dev/null; then
    echo "Installing protoc-gen-go-grpc..."
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
fi

mkdir -p api/proto
protoc -I=proto \
  --go_out=api/proto --go_opt=paths=source_relative \
  --go-grpc_out=api/proto --go-grpc_opt=paths=source_relative \
  proto/*.proto

echo "==> Done! Generated files in api/proto/"
