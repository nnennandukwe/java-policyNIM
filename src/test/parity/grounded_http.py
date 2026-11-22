"""Compare the pinned Python HTTP service and the grounded Java jar on loopback."""

import argparse
import asyncio
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
from contextlib import asynccontextmanager, suppress
from importlib import metadata
from pathlib import Path

import httpx2
from legacy_stdio import jar_version, require
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

LEGACY = "2025-11-25"
MODERN = "2026-07-28"
VERSION_META = "io.modelcontextprotocol/protocolVersion"
CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities"
PUBLIC_BUNDLE = "sha256:376ba2727eb73e56696bce91eaf68ac2b6eae20b315a99b87e405cc56a380dce"
JAVA_TOOLS = {"policy_search", "policy_preflight"}
PYTHON_TOOLS = JAVA_TOOLS | {"policy_compile", "policy_preflight_evidence", "eval_submit",
                            "eval_inspect", "eval_result", "eval_cancel"}


@asynccontextmanager
async def server(command, environment, directory, started):
    """Read the listening port from the child that owns it and reap that child on exit."""
    process = await asyncio.create_subprocess_exec(
        *command, cwd=directory, env=environment, stdin=subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
    )
    try:
        async with asyncio.timeout(45):
            while line := await process.stdout.readline():
                match = started.search(line)
                if match:
                    break
            else:
                raise RuntimeError(f"{Path(command[0]).name} exited before it opened a listener.")

        async def discard_output():
            """Drain server logs so a full pipe can't stall the process."""
            while await process.stdout.read(8192):
                pass

        drain = asyncio.create_task(discard_output())
        try:
            yield f"http://127.0.0.1:{int(match.group(1))}"
        finally:
            had_failure = sys.exc_info()[0] is not None
            if process.returncode is None:
                process.terminate()
            try:
                await asyncio.wait_for(process.wait(), 10)
            except TimeoutError:
                if not had_failure:
                    raise RuntimeError(f"{Path(command[0]).name} didn't stop within 10 seconds.")
            finally:
                drain.cancel()
                with suppress(asyncio.CancelledError):
                    await drain
    finally:
        if process.returncode is None:
            process.kill()
            await process.wait()


def home(directory):
    """Create a private home and an empty configuration file for one server."""
    directory.mkdir()
    config = directory / "empty.env"
    config.touch()
    return {"HOME": str(directory), "XDG_CONFIG_HOME": str(directory / "config"),
            "XDG_DATA_HOME": str(directory / "data"), "POLICYNIM_CONFIG_FILE": str(config),
            "PATH": os.defpath, "PYTHONDONTWRITEBYTECODE": "1"}


async def sdk_round_trip(label, mode, endpoint, token, tools, version):
    """Check negotiation, tool refusals, and discovery with one SDK client."""
    methods = []
    replies = []
    tool_replies = {}
    issued_session = False
    identity = None

    async def request_sent(request):
        """Record the methods the client sends to the server."""
        if request.method == "POST":
            methods.append(json.loads(request.content)["method"])

    async def response_received(response):
        """Capture JSON replies and check whether the server issued a session ID."""
        nonlocal issued_session
        issued_session |= "mcp-session-id" in response.headers
        if response.headers.get("content-type", "").startswith("application/json") and await response.aread():
            frame = response.json()
            replies.append(frame)
            request = json.loads(response.request.content)
            if request.get("method") == "tools/call":
                tool = request["params"]["name"]
                require(response.status_code == 200 and frame.get("jsonrpc") == "2.0"
                        and frame.get("id") == request["id"] and "error" not in frame,
                        f"{label}/{mode}: expected a correlated HTTP 200 tool result for {tool}")
                result = frame.get("result", {})
                require(result.get("isError") is True, f"{label}/{mode}: missing raw tool refusal for {tool}")
                tool_replies[tool] = result

    hooks = {"request": [request_sent], "response": [response_received]}
    async with httpx2.AsyncClient(headers={"Authorization": f"Bearer {token}"}, timeout=5,
                                  trust_env=False, event_hooks=hooks) as http:
        transport = streamable_http_client(endpoint, http_client=http)
        async with asyncio.timeout(25), Client(transport, mode=mode, read_timeout_seconds=5) as client:
            expected = LEGACY if mode == "legacy" else MODERN
            require(client.protocol_version == expected, f"{label}/{mode}: negotiated {client.protocol_version}")
            if mode != MODERN:
                identity = client.server_info.version
                require(identity == version and client.server_info.name == "PolicyNIM",
                        f"{label}/{mode}: wrong installed server identity")
            for tool, field in (("policy_search", "query"), ("policy_preflight", "task")):
                refusal = await client.call_tool(tool, {})
                require(refusal.is_error is True, f"{label}/{mode}: {tool} accepted missing input")
                detail = " ".join(getattr(block, "text", "") for block in refusal.content)
                require(field in detail, f"{label}/{mode}: {tool} refusal omitted {field}")
                raw_text = " ".join(block["text"] for block in tool_replies[tool]["content"]
                                    if block["type"] == "text")
                require(raw_text == detail, f"{label}/{mode}: SDK refusal text differs from the raw {tool} result")
            found = {item.name for item in (await client.list_tools()).tools}
            require(found == tools, f"{label}/{mode}: unexpected discovery {sorted(found)}")
    first = {"auto": "server/discover", "legacy": "initialize", MODERN: "tools/call"}[mode]
    require(methods[0] == first, f"{label}/{mode}: unexpected first request {methods[0]}")
    require(not issued_session, f"{label}/{mode}: server issued an HTTP session ID")
    results = [r["result"] for r in replies if "result" in r]
    require(len(results) >= 3, f"{label}/{mode}: missing JSON tool results")
    if mode != "legacy":
        require(all(r.get("resultType") == "complete" for r in results), f"{label}/{mode}: incomplete result")
    return {"mode": mode, "protocol": expected, "firstMethod": first, "issuedSession": issued_session,
            "negotiatedServerVersion": identity, "tools": sorted(found), "rawToolRefusals": tool_replies}


async def raw_round_trip(endpoint, token):
    """Check independent public error codes and a successful retry after each refusal."""
    observed = {}
    async with httpx2.AsyncClient(timeout=5, trust_env=False) as http:
        async def call(method="tools/list", *, version=MODERN, meta=None, headers=None, params=None,
                       accept="application/json", authorized=True, path=endpoint):
            """Send a modern request with the specified envelope and HTTP headers."""
            values = {VERSION_META: version, CAPABILITIES_META: {}} if meta is None else meta
            body = {"jsonrpc": "2.0", "id": "parity-42", "method": method,
                    "params": {"_meta": values, **(params or {})}}
            wire_headers = {"MCP-Protocol-Version": version, "Mcp-Method": method, "Accept": accept}
            if authorized:
                wire_headers["Authorization"] = f"Bearer {token}"
            return await http.post(path, headers=wire_headers | (headers or {}), json=body)

        async def recovered(accept="application/json", headers=None):
            """Verify that a sessionless tools/list request returns a complete JSON result."""
            response = await call(accept=accept, headers=headers)
            require(response.status_code == 200, f"tools/list: HTTP {response.status_code}")
            require(response.headers.get("content-type", "").startswith("application/json"),
                    "tools/list: expected JSON response")
            frame = response.json()
            require(frame["jsonrpc"] == "2.0" and frame["id"] == "parity-42"
                    and frame["result"]["resultType"] == "complete",
                    "tools/list: missing correlated modern result")
            require("mcp-session-id" not in response.headers, "tools/list: session ID issued")
            return response.status_code

        initialize = {"jsonrpc": "2.0", "id": "legacy-1", "method": "initialize", "params": {
            "protocolVersion": LEGACY, "capabilities": {}, "clientInfo": {"name": "parity", "version": "1"},
        }}
        observed["accept"] = {}
        for accept in (
            "application/json", "*/*", "application/*", "application/json, text/event-stream",
            "application/json;q=0", "application/json;q=0, */*;q=1", "*/*;q=1, application/*;q=0",
            "application/*;q=0", "ApPlIcAtIoN/JsOn; profile=public",
        ):
            legacy = await http.post(endpoint, headers={"Authorization": f"Bearer {token}", "Accept": accept},
                                     json=initialize)
            require(legacy.status_code == 200 and "mcp-session-id" not in legacy.headers,
                    f"legacy initialize: {accept} failed to create a sessionless response")
            frame = legacy.json()
            require(frame["id"] == "legacy-1" and frame["result"]["protocolVersion"] == LEGACY,
                    "legacy initialize: wrong ID or protocol")
            observed["accept"][accept] = {"legacy": legacy.status_code, "modern": await recovered(accept)}
        trusted = {"Host": "parity.invalid", "Origin": "https://parity.invalid"}
        await recovered(headers=trusted)
        observed["publicOrigin"] = 200
        for path in (endpoint, endpoint + "/"):
            for field, value, status in (("Host", "other.invalid", 421), ("Origin", "https://other.invalid", 403)):
                rejected = await call(headers=trusted | {field: value}, authorized=False, path=path)
                require(rejected.status_code == status, f"{field}: expected HTTP {status} before authentication")
                observed[f"untrusted{field} {path.removeprefix(endpoint) or 'exact'}"] = rejected.status_code
                await recovered(headers=trusted)
        cases = [
            ("missing bearer", {"authorized": False}, 401, None),
            ("unacceptable representation", {"accept": "text/plain"}, 406, None),
            ("wrong bearer", {"headers": {"Authorization": "Bearer wrong-synthetic"}}, 401, None),
            ("name disagreement", {"method": "tools/call", "params": {"name": "policy_search", "arguments": {}},
                                   "headers": {"Mcp-Name": "policy_preflight"}}, 400, -32020),
            ("revision disagreement", {"meta": {VERSION_META: "2099-01-01", CAPABILITIES_META: {}}}, 400, -32020),
            ("missing capabilities", {"meta": {VERSION_META: MODERN}}, 400, -32602),
            ("unsupported revision", {"version": "2099-01-01"}, 400, -32022),
            ("unknown method", {"method": "unknown/parity"}, 404, -32601),
        ]
        for label, arguments, status, code in cases:
            response = await call(**arguments)
            require(response.status_code == status, f"{label}: expected HTTP {status}, got {response.status_code}")
            observed[label] = {"status": response.status_code}
            if code is not None:
                frame = response.json()
                observed[label]["code"] = frame["error"]["code"]
                require(frame.get("id") == "parity-42" and frame["error"]["code"] == code,
                        f"{label}: wrong request ID or error code")
                if code == -32022:
                    require(MODERN in frame["error"]["data"]["supported"], "unsupported revision: missing supported version")
            await recovered()
    return observed


async def exercise(label, endpoint, token, tools, version):
    """Run all three SDK client modes and the direct HTTP checks on one server."""
    sessions = []
    for mode in ("legacy", "auto", MODERN):
        sessions.append(await sdk_round_trip(label, mode, endpoint, token, tools, version))
    return {"sdk": sessions, "wireChecks": await raw_round_trip(endpoint, token)}


async def main():
    """Start both installed servers and report their results with the tested jar's digest."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--reference-root", required=True, type=Path, help="Extracted pinned Python PolicyNIM tree")
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    require(args.java, "Supply the Java 21 executable with --java.")
    require(metadata.version("mcp") == "2.2.0" and metadata.version("policynim") == "0.3.0",
            "Run with the pinned PolicyNIM 0.3.0 and MCP 2.2.0 environment.")
    cli = shutil.which("policynim", path=str(Path(sys.executable).parent))
    require(cli, "Run with the Python interpreter beside the installed policynim command.")
    jar = args.jar.resolve(strict=True)
    with jar.open("rb") as artifact:
        digest = hashlib.file_digest(artifact, "sha256").hexdigest()
    bundle = (args.reference_root / "artifacts/public-generation/generation.zip").resolve(strict=True)
    token = secrets.token_urlsafe(32)
    with tempfile.TemporaryDirectory() as workspace:
        python_dir = Path(workspace) / "python"
        python_env = home(python_dir)
        python_env.update(POLICYNIM_GENERATIONS_DIR=str(python_dir / "generations"),
                          POLICYNIM_EVAL_WORKSPACE_DIR=str(python_dir / "evaluations"))
        await asyncio.to_thread(subprocess.run, [cli, "generation", "import", str(bundle), "--expect-digest", PUBLIC_BUNDLE,
                        "--require-source-match", "--format", "json"], cwd=python_dir, env=python_env,
                       stdout=subprocess.DEVNULL, check=True, timeout=45)
        # The reference CLI takes a numbered port. Its own Uvicorn startup log confirms it acquired the port.
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        python_env.update(POLICYNIM_MCP_HOST="127.0.0.1", POLICYNIM_MCP_PORT=str(port),
                          POLICYNIM_MCP_REQUIRE_AUTH="true", POLICYNIM_MCP_BEARER_TOKENS=token,
                          POLICYNIM_MCP_PUBLIC_BASE_URL="https://parity.invalid")
        async with server([cli, "mcp", "--transport", "streamable-http"], python_env, python_dir,
                          re.compile(rb"Uvicorn running on http://127\.0\.0\.1:(\d+)")) as origin:
            python = await exercise("python", origin + "/mcp", token, PYTHON_TOOLS, "0.3.0")

        java_dir = Path(workspace) / "java"
        java_env = home(java_dir)
        java_env.update(POLICYNIM_MCP_AUTH_ENABLED="true", POLICYNIM_MCP_BEARER_TOKEN=token,
                        POLICYNIM_PROVIDER_NVIDIA_ENABLED="false", POLICYNIM_MCP_PUBLIC_BASE_URL="https://parity.invalid")
        command = [args.java, f"-Duser.home={java_dir}", "-jar", str(jar), "--server.address=127.0.0.1",
                   "--server.port=0", "--policynim.mcp.contract=grounded-v1",
                   "--policynim.mcp.streamable-http-path=/policy-tools"]
        async with server(command, java_env, java_dir, re.compile(rb"Tomcat started on port (\d+)")) as origin:
            java = await exercise("java", origin + "/policy-tools", token, JAVA_TOOLS, jar_version(jar))
    with jar.open("rb") as artifact:
        require(hashlib.file_digest(artifact, "sha256").hexdigest() == digest, "The jar changed during testing.")
    print(json.dumps({"javaJarSha256": digest, "mcpClientVersion": metadata.version("mcp"), "pythonGenerationBundle": PUBLIC_BUNDLE,
                      "python": python, "java": java}, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
