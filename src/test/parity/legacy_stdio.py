"""Check both installed servers with the legacy client from the pinned Python lock."""

import argparse
import asyncio
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from importlib import metadata
from pathlib import Path

from mcp import Client
from mcp.client.stdio import StdioServerParameters, stdio_client


def require(condition, message):
    if not condition:
        raise ValueError(message)


def jar_version(jar):
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    for line in manifest.splitlines():
        if line.startswith("Implementation-Version: "):
            return line.removeprefix("Implementation-Version: ")
    raise ValueError("The jar manifest lacks Implementation-Version.")


async def check_java_discovery(client):
    capabilities = client.session.initialize_result.capabilities.model_dump(by_alias=True, exclude_none=True)
    expected = {
        "completions": {},
        "logging": {},
        "prompts": {"listChanged": True},
        "resources": {"subscribe": False, "listChanged": True},
        "tools": {"listChanged": True},
    }
    require(capabilities == expected, f"java: initialize capabilities changed: {capabilities}")
    require((await client.list_prompts()).prompts == [], "java: unexpected prompt list")
    require((await client.list_resources()).resources == [], "java: unexpected resource list")
    templates = await client.list_resource_templates()
    require(templates.resource_templates == [], "java: unexpected resource template list")


async def probe(label, program, arguments, root, version, tool_names):
    home = root / label
    home.mkdir()
    config = home / "empty.env"
    config.touch()
    env = {
        "HOME": str(home),
        "XDG_CONFIG_HOME": str(home),
        "XDG_DATA_HOME": str(home),
        "POLICYNIM_CONFIG_FILE": str(config),
        "POLICYNIM_MCP_TRANSPORT": "stdio",
        "POLICYNIM_PROVIDER_NVIDIA_ENABLED": "false",
        "POLICYNIM_STORAGE_MODE": "noop",
    }
    params = StdioServerParameters(command=program, args=arguments, env=env, cwd=home)
    with (home / "server.log").open("w") as diagnostics:
        async with asyncio.timeout(60), Client(
            stdio_client(params, errlog=diagnostics), mode="legacy", read_timeout_seconds=10
        ) as client:
            require(client.session.initialize_result is not None, f"{label}: missing initialize")
            require(client.protocol_version == "2025-11-25", f"{label}: negotiated {client.protocol_version}")
            require(client.server_info.version == version, f"{label}: unexpected server version")
            names = {tool.name for tool in (await client.list_tools()).tools}
            require(names == tool_names, f"{label}: unexpected tools {sorted(names)}")
            error = await client.call_tool("policy_search", {})
            require(error.is_error, f"{label}: missing query succeeded")
            if label == "java":
                await check_java_discovery(client)
                require(error.structured_content == {"error": "query must not be blank"},
                        "java: missing-query error changed")
            else:
                require(any("query" in getattr(block, "text", "") for block in error.content),
                        "python: missing-query error omitted its field")
            recovered = {tool.name for tool in (await client.list_tools()).tools}
            require(recovered == names, f"{label}: discovery failed after a tool error")
            return {"serverVersion": version, "protocol": client.protocol_version, "tools": sorted(names)}


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"), help="Java 21 executable")
    args = parser.parse_args()
    require(args.java, "Supply the path to Java 21 with --java.")
    require(metadata.version("mcp") == "2.2.0", "Run with the pinned Python MCP SDK 2.2.0.")
    require(metadata.version("policynim") == "0.3.0", "Run with the pinned PolicyNIM 0.3.0 installation.")
    jar = args.jar.resolve(strict=True)
    python_cli = shutil.which("policynim", path=str(Path(sys.executable).parent))
    require(python_cli, "Run with the Python interpreter beside the installed policynim command.")
    baseline_tools = {"policy_search", "policy_preflight"}
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        python_result = await probe(
            "python", python_cli, ["mcp", "--transport", "stdio"], root, "0.3.0",
            baseline_tools | {"policy_compile", "policy_preflight_evidence"},
        )
        java_result = await probe(
            "java", args.java, [f"-Duser.home={root / 'java'}", "-jar", str(jar)],
            root, jar_version(jar), baseline_tools,
        )
    with jar.open("rb") as artifact:
        digest = hashlib.file_digest(artifact, "sha256").hexdigest()
    print(json.dumps({"javaJarSha256": digest, "java": java_result, "python": python_result}, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
