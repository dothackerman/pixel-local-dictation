"""Gate checks: no broken relative Markdown links; agent skills laid out as their installers own them."""
import json
from pathlib import Path
import tempfile
import unittest

import repo_checks


class RepositoryTests(unittest.TestCase):
    def test_no_broken_relative_markdown_links(self):
        self.assertEqual([], repo_checks.broken_links())

    def test_agent_skills_match_their_installers(self):
        self.assertEqual([], repo_checks.skill_problems())


class LinkCheckTests(unittest.TestCase):
    def test_reports_missing_target_and_ignores_code_urls_and_anchors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'docs').mkdir()
            (root / 'docs/a.md').write_text('ok')
            (root / 'README.md').write_text(
                '[a](docs/a.md#part) [web](https://x.org/y) [top](#top) `[no](gone.md)`\n'
                '```\n[fenced](gone.md)\n```\n[bad](docs/b.md)\n')
            self.assertEqual(['README.md:5: broken link docs/b.md'],
                             repo_checks.broken_links(root, ['README.md']))


class SkillCheckTests(unittest.TestCase):
    def layout(self, root, codex='4.8.0', claude='4.8.0'):
        (root / '.agents/skills/tdd').mkdir(parents=True)
        (root / '.agents/skills/tdd/SKILL.md').write_text('tdd')
        (root / '.agents/skills/impeccable').mkdir()
        (root / '.agents/skills/impeccable/SKILL.md').write_text(f'---\nmetadata:\n  version: {codex}\n---\n')
        (root / '.claude/skills/impeccable').mkdir(parents=True)
        (root / '.claude/skills/impeccable/SKILL.md').write_text(f'---\nversion: {claude}\n---\n')
        (root / '.claude/skills/tdd').symlink_to('../../.agents/skills/tdd')
        (root / 'skills-lock.json').write_text(json.dumps({'skills': {'tdd': {}}}))

    def test_valid_layout_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            self.layout(Path(directory))
            self.assertEqual([], repo_checks.skill_problems(directory))

    def test_copied_skill_stray_folder_and_version_drift_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.layout(root, claude='4.9.0')
            (root / '.claude/skills/tdd').unlink()
            (root / '.claude/skills/tdd').mkdir()
            (root / '.claude/skills/stray').mkdir()
            problems = repo_checks.skill_problems(root)
            self.assertEqual(3, len(problems), problems)
            self.assertIn('.claude/skills/tdd: must be a symlink to .agents/skills/tdd', problems)
            self.assertIn('.claude/skills/stray: real folder not owned by an installer', problems)

    def test_missing_locked_skill_and_dangling_link_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.layout(root)
            (root / '.agents/skills/tdd/SKILL.md').unlink()
            (root / '.agents/skills/tdd').rmdir()
            problems = repo_checks.skill_problems(root)
            self.assertIn('.agents/skills/tdd: locked skill missing', problems)
            self.assertIn('.claude/skills/tdd: symlink does not resolve to .agents/skills/tdd', problems)


if __name__ == '__main__':
    unittest.main()
