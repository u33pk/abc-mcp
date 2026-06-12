#!/bin/bash
# Test script for MCP server
cd /home/orz/project/abcde

# Build the JAR
./gradlew :modules:mcp:jvmJar 2>/dev/null

# Create a named pipe for communication
PIPE=$(mktemp -u)
mkfifo "$PIPE"

# Run the MCP server in background, reading from pipe
java -jar modules/mcp/build/libs/mcp-jvm-0.1.0-main-*.jar < "$PIPE" &
SERVER_PID=$!

# Open pipe for writing
exec 3>"$PIPE"

# Send initialize request
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' >&3
sleep 0.5

# Send tools/list request
echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' >&3
sleep 0.5

# Send open_abc tool call
echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"open_abc","arguments":{"path":"/home/orz/project/unitTest/test.abc"}}}' >&3
sleep 0.5

# Send list_classes tool call
echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_classes","arguments":{"path":"/home/orz/project/unitTest/test.abc"}}}' >&3
sleep 0.5

# Cleanup
exec 3>&-
kill $SERVER_PID 2>/dev/null
rm -f "$PIPE"
