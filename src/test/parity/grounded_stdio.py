"""Compare the installed PolicyNIM stdio services using the pinned MCP client and raw JSON-RPC."""

import argparse
import asyncio
import hashlib
import json
import shutil
import sys
import tempfile
from contextlib import asynccontextmanager
from importlib import metadata
from pathlib import Path

from grounded_http import (
    CAPABILITIES_META,
    JAVA_TOOLS,
    LEGACY,
    MODERN,
    VERSION_META,
    home,
)
from legacy_stdio import jar_version, require
from mcp import Client
from mcp.client.stdio import StdioServerParameters, stdio_client

PYTHON_STDIO_TOOLS = JAVA_TOOLS | {"policy_compile", "policy_preflight_evidence"}
MODERN_META = {"_meta": {VERSION_META: MODERN, CAPABILITIES_META: {}}}
INITIALIZE = {"protocolVersion": LEGACY, "capabilities": {}, "clientInfo": {"name": "parity", "version": "1"}}
INITIALIZED = {"jsonrpc": "2.0", "method": "notifications/initialized"}
JAVA_FRAME_BYTES = 4 * 1024 * 1024


def request(request_id, method, params):
    """Build a JSON-RPC request with a caller-chosen ID."""
    return {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}


async def sdk_checks(parameters, tools, version):
    """Negotiate each client mode, call both tools, and verify discovery after both refusals."""
    results = []
    for mode in ("legacy", "auto", MODERN):
        with (Path(parameters.cwd) / f"sdk-{mode}.log").open("w") as diagnostics:
            transport = stdio_client(parameters, errlog=diagnostics)
            async with asyncio.timeout(45), Client(transport, mode=mode, read_timeout_seconds=10) as client:
                protocol = LEGACY if mode == "legacy" else MODERN
                require(client.protocol_version == protocol, f"{mode}: unexpected protocol revision")
                if mode != MODERN:
                    require(client.server_info.name == "PolicyNIM" and client.server_info.version == version,
                            f"{mode}: wrong installed server identity")
                for tool, field in (("policy_search", "query"), ("policy_preflight", "task")):
                    error = await client.call_tool(tool, {})
                    require(error.is_error and any(field in getattr(item, "text", "") for item in error.content),
                            f"{mode}: missing safe {tool} refusal for {field}")
                names = sorted(tool.name for tool in (await client.list_tools()).tools)
                require(names == sorted(tools), f"{mode}: unexpected tools {names}")
                results.append({"mode": mode, "protocol": protocol, "tools": names})
    return results


@asynccontextmanager
async def raw_server(parameters):
    """Start a child with separate stdio pipes and reap it if a wire check fails."""
    with (Path(parameters.cwd) / "raw.log").open("ab") as diagnostics:
        child = await asyncio.create_subprocess_exec(parameters.command, *parameters.args,
            cwd=parameters.cwd, env=parameters.env, stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=diagnostics)
        try:
            async with asyncio.timeout(45):
                yield child
        finally:
            if child.returncode is None:
                child.kill()
                await asyncio.wait_for(child.wait(), 5)


async def send(child, *frames):
    """Write a batch in one flush, preserving malformed or padded lines supplied as bytes."""
    for frame in frames:
        data = frame if isinstance(frame, bytes) else json.dumps(frame, separators=(",", ":")).encode()
        child.stdin.write(data + b"\n")
    await child.stdin.drain()


def decode(line):
    """Accept one JSON-RPC response on stdout and preserve its correlation ID."""
    require(line.endswith(b"\n"), "The server closed stdout before a complete frame.")
    frame = json.loads(line)
    require(frame.get("jsonrpc") == "2.0" and "id" in frame and (("result" in frame) != ("error" in frame)),
            "Expected one JSON-RPC response on stdout.")
    return frame


async def replies(child, *ids):
    """Collect exactly one response for each ID even if pipelined replies complete out of order."""
    received = {}
    for _ in ids:
        frame = decode(await asyncio.wait_for(child.stdout.readline(), 15))
        request_id = frame["id"]
        require(request_id in ids and request_id not in received, f"Unexpected response ID {request_id!r}.")
        received[request_id] = frame
    return received


def tool_list(frame, tools, modern):
    """Match the tool names and completion envelope for the selected protocol."""
    result = frame["result"]
    require(sorted(item["name"] for item in result["tools"]) == sorted(tools), "Unexpected stdio tool list.")
    require(result.get("resultType") == "complete" if modern else "resultType" not in result,
            "Unexpected tool-list completion envelope.")


async def missing_query(child, modern):
    """Check the raw correlated tool result when the caller omits the required query."""
    params = {"name": "policy_search", "arguments": {}}
    if modern:
        params.update(MODERN_META)
    await send(child, request("missing", "tools/call", params))
    result = (await replies(child, "missing"))["missing"]["result"]
    require(result.get("isError") is True and
            any(item.get("type") == "text" and "query" in item["text"] for item in result["content"]),
            "Expected a correlated tool result naming the missing query.")
    require(result.get("resultType") == "complete" if modern else "resultType" not in result,
            "Unexpected completion envelope for the tool refusal.")


async def eof(child):
    """Close stdin, collect any final protocol frames, and require a successful bounded exit."""
    child.stdin.close()
    async with asyncio.timeout(10):
        remaining = [decode(line) async for line in child.stdout]
        require(await child.wait() == 0, "The stdio server exited unsuccessfully at EOF.")
    return remaining


async def modern_checks(parameters, product, tools):
    """Open modern with a partial envelope, recover, and keep the same era through malformed input."""
    async with raw_server(parameters) as child:
        partial = {"_meta": {VERSION_META: MODERN}}
        await send(child, *([INITIALIZED] * 9), b"{", request("partial", "tools/list", partial),
                   request("direct", "tools/list", MODERN_META))
        opened = await replies(child, "partial", "direct")
        require(opened["partial"]["error"]["code"] == -32602, "Missing modern capabilities must return -32602.")
        tool_list(opened["direct"], tools, True)
        await missing_query(child, True)

        for label, params in (("plain", INITIALIZE), ("stamped", INITIALIZE | MODERN_META)):
            await send(child, request(label, "initialize", params))
            error = (await replies(child, label))[label]["error"]
            require(error["code"] == -32022 and error["data"]["requested"] == LEGACY
                    and MODERN in error["data"]["supported"], f"{label}: unexpected wrong-era refusal")
            await send(child, request("retry", "tools/list", MODERN_META))
            tool_list((await replies(child, "retry"))["retry"], tools, True)

        large = json.dumps(request("large", "tools/list", MODERN_META)).encode().ljust(JAVA_FRAME_BYTES + 1)
        await send(child, b"{", large, request("next", "tools/list", MODERN_META))
        ids = ("large", "next") if product == "python" else ("next",)
        after_large = await replies(child, *ids)
        for frame in after_large.values():
            tool_list(frame, tools, True)
        require(not await eof(child), "Unexpected stdout after the final modern reply.")
    return {"directTools": sorted(tools), "missingCapabilities": -32602, "legacyInitialize": -32022,
            "stampedInitialize": -32022, "overLimitBytes": len(large), "afterOverLimit": sorted(after_large)}


async def legacy_checks(parameters, tools):
    """Select legacy with a progress token, pipeline initialize and discovery, and recover from a modern request."""
    async with raw_server(parameters) as child:
        await send(child, request("progress", "ping", {"_meta": {"progressToken": "sample"}}))
        require((await replies(child, "progress"))["progress"]["result"] == {}, "The first legacy ping failed.")
        await send(child, request("init", "initialize", INITIALIZE | MODERN_META), INITIALIZED,
                   request("list", "tools/list", {}))
        opened = await replies(child, "init", "list")
        require(opened["init"]["result"]["protocolVersion"] == LEGACY, "The server lost its selected legacy era.")
        tool_list(opened["list"], tools, False)
        await missing_query(child, False)
        await send(child, request("wrong", "tools/list", MODERN_META))
        error = (await replies(child, "wrong"))["wrong"]["error"]
        require(error["code"] == -32600, "A modern request on legacy must return -32600.")
        await send(child, request("last", "tools/list", {}))
        tool_list((await replies(child, "last"))["last"], tools, False)
        require(not await eof(child), "Unexpected stdout after the final legacy reply.")
    return {"progressTokenPing": True, "pipelinedList": sorted(tools), "modernRequest": error["code"]}


async def eof_checks(parameters, product, tools):
    """Compare EOF before selection and immediately after writing two direct modern requests."""
    async with raw_server(parameters) as child:
        require(not await eof(child), "EOF before the first request produced a response.")
    async with raw_server(parameters) as child:
        ids = ["first", "second"]
        await send(child, *(request(i, "tools/list", MODERN_META) for i in ids))
        final = await eof(child)
        received = sorted(frame["id"] for frame in final)
        require(len(received) == len(set(received)) and set(received) <= set(ids), "Unexpected IDs at active EOF.")
        if product == "java":
            require(received == ids, "The Java server lost a pending response at EOF.")
        for frame in final:
            tool_list(frame, tools, True)
    return {"beforeSelection": 0, "activeExit": 0, "activeReplyIds": received}


async def wide_id_checks(parameters, product, tools):
    """Check one integer beyond Java's signed long range and retry using its string spelling."""
    wide_id = 2**63
    observed = {}
    for era, method, params in (("modern", "tools/list", MODERN_META), ("legacy", "initialize", INITIALIZE)):
        async with raw_server(parameters) as child:
            results = []
            for request_id in (wide_id, str(wide_id)):
                await send(child, request(request_id, method, params))
                reply = (await replies(child, request_id))[request_id]
                require(type(reply["id"]) is type(request_id), f"{era}: the server changed the ID's JSON type")
                code = None
                if product == "java" and request_id == wide_id:
                    code = reply["error"]["code"]
                    require(code == -32600, f"{era}: expected a correlated refusal for the wide integer ID")
                elif era == "modern":
                    tool_list(reply, tools, True)
                else:
                    require(reply["result"]["protocolVersion"] == LEGACY, "Expected a successful legacy initialize.")
                results.append({"id": reply["id"], "errorCode": code})
            if era == "legacy":
                await send(child, INITIALIZED, request("list", "tools/list", {}))
                tool_list((await replies(child, "list"))["list"], tools, False)
            require(not await eof(child), f"{era}: unexpected output after the wide ID retry")
            observed[era] = results
    return observed


async def main():
    """Compare the installed services from private working directories and print their observed wire results."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    cli = shutil.which("policynim", path=str(Path(sys.executable).parent))
    require(args.java and cli and metadata.version("mcp") == "2.2.0" and metadata.version("policynim") == "0.3.0",
            "Use Java 21 and the installed pinned Python PolicyNIM 0.3.0 and MCP 2.2.0 environment.")
    jar = args.jar.resolve(strict=True)
    with jar.open("rb") as artifact:
        digest = hashlib.file_digest(artifact, "sha256").hexdigest()
    result = {"javaJarSha256": digest, "mcpClientVersion": metadata.version("mcp")}
    with tempfile.TemporaryDirectory() as workspace:
        servers = (("python", cli, PYTHON_STDIO_TOOLS, "0.3.0"), ("java", args.java, JAVA_TOOLS, jar_version(jar)))
        for product, program, tools, version in servers:
            directory = Path(workspace) / product
            env = home(directory) | {"POLICYNIM_EVAL_WORKSPACE_DIR": str(directory / "evaluations"),
                                     "POLICYNIM_MCP_TRANSPORT": "stdio", "POLICYNIM_PROVIDER_NVIDIA_ENABLED": "false"}
            arguments = ["mcp", "--transport", "stdio"]
            if product == "java":
                arguments = [f"-Duser.home={directory}", "-jar", str(jar), "--policynim.mcp.contract=grounded-v1"]
            params = StdioServerParameters(command=program, args=arguments, cwd=directory, env=env)
            result[product] = {
                "sdk": await sdk_checks(params, tools, version),
                "modern": await modern_checks(params, product, tools),
                "legacy": await legacy_checks(params, tools),
                "wideId": await wide_id_checks(params, product, tools),
                "eof": await eof_checks(params, product, tools),
            }
    with jar.open("rb") as artifact:
        require(hashlib.file_digest(artifact, "sha256").hexdigest() == digest, "The jar changed during testing.")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
