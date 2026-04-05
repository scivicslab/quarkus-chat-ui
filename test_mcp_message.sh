#!/bin/bash
# Test sending message to port 8203 chat API

curl -X POST http://localhost:8203/api/chat \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello from MCP test - testing message delivery"}' \
  -w "\n"
