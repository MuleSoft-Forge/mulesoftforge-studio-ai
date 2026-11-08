#!/usr/bin/env python3
"""Probe the hosted MuleSoft Platform MCP server with Anypoint tokens.

Reads .credentials/credentials.json (mulesoft.*). Never prints a token.
Prints, per token kind: HTTP status of initialize, the session header, and the tool names.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
creds = json.load(open(os.path.join(ROOT, ".credentials", "credentials.json")))["mulesoft"]
host = (creds.get("host") or "anypoint.mulesoft.com").replace("https://", "").rstrip("/")
mcp = sys.argv[1] if len(sys.argv) > 1 else "https://omni.mulesoft.com/mcp"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


opener = urllib.request.build_opener(NoRedirect)


def post(url, body, headers, hops=3):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
    try:
        with opener.open(req, timeout=30) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        location = e.headers.get("Location")
        if e.code in (307, 308) and location and hops:
            target = urllib.parse.urljoin(url, location)
            print(f"  {e.code} -> {target}")
            return post(target, body, headers, hops - 1)
        return e.code, dict(e.headers), e.read().decode()


def token_password():
    s, _, b = post(f"https://{host}/accounts/login",
                   {"username": creds["username"], "password": creds["password"]},
                   {"Content-Type": "application/json"})
    return json.loads(b).get("access_token") if s == 200 else None


def token_client():
    s, _, b = post(f"https://{host}/accounts/api/v2/oauth2/token",
                   {"grant_type": "client_credentials", "client_id": creds["client_id"],
                    "client_secret": creds["client_secret"]},
                   {"Content-Type": "application/json"})
    if s != 200:
        print(f"client-credentials token: HTTP {s} {b[:200]}")
    return json.loads(b).get("access_token") if s == 200 else None


def rpc_result(text):
    for line in text.splitlines():
        if line.startswith("data:"):
            text = line[5:]
    try:
        return json.loads(text)
    except ValueError:
        return {"raw": text[:300]}


def probe(kind, token):
    print(f"=== {kind}: token {'obtained' if token else 'NOT obtained'}")
    if not token:
        return
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
         "Authorization": f"Bearer {token}"}
    s, hdr, b = post(mcp, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                           "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                                      "clientInfo": {"name": "msf-probe", "version": "0"}}}, h)
    session = next((v for k, v in hdr.items() if k.lower() == "mcp-session-id"), None)
    init = rpc_result(b)
    print(f"initialize: HTTP {s}, session {'yes' if session else 'no'}, "
          f"server {init.get('result', {}).get('serverInfo')}, "
          f"protocol {init.get('result', {}).get('protocolVersion')}")
    if s != 200:
        print("  body:", b[:300])
        return
    if session:
        h["Mcp-Session-Id"] = session
    post(mcp, {"jsonrpc": "2.0", "method": "notifications/initialized"}, h)
    s, _, b = post(mcp, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, h)
    tools = rpc_result(b).get("result", {}).get("tools", [])
    print(f"tools/list: HTTP {s}, {len(tools)} tools")
    for t in tools:
        ann = t.get("annotations") or {}
        print(f"  {t['name']}  readOnly={ann.get('readOnlyHint')} destructive={ann.get('destructiveHint')}")
    out = os.path.join("/tmp", f"platform-mcp-tools-{kind}.json")
    json.dump(tools, open(out, "w"), indent=1)
    print("  saved", out)


probe("password-login", token_password())
probe("client-credentials", token_client())
