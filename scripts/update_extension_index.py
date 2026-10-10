
#!/usr/bin/env python3
"""Actualiza una extension en el catalogo de KayHelthRepo."""

import json
import re
import sys
from pathlib import Path


def read_value(text, key):
    match = re.search(
        rf'\b{re.escape(key)}\s*=\s*"([^"]+)"',
        text,
    )
    return match.group(1) if match else None


def main():
    if len(sys.argv) != 4:
        raise SystemExit(
            "Uso: update_extension_index.py REPO EXTENSION APK"
        )

    repo = Path(sys.argv[1]).resolve()
    extension = sys.argv[2]
    apk = Path(sys.argv[3]).resolve()

    match = re.fullmatch(
        r"([a-z]{2,3})/([a-z0-9_]+)",
        extension,
    )
    if not match:
        raise ValueError(f"Extension invalida: {extension}")

    lang, slug = match.groups()

    apk_match = re.fullmatch(
        r"tachiyomi-([a-z]{2,3})\.([a-z0-9_]+)"
        r"-v(\d+)\.(\d+)\.(\d+)\.apk",
        apk.name,
    )

    if not apk_match or apk_match.group(1, 2) != (lang, slug):
        raise ValueError(f"Nombre de APK inesperado: {apk.name}")

    major, minor, patch = map(
        int, apk_match.group(3, 4, 5)
    )

    if minor >= 100 or patch >= 1000:
        raise ValueError("Version fuera del rango permitido")

    code = major * 100000 + minor * 1000 + patch
    version = f"{major}.{minor}.{patch}"
    package = (
        f"eu.kanade.tachiyomi.extension.{lang}.{slug}"
    )

    if not apk.is_file():
        raise FileNotFoundError(apk)

    published_apk = repo / "apk" / apk.name
    if not published_apk.is_file():
        raise FileNotFoundError(published_apk)

    index_path = repo / "index.json"
    index = json.loads(
        index_path.read_text(encoding="utf-8")
    )

    extensions = index["extensionList"]["extensions"]

    matches = [
        item for item in extensions
        if item["packageName"] == package
    ]

    if len(matches) > 1:
        raise ValueError(f"Extension duplicada: {package}")

    apk_url = (
        "https://helthstin.github.io/KayHelthRepo/apk/"
        + apk.name
    )

    if matches:
        entry = matches[0]
        current = int(entry["versionCode"])

        if code < current:
            raise ValueError(
                f"Version anterior bloqueada: {code} < {current}"
            )

        if code == current:
            print(
                "AVISO: version sin aumentar. "
                "Kairead no detectara una actualizacion nueva."
            )

        entry["resources"]["apkUrl"] = apk_url
        entry["versionCode"] = code
        entry["versionName"] = version

    else:
        gradle = (
            Path("src") / extension / "build.gradle.kts"
        )

        if not gradle.is_file():
            raise FileNotFoundError(gradle)

        content = gradle.read_text(encoding="utf-8")

        name = read_value(content, "name")
        lib = read_value(content, "libVersion")
        home = read_value(content, "baseUrl")

        if not name or not lib:
            raise ValueError(
                f"Faltan datos de la extension: {extension}"
            )

        warning = re.search(
            r"\bcontentWarning\s*=\s*ContentWarning\.(\w+)",
            content,
        )

        safety = warning.group(1) if warning else "SAFE"

        if safety not in {"SAFE", "MIXED", "NSFW"}:
            raise ValueError(
                f"Clasificacion desconocida: {safety}"
            )

        entry = {
            "name": name,
            "packageName": package,
            "resources": {
                "apkUrl": apk_url,
                "iconUrl": (
                    "https://raw.githubusercontent.com/"
                    "helthstin/kairead-extensions-source/"
                    f"main/src/{extension}/res/"
                    "mipmap-xhdpi/ic_launcher.png"
                )
            },
            "extensionLib": lib,
            "versionCode": code,
            "versionName": version,
            "contentWarning": (
                f"CONTENT_WARNING_{safety}"
            ),
            "sources": [
                {
                    "id": 0,
                    "name": name,
                    "language": lang,
                    "homeUrl": home or ""
                }
            ]
        }

        extensions.append(entry)

    index_path.write_text(
        json.dumps(
            index,
            ensure_ascii=False,
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )

    print(
        f"Catalogo actualizado: {package} "
        f"v{version} ({code})"
    )


if __name__ == "__main__":
    main()
  
