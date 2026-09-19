from __future__ import annotations

import unicodedata
from collections import Counter
from dataclasses import dataclass


@dataclass(frozen=True)
class PrecisionRecallF1:
    precision: float
    recall: float
    f1: float


def normalize_text(value: str) -> str:
    """Normalize OCR text without hiding character recognition errors."""
    normalized_lines = [
        " ".join(line.split())
        for line in unicodedata.normalize("NFC", value).replace("\r\n", "\n").split("\n")
    ]
    return "\n".join(line for line in normalized_lines if line)


def character_error_rate(reference: str, prediction: str) -> float:
    normalized_reference = normalize_text(reference).replace("\n", " ")
    normalized_prediction = normalize_text(prediction).replace("\n", " ")
    if not normalized_reference:
        return 0.0 if not normalized_prediction else 1.0
    return _levenshtein_distance(normalized_reference, normalized_prediction) / len(
        normalized_reference
    )


def exact_line_accuracy(reference: str, prediction: str) -> float:
    reference_lines = normalize_text(reference).splitlines()
    prediction_lines = normalize_text(prediction).splitlines()
    if not reference_lines:
        return 1.0 if not prediction_lines else 0.0
    matches = sum(
        expected == actual
        for expected, actual in zip(reference_lines, prediction_lines, strict=False)
    )
    return matches / max(len(reference_lines), len(prediction_lines))


def line_precision_recall_f1(reference: str, prediction: str) -> PrecisionRecallF1:
    expected = Counter(normalize_text(reference).splitlines())
    actual = Counter(normalize_text(prediction).splitlines())
    true_positives = sum((expected & actual).values())
    return _precision_recall_f1(
        true_positives,
        sum(actual.values()),
        sum(expected.values()),
    )


def reading_order_pair_accuracy(reference: list[str], prediction: list[str]) -> float:
    if len(reference) < 2:
        return 1.0
    predicted_positions = {identifier: index for index, identifier in enumerate(prediction)}
    correct = 0
    total = 0
    for left_index, left in enumerate(reference):
        for right in reference[left_index + 1 :]:
            total += 1
            if (
                left in predicted_positions
                and right in predicted_positions
                and predicted_positions[left] < predicted_positions[right]
            ):
                correct += 1
    return correct / total


def paragraph_boundary_scores(
    reference_boundaries: set[int],
    prediction_boundaries: set[int],
) -> PrecisionRecallF1:
    return _precision_recall_f1(
        len(reference_boundaries & prediction_boundaries),
        len(prediction_boundaries),
        len(reference_boundaries),
    )


def _precision_recall_f1(
    true_positives: int,
    predicted_count: int,
    reference_count: int,
) -> PrecisionRecallF1:
    precision = true_positives / predicted_count if predicted_count else float(reference_count == 0)
    recall = true_positives / reference_count if reference_count else float(predicted_count == 0)
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return PrecisionRecallF1(precision=precision, recall=recall, f1=f1)


def _levenshtein_distance(left: str, right: str) -> int:
    if len(left) < len(right):
        left, right = right, left
    previous = list(range(len(right) + 1))
    for left_index, left_character in enumerate(left, start=1):
        current = [left_index]
        for right_index, right_character in enumerate(right, start=1):
            current.append(
                min(
                    current[-1] + 1,
                    previous[right_index] + 1,
                    previous[right_index - 1] + (left_character != right_character),
                )
            )
        previous = current
    return previous[-1]
