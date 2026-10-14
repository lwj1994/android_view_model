import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from normalize_kover_for_codecov import normalize_report


REPORT = b'''<?xml version="1.0" encoding="UTF-8"?>
<report name="Kover">
  <package name="milu/viewmodel">
    <class name="milu/viewmodel/Node" sourcefilename="Node.kt">
      <counter type="LINE" missed="1" covered="3"/>
    </class>
    <sourcefile name="Node.kt">
      <line nr="1" mi="0" ci="0" mb="0" cb="0"/>
      <line nr="2" mi="0" ci="8" mb="0" cb="0"/>
      <line nr="3" mi="4" ci="0" mb="0" cb="0"/>
      <line nr="4" mi="2" ci="6" mb="1" cb="1"/>
      <line nr="5" mi="0" ci="0" mb="1" cb="1"/>
      <line nr="6" mi="0" ci="0" mb="0"/>
      <counter type="INSTRUCTION" missed="6" covered="14"/>
      <counter type="BRANCH" missed="2" covered="2"/>
      <counter type="LINE" missed="1" covered="3"/>
    </sourcefile>
  </package>
  <counter type="LINE" missed="1" covered="3"/>
</report>
'''


class NormalizeKoverTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "kover.xml"
        self.output = self.root / "codecov.xml"
        self.source.write_bytes(REPORT)

    def test_only_zero_metadata_is_removed_and_original_is_unchanged(self):
        self.assertEqual(normalize_report(self.source, self.output), 1)
        self.assertEqual(self.source.read_bytes(), REPORT)
        original = ET.fromstring(REPORT)
        result = ET.parse(self.output).getroot()
        expected_lines = [line.attrib for line in original.iter("line")
                          if line.get("nr") != "1"]
        self.assertEqual([line.attrib for line in result.iter("line")], expected_lines)
        self.assertEqual([counter.attrib for counter in result.iter("counter")],
                         [counter.attrib for counter in original.iter("counter")])
        self.assertEqual(ET.tostring(result.find("package/class")),
                         ET.tostring(original.find("package/class")))

    def test_report_with_no_zero_metadata_keeps_real_coverage(self):
        normalize_report(self.source, self.output)
        second_output = self.root / "second.xml"
        self.assertEqual(normalize_report(self.output, second_output), 0)
        self.assertEqual(self.output.read_bytes(), second_output.read_bytes())

    def test_original_report_cannot_be_overwritten_directly_or_via_alias(self):
        for destination in (self.source, self.output):
            if destination == self.output:
                destination.symlink_to(self.source)
            with self.subTest(destination=destination), self.assertRaises(ValueError):
                normalize_report(self.source, destination)
        self.assertEqual(self.source.read_bytes(), REPORT)
        self.output.unlink()
        os.link(self.source, self.output)
        with self.assertRaises(ValueError):
            normalize_report(self.source, self.output)
        self.assertEqual(self.source.read_bytes(), REPORT)

    def test_invalid_xml_fails_without_creating_an_upload_report(self):
        self.source.write_text("<report>")
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "normalize_kover_for_codecov.py"),
             str(self.source), "--output", str(self.output)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

    def test_other_xml_formats_are_rejected(self):
        self.source.write_text("<coverage/>")
        with self.assertRaises(ValueError):
            normalize_report(self.source, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
