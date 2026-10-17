#!/usr/bin/env python3
"""Usage: verify-release-zip.py <dist-dir> <plugin-id> <version>

Checks that <dist-dir> holds exactly one ZIP, <name>-<version>.zip, whose main jar
<name>/lib/<name>-<version>.jar declares <plugin-id> and <version> in META-INF/plugin.xml.
<name> is the last dot-separated part of <plugin-id>. Prints the ZIP path on success.
"""
import io
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def verify(dist: Path, plugin_id: str, version: str) -> Path:
    if re.search(r"-dev\.|-SNAPSHOT$|\.dirty$", version):
        raise ValueError(f"{version} is not a release version")
    name = plugin_id.rsplit(".", 1)[-1]
    zips = sorted(p.name for p in dist.glob("*.zip"))
    expected = f"{name}-{version}.zip"
    if zips != [expected]:
        raise ValueError(f"expected exactly {expected} in {dist}, found {zips}")
    archive = dist / expected
    with zipfile.ZipFile(archive) as outer:
        prefix = f"{name}/lib/{name}-"
        main_jars = [n for n in outer.namelist() if n.startswith(prefix) and n.endswith(".jar")]
        jar = f"{prefix}{version}.jar"
        if main_jars != [jar]:
            raise ValueError(f"expected main jar {jar}, found {main_jars}")
        with zipfile.ZipFile(io.BytesIO(outer.read(jar))) as inner:
            try:
                descriptor = ET.fromstring(inner.read("META-INF/plugin.xml"))
            except KeyError:
                raise ValueError(f"{jar} has no META-INF/plugin.xml") from None
    actual = {tag: descriptor.findtext(tag, "").strip() for tag in ("id", "version")}
    if actual != {"id": plugin_id, "version": version}:
        raise ValueError(f"plugin.xml declares id {actual['id']!r} version {actual['version']!r}, expected {plugin_id!r} {version!r}")
    return archive


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip())
    try:
        print(verify(Path(sys.argv[1]), sys.argv[2], sys.argv[3]))
    except (ValueError, zipfile.BadZipFile) as error:
        sys.exit(f"verify-release-zip: {error}")
