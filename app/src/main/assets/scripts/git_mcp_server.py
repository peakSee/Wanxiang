#!/usr/bin/env python3
"""Dependency-free Git MCP server for the WanXiang sandbox."""
import argparse
import json
import subprocess
import sys

PROTOCOL_VERSION = "2025-06-18"


def response(req_id, result=None, error=None):
    value = {"jsonrpc": "2.0", "id": req_id}
    value["error" if error is not None else "result"] = error if error is not None else result
    return value


def run(repo, command):
    proc = subprocess.run(["git", "-C", repo] + command, capture_output=True, text=True, timeout=30)
    output = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0:
        raise RuntimeError(output.strip() or "git 命令失败")
    return output.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default="/workspace")
    args = parser.parse_args()
    tools = [
        {"name": "git_status", "description": "获取工作区状态", "inputSchema": {"type": "object", "properties": {}}},
        {"name": "git_log", "description": "获取提交历史", "inputSchema": {"type": "object", "properties": {"limit": {"type": "integer"}}}},
        {"name": "git_diff", "description": "获取工作区差异", "inputSchema": {"type": "object", "properties": {"target": {"type": "string"}}}},
    ]
    for line in sys.stdin:
        try:
            req = json.loads(line)
            method, req_id = req.get("method"), req.get("id")
            if method == "initialize":
                out = response(req_id, {"protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {}}, "serverInfo": {"name": "wanxiang-git", "version": "1.0.0"}})
            elif method == "notifications/initialized":
                continue
            elif method == "tools/list":
                out = response(req_id, {"tools": tools})
            elif method == "tools/call":
                params = req.get("params") or {}
                try:
                    name, tool_args = params.get("name"), params.get("arguments") or {}
                    if name == "git_status": text = run(args.repository, ["status", "--short", "--branch"])
                    elif name == "git_log": text = run(args.repository, ["log", "--oneline", "-n", str(max(1, min(int(tool_args.get("limit", 20)), 200)))])
                    elif name == "git_diff": text = run(args.repository, ["diff", str(tool_args.get("target", ""))] if tool_args.get("target") else ["diff"])
                    else: raise ValueError("未知工具: " + str(name))
                    result = {"content": [{"type": "text", "text": text}], "isError": False}
                except Exception as exc:
                    result = {"content": [{"type": "text", "text": str(exc)}], "isError": True}
                out = response(req_id, result)
            else:
                continue
            sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
            sys.stdout.flush()
        except Exception as exc:
            sys.stdout.write(json.dumps(response(None, error={"code": -32603, "message": str(exc)}), ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
