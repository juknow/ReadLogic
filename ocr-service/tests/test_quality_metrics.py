import pytest
from quality_metrics import (
    character_error_rate,
    exact_line_accuracy,
    line_precision_recall_f1,
    normalize_text,
    paragraph_boundary_scores,
    reading_order_pair_accuracy,
)


def test_normalizes_unicode_whitespace_and_line_endings() -> None:
    assert normalize_text(" 독서  는\r\n\r\n 생각 ") == "독서 는\n생각"


def test_measures_character_error_rate_without_hiding_text_errors() -> None:
    assert character_error_rate("독서는 생각을", "독서는 생가글") == pytest.approx(2 / 7)
    assert character_error_rate("", "") == 0
    assert character_error_rate("", "noise") == 1


def test_measures_exact_line_accuracy() -> None:
    reference = "첫 번째 줄\n두 번째 줄"

    assert exact_line_accuracy(reference, reference) == 1
    assert exact_line_accuracy(reference, "첫 번째 줄\n다른 줄") == 0.5


def test_measures_line_precision_recall_and_f1() -> None:
    scores = line_precision_recall_f1("첫 줄\n둘째 줄", "첫 줄\n다른 줄")

    assert scores.precision == 0.5
    assert scores.recall == 0.5
    assert scores.f1 == 0.5


def test_measures_reading_order_pairs_and_paragraph_boundaries() -> None:
    assert reading_order_pair_accuracy(["a", "b", "c"], ["a", "c", "b"]) == pytest.approx(
        2 / 3
    )
    boundaries = paragraph_boundary_scores({2, 5}, {2, 4})

    assert boundaries.precision == 0.5
    assert boundaries.recall == 0.5
    assert boundaries.f1 == 0.5
