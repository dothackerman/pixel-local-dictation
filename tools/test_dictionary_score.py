"""Dictionary scorer's public text/report seam; examples are synthetic."""
import copy
import unittest

import dictionary_score


class DictionaryScoringTests(unittest.TestCase):
    def test_distinct_spoken_numbers_are_not_spacing_variants(self):
        for lang, reference, hypothesis in [('en', 'two three', 'twenty three'),
                                            ('de', 'zwei drei', 'dreiundzwanzig')]:
            with self.subTest(lang=lang):
                self.assertGreater(dictionary_score.score_text(reference, hypothesis, lang)['errors'], 0)

    def test_formatting_forms_do_not_count_as_word_errors(self):
        examples = [
            ('de', 'Der Tensor-Chip kostet 4,80 Franken.', 'Der Tensorchip kostet vier Franken achtzig.'),
            ('en', 'The blue-green box costs £3.80.', 'The blue green box costs three pounds eighty.'),
            ('de', 'Die Strasse hat 16 GB.', 'Die Straße hat sechzehn Gigabytes.'),
            ('en', 'Meet at nine twenty am.', 'Meet at 9.20 am.'),
            ('de', 'Um vierzehn Uhr fünfunddreissig.', 'Um 14.35 Uhr.'),
        ]
        for language, reference, hypothesis in examples:
            with self.subTest(language=language, reference=reference):
                self.assertEqual(dictionary_score.score_text(reference, hypothesis, language)['errors'], 0)

    def test_error_types_partition_the_alignment_and_count_false_accepts(self):
        result = dictionary_score.score_text(
            'Aster paid 12 francs yesterday', 'Birch paid 13 francs today Cedar',
            'en', ['Aster', 'Birch', 'Cedar'])
        self.assertEqual(result['errors'], 4)
        self.assertEqual((result['listed_errors'], result['number_format_errors'], result['other_errors']), (2, 1, 1))
        self.assertEqual((result['dictionary_insertions'], result['dictionary_substitutions']), (1, 1))

    def test_substitution_toward_dictionary_still_counts_as_harm_to_unlisted_speech(self):
        result = dictionary_score.score_text('Visit home', 'Visit Aster', 'en', ['Aster'])
        self.assertEqual((result['other_errors'], result['listed_errors'], result['dictionary_substitutions']), (1, 0, 1))

    def test_repeated_listed_word_is_an_insertion_even_when_reference_contains_it(self):
        result = dictionary_score.score_text('Visit Aster', 'Visit Aster Aster', 'en', ['Aster'])
        self.assertEqual(result['dictionary_insertions'], 1)
        self.assertEqual(result['listed_errors'], 1)

    def test_report_rows_bind_counts_to_feed_run_and_no_dictionary_control(self):
        control = {'variant': {'name': 'control', 'decoding': {'method': 'modified_beam_search'}, 'hotwords': {'file': '', 'score': 2.0}},
                   'takes': [{'file': 'a.wav', 'text': 'Visit wrong', 'jitter': [
                       {'feed': 512, 'text': 'Visit wrong'}, {'feed': 800, 'text': 'Visit wrong'}]}]}
        candidate = copy.deepcopy(control)
        candidate['variant']['name'] = 'candidate'
        candidate['variant']['hotwords']['file'] = 'dictionary.txt'
        candidate['takes'][0]['text'] = 'Visit Aster'
        refs = {'a.wav': ('en', 'Visit Aster')}
        with self.assertRaisesRegex(ValueError, 'control'):
            dictionary_score.score_report(candidate, refs, ['Aster'], run_id='candidate-1')
        rows = dictionary_score.score_report(candidate, refs, ['Aster'], run_id='candidate-1',
                                             control=control, control_run_id='control-1')
        self.assertEqual([(r['feed'], r['errors']) for r in rows], [(512, 1), (640, 0), (800, 1)])
        self.assertTrue(all(r['run_id'] == 'candidate-1' and r['control_run_id'] == 'control-1'
                            and len(r['report_sha256']) == 64 for r in rows))
        wrong = copy.deepcopy(control)
        wrong['variant']['threads'] = 8
        with self.assertRaisesRegex(ValueError, 'same pipeline'):
            dictionary_score.score_report(candidate, refs, ['Aster'], run_id='candidate-1',
                                          control=wrong, control_run_id='wrong')

    def test_dictionary_keeps_currency_after_price_and_explicit_name_components(self):
        entries = dictionary_score.prepare_dictionary(
            ['4.80 Franken', 'Aster Birch', 'Lange Straße', '123'], names=['Aster Birch'])
        self.assertEqual(entries, ['Franken', 'Aster Birch', 'Lange Straße', 'Lange Strasse', 'Aster', 'Birch'])
        self.assertFalse(any(c.isdigit() for entry in entries for c in entry))
        self.assertNotIn('Lange', entries)  # A phrase is not guessed to be a person's name.


if __name__ == '__main__':
    unittest.main()
