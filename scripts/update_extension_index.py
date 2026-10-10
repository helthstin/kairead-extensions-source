
#!/usr/bin/env python3
"""Actualiza KayHelthRepo usando los metadatos reales de Gradle."""

import json
import re
import sys
from pathlib import Path


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
    module = f"{lang}.{slug}"

    metadata_path = (
        Path("src") / extension /
        "build/keiyoushi-source-info.json"
    )

    metadata = json.loads(
        metadata_path.read_text(encoding="utf-8")
    )

    package = metadata["packageName"]
    version = metadata["versionName"]
    code = int(metadata["versionCode"])

    expected_package = (
        f"eu.kanade.tachiyomi.extension.{module}"
    )

    if metadata["module"] != module:
        raise ValueError("El modulo no coincide")

    if package != expected_package:
        raise ValueError("El paquete no coincide")

    expected_apk = f"tachiyomi-{module}-v{version}.apk"

    if apk.name != expected_apk:
        raise ValueError(
            f"APK incorrecta: {apk.name}. "
            f"Se esperaba: {expected_apk}"
        )

    if not apk.is_file():
        raise FileNotFoundError(apk)

    if not (repo / "apk" / apk.name).is_file():
        raise FileNotFoundError(
            f"APK no publicada: {apk.name}"
        )

    sources = metadata["sources"]

    if not sources:
        raise ValueError("La extension no tiene fuentes")

    source_entries = [
        {
            "id": int(source["id"]),
            "name": source["name"],
            "language": source["lang"],
            "homeUrl": source["baseUrl"],
        }
        for source in sources
    ]

    warnings = {
        1: "CONTENT_WARNING_SAFE",
        2: "CONTENT_WARNING_MIXED",
        3: "CONTENT_WARNING_NSFW",
    }

    warning_code = int(metadata["contentWarning"])

    if warning_code not in warnings:
        raise ValueError(
            f"Clasificacion desconocida: {warning_code}"
        )

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
        raise ValueError(f"Paquete duplicado: {package}")

    apk_url = (
        "https://helthstin.github.io/KayHelthRepo/apk/"
        + apk.name
    )

    if matches:
        entry = matches[0]
        previous_code = int(entry["versionCode"])

        if code < previous_code:
            raise ValueError(
                f"Version anterior bloqueada: "
                f"{code} < {previous_code}"
            )

        if code == previous_code:
            previous_apk = Path(
                entry["resources"]["apkUrl"]
            ).name

            if previous_apk != apk.name:
                raise ValueError(
                    "Mismo codigo con distinta APK"
                )

            print("Version ya registrada, sin cambios")
            return

        entry["resources"]["apkUrl"] = apk_url
        entry["versionCode"] = code
        entry["versionName"] = version
        entry["name"] = metadata["name"]
        entry["extensionLib"] = metadata["extensionLib"]
        entry["contentWarning"] = warnings[warning_code]
        entry["sources"] = source_entries

    else:
        entry = {
            "name": metadata["name"],
            "packageName": package,
            "resources": {
                "apkUrl": apk_url,
                "iconUrl": (
                    "https://raw.githubusercontent.com/"
                    "helthstin/kairead-extensions-source/"
                    f"main/src/{extension}/res/"
                    "mipmap-xhdpi/ic_launcher.png"
                ),
            },
            "extensionLib": metadata["extensionLib"],
            "versionCode": code,
            "versionName": version,
            "contentWarning": warnings[warning_code],
            "sources": source_entries,
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
    
