#!/usr/bin/env python3
"""Vendor the Agency Agents catalog into Android assets.

Usage:
    python scripts/import_agency_agents.py <agency-agents-checkout> \
        core/database/src/main/assets/agency_agents

The generated assets are deterministic for a given upstream commit. Agent markdown
is kept verbatim so provenance and future catalog refreshes remain auditable.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


REPOSITORY_URL = "https://github.com/msitarzewski/agency-agents"

# WanXiang is a software-building environment, so the bundled roster intentionally
# excludes sales, finance, healthcare, marketing, and other business functions.
# None means every upstream agent in that department; a set is an explicit curation.
CURATED_DEPARTMENTS: dict[str, set[str] | None] = {
    "engineering": None,
    "design": {
        "design-persona-walkthrough",
        "design-ui-designer",
        "design-ui-finish-gate-reviewer",
        "design-ux-architect",
        "design-ux-researcher",
        "design-whimsy-injector",
    },
    "product": None,
    "project-management": {
        "project-management-experiment-tracker",
        "project-management-jira-workflow-steward",
        "project-management-project-shepherd",
        "project-manager-senior",
    },
    "testing": None,
    "security": None,
    "game-development": None,
    "spatial-computing": None,
    "specialized": {
        "agentic-identity-trust",
        "agents-orchestrator",
        "automation-governance-architect",
        "data-consolidation-agent",
        "identity-graph-operator",
        "lsp-index-engineer",
        "specialized-codebase-archaeologist",
        "specialized-developer-advocate",
        "specialized-document-generator",
        "specialized-mcp-builder",
        "specialized-model-qa",
        "specialized-salesforce-architect",
        "specialized-workflow-architect",
        "zk-steward",
    },
}


def parse_frontmatter(text: str, source: Path) -> dict[str, str]:
    normalized = text.replace("\r\n", "\n")
    if not normalized.startswith("---\n"):
        raise ValueError(f"{source}: missing YAML frontmatter")
    end = normalized.find("\n---\n", 4)
    if end < 0:
        raise ValueError(f"{source}: unterminated YAML frontmatter")
    lines = normalized[4:end].splitlines()
    values: dict[str, str] = {}
    index = 0
    while index < len(lines):
        match = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", lines[index])
        if not match:
            index += 1
            continue
        key, raw_value = match.groups()
        if raw_value in {"|", "|-", ">", ">-"}:
            block: list[str] = []
            index += 1
            while index < len(lines) and (not lines[index] or lines[index][0].isspace()):
                block.append(lines[index].lstrip())
                index += 1
            separator = "\n" if raw_value.startswith("|") else " "
            values[key] = separator.join(block).strip()
            continue
        value = raw_value.strip()
        if len(value) >= 2 and value[0] == value[-1] == '"':
            try:
                value = json.loads(value)
            except json.JSONDecodeError:
                value = value[1:-1]
        elif len(value) >= 2 and value[0] == value[-1] == "'":
            value = value[1:-1].replace("''", "'")
        values[key] = value
        index += 1
    return values


def git_revision(source_root: Path) -> str:
    return subprocess.check_output(
        ["git", "-C", str(source_root), "rev-parse", "HEAD"],
        text=True,
    ).strip()


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2

    source_root = Path(sys.argv[1]).resolve()
    output_root = Path(sys.argv[2]).resolve()
    divisions_path = source_root / "divisions.json"
    license_path = source_root / "LICENSE"
    if not divisions_path.is_file() or not license_path.is_file():
        raise ValueError(f"Not an agency-agents checkout: {source_root}")
    if output_root.name != "agency_agents":
        raise ValueError(f"Refusing to replace unexpected output directory: {output_root}")

    divisions_source = json.loads(divisions_path.read_text(encoding="utf-8"))["divisions"]
    revision = git_revision(source_root)
    departments: list[dict[str, object]] = []
    agents: list[dict[str, object]] = []
    seen_ids: set[str] = set()

    for department_order, (department_id, included_stems) in enumerate(CURATED_DEPARTMENTS.items()):
        metadata = divisions_source[department_id]
        departments.append(
            {
                "id": department_id,
                "name": metadata["label"],
                "icon": metadata["icon"],
                "color": metadata["color"],
                "sortOrder": department_order,
            }
        )
        department_root = source_root / department_id
        markdown_files = sorted(department_root.rglob("*.md"), key=lambda path: path.as_posix())
        if included_stems is not None:
            markdown_files = [path for path in markdown_files if path.stem in included_stems]
            missing = included_stems - {path.stem for path in markdown_files}
            if missing:
                raise ValueError(f"{department_id}: curated agents missing upstream: {sorted(missing)}")
        for department_agent_order, markdown_path in enumerate(markdown_files):
            frontmatter = parse_frontmatter(markdown_path.read_text(encoding="utf-8"), markdown_path)
            if not frontmatter.get("name") or not frontmatter.get("description"):
                raise ValueError(f"{markdown_path}: name and description are required")
            agent_id = "agency_" + markdown_path.stem.replace("-", "_")
            if agent_id in seen_ids:
                raise ValueError(f"Duplicate generated agent id: {agent_id}")
            seen_ids.add(agent_id)
            relative_source = markdown_path.relative_to(source_root).as_posix()
            agents.append(
                {
                    "id": agent_id,
                    "name": frontmatter["name"],
                    "description": frontmatter["description"],
                    "departmentId": department_id,
                    "promptPath": f"agency_agents/agents/{relative_source}",
                    "sortOrder": department_order * 1000 + department_agent_order,
                }
            )

    if output_root.exists():
        shutil.rmtree(output_root)
    (output_root / "agents").mkdir(parents=True)
    for department_id in CURATED_DEPARTMENTS:
        department_agents = [agent for agent in agents if agent["departmentId"] == department_id]
        for agent in department_agents:
            relative_source = Path(str(agent["promptPath"])).relative_to("agency_agents/agents")
            destination = output_root / "agents" / relative_source
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_root / relative_source, destination)
    shutil.copy2(license_path, output_root / "LICENSE")

    source_metadata = {
        "repository": REPOSITORY_URL,
        "revision": revision,
        "license": "MIT",
        "agentCount": len(agents),
        "departmentCount": len(departments),
    }
    (output_root / "source.json").write_text(
        json.dumps(source_metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    (output_root / "catalog.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "source": source_metadata,
                "departments": departments,
                "agents": agents,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Imported {len(agents)} agents in {len(departments)} departments from {revision}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
