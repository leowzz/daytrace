from benchmark import normalize, score


def test_reference_punctuation_does_not_mask_a_missing_filler():
    reference = "啊, 现在是人声录制"
    assert score(reference, "啊，现在是人声录制。大")['cer'] == 1 / 8
    assert score(reference, "啊，现在是人声录制。")['cer'] == 0
    assert score(reference, "现在是人声录制")['cer'] == 1 / 8


def test_actual_historical_errors_and_homophones_are_counted():
    assert score("啊, 现在是人声录制", "现在是人生努力")['cer'] == 4 / 8
    assert score("啊, 现在是人声录制", "啊现在是人生入职")['cer'] == 3 / 8
    assert score("啊, 现在是人声录制", "哦现在是人生入职")['cer'] == 4 / 8


def test_empty_reference_and_unicode_normalization():
    assert score("", "test")['cer'] is None
    assert score("啊", "")['cer'] == 1
    assert normalize("ＡＢＣ，１２３。\n") == "abc123"
