"""Dictionary scorer's public text/report seam; examples are synthetic."""
import copy
import unittest

import dictionary_score


class DictionaryScoringTests(unittest.TestCase):
    def test_guard_detects_replacing_one_false_accepted_term_with_another(self):
        result = dictionary_score.score_guard('Go home', 'Go Birch', 'Go Aster',
                                              'en', ['Aster', 'Birch'])
        self.assertEqual(result['strict_introduced_dictionary_false_accepts'], 1)

    def test_guard_compares_aligned_occurrences_instead_of_net_error_counts(self):
        cases = [
            ('insertion replacement', 'Go home', 'Go home Aster', 'Go home Birch', 1),
            ('substitution and insertion cancellation', 'Go home now',
             'Go Aster now Aster', 'Go home Aster Aster', 1),
            ('unchanged false accept with another correction', 'Go home now',
             'Go Aster later', 'Go Aster now', 0),
            ('valid correction', 'Go Aster', 'Go Birch', 'Go Aster', 0),
            ('same word at another reference location', 'Go home then work',
             'Go Aster then work', 'Go home then Aster', 1),
            ('duplicate at same insertion gap', 'Go home',
             'Go home Aster', 'Go home Aster Aster', 1),
            ('unchanged duplicates', 'Go home',
             'Go home Aster Aster', 'Go home Aster Aster', 0),
            ('case and punctuation normalization', 'Go home',
             'Go ASTER!', 'Go aster.', 0),
        ]
        for label, reference, control, candidate, expected in cases:
            with self.subTest(label=label):
                result = dictionary_score.score_guard(reference, candidate, control,
                                                      'en', ['Aster', 'Birch'])
                self.assertEqual(result['strict_introduced_dictionary_false_accepts'], expected)

    def test_guard_keeps_strict_listed_set_and_formatting_normalization(self):
        result = dictionary_score.score_guard('Go home', 'Go and', 'Go home',
                                              'en', ['Fern & Footpath'])
        self.assertEqual(result['introduced_dictionary_false_accepts'], 1)
        self.assertEqual(result['strict_introduced_dictionary_false_accepts'], 0)
        result = dictionary_score.score_guard('Die Straße ist frei', 'Die Straße ist Aster',
                                              'Die Strasse ist Aster', 'de', ['Aster'])
        self.assertEqual(result['strict_introduced_dictionary_false_accepts'], 0)
        result = dictionary_score.score_guard('Go blue-green home', 'Go blue green Aster',
                                              'Go bluegreen Aster', 'en', ['Aster'])
        self.assertEqual(result['strict_introduced_dictionary_false_accepts'], 0)

    def test_decimal_values_attached_to_units_are_spacing_variants(self):
        for language, reference, hypothesis in [
            ('de', 'Der Weg ist 1,5km lang.', 'Der Weg ist 1,5 km lang.'),
            ('en', 'It holds 4.8GB.', 'It holds 4.8 GB.'),
            ('de', 'Die Änderung beträgt -1,5km.', 'Die Änderung beträgt -1,5 km.'),
            ('en', 'The change is +4.8GB.', 'The change is +4.8 GB.'),
        ]:
            with self.subTest(language=language):
                self.assertEqual(dictionary_score.score_text(reference, hypothesis, language)['errors'], 0)

    def test_decimal_unit_spacing_does_not_hide_different_numeric_values(self):
        for language, reference, hypothesis in [
            ('de', '1,5km', '1,6 km'),
            ('en', '4.8GB', '48 GB'),
            ('en', '-4.8GB', '-4.9 GB'),
        ]:
            with self.subTest(reference=reference, hypothesis=hypothesis):
                result = dictionary_score.score_text(reference, hypothesis, language)
                self.assertEqual(result['errors'], 1)
                self.assertEqual(result['number_format_errors'], 1)

    def test_decimal_unit_boundary_keeps_ordinals_and_attached_clock_times(self):
        for language, reference, hypothesis in [
            ('en', 'On the 12th day', 'On the twelfth day'),
            ('en', 'Meet at 9.20am', 'Meet at nine twenty am'),
            ('de', 'Um 14.35Uhr', 'Um vierzehn Uhr fünfunddreissig'),
        ]:
            with self.subTest(reference=reference):
                self.assertEqual(dictionary_score.score_text(reference, hypothesis, language)['errors'], 0)

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
            ('en', 'Pixel 10 has 16 GB.', 'Pixel10 has 16GB.'),
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

    def test_strict_counts_ignore_generic_words_from_multiword_entries(self):
        result = dictionary_score.score_text('Meet Frau Keller or Aster', 'Meet Herr Keller and Aster Aster',
                                             'en', ['Frau Keller', 'Fern & Footpath', 'Aster'])
        self.assertEqual((result['listed_errors'], result['strict_listed_errors']), (2, 1))
        self.assertEqual((result['dictionary_substitutions'], result['strict_dictionary_substitutions']), (1, 0))
        self.assertEqual((result['dictionary_insertions'], result['strict_dictionary_insertions']), (1, 1))
        self.assertTrue(dictionary_score.STOP_WORDS.issuperset({'and', 'und', 'frau', 'chip', 'bahnhof'}))

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
