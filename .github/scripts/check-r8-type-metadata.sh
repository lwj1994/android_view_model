#!/usr/bin/env bash
set -euo pipefail

# Run after Gradle verification, using the same JDK 21 and cached AGP/R8 dependency.
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd -- "$script_dir/../.." && pwd)"
report_dir="$repository_dir/android-view-model/build/reports/r8-type-metadata"
fixture_dir="$script_dir/r8-type-metadata"
r8_java_home="${JAVA_HOME:?Set JAVA_HOME to the JDK used by the Android CI build.}"
r8_cache_dir="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.android.tools/r8"

if [[ -n "${R8_JAR:-}" ]]; then
    r8_jar="$R8_JAR"
else
    if [[ ! -d "$r8_cache_dir" ]]; then
        echo "R8 is absent from the Gradle cache; run the Android Gradle verification first." >&2
        exit 1
    fi
    r8_jar="$(rg --files "$r8_cache_dir" -g 'r8-*.jar' -g '!*-sources.jar' -g '!*-javadoc.jar' | sort -V | tail -n 1)"
fi
if [[ ! -f "$r8_jar" ]]; then
    echo "No cached R8 jar found. R8_JAR may select an already installed R8 jar." >&2
    exit 1
fi

mkdir -p "$report_dir"
work_dir="$(mktemp -d "$report_dir/work.XXXXXX")"
mkdir -p "$work_dir/input-classes" "$work_dir/verifier-classes"
{
    "$r8_java_home/bin/javac" -version
    "$r8_java_home/bin/java" -cp "$r8_jar" com.android.tools.r8.R8 --version
    echo "R8 jar: $r8_jar"
} 2>&1 | tee "$report_dir/tool-versions.txt"

"$r8_java_home/bin/javac" --release 17 -d "$work_dir/input-classes" "$fixture_dir/MetadataSmoke.java"
"$r8_java_home/bin/javac" --release 17 -d "$work_dir/verifier-classes" "$fixture_dir/MetadataVerifier.java"
"$r8_java_home/bin/jar" --create --file "$report_dir/input.jar" -C "$work_dir/input-classes" .
cat > "$report_dir/entry-point.pro" <<'RULES'
-keep class milu.viewmodel.MetadataSmoke {
    public static void main(java.lang.String[]);
}
RULES

# The only smoke-specific keep is its entry point. Metadata retention must come
# from the actual consumer rules, with full shrinking/optimization/obfuscation on.
"$r8_java_home/bin/java" -cp "$r8_jar" com.android.tools.r8.R8 \
    --classfile --release --thread-count 1 \
    --lib "$r8_java_home" \
    --pg-conf "$repository_dir/android-view-model/consumer-rules.pro" \
    --pg-conf "$report_dir/entry-point.pro" \
    --pg-map-output "$report_dir/mapping.txt" \
    --output "$report_dir/shrunk.jar" "$report_dir/input.jar" \
    2>&1 | tee "$report_dir/r8.log"

"$r8_java_home/bin/java" -cp "$report_dir/shrunk.jar" milu.viewmodel.MetadataSmoke \
    | tee "$report_dir/classes.txt"
# The verifier is deliberately outside R8's input so its reflection assertions
# cannot be folded away. The unshrunk fixture classes are absent from this path.
"$r8_java_home/bin/java" -cp "$work_dir/verifier-classes:$report_dir/shrunk.jar" \
    MetadataVerifier "$report_dir/classes.txt" | tee "$report_dir/result.txt"
