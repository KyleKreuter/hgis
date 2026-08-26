"""Ein Werkzeugaufruf über das MCP-Protokoll, von der Kommandozeile.

    python tools/mcp_call.py --list
    python tools/mcp_call.py <werkzeug> '<json-argumente>'

Startet den hGIS-MCP-Server als Unterprozess über stdio, ruft ein Werkzeug und
druckt die Antwort. Gedacht für die wiederkehrende Abnahmeprobe aus TASKS.md,
Aufgabe 23: ein Agent bedient hGIS eine Stunde lang nur über die Werkzeuge.

Der Weg über das Protokoll ist Absicht. Ein direkter Python-Aufruf überspringt
die Schicht, in der pydantic die Argumente aufbaut -- und genau dort saß der
stille Datenverlust, den Phase 33 gefunden hat.

Die Antwortfelder heißen in der Python-Bibliothek snake_case
(input_schema, is_error, structured_content), nicht wie im
Drahtformat. Wer das verwechselt, bekommt einen AttributeError statt einer
Antwort; TASKS.md führt es in der Fallstricktabelle.
"""

import asyncio, json, os, sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

PARAMS = StdioServerParameters(
    command=sys.executable,
    args=["-m", "hgis.mcp"],
    env={**os.environ, "HGIS_URL": os.environ.get("HGIS_URL", "")},
)


async def main() -> int:
    async with stdio_client(PARAMS) as (read, write):
        async with ClientSession(read, write) as session:
            init = await session.initialize()
            if sys.argv[1] == "--list":
                print("Server:", getattr(init, "instructions", None) or "(keine Anleitung)")
                tools = (await session.list_tools()).tools
                print(f"\n{len(tools)} Werkzeuge:\n")
                for t in tools:
                    print("=" * 70)
                    print(t.name)
                    print(t.description)
                    print("Argumente:", json.dumps(t.input_schema, ensure_ascii=False, indent=2))
                return 0
            name = sys.argv[1]
            args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
            result = await session.call_tool(name, args)
            if result.is_error:
                print("FEHLER:")
            for item in result.content:
                print(getattr(item, "text", item))
            if result.structured_content is not None:
                print("\nstructured_content:")
                print(json.dumps(result.structured_content, ensure_ascii=False, indent=2))
            return 1 if result.is_error else 0


sys.exit(asyncio.run(main()))
