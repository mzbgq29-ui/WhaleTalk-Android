#!/usr/bin/env python3
"""Preserve Linux notices and bind updated Android artifacts to actual source and license evidence."""
from pathlib import Path
import hashlib
import json
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
folder = root / ".local/inputs/assets/third-party"
index_file = folder / "manifest.json"
index = json.loads(index_file.read_text())
rows = (root / "app/build/distribution/android-dependencies/artifacts.tsv").read_text().splitlines()
artifacts = sorted(":".join(row.split("\t")[:5]) for row in rows)
def coordinates(value): return value.rsplit(":", 1)[0]
baseline = index.setdefault("bigfishUpstreamAndroidArtifacts", index["androidArtifacts"])
old = {coordinates(item): item for item in baseline}
new = {coordinates(item): item for item in artifacts}
for name in old.keys() & new.keys():
    assert old[name] == new[name] or name == "AstrBotAndroid:core:unspecified:jar", "External artifact bytes changed without a version change: " + name
metadata_folder = root / "app/build/distribution/android-dependencies"
def resolved(name):
    result = {}
    for row in (metadata_folder / name).read_text().splitlines():
        module, path, status = row.split("\t")
        if status == "resolved": result[module] = Path(path)
    return result
sources = resolved("sources.tsv")
poms = resolved("poms.tsv")
upgraded = sorted({":".join(name.split(":")[:3]) for name in new.keys() - old.keys() if not name.startswith("AstrBotAndroid:")})
evidence = []
license_parents = {}
for module in upgraded:
    assert module in sources and sources[module].is_file(), "Missing actual sources for upgraded dependency: " + module
    assert module in poms and poms[module].is_file(), "Missing actual POM for upgraded dependency: " + module
    pom = ET.parse(poms[module]).getroot()
    licenses = [{"name": item.findtext("{*}name"), "url": item.findtext("{*}url")} for item in pom.findall("{*}licenses/{*}license")]
    # Maven modules may inherit license metadata from their published parent POM.
    # Keep that exact parent as evidence instead of inventing a module exception.
    if not licenses and pom.find("{*}parent") is not None:
        parent = pom.find("{*}parent")
        group, artifact, version = (parent.findtext("{*}" + key) for key in ("groupId", "artifactId", "version"))
        candidate = root / "docs/legal/maven" / group.replace(".", "/") / artifact / version / "pom.xml"
        if candidate.is_file():
            parent_pom = ET.parse(candidate).getroot()
            assert [parent_pom.findtext("{*}" + key) for key in ("groupId", "artifactId", "version")] == [group, artifact, version]
            licenses = [{"name": item.findtext("{*}name"), "url": item.findtext("{*}url")} for item in parent_pom.findall("{*}licenses/{*}license")]
            license_parents[module] = candidate
    assert licenses and all(item["name"] and item["url"] for item in licenses), "Missing license evidence: " + module
    evidence.append({"module": module, "sourceSHA256": hashlib.sha256(sources[module].read_bytes()).hexdigest(),
        "pomSHA256": hashlib.sha256(poms[module].read_bytes()).hexdigest(), "licenses": licenses})
    if module in license_parents:
        evidence[-1]["licenseParentPomSHA256"] = hashlib.sha256(license_parents[module].read_bytes()).hexdigest()
archive = folder / "notices.zip"
temporary = folder / "notices.new.zip"
metadata = {
    "upstream": "https://github.com/JunWan666/astrbot-for-android/tree/3ea3dc662ab9274e19440a41efc385444f64edab",
    "upstreamApkSHA256": "cacdc3332c84df32a9958cdb16a3167c0a90d8f9c27815cde63df9e93ad658a1",
    "runtimeSHA256": index["runtimeSha256"],
    "androidArtifacts": artifacts,
    "additionalAndroidSourceEvidence": evidence,
    "note": "Original collection records describe unchanged upstream Linux payloads. Current Android artifact hashes are listed here; new or upgraded external modules include their resolved sources, POMs and license declarations. Modified application/core sources accompany the BigFish source archive.",
}
with zipfile.ZipFile(archive) as source, zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
    for item in source.infolist():
        if not item.filename.startswith("bigfish/"): target.writestr(item, source.read(item))
    target.writestr("bigfish/LICENSE", (root / "LICENSE").read_bytes())
    target.writestr("bigfish/AUTHOR.txt", "大肥鱼应用界面与维护：mzbgq。\n开源运行层及社区作品保留各自作者与原许可。\n")
    for path in (root / "docs/artwork").iterdir():
        if path.is_file(): target.writestr("bigfish/artwork/" + path.name, path.read_bytes())
    for item in evidence:
        module = item["module"]
        path = "bigfish/android/" + module.replace(":", "/")
        target.writestr(path + "/sources.jar", sources[module].read_bytes())
        target.writestr(path + "/pom.xml", poms[module].read_bytes())
        if module in license_parents: target.writestr(path + "/license-parent.pom.xml", license_parents[module].read_bytes())
    # Preserve the private platform distribution's actual vendor terms separately.
    vendor = root / "docs/legal/snowluma"
    if vendor.is_dir():
        for path in vendor.iterdir():
            if path.is_file(): target.writestr("bigfish/vendor/SnowLuma/" + path.name, path.read_bytes())
    qq_legal = root / "docs/legal/qq-3.2.35"
    if qq_legal.is_dir():
        for path in sorted(qq_legal.iterdir()):
            if path.is_file(): target.writestr("bigfish/vendor/QQ-3.2.35/" + path.name, path.read_bytes())
        metadata["bundledOfficialQq"] = json.loads((qq_legal / "package-source.json").read_text())
    companion_legal = root / "docs/legal/bundled-plugins"
    if companion_legal.is_dir():
        for path in sorted(companion_legal.iterdir()):
            if path.is_file(): target.writestr("bigfish/vendor/CompanionPlugins/" + path.name, path.read_bytes())
        metadata["bundledCompanionPlugins"] = json.loads((companion_legal / "provenance.json").read_text())
    persona_legal = root / "docs/legal/persona-library"
    for path in sorted(persona_legal.iterdir()):
        if path.is_file(): target.writestr("bigfish/vendor/PersonaLibrary/" + path.name, path.read_bytes())
    metadata["personaLibrary"] = json.loads((persona_legal / "provenance.json").read_text())
    tavern_legal = root / "docs/legal/tavern-library"
    for path in sorted(tavern_legal.rglob("*")):
        if path.is_file(): target.writestr("bigfish/vendor/TavernLibrary/" + path.relative_to(tavern_legal).as_posix(), path.read_bytes())
    metadata["tavernLibrary"] = json.loads((tavern_legal / "provenance.json").read_text())
    target.writestr("bigfish/FORK-MANIFEST.json", json.dumps(metadata, ensure_ascii=False, indent=2))
temporary.replace(archive)
with zipfile.ZipFile(archive) as result: index["names"] = sorted(result.namelist())
index["androidArtifacts"] = artifacts
index["noticesSha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
index_file.write_text(json.dumps(index, ensure_ascii=False, indent=2))
print(f"All upstream Linux notices retained; {len(evidence)} new/upgraded Android modules bound to sources, POMs and licenses.")
