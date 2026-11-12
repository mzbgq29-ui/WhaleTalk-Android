#!/usr/bin/env python3
"""Run the generated configuration through pinned AstrBot functions, without an account or network."""
from pathlib import Path
import argparse
import ast
import asyncio
import copy
import dataclasses
import json
import runpy
from types import SimpleNamespace

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--astrbot-source", required=True, type=Path)
args = parser.parse_args()
source = args.astrbot_source
generated = json.loads((root / "app/build/bigfish-fixtures/astrbot.json").read_text())
agent = runpy.run_path(str(source / "astrbot/core/config/agent_runner.py"))
cua = runpy.run_path(str(source / "astrbot/core/computer/booters/cua_defaults.py"))

def selected(path, predicate, namespace, deferred=False):
    tree = ast.parse(path.read_text())
    items = [node for node in tree.body if predicate(node)]
    if deferred: items.insert(0, ast.ImportFrom(module="__future__", names=[ast.alias(name="annotations")], level=0))
    ast.fix_missing_locations(module := ast.Module(body=items, type_ignores=[]))
    exec(compile(module, str(path), "exec"), namespace)

defaults = {"get_agent_runner_config_default": agent["get_agent_runner_config_default"], "CUA_DEFAULT_CONFIG": cua["CUA_DEFAULT_CONFIG"]}
selected(source / "astrbot/core/config/default.py", lambda n: isinstance(n, ast.FunctionDef) and n.name == "get_local_permission_defaults" or
         isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "DEFAULT_CONFIG" for t in n.targets), defaults)
configuration = copy.deepcopy(defaults["DEFAULT_CONFIG"])
def merge(current, incoming):
    for key, value in incoming.items():
        if isinstance(current.get(key), dict) and isinstance(value, dict): merge(current[key], value)
        else: current[key] = copy.deepcopy(value)
merge(configuration, generated)
normalized = agent["normalize_agent_runner"](configuration["agent_runner"])
assert normalized["config"]["model"]["provider_id"] == "bigfish-chat"
assert normalized["config"]["model"]["request_max_retries"] == 1
assert normalized["config"]["compression"]["max_turns"] == 16
assert normalized["config"]["misc"]["max_steps"] == 8
assert configuration["provider_tts_settings"]["enable"] is False
assert configuration["provider_stt_settings"]["enable"] is False

provider_tree = ast.parse((source / "astrbot/core/provider/manager.py").read_text())
manager = next(n for n in provider_tree.body if isinstance(n, ast.ClassDef) and any(isinstance(f, ast.FunctionDef) and f.name == "get_merged_provider_config" for f in n.body))
method = next(n for n in manager.body if isinstance(n, ast.FunctionDef) and n.name == "get_merged_provider_config")
namespace = {"copy": copy}
exec(compile(ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[])), "pinned-provider-manager", "exec"), namespace)
model = next(p for p in configuration["provider"] if p["id"] == "bigfish-chat")
resolved = namespace["get_merged_provider_config"](SimpleNamespace(provider_sources_config=configuration["provider_sources"]), model)
assert resolved["model"] == "deepseek-chat" and resolved["key"] == ["sk-test-not-a-real-secret"]
assert resolved["api_base"] == "https://api.deepseek.com/v1"
assert resolved["custom_extra_body"]["max_tokens"] == 1536

message_type = runpy.run_path(str(source / "astrbot/core/platform/message_type.py"))["MessageType"]
sessions = {"__name__": __name__, "dataclass": dataclasses.dataclass, "field": dataclasses.field, "MessageType": message_type}
selected(source / "astrbot/core/platform/message_session.py", lambda n: isinstance(n, ast.ClassDef) and n.name == "MessageSession", sessions)
stages = {"Stage": object, "register_stage": lambda value: value, "MessageType": message_type, "logger": SimpleNamespace(info=lambda *args: None)}
selected(source / "astrbot/core/pipeline/whitelist_check/stage.py", lambda n: isinstance(n, ast.ClassDef), stages, deferred=True)
platform_id = next(p["id"] for p in configuration["platform"] if p["type"] == "aiocqhttp")
class Event:
    def __init__(self, sender, group=""):
        self.role = "admin" if sender == "100001" else "member"
        self.kind = message_type.GROUP_MESSAGE if group else message_type.FRIEND_MESSAGE
        self.unified_msg_origin = str(sessions["MessageSession"](platform_id, self.kind, group or sender))
        self.group = group; self.stopped = False
    def get_platform_name(self): return "aiocqhttp"
    def get_message_type(self): return self.kind
    def get_group_id(self): return self.group
    def stop_event(self): self.stopped = True

async def check():
    stage = stages["WhitelistCheckStage"]()
    await stage.initialize(SimpleNamespace(astrbot_config=configuration))
    cases = [("owner friend", Event("100001"), False), ("allowed friend", Event("100002"), False),
             ("unlisted friend", Event("100003"), True), ("allowed group", Event("100003", "200001"), False),
             ("owner in unlisted group", Event("100001", "200002"), True)]
    for name, event, stopped in cases:
        await stage.process(event)
        assert event.stopped is stopped, name
    return len(cases)
count = asyncio.run(check())
report = {"scope": "Pinned AstrBot 4.29.3 actual normalization, provider merge and whitelist methods; no QQ or paid API.",
          "platformId": platform_id, "agentRunner": "passed", "providerMerge": "passed", "actualWhitelistCases": count, "result": "passed"}
(root / "validation").mkdir(exist_ok=True)
(root / "validation/upstream-contract.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps(report, ensure_ascii=False, indent=2))
