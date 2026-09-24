#!/usr/bin/env bash
#
# Build DEB using Maven version (pom.xml) + jpackage.
# Linux equivalent of build-msi.ps1.
#
# Usage (bash):
#   ./build-deb.sh
#   ./build-deb.sh --skip-build        # reuse existing target/ artifacts
#   ./build-deb.sh --version 1.0.02    # override app version
#
set -euo pipefail

APP_NAME="Xpendz"
PACKAGE_NAME="xpendz"
VENDOR="JCadenas Software"
# jpackage formats the field as "<vendor> <<maintainer>>"; pass the bare address.
MAINTAINER="servicios@jcadenas.com"
MAIN_CLASS="com.myfinaces.MyFinances"
MENU_GROUP="Office;Utility"
APP_CATEGORY="Office"
# Runtime deps for JavaFX on Debian/Ubuntu (alternatives cover 22.04/24.04+ t64 renames).
PACKAGE_DEPS="libgtk-3-0t64 | libgtk-3-0, libxtst6, libxrender1, libasound2t64 | libasound2, libavcodec-extra | libavcodec61 | libavcodec60 | libavcodec59 | libavcodec58"

SKIP_BUILD=0
APP_VERSION_OVERRIDE=""

usage() {
    cat <<'EOF'
Usage: ./build-deb.sh [options]

Options:
  --skip-build        Skip 'mvn package' and reuse existing target/ artifacts
  --version <ver>     Override application version (default: pom.xml version)
  -h, --help          Show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)   SKIP_BUILD=1; shift ;;
        --version)      APP_VERSION_OVERRIDE="${2:?--version requires a value}"; shift 2 ;;
        -h|--help)      usage; exit 0 ;;
        *)              echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
    esac
done

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

get_project_version() {
    # <artifactId>MyFinances</artifactId> is immediately followed by <version>
    awk '
        /<artifactId>[[:space:]]*MyFinances[[:space:]]*<\/artifactId>/ { found=1 }
        found && /<version>/ {
            sub(/.*<version>[[:space:]]*/, "")
            sub(/[[:space:]]*<\/version>.*/, "")
            print
            exit
        }
    ' "$project_dir/pom.xml"
}

ver="$(get_project_version)"
if [[ -z "$ver" ]]; then
    echo "Could not parse project version from pom.xml" >&2
    exit 1
fi

app_ver="$ver"
if [[ -n "$APP_VERSION_OVERRIDE" ]]; then
    app_ver="$APP_VERSION_OVERRIDE"
fi
echo "Building ${APP_NAME} version ${app_ver}"

# Build jar + copy dependencies to target/lib + copy config to target/config
if [[ "$SKIP_BUILD" -eq 0 ]]; then
    mvn -DskipTests package
fi

target_dir="$project_dir/target"
jar_name="MyFinances-${ver}.jar"
jar_path="$target_dir/$jar_name"
[[ -f "$jar_path" ]] || { echo "Jar not found: $jar_path" >&2; exit 1; }

lib_dir="$target_dir/lib"
[[ -d "$lib_dir" ]] || { echo "Dependencies folder not found: $lib_dir" >&2; exit 1; }

# Prepare jpackage input folder
jp_in="$target_dir/jpackage-input"
rm -rf "$jp_in"
mkdir -p "$jp_in"

cp -f "$jar_path" "$jp_in"
cp -Rf "$lib_dir" "$jp_in/lib"

# Runtime config: prefer local override config/app.properties (gitignored,
# same convention as the Windows build), else the committed packaging template.
cfg_src=""
if [[ -f "$project_dir/config/app.properties" ]]; then
    cfg_src="$project_dir/config/app.properties"
elif [[ -f "$project_dir/build-resources/packaging/config/app.properties" ]]; then
    cfg_src="$project_dir/build-resources/packaging/config/app.properties"
elif [[ -f "$target_dir/config/app.properties" ]]; then
    cfg_src="$target_dir/config/app.properties"
fi
if [[ -n "$cfg_src" ]]; then
    mkdir -p "$jp_in/config"
    cp -f "$cfg_src" "$jp_in/config/app.properties"
else
    echo "WARNING: no app.properties found; the package will rely on ~/.myfinances/config/app.properties" >&2
fi

dist_dir="$project_dir/dist"
mkdir -p "$dist_dir"

icon_path="$project_dir/build-resources/xpendz.png"
icon_args=()
if [[ -f "$icon_path" ]]; then
    icon_args=(--icon "$icon_path")
fi

modules=(javafx.controls javafx.fxml javafx.media javafx.graphics javafx.base \
         java.net.http jdk.httpserver jdk.crypto.ec java.naming jdk.localedata)
if compgen -G "$lib_dir/sqlite-jdbc*.jar" > /dev/null; then
    modules+=(java.sql java.logging)
fi

jpackage_args=(
    --type deb
    --dest "$dist_dir"
    --name "$APP_NAME"
    --app-version "$app_ver"
    --vendor "$VENDOR"
    --description "Xpendz Desktop - personal finances manager"
    --input "$jp_in"
    --main-jar "$jar_name"
    --main-class "$MAIN_CLASS"
    --linux-package-name "$PACKAGE_NAME"
    --linux-deb-maintainer "$MAINTAINER"
    --linux-app-category "$APP_CATEGORY"
    --linux-menu-group "$MENU_GROUP"
    --linux-shortcut
    --linux-package-deps "$PACKAGE_DEPS"
    --module-path "$lib_dir"
    --add-modules "$(IFS=,; echo "${modules[*]}")"
    "${icon_args[@]}"
)

jpackage_cmd=""
if [[ -n "${JPACKAGE_CMD:-}" ]]; then
    jpackage_cmd="$JPACKAGE_CMD"
elif command -v jpackage > /dev/null 2>&1; then
    jpackage_cmd="$(command -v jpackage)"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then
    jpackage_cmd="$JAVA_HOME/bin/jpackage"
else
    echo "jpackage not found. Install a JDK that includes jpackage (Java 14+) or set JPACKAGE_CMD." >&2
    exit 1
fi

echo "Running jpackage..."
"$jpackage_cmd" "${jpackage_args[@]}"

# jpackage emits <pkg>_<ver>[-<release>]_<arch>.deb; rename to the release-asset name.
expected="$dist_dir/${APP_NAME}-${app_ver}.deb"
for produced in "$dist_dir/${PACKAGE_NAME}_${app_ver}"*_*.deb; do
    if [[ -f "$produced" && "$produced" != "$expected" ]]; then
        mv -f "$produced" "$expected"
    fi
done
if [[ ! -f "$expected" ]]; then
    latest="$(ls -t "$dist_dir"/*.deb 2>/dev/null | head -1 || true)"
    if [[ -n "$latest" ]]; then
        echo "jpackage finished but expected DEB was not generated: $expected. Latest DEB found: $latest" >&2
    else
        echo "jpackage finished but expected DEB was not generated: $expected" >&2
    fi
    exit 1
fi

echo "Done. DEB generated: $expected"
