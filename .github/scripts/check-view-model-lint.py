"""Verify AAR packaging and discovery by the real Android Lint consumer."""

from pathlib import Path
import io
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "ViewModelDependencyCycle",
    "ViewModelCachedInstance",
    "ViewModelAliveForeverKey",
    "ViewModelUnmanagedConstruction",
}


def gradle(*tasks):
    subprocess.run(
        ["bash", "./gradlew", *tasks, "--no-parallel", "--max-workers=1"],
        cwd=ROOT,
        check=True,
    )


def main():
    gradle(":android-view-model:assembleDebug")
    aar = ROOT / "android-view-model/build/outputs/aar/android-view-model-debug.aar"
    with zipfile.ZipFile(aar) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read("lint.jar"))) as lint:
            manifest = lint.read("META-INF/MANIFEST.MF").decode()
            if "milu.viewmodel.lint.ViewModelIssueRegistry" not in manifest:
                raise AssertionError("AAR lint.jar is missing its issue registry")

    sources = ROOT / "example/src/main/kotlin"
    try:
        with tempfile.TemporaryDirectory(prefix="lint_probe_", dir=sources) as temp:
            (Path(temp) / "LintProbe.kt").write_text(
                """package milu.viewmodel.example.lintprobe
import milu.viewmodel.*
class LintProbe : ViewModel() {
    val self by readViewModel(probeSpec) { viewModelBinding }
}
val probeSpec = viewModelSpec { LintProbe() }
val retainedProbe = viewModelSpec(aliveForever = true) { LintProbe() }
val argumentProbe = viewModelSpecWithArg<LintProbe, String>(builder = { LintProbe() })
val unmanagedProbe = LintProbe()
class ProbeOwner {
    val binding = ViewModelBinding()
    val cached = binding.read(probeSpec)
}
"""
            )
            gradle(":example:lintDebug")
            report = ROOT / "example/build/reports/lint-results-debug.xml"
            reported = {issue.attrib["id"] for issue in ET.parse(report).findall("issue")}
            missing = EXPECTED - reported
            if missing:
                raise AssertionError(f"Consumer did not discover lint checks: {sorted(missing)}")
            if "ViewModelParameterizedSpecKey" in reported:
                raise AssertionError("Unkeyed parameterized specs must not warn")
            print("Verified all four bundled ViewModel diagnostics in the consumer.")
    finally:
        # Leave generated reports consistent with the original example sources.
        gradle(":example:lintDebug")


if __name__ == "__main__":
    main()
